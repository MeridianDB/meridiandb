// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadException;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.regions.Region;

final class S3CheckpointHeadsTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final ObjectId LINEAGE = new ObjectId("2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c");
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, LINEAGE);
    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("fixture-access", "fixture-secret"));
    private static final S3ObjectStore.Timeouts LIMITS = new S3ObjectStore.Timeouts(
            Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(3));
    private static final String KEY = S3CheckpointHeads.headKey(NAMESPACE).value();
    private static final ObjectCheckpointHead FIRST = head(NAMESPACE, PUBLISHER, 3, 1, null);
    private static final byte[] FIRST_IMAGE = ObjectCheckpointHeadCodec.encode(FIRST);
    private static final byte[] SECOND_IMAGE = next(FIRST, 4);
    private S3HeadProtocolFixture fixture;

    private S3CheckpointHeads connect() {
        return new S3CheckpointHeads(fixture.endpoint(), Region.US_EAST_1, S3HeadProtocolFixture.BUCKET, CREDENTIALS, BINDING, LIMITS);
    }

    @BeforeEach void start() throws IOException { fixture = new S3HeadProtocolFixture(); }

    @AfterEach void stop() throws IOException {
        try { assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString()); }
        finally { fixture.close(); }
    }

    @Test void signedBinaryCreateGetAndReplaceUseExactBytesAndOpaqueObservedToken() throws IOException {
        try (var heads = connect()) {
            assertTrue(heads.read().isEmpty());
            assertEquals(ObjectReference.forBytes(FIRST_IMAGE), heads.createIfAbsent(FIRST_IMAGE));
            var first = heads.read().orElseThrow();
            assertEquals(FIRST, first.head());
            assertArrayEquals(FIRST_IMAGE, first.image());
            assertEquals(fixture.endpoint(), first.endpoint());
            assertEquals("bucket", first.bucket());
            assertEquals(KEY, first.key().value());
            assertEquals(fixture.state(KEY).etag(), first.token().value());
            assertNotEquals(first.reference().id().value(), first.token().value());
            assertEquals(ObjectReference.forBytes(SECOND_IMAGE), heads.replace(first, SECOND_IMAGE));
            var second = heads.read().orElseThrow();
            assertArrayEquals(SECOND_IMAGE, second.image());
            assertNotEquals(first.token(), second.token());
        }
        var puts = fixture.requests().stream().filter(request -> request.method().equals("PUT")).toList();
        assertEquals(2, puts.size());
        assertEquals("*", puts.getFirst().header("if-none-match"));
        assertNull(puts.getFirst().header("if-match"));
        assertNull(puts.getLast().header("if-none-match"));
        assertTrue(puts.getLast().header("authorization").contains("if-match"));
        assertTrue(puts.getFirst().header("authorization").contains("if-none-match"));
        assertEquals("256", puts.getFirst().header("content-length"));
        assertArrayEquals(FIRST_IMAGE, puts.getFirst().body());
        assertArrayEquals(SECOND_IMAGE, puts.getLast().body());
        assertEquals(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(ObjectReference.forBytes(FIRST_IMAGE).id().value())),
                puts.getFirst().header("x-amz-checksum-sha256"));
        assertTrue(fixture.requests().stream().allMatch(request -> request.method().equals("GET") || request.method().equals("PUT")));
    }

    @Test void createRaceHasOneWinnerAndPreservesWinningBytes() throws Exception {
        byte[] other = ObjectCheckpointHeadCodec.encode(head(NAMESPACE, PUBLISHER, 9, 1, null));
        try (var first = connect(); var second = connect(); var workers = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var one = workers.submit(() -> { gate.await(); return create(first, FIRST_IMAGE); });
            var two = workers.submit(() -> { gate.await(); return create(second, other); });
            gate.countDown();
            boolean oneWon = one.get(10, TimeUnit.SECONDS);
            assertNotEquals(oneWon, two.get(10, TimeUnit.SECONDS));
            assertArrayEquals(oneWon ? FIRST_IMAGE : other, fixture.state(KEY).bytes());
        }
        assertEquals(2, fixture.count("PUT", KEY));
        assertEquals(0, fixture.count("GET", KEY));
    }

    private static boolean create(S3CheckpointHeads heads, byte[] image) throws IOException {
        try { heads.createIfAbsent(image); return true; }
        catch (S3HeadWriteException failure) {
            assertEquals(S3HeadWriteException.Code.PRECONDITION_FAILED, failure.code());
            assertEquals(412, failure.statusCode());
            return false;
        }
    }

    @Test void replacementRaceAndStaleObservationNeverRebase() throws Exception {
        fixture.put(KEY, FIRST_IMAGE);
        byte[] other = next(FIRST, 9);
        try (var first = connect(); var second = connect(); var workers = Executors.newFixedThreadPool(2)) {
            var observed = first.read().orElseThrow();
            var gate = new CountDownLatch(1);
            var one = workers.submit(() -> { gate.await(); return replace(first, observed, SECOND_IMAGE); });
            var two = workers.submit(() -> { gate.await(); return replace(second, observed, other); });
            gate.countDown();
            boolean oneWon = one.get(10, TimeUnit.SECONDS);
            assertNotEquals(oneWon, two.get(10, TimeUnit.SECONDS));
            byte[] winner = oneWon ? SECOND_IMAGE : other;
            assertArrayEquals(winner, fixture.state(KEY).bytes());
            assertEquals(1, fixture.count("GET", KEY), "Replacement must use its original token without a refresh GET");
            assertEquals(S3HeadWriteException.Code.PRECONDITION_FAILED,
                    assertThrows(S3HeadWriteException.class, () -> first.replace(observed, next(FIRST, 10))).code());
            assertArrayEquals(winner, fixture.state(KEY).bytes());
            assertEquals(1, fixture.count("GET", KEY));
        }
    }

    private static boolean replace(S3CheckpointHeads heads, S3HeadObservation observed, byte[] bytes) throws IOException {
        try { heads.replace(observed, bytes); return true; }
        catch (S3HeadWriteException failure) {
            assertEquals(S3HeadWriteException.Code.PRECONDITION_FAILED, failure.code());
            return false;
        }
    }

    @Test void getPairsCapturedBytesAndTokenWhileAnotherWriterChangesTheHead() throws IOException {
        var original = fixture.put(KEY, FIRST_IMAGE);
        fixture.changeAfterNextGet(KEY, SECOND_IMAGE);
        try (var heads = connect()) {
            var observed = heads.read().orElseThrow();
            assertArrayEquals(FIRST_IMAGE, observed.image());
            assertEquals(original.etag(), observed.token().value());
            assertArrayEquals(SECOND_IMAGE, fixture.state(KEY).bytes());
            assertNotEquals(observed.token().value(), fixture.state(KEY).etag());
            assertEquals(S3HeadWriteException.Code.PRECONDITION_FAILED,
                    assertThrows(S3HeadWriteException.class, () -> heads.replace(observed, next(FIRST, 9))).code());
        }
        assertEquals(1, fixture.count("GET", KEY));
    }

    @Test void capturedAbsenceDoesNotMakeLaterCreationUnconditional() throws IOException {
        fixture.changeAfterNextGet(KEY, FIRST_IMAGE);
        try (var heads = connect()) {
            assertTrue(heads.read().isEmpty());
            assertEquals(S3HeadWriteException.Code.PRECONDITION_FAILED,
                    assertThrows(S3HeadWriteException.class, () -> heads.createIfAbsent(FIRST_IMAGE)).code());
            assertArrayEquals(FIRST_IMAGE, fixture.state(KEY).bytes());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostCreateReplyRetainsExactIntentAndMakesNoAutomaticReconciliation(boolean afterEffect) throws IOException {
        fixture.fault("PUT", KEY, afterEffect ? S3HeadProtocolFixture.Fault.DROP_AFTER : S3HeadProtocolFixture.Fault.DROP_BEFORE);
        try (var heads = connect()) {
            assertEquals(S3HeadWriteException.Code.UNCERTAIN,
                    assertThrows(S3HeadWriteException.class, () -> heads.createIfAbsent(FIRST_IMAGE)).code());
            assertEquals(1, fixture.count("PUT", KEY));
            assertEquals(0, fixture.count("GET", KEY));
            if (afterEffect) { assertArrayEquals(FIRST_IMAGE, fixture.state(KEY).bytes()); }
            else { assertNull(fixture.state(KEY)); }
            fixture.clearFault("PUT", KEY);
            if (afterEffect) {
                assertArrayEquals(FIRST_IMAGE, heads.read().orElseThrow().image());
                assertEquals(S3HeadWriteException.Code.PRECONDITION_FAILED,
                        assertThrows(S3HeadWriteException.class, () -> heads.createIfAbsent(FIRST_IMAGE)).code());
            } else { assertEquals(ObjectReference.forBytes(FIRST_IMAGE), heads.createIfAbsent(FIRST_IMAGE)); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostReplacementReplyCanBeRetriedOnlyWithOriginalObservationAndBytes(boolean afterEffect) throws IOException {
        fixture.put(KEY, FIRST_IMAGE);
        try (var heads = connect()) {
            var observed = heads.read().orElseThrow();
            fixture.fault("PUT", KEY, afterEffect ? S3HeadProtocolFixture.Fault.DROP_AFTER : S3HeadProtocolFixture.Fault.DROP_BEFORE);
            assertEquals(S3HeadWriteException.Code.UNCERTAIN,
                    assertThrows(S3HeadWriteException.class, () -> heads.replace(observed, SECOND_IMAGE)).code());
            assertEquals(1, fixture.count("PUT", KEY));
            assertEquals(1, fixture.count("GET", KEY));
            fixture.clearFault("PUT", KEY);
            assertArrayEquals(afterEffect ? SECOND_IMAGE : FIRST_IMAGE, heads.read().orElseThrow().image());
            var restored = S3HeadObservation.restore(observed.endpoint(), observed.bucket(), observed.image(), observed.token());
            if (afterEffect) {
                assertEquals(S3HeadWriteException.Code.PRECONDITION_FAILED,
                        assertThrows(S3HeadWriteException.class, () -> heads.replace(restored, SECOND_IMAGE)).code());
            } else { heads.replace(restored, SECOND_IMAGE); }
            var puts = fixture.requests().stream().filter(request -> request.method().equals("PUT")).toList();
            assertEquals(observed.token().value(), puts.getFirst().header("if-match"));
            assertEquals(puts.getFirst().header("if-match"), puts.getLast().header("if-match"));
            assertArrayEquals(puts.getFirst().body(), puts.getLast().body());
        }
    }

    @Test void explicitRollbackShowsThatAnEtagPreconditionIsNotFencing() throws IOException {
        var original = fixture.put(KEY, FIRST_IMAGE);
        try (var heads = connect()) {
            var observed = heads.read().orElseThrow();
            heads.replace(observed, SECOND_IMAGE);
            fixture.forceState(KEY, FIRST_IMAGE, original.etag()); // Unsupported external rollback A→B→A.
            byte[] candidate = next(FIRST, 9);
            heads.replace(observed, candidate);
            assertArrayEquals(candidate, fixture.state(KEY).bytes());
        }
        // A future publisher needs durable expected-state evidence and exclusive placement, not only this primitive.
    }

    @Test void oldHeadAfterTimeoutCanPrecedeTheOriginalRequestsLateEffect() throws Exception {
        fixture.put(KEY, FIRST_IMAGE);
        var limits = new S3ObjectStore.Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (var observer = connect();
             var writer = new S3CheckpointHeads(fixture.endpoint(), Region.US_EAST_1, "bucket", CREDENTIALS, BINDING, limits);
             var worker = Executors.newSingleThreadExecutor()) {
            var observed = observer.read().orElseThrow();
            var gate = fixture.holdNextPut(KEY);
            try {
                var pending = worker.submit(() -> assertThrows(S3HeadWriteException.class, () -> writer.replace(observed, SECOND_IMAGE)));
                assertTrue(gate.awaitStarted(Duration.ofSeconds(5)));
                assertEquals(S3HeadWriteException.Code.UNCERTAIN, pending.get(10, TimeUnit.SECONDS).code());
                assertArrayEquals(FIRST_IMAGE, observer.read().orElseThrow().image());
                assertEquals(1, fixture.count("PUT", KEY));
                gate.release();
                assertTrue(gate.awaitFinished(Duration.ofSeconds(5)));
                assertArrayEquals(SECOND_IMAGE, observer.read().orElseThrow().image());
                assertEquals(1, fixture.count("PUT", KEY), "The original timed-out request, not a retry, took effect");
            } finally { gate.release(); }
        }
    }

    @ParameterizedTest @EnumSource(value = S3HeadProtocolFixture.Fault.class,
            names = {"CONFLICT_409", "PRECONDITION_412", "NOT_FOUND_404", "FORBIDDEN", "NO_BUCKET"})
    void writeServiceResponsesHavePreciseClassesWithoutRetryOrGet(S3HeadProtocolFixture.Fault fault) throws IOException {
        fixture.put(KEY, FIRST_IMAGE);
        try (var heads = connect()) {
            var observed = heads.read().orElseThrow();
            fixture.fault("PUT", KEY, fault);
            var failure = assertThrows(S3HeadWriteException.class, () -> heads.replace(observed, SECOND_IMAGE));
            var expected = switch (fault) {
                case CONFLICT_409 -> S3HeadWriteException.Code.CONCURRENT_OPERATION;
                case PRECONDITION_412 -> S3HeadWriteException.Code.PRECONDITION_FAILED;
                case NOT_FOUND_404 -> S3HeadWriteException.Code.MISSING_KEY;
                default -> S3HeadWriteException.Code.SERVICE_FAILURE;
            };
            assertEquals(expected, failure.code());
            assertEquals(fault == S3HeadProtocolFixture.Fault.CONFLICT_409 ? 409
                    : fault == S3HeadProtocolFixture.Fault.PRECONDITION_412 ? 412
                    : fault == S3HeadProtocolFixture.Fault.FORBIDDEN ? 403 : 404, failure.statusCode());
            assertArrayEquals(FIRST_IMAGE, fixture.state(KEY).bytes());
            assertEquals(1, fixture.count("PUT", KEY));
            assertEquals(1, fixture.count("GET", KEY));
        }
    }

    @Test void absentReplacementIsAKeyConflictAndReadDoesNotCreate() throws IOException {
        try (var heads = connect()) {
            var saved = S3HeadObservation.restore(fixture.endpoint(), "bucket", FIRST_IMAGE, new S3HeadToken("\"old-token\""));
            assertEquals(S3HeadWriteException.Code.MISSING_KEY,
                    assertThrows(S3HeadWriteException.class, () -> heads.replace(saved, SECOND_IMAGE)).code());
            assertTrue(heads.read().isEmpty());
            assertNull(fixture.state(KEY));
        }
    }

    @ParameterizedTest @EnumSource(value = S3HeadProtocolFixture.Fault.class,
            names = {"FORBIDDEN", "NO_BUCKET", "HUGE_ERROR", "DROP_BEFORE"})
    void readErrorsNeverBecomeMissingHead(S3HeadProtocolFixture.Fault fault) throws IOException {
        fixture.fault("GET", KEY, fault);
        try (var heads = connect()) { assertThrows(IOException.class, heads::read); }
        assertEquals(0, fixture.count("PUT", KEY));
    }

    @ParameterizedTest @EnumSource(value = S3HeadProtocolFixture.Fault.class,
            names = {"PARTIAL_206", "CONTENT_RANGE_200", "CHUNKED", "SHORT_BODY", "TRUNCATED_BODY", "LONG_BODY",
                    "OVERSIZE_LENGTH", "MISSING_ETAG", "WEAK_ETAG", "MALFORMED_ETAG", "MULTIPLE_ETAGS", "OVERSIZE_ETAG", "DUPLICATE_LENGTH"})
    void malformedFramingAndTokensExposeNoObservation(S3HeadProtocolFixture.Fault fault) throws IOException {
        fixture.put(KEY, FIRST_IMAGE);
        fixture.fault("GET", KEY, fault);
        try (var heads = connect()) {
            assertThrows(IOException.class, heads::read);
            fixture.clearFault("GET", KEY);
            assertArrayEquals(FIRST_IMAGE, heads.read().orElseThrow().image());
        }
    }

    @ParameterizedTest @EnumSource(value = S3HeadProtocolFixture.Fault.class,
            names = {"SUCCESS_ERROR_BODY", "HUGE_SUCCESS_BODY", "HUGE_ERROR"})
    void guardedPutBodiesKeepStoredOutcomeExplicitlyUncertain(S3HeadProtocolFixture.Fault fault) throws IOException {
        fixture.fault("PUT", KEY, fault);
        try (var heads = connect()) {
            assertEquals(S3HeadWriteException.Code.UNCERTAIN,
                    assertThrows(S3HeadWriteException.class, () -> heads.createIfAbsent(FIRST_IMAGE)).code());
        }
        assertEquals(1, fixture.count("PUT", KEY));
        assertEquals(0, fixture.count("GET", KEY));
    }

    @Test void corruptHeadAndWrongBindingsFailAfterCompleteRead() throws IOException {
        try (var heads = connect()) {
            byte[] corrupt = FIRST_IMAGE.clone(); corrupt[255] ^= 1;
            for (byte[] image : List.of(corrupt, ObjectCheckpointHeadCodec.encode(head(NAMESPACE, new UUID(0, 9), 3, 1, null)),
                    ObjectCheckpointHeadCodec.encode(head(new UUID(0, 9), PUBLISHER, 3, 1, null)))) {
                fixture.put(KEY, image);
                var failure = assertThrows(IOException.class, heads::read);
                assertTrue(failure.getCause() instanceof ObjectCheckpointHeadException);
            }
        }
    }

    @Test void preflightRejectsWrongScopeBindingAndTransitionWithoutMutation() throws IOException {
        var heads = connect();
        try {
            assertThrows(NullPointerException.class, () -> heads.createIfAbsent(null));
            assertThrows(IllegalArgumentException.class, () -> heads.createIfAbsent(new byte[257]));
            assertThrows(IllegalArgumentException.class, () -> heads.createIfAbsent(SECOND_IMAGE));
            assertThrows(ObjectCheckpointHeadException.class,
                    () -> heads.createIfAbsent(ObjectCheckpointHeadCodec.encode(head(NAMESPACE, new UUID(0, 9), 3, 1, null))));
            byte[] corrupt = FIRST_IMAGE.clone(); corrupt[255] ^= 1;
            assertThrows(ObjectCheckpointHeadException.class, () -> heads.createIfAbsent(corrupt));
            var otherEndpoint = S3HeadObservation.restore(URI.create("https://different.invalid"), "bucket", FIRST_IMAGE, new S3HeadToken("\"t\""));
            var otherBucket = S3HeadObservation.restore(fixture.endpoint(), "other-bucket", FIRST_IMAGE, new S3HeadToken("\"t\""));
            assertThrows(IllegalArgumentException.class, () -> heads.replace(otherEndpoint, SECOND_IMAGE));
            assertThrows(IllegalArgumentException.class, () -> heads.replace(otherBucket, SECOND_IMAGE));
            var saved = S3HeadObservation.restore(fixture.endpoint(), "bucket", FIRST_IMAGE, new S3HeadToken("\"t\""));
            assertThrows(ObjectCheckpointHeadException.class, () -> heads.replace(saved, FIRST_IMAGE));
            assertThrows(NullPointerException.class, () -> heads.replace(null, SECOND_IMAGE));
            assertTrue(fixture.requests().isEmpty());
        } finally { heads.close(); }
    }

    @Test void opaqueTagCharactersSurviveTheRealRequestUnchanged() throws IOException {
        for (String tag : List.of("\"\"", "\"*\"", "\"comma,back\\slash\"", "\"" + "x".repeat(1022) + "\"")) {
            fixture.forceState(KEY, FIRST_IMAGE, tag);
            try (var heads = connect()) {
                var observed = heads.read().orElseThrow();
                assertEquals(tag, observed.token().value());
                heads.replace(observed, SECOND_IMAGE);
                assertEquals(tag, fixture.requests().getLast().header("if-match"));
            }
        }
    }

    @Test void bodyTransferTimeoutStaysInsideSdkAttemptTimer() throws IOException {
        fixture.put(KEY, FIRST_IMAGE);
        fixture.fault("GET", KEY, S3HeadProtocolFixture.Fault.SLOW_BODY);
        var limits = new S3ObjectStore.Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (var heads = new S3CheckpointHeads(fixture.endpoint(), Region.US_EAST_1, "bucket", CREDENTIALS, BINDING, limits)) {
            var failure = assertThrows(IOException.class, heads::read);
            Throwable cause = failure; boolean timed = false;
            for (int i = 0; cause != null && i < 32; i++, cause = cause.getCause()) {
                timed |= cause instanceof ApiCallAttemptTimeoutException || cause instanceof ApiCallTimeoutException;
            }
            assertTrue(timed);
            assertEquals(1, fixture.count("GET", KEY));
        }
    }

    @Test void snapshotsOutliveAdapterCloseAndInputCopiesCannotChangeWrittenBytes() throws IOException {
        var heads = connect();
        try {
            byte[] supplied = FIRST_IMAGE.clone();
            heads.createIfAbsent(supplied); supplied[0] = 0;
            var saved = heads.read().orElseThrow();
            heads.close(); heads.close();
            assertArrayEquals(FIRST_IMAGE, saved.image());
            assertArrayEquals(FIRST_IMAGE, fixture.state(KEY).bytes());
            assertThrows(IllegalStateException.class, heads::read);
            assertThrows(IllegalStateException.class, () -> heads.createIfAbsent(FIRST_IMAGE));
            assertThrows(IllegalStateException.class, () -> heads.replace(saved, SECOND_IMAGE));
        } finally { heads.close(); }
    }

    @Test void closingOneAdapterLeavesSharedCallerCredentialsAndOtherClientsUsable() throws IOException {
        var credentials = new OwnedCredentials();
        var first = new S3CheckpointHeads(fixture.endpoint(), Region.US_EAST_1, "bucket", credentials, BINDING, LIMITS);
        try (var second = new S3CheckpointHeads(fixture.endpoint(), Region.US_EAST_1, "bucket", credentials, BINDING, LIMITS)) {
            first.createIfAbsent(FIRST_IMAGE); first.close();
            assertArrayEquals(FIRST_IMAGE, second.read().orElseThrow().image());
        } finally { first.close(); }
        assertEquals(0, credentials.closes.get());
        credentials.close();
        assertEquals(1, credentials.closes.get());
    }

    private static final class OwnedCredentials implements AwsCredentialsProvider, AutoCloseable {
        private final AtomicInteger closes = new AtomicInteger();
        @Override public AwsCredentials resolveCredentials() {
            if (closes.get() != 0) { throw new IllegalStateException("closed credentials"); }
            return AwsBasicCredentials.create("fixture-access", "fixture-secret");
        }
        @Override public void close() { closes.incrementAndGet(); }
    }

    private static ObjectCheckpointHead head(UUID namespace, UUID publisher, long publication, long revision, ObjectReference previous) {
        return new ObjectCheckpointHead(namespace, publisher, new UUID(0, publication), LINEAGE, revision,
                new ObjectReference(new ObjectId("a".repeat(64)), 136), new JournalPrefix(0, 24, LINEAGE.value()), previous);
    }

    private static byte[] next(ObjectCheckpointHead previous, long publication) {
        return ObjectCheckpointHeadCodec.encode(head(previous.namespaceId(), previous.publisherId(), publication,
                previous.revision() + 1, ObjectCheckpointHeadCodec.reference(previous)));
    }
}
