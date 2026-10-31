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
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.object.ObjectAlreadyExistsException;
import org.meridiandb.storage.object.ObjectIntegrityException;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.storage.segment.SegmentObjects;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

final class S3ObjectStoreTest {
    private static final byte[] ABC = {97, 98, 99};
    private static final ObjectKey KEY = new ObjectKey("object.bin");
    private static final ObjectReference EXPECTED = ObjectReference.forBytes(ABC);
    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("fixture-access", "fixture-secret"));
    private S3ProtocolFixture fixture;

    static S3ObjectStore connect(S3ProtocolFixture fixture) {
        return new S3ObjectStore(fixture.endpoint(), Region.US_EAST_1, S3ProtocolFixture.BUCKET, CREDENTIALS,
                new S3ObjectStore.Timeouts(Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }

    @BeforeEach void start() throws IOException { fixture = new S3ProtocolFixture(); }

    @AfterEach void stop() throws IOException {
        try { assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString()); }
        finally { fixture.close(); }
    }

    @Test void realSignedConditionalBinaryRequestsAndOpaqueEtags() throws IOException {
        try (var store = connect(fixture)) {
            assertEquals(EXPECTED, store.createNew(KEY, ABC));
            try (var reader = store.read(KEY, EXPECTED)) {
                assertArrayEquals(new byte[] {98}, reader.readRange(1, 1));
            }
        }
        assertEquals(2, fixture.requests().size());
        var put = fixture.requests().getFirst();
        assertEquals("/bucket/object.bin", put.path());
        assertEquals("*", put.header("if-none-match"));
        assertEquals("3", put.header("content-length"));
        assertArrayEquals(ABC, put.body());
        assertEquals(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(EXPECTED.id().value())),
                put.header("x-amz-checksum-sha256"));
        assertTrue(put.header("authorization").contains("if-none-match"));
        assertNull(fixture.requests().getLast().header("range"));
        assertNotEquals(EXPECTED.id().value(), S3ProtocolFixture.ETAG);
    }

    @Test void twoIndependentClientsRaceWithoutReplacingWinningBytes() throws Exception {
        try (var first = connect(fixture); var second = connect(fixture);
             var workers = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            byte[] other = {1, 2, 3};
            var one = workers.submit(() -> { gate.await(); return attempt(first, ABC); });
            var two = workers.submit(() -> { gate.await(); return attempt(second, other); });
            gate.countDown();
            boolean firstWon = one.get(10, TimeUnit.SECONDS);
            boolean secondWon = two.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstWon, secondWon);
            byte[] winner = firstWon ? ABC : other;
            assertArrayEquals(winner, fixture.bytes(KEY.value()));
            try (var reader = first.read(KEY, ObjectReference.forBytes(winner))) {
                assertArrayEquals(winner, reader.bytes());
            }
        }
        assertEquals(2, fixture.count("PUT", KEY.value()));
    }

    private static boolean attempt(S3ObjectStore store, byte[] bytes) throws IOException {
        try { store.createNew(KEY, bytes); return true; }
        catch (ObjectAlreadyExistsException conflict) { return false; }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostPutResponseRequiresExplicitRetryAndFullReadback(boolean afterEffect) throws IOException {
        fixture.fault("PUT", KEY.value(), afterEffect ? S3ProtocolFixture.Fault.DROP_AFTER : S3ProtocolFixture.Fault.DROP_BEFORE);
        try (var store = connect(fixture)) {
            var failure = assertThrows(IOException.class, () -> store.createNew(KEY, ABC));
            assertFalse(failure instanceof ObjectAlreadyExistsException);
            assertEquals(1, fixture.count("PUT", KEY.value()), "SDK must not retry an uncertain create");
            if (afterEffect) { assertArrayEquals(ABC, fixture.bytes(KEY.value())); }
            else { assertNull(fixture.bytes(KEY.value())); }
            fixture.clearFault("PUT", KEY.value());
            var retry = store.createOrVerify(KEY, ABC);
            assertEquals(!afterEffect, retry.created());
            assertEquals(EXPECTED, retry.reference());
            assertEquals(afterEffect ? 1 : 0, fixture.count("GET", KEY.value()));
        }
    }

    @Test void lostResponseDoesNotMakeConflictingContentReusable() throws IOException {
        fixture.fault("PUT", KEY.value(), S3ProtocolFixture.Fault.DROP_AFTER);
        try (var store = connect(fixture)) {
            assertThrows(IOException.class, () -> store.createNew(KEY, ABC));
            fixture.clearFault("PUT", KEY.value());
            fixture.put(KEY.value(), new byte[] {7, 8, 9});
            var failure = assertThrows(ObjectIntegrityException.class, () -> store.createOrVerify(KEY, ABC));
            assertEquals(ObjectIntegrityException.Code.ID_MISMATCH, failure.code());
            assertArrayEquals(new byte[] {7, 8, 9}, fixture.bytes(KEY.value()));
        }
    }

    @Test void onlyNoSuchKeyClassifiesAbsence() throws IOException {
        try (var store = connect(fixture)) {
            assertThrows(NoSuchFileException.class, () -> store.read(KEY, EXPECTED));
            for (var fault : new S3ProtocolFixture.Fault[] {S3ProtocolFixture.Fault.FORBIDDEN, S3ProtocolFixture.Fault.NO_BUCKET}) {
                fixture.fault("GET", KEY.value(), fault);
                assertFalse(assertThrows(IOException.class, () -> store.read(KEY, EXPECTED)) instanceof NoSuchFileException);
            }
        }
        assertEquals(0, fixture.count("PUT", KEY.value()));
    }

    @ParameterizedTest @EnumSource(value = S3ProtocolFixture.Fault.class,
            names = {"CONFLICT_409", "FORBIDDEN", "NO_BUCKET", "HUGE_ERROR"})
    void ordinaryPutFailuresAreNotExistingObjectProof(S3ProtocolFixture.Fault fault) throws IOException {
        fixture.fault("PUT", KEY.value(), fault);
        try (var store = connect(fixture)) {
            assertFalse(assertThrows(IOException.class, () -> store.createOrVerify(KEY, ABC)) instanceof ObjectAlreadyExistsException);
        }
        assertEquals(0, fixture.count("GET", KEY.value()), "Only a conditional existing-object result permits readback");
        assertNull(fixture.bytes(KEY.value()));
    }

    @ParameterizedTest @EnumSource(value = S3ProtocolFixture.Fault.class,
            names = {"PARTIAL_206", "CONTENT_RANGE_200", "CHUNKED", "SHORT_BODY", "LONG_BODY", "TRUNCATED_BODY", "OVERSIZE_LENGTH", "HUGE_ERROR", "DROP_BEFORE"})
    void malformedOrTransportResponsesNeverExposeAReader(S3ProtocolFixture.Fault fault) throws IOException {
        fixture.put(KEY.value(), ABC);
        fixture.fault("GET", KEY.value(), fault);
        try (var store = connect(fixture)) {
            assertThrows(IOException.class, () -> store.read(KEY, EXPECTED));
            fixture.clearFault("GET", KEY.value());
            try (var reader = store.read(KEY, EXPECTED)) { assertArrayEquals(ABC, reader.bytes()); }
        }
    }

    @ParameterizedTest @EnumSource(value = S3ProtocolFixture.Fault.class,
            names = {"SUCCESS_ERROR_BODY", "HUGE_SUCCESS_BODY"})
    void unexpectedSuccessfulPutBodiesRemainUncertainUntilVerified(S3ProtocolFixture.Fault fault) throws IOException {
        fixture.fault("PUT", KEY.value(), fault);
        try (var store = connect(fixture)) {
            assertThrows(IOException.class, () -> store.createNew(KEY, ABC));
            assertArrayEquals(ABC, fixture.bytes(KEY.value()));
            fixture.clearFault("PUT", KEY.value());
            assertFalse(store.createOrVerify(KEY, ABC).created());
            assertEquals(1, fixture.count("GET", KEY.value()));
        }
    }

    @Test void oversizedErrorIsRejectedBeforeSdkXmlParsing() throws IOException {
        fixture.fault("GET", KEY.value(), S3ProtocolFixture.Fault.HUGE_ERROR);
        try (var store = connect(fixture)) {
            var failure = assertThrows(IOException.class, () -> store.read(KEY, EXPECTED));
            Throwable cause = failure;
            boolean capped = false;
            for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
                capped |= cause.getMessage() != null && cause.getMessage().contains("64 KiB");
            }
            assertTrue(capped, "Must fail at bounded transport guard before XML decoding");
        }
    }

    @Test void changedLengthOrHashDoesNotPassWithTheSameEtag() throws IOException {
        try (var store = connect(fixture)) {
            store.createNew(KEY, ABC);
            fixture.put(KEY.value(), new byte[] {8, 8, 8});
            assertEquals(ObjectIntegrityException.Code.ID_MISMATCH,
                    assertThrows(ObjectIntegrityException.class, () -> store.read(KEY, EXPECTED)).code());
            fixture.put(KEY.value(), new byte[] {8});
            assertEquals(ObjectIntegrityException.Code.LENGTH_MISMATCH,
                    assertThrows(ObjectIntegrityException.class, () -> store.read(KEY, EXPECTED)).code());
            fixture.remove(KEY.value());
            assertThrows(NoSuchFileException.class, () -> store.read(KEY, EXPECTED));
        }
    }

    @Test void bodyTransferRemainsInsideTheOperationTimeout() throws IOException {
        fixture.put(KEY.value(), ABC);
        fixture.fault("GET", KEY.value(), S3ProtocolFixture.Fault.SLOW_BODY);
        var limits = new S3ObjectStore.Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (var store = new S3ObjectStore(fixture.endpoint(), Region.US_EAST_1, "bucket", CREDENTIALS, limits)) {
            long start = System.nanoTime();
            var failure = assertThrows(IOException.class, () -> store.read(KEY, EXPECTED));
            Throwable cause = failure;
            boolean apiTimeout = false;
            for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
                apiTimeout |= cause instanceof ApiCallAttemptTimeoutException || cause instanceof ApiCallTimeoutException;
            }
            assertTrue(apiTimeout, "Operation/attempt timer must remain active through body consumption");
            assertEquals(1, fixture.count("GET", KEY.value()));
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos());
        }
    }

    @Test void validationPrecedesRequestsAndReadersOutliveStoreClose() throws IOException {
        var store = connect(fixture);
        try {
            assertThrows(NullPointerException.class, () -> store.createNew(null, ABC));
            assertThrows(NullPointerException.class, () -> store.createNew(KEY, null));
            assertThrows(NullPointerException.class, () -> store.read(KEY, null));
            assertThrows(IllegalArgumentException.class, () -> store.createNew(KEY, new byte[ObjectReference.MAX_BYTES + 1]));
            assertTrue(fixture.requests().isEmpty());
            store.createNew(KEY, ABC);
            try (var reader = store.read(KEY, EXPECTED)) {
                store.close(); store.close();
                assertThrows(IllegalStateException.class, () -> store.createNew(KEY, ABC));
                assertThrows(IllegalStateException.class, () -> store.read(KEY, EXPECTED));
                assertArrayEquals(ABC, reader.bytes());
            }
        } finally { store.close(); }
    }

    @Test void maximumBoundedImageRoundTripsThroughActualHttp() throws IOException {
        byte[] image = new byte[ObjectReference.MAX_BYTES];
        for (int i = 0; i < image.length; i++) { image[i] = (byte) (i * 31); }
        var expected = ObjectReference.forBytes(image);
        try (var store = connect(fixture)) {
            assertEquals(expected, store.createNew(KEY, image));
            try (var reader = store.read(KEY, expected)) {
                assertEquals(expected, reader.reference());
                assertArrayEquals(new byte[] {image[image.length - 1]}, reader.readRange(image.length - 1, 1));
            }
        }
    }

    @Test void invalidNamespaceAndTimeoutsFailBeforeNetwork() {
        for (String bucket : new String[] {"", "aa", "bucket.with.dots", "Bucket", "bucket--x-s3", "x".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> new S3ObjectStore(fixture.endpoint(), Region.US_EAST_1, bucket, CREDENTIALS));
        }
        for (String endpoint : new String[] {"file:///tmp", "http://user@localhost", "http://localhost/path", "http://localhost/?x=1"}) {
            assertThrows(IllegalArgumentException.class, () -> new S3ObjectStore(URI.create(endpoint), Region.US_EAST_1, "bucket", CREDENTIALS));
        }
        assertThrows(IllegalArgumentException.class, () -> new S3ObjectStore.Timeouts(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new S3ObjectStore.Timeouts(Duration.ofSeconds(1), Duration.ofMinutes(11), Duration.ofSeconds(1)));
        assertTrue(fixture.requests().isEmpty());
    }

    @Test void closingClientLeavesSharedCallerOwnedCredentialsUsable() throws IOException {
        var provider = new OwnedCredentials();
        var first = new S3ObjectStore(fixture.endpoint(), Region.US_EAST_1, "bucket", provider);
        try (var second = new S3ObjectStore(fixture.endpoint(), Region.US_EAST_1, "bucket", provider)) {
            first.createNew(KEY, ABC);
            first.close();
            try (var reader = second.read(KEY, EXPECTED)) { assertArrayEquals(ABC, reader.bytes()); }
        } finally { first.close(); }
        assertEquals(0, provider.closes.get());
        provider.close();
        assertEquals(1, provider.closes.get());
    }

    @Test void historySegmentConsumerPreservesOriginalOrderedTransactions() throws IOException {
        var entity = new EntityId("salary");
        var segment = new ImmutableSegment(new DatabaseId("remote-history"), List.of(
                new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(10L, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(20L, 30L), 120)))),
                new JournalRecord(3, new Transaction(new RequestId("retract"), List.of(
                        new WriteOperation.Retract(entity, new ValidTimeInterval(25L, 28L)))))));
        try (var store = connect(fixture)) {
            var created = SegmentObjects.createOrVerify(store, KEY, segment);
            assertTrue(created.created());
            assertFalse(SegmentObjects.createOrVerify(store, KEY, segment).created());
            var reopened = SegmentObjects.read(store, KEY, new SegmentId(created.reference().id().value()),
                    created.reference().byteLength());
            assertEquals(segment, reopened.segment());
        }
    }

    private static final class OwnedCredentials implements AwsCredentialsProvider, AutoCloseable {
        private final AtomicInteger closes = new AtomicInteger();
        @Override public AwsCredentials resolveCredentials() {
            if (closes.get() != 0) { throw new IllegalStateException("credentials closed"); }
            return AwsBasicCredentials.create("fixture-access", "fixture-secret");
        }
        @Override public void close() { closes.incrementAndGet(); }
    }
}
