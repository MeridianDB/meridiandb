// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3CheckpointPublisher.Boundary;
import org.meridiandb.storage.s3.S3CheckpointPublisher.WriteReply;
import org.meridiandb.storage.s3.S3PublicationProtocolFixture.Fault;
import org.meridiandb.storage.s3.S3PublicationState.Operation;
import org.meridiandb.storage.s3.S3PublicationState.Target;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/** Real SDK, durable local pending state and bounded JVM exits; no live-provider/power-loss claim. */
@Timeout(60)
class S3CheckpointPublisherRecoveryTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final DatabaseId DATABASE = new DatabaseId("publisher-recovery");
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(
            NAMESPACE, PUBLISHER, ObjectCheckpointHeadVerifier.lineageId(DATABASE));
    private static final ObjectCheckpointGraphs.Limits LIMITS = ObjectCheckpointGraphs.DEFAULT_LIMITS;
    private static final JournalRecord FIRST = record(1, "first!", 100);
    private static final JournalRecord SECOND = record(2, "second", 120);
    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("fixture-access", "fixture-secret"));
    private static final S3ObjectStore.Timeouts TIMEOUTS = new S3ObjectStore.Timeouts(
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(2));
    private static final S3ObjectStore.Timeouts HELD_TIMEOUTS = new S3ObjectStore.Timeouts(
            Duration.ofMillis(800), Duration.ofSeconds(1), Duration.ofSeconds(2));
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);
    private static final int HALT_STATUS = 74;
    private static final int UNSUPPORTED_STATUS = 77;
    private static final int MAX_LOG_BYTES = 64 * 1024;

    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void competingSelectionBetweenGetAndPutRejectsTheActualOriginalCondition(boolean replacement) throws Exception {
        try (var env = environment(replacement)) {
            var candidate = ObjectCheckpointHeadCodec.decode(env.candidate).head();
            byte[] competing = ObjectCheckpointHeadCodec.encode(new ObjectCheckpointHead(candidate.namespaceId(),
                    candidate.publisherId(), new UUID(0, 990), candidate.lineageId(), candidate.revision(),
                    candidate.manifest(), candidate.journalPrefix(), candidate.predecessor()));
            var publisher = new S3CheckpointPublisher(env.objects, env.heads, env.local, env.checkpoints, env.journal, boundary -> {
                if (boundary == Boundary.BEFORE_WRITE) { env.fixture.forceState(headKey(), competing, "\"competing-head-token\""); }
            });
            var failure = assertThrows(S3CheckpointPublicationException.class, () -> publisher.submit(LIMITS));
            assertUnresolved(failure, true);
            assertEquals(S3HeadWriteException.Code.PRECONDITION_FAILED,
                    ((S3HeadWriteException) failure.getCause()).code());
            assertPending(env); assertOriginalAttempts(env);
            assertArrayEquals(competing, env.fixture.state(headKey()).bytes());
            assertEquals(env.initialPuts + 1, env.fixture.count("PUT", headKey()));
            var conflict = assertThrows(S3CheckpointPublicationException.class, () -> env.publisher().reconcile(LIMITS));
            assertEquals(S3CheckpointPublicationException.Code.REMOTE_CONFLICT, conflict.code());
            assertFalse(conflict.writeAttempted()); assertPending(env);
            assertEquals(env.initialPuts + 1, env.fixture.count("PUT", headKey()));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostBeforeEffectRetainsOriginalPendingAcrossReopenAndExplicitRetry(boolean replacement) throws Exception {
        try (var env = environment(replacement)) {
            env.fixture.fault("PUT", headKey(), Fault.DROP_BEFORE);
            var failed = assertThrows(S3CheckpointPublicationException.class, () -> env.publisher().submit(LIMITS));
            assertUnresolved(failed, true);
            assertPending(env);
            assertOriginalRemote(env);
            assertTrue(env.fixture.count("PUT", headKey()) > env.initialPuts);
            assertOriginalAttempts(env);
            env.reopenLocal();
            assertPending(env);
            var unresolved = assertThrows(S3CheckpointPublicationException.class, () -> env.publisher().reconcile(LIMITS));
            assertUnresolved(unresolved, false);
            assertPending(env);
            refuseNewPreparation(env);
            env.fixture.clearFault("PUT", headKey());
            long before = env.fixture.count("PUT", headKey());
            var selected = env.publisher().submit(LIMITS);
            assertSelected(env, selected, WriteReply.ACKNOWLEDGED);
            assertEquals(before + 1, env.fixture.count("PUT", headKey()));
            assertOriginalAttempts(env);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostAfterEffectResolvesOnlyFromExactCandidateAndReportsFailedWriteReply(boolean replacement) throws Exception {
        try (var env = environment(replacement)) {
            env.fixture.fault("PUT", headKey(), Fault.DROP_AFTER);
            var selected = env.publisher().submit(LIMITS);
            assertSelected(env, selected, WriteReply.FAILED);
            assertEquals(S3HeadWriteException.Code.UNCERTAIN, selected.writeFailureCode().orElseThrow());
            assertTrue(env.fixture.count("PUT", headKey()) > env.initialPuts);
            assertOriginalAttempts(env);
            env.reopenLocal();
            assertArrayEquals(S3PublicationStateCodec.encode(selected.state()), S3PublicationStateCodec.encode(env.local.state()));
            long before = env.fixture.count("PUT", headKey());
            var noPending = assertThrows(S3PublicationStateException.class, () -> env.publisher().submit(LIMITS));
            assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION, noPending.code());
            assertEquals(before, env.fixture.count("PUT", headKey()));
        }
    }

    static Stream<Arguments> serviceFailures() {
        return Stream.of(false, true).flatMap(replacement -> Stream.of(
                Fault.CONFLICT_409, Fault.PRECONDITION_412, Fault.FORBIDDEN)
                .map(fault -> Arguments.of(replacement, fault)));
    }

    @ParameterizedTest(name = "replacement={0}, {1}") @MethodSource("serviceFailures")
    void serviceRejectionNeverClearsOrRebasesDurableIntent(boolean replacement, Fault fault) throws Exception {
        try (var env = environment(replacement)) {
            env.fixture.fault("PUT", headKey(), fault);
            var failed = assertThrows(S3CheckpointPublicationException.class, () -> env.publisher().submit(LIMITS));
            assertUnresolved(failed, true);
            assertNotNull(failed.getCause());
            assertPending(env);
            assertOriginalRemote(env);
            assertEquals(env.initialPuts + 1, env.fixture.count("PUT", headKey()));
            assertOriginalAttempts(env);
            env.reopenLocal();
            assertPending(env);
            long before = env.fixture.count("PUT", headKey());
            var old = assertThrows(S3CheckpointPublicationException.class, () -> env.publisher().reconcile(LIMITS));
            assertUnresolved(old, false);
            assertEquals(before, env.fixture.count("PUT", headKey()));
            refuseNewPreparation(env);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acknowledgedWriteFollowedByCapturedOldReadRetainsPendingUntilFreshCandidateRead(boolean replacement) throws Exception {
        try (var env = environment(replacement)) {
            var publisher = env.publisher(boundary -> {
                if (boundary == Boundary.WRITE_FINISHED) {
                    // Expose a captured predecessor/absence while its later replacement is installed.
                    // This controls reply ordering; it is not a model of provider consistency.
                    if (env.original == null) { env.fixture.remove(headKey()); }
                    else { env.fixture.forceState(headKey(), env.original.image(), env.original.token().value()); }
                    env.fixture.captureNextGet(headKey(), env.candidate);
                }
            });
            var failed = assertThrows(S3CheckpointPublicationException.class, () -> publisher.submit(LIMITS));
            assertUnresolved(failed, true);
            assertPending(env);
            assertArrayEquals(env.candidate, env.fixture.state(headKey()).bytes());
            assertEquals(env.initialPuts + 1, env.fixture.count("PUT", headKey()));
            refuseNewPreparation(env);
            env.reopenLocal();
            assertPending(env);
            long before = env.fixture.count("PUT", headKey());
            var resolved = env.publisher().reconcile(LIMITS);
            assertSelected(env, resolved, WriteReply.NOT_ATTEMPTED);
            assertEquals(before, env.fixture.count("PUT", headKey()));
            assertOriginalAttempts(env);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void timedOutHeldWriteCanTakeEffectAfterOldReadAndLocalOwnerReopen(boolean replacement) throws Exception {
        try (var env = environment(replacement);
             var timedHeads = heads(env.target, HELD_TIMEOUTS)) {
            var gate = env.fixture.holdNextPut(headKey());
            var publisher = new S3CheckpointPublisher(env.objects, timedHeads, env.local, env.checkpoints, env.journal);
            var submission = new FutureTask<Throwable>(() -> {
                try { publisher.submit(LIMITS); return null; }
                catch (Throwable failure) { return failure; }
            });
            var writer = new Thread(submission, "held-publication-submit");
            writer.start();
            try {
                assertTrue(gate.awaitStarted(WAIT), "The real SDK PUT must arrive before its timeout");
                var failed = submission.get(6, TimeUnit.SECONDS);
                assertTrue(failed instanceof S3CheckpointPublicationException, () -> "Unexpected submit outcome: " + failed);
                assertUnresolved((S3CheckpointPublicationException) failed, true);
                assertFalse(gate.awaitFinished(Duration.ofMillis(1)), "The remote request remains held after the caller times out");
                assertOriginalRemote(env);
                var observed = env.heads.read();
                if (env.original == null) { assertTrue(observed.isEmpty()); }
                else {
                    assertArrayEquals(env.original.image(), observed.orElseThrow().image());
                    assertEquals(env.original.token(), observed.orElseThrow().token());
                }
                assertPending(env);
                long before = env.fixture.count("PUT", headKey());
                assertEquals(env.initialPuts + 1, before);
                var unresolved = assertThrows(S3CheckpointPublicationException.class, () -> env.publisher().reconcile(LIMITS));
                assertUnresolved(unresolved, false);
                refuseNewPreparation(env);
                env.reopenLocal();
                assertPending(env);
                assertOriginalRemote(env);
                gate.release();
                assertTrue(gate.awaitFinished(WAIT), "The original request must finish its condition/effect after release");
                assertArrayEquals(env.candidate, env.fixture.state(headKey()).bytes());
                if (env.original != null) { assertNotEquals(env.original.token().value(), env.fixture.state(headKey()).etag()); }
                var resolved = env.publisher().reconcile(LIMITS);
                assertSelected(env, resolved, WriteReply.NOT_ATTEMPTED);
                assertEquals(before, env.fixture.count("PUT", headKey()), "Reconciliation must not issue a second PUT");
                assertOriginalAttempts(env);
            } finally {
                gate.release();
                writer.join(6000);
                if (writer.isAlive()) { writer.interrupt(); writer.join(1000); }
                assertFalse(writer.isAlive(), "Publication caller did not terminate");
            }
        }
    }

    static Stream<Arguments> successBodyFailures() {
        return Stream.of(false, true).flatMap(replacement -> Stream.of(Fault.SUCCESS_ERROR_BODY, Fault.HUGE_SUCCESS_BODY)
                .map(fault -> Arguments.of(replacement, fault)));
    }

    @ParameterizedTest(name = "replacement={0}, {1}") @MethodSource("successBodyFailures")
    void malformedSuccessfulPutReplyCanResolveOnlyAfterCandidateAndGraphReadback(boolean replacement, Fault fault)
            throws Exception {
        try (var env = environment(replacement)) {
            env.fixture.fault("PUT", headKey(), fault);
            var selected = env.publisher().submit(LIMITS);
            assertSelected(env, selected, WriteReply.FAILED);
            assertTrue(selected.writeFailureCode().isPresent());
            assertEquals(env.initialPuts + 1, env.fixture.count("PUT", headKey()));
            assertOriginalAttempts(env);
            env.reopenLocal();
            assertArrayEquals(S3PublicationStateCodec.encode(selected.state()), S3PublicationStateCodec.encode(env.local.state()));
        }
    }

    static Stream<Arguments> postWriteReadFailures() {
        return Stream.of(false, true).flatMap(replacement -> Stream.of(Fault.MISSING_ETAG, Fault.CHUNKED)
                .map(fault -> Arguments.of(replacement, fault)));
    }

    @ParameterizedTest(name = "replacement={0}, {1}") @MethodSource("postWriteReadFailures")
    void failedCandidateReadAfterAcknowledgedPutRetainsPendingAcrossOwnerReopen(boolean replacement, Fault fault)
            throws Exception {
        try (var env = environment(replacement)) {
            var publisher = env.publisher(boundary -> {
                if (boundary == Boundary.WRITE_FINISHED) { env.fixture.fault("GET", headKey(), fault); }
            });
            var failed = assertThrows(S3CheckpointPublicationException.class, () -> publisher.submit(LIMITS));
            assertUnresolved(failed, true);
            assertNotNull(failed.getCause());
            assertPending(env);
            assertArrayEquals(env.candidate, env.fixture.state(headKey()).bytes());
            assertEquals(env.initialPuts + 1, env.fixture.count("PUT", headKey()));
            env.reopenLocal();
            assertPending(env);
            env.fixture.clearFault("GET", headKey());
            long before = env.fixture.count("PUT", headKey());
            var resolved = env.publisher().reconcile(LIMITS);
            assertSelected(env, resolved, WriteReply.NOT_ATTEMPTED);
            assertEquals(before, env.fixture.count("PUT", headKey()));
            assertOriginalAttempts(env);
        }
    }

    static Stream<Arguments> processBoundaries() {
        return Stream.of(false, true).flatMap(replacement -> Stream.of(
                Boundary.WRITE_FINISHED, Boundary.CANDIDATE_VERIFIED, Boundary.STATE_SYNCED)
                .map(boundary -> Arguments.of(replacement, boundary)));
    }

    @ParameterizedTest(name = "replacement={0}, halt={1}") @MethodSource("processBoundaries")
    void realProcessExitPreservesExactIntentAndReopensOnlyDurablyRecordedSelection(boolean replacement, Boundary boundary)
            throws Exception {
        try (var env = environment(replacement)) {
            var pending = env.local.state();
            byte[] pendingImage = S3PublicationStateCodec.encode(pending);
            long before = env.fixture.count("PUT", headKey());
            env.closeOwners();
            try (var child = start(env.home, env.target.endpoint(), boundary)) {
                assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
                assertTrue(child.diagnostics().lines().anyMatch(("HALT " + boundary)::equals), child.diagnostics());
            }
            assertEquals(before + 1, env.fixture.count("PUT", headKey()));
            assertArrayEquals(env.candidate, env.fixture.state(headKey()).bytes());
            assertOriginalAttempts(env);
            // The exact original pending generation remains retained even after result installation.
            assertArrayEquals(pendingImage, Files.readAllBytes(selectedFile(env.localDirectory, pending)));
            env.reopenOwners();
            if (boundary == Boundary.STATE_SYNCED) {
                assertTrue(env.local.state().pending().isEmpty());
                assertEquals(pending.generation() + 1, env.local.state().generation());
                assertArrayEquals(env.candidate, env.local.state().expected().orElseThrow().image());
                assertEquals(env.fixture.state(headKey()).etag(), env.local.state().expected().orElseThrow().token().value());
                var noPending = assertThrows(S3PublicationStateException.class, () -> env.publisher().reconcile(LIMITS));
                assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION, noPending.code());
            } else {
                assertPending(env);
                var resolved = env.publisher().reconcile(LIMITS);
                assertSelected(env, resolved, WriteReply.NOT_ATTEMPTED);
            }
            assertEquals(before + 1, env.fixture.count("PUT", headKey()), "Reopen/reconciliation must not resend the child PUT");
            byte[] selected = S3PublicationStateCodec.encode(env.local.state());
            env.reopenLocal();
            assertArrayEquals(selected, S3PublicationStateCodec.encode(env.local.state()));
        }
    }

    private Environment environment(boolean replacement) throws IOException {
        try { return Environment.create(Files.createDirectory(directory.resolve("home")), replacement); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException | LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
            throw unsupported;
        }
    }

    private static void assertUnresolved(S3CheckpointPublicationException failure, boolean attempted) {
        assertEquals(S3CheckpointPublicationException.Code.STILL_PENDING, failure.code());
        assertEquals(attempted, failure.writeAttempted());
    }

    private static void assertPending(Environment env) throws IOException {
        var state = env.local.state();
        assertArrayEquals(env.pendingImage, S3PublicationStateCodec.encode(state));
        var intent = state.pending().orElseThrow();
        assertArrayEquals(env.candidate, intent.image());
        assertArrayEquals(env.candidate, env.fixture.bytes(intentKey(env.candidate)));
        assertEquals(env.original == null ? Operation.CREATE_IF_ABSENT : Operation.REPLACE_IF_MATCH, intent.operation());
        assertEquals(env.original == null ? "*" : env.original.token().value(), intent.precondition());
        if (env.original == null) { assertTrue(state.expected().isEmpty()); }
        else {
            assertArrayEquals(env.original.image(), state.expected().orElseThrow().image());
            assertEquals(env.original.token(), state.expected().orElseThrow().token());
        }
    }

    private static void assertOriginalRemote(Environment env) {
        if (env.original == null) { assertNull(env.fixture.state(headKey())); }
        else {
            assertArrayEquals(env.original.image(), env.fixture.state(headKey()).bytes());
            assertEquals(env.original.token().value(), env.fixture.state(headKey()).etag());
        }
    }

    private static void refuseNewPreparation(Environment env) throws IOException {
        int before = env.fixture.requests().size();
        var pending = assertThrows(S3PublicationStateException.class, () -> env.preparer().prepare(env.candidate, LIMITS));
        assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION, pending.code());
        assertEquals(before, env.fixture.requests().size());
        assertPending(env);
    }

    private static void assertOriginalAttempts(Environment env) {
        var writes = env.fixture.requests().stream().filter(request -> request.method().equals("PUT") && request.key().equals(headKey()))
                .skip(env.initialPuts).toList();
        assertFalse(writes.isEmpty());
        for (var request : writes) {
            assertArrayEquals(env.candidate, request.body());
            if (env.original == null) {
                assertEquals("*", request.header("if-none-match"));
                assertTrue(request.headerValues("if-match").isEmpty());
            } else {
                assertEquals(env.original.token().value(), request.header("if-match"));
                assertTrue(request.headerValues("if-none-match").isEmpty());
            }
        }
    }

    private static void assertSelected(Environment env, S3CheckpointPublisher.Result result, WriteReply reply) throws IOException {
        assertEquals(reply, result.writeReply());
        assertEquals(reply != WriteReply.NOT_ATTEMPTED, result.writeAttempted());
        assertEquals(reply == WriteReply.ACKNOWLEDGED, result.writeAcknowledged());
        if (reply != WriteReply.FAILED) { assertTrue(result.writeFailureCode().isEmpty()); }
        assertTrue(result.state().pending().isEmpty());
        assertArrayEquals(env.candidate, result.observation().image());
        assertArrayEquals(env.candidate, result.state().expected().orElseThrow().image());
        assertEquals(env.fixture.state(headKey()).etag(), result.observation().token().value());
        assertEquals(S3PublicationStateCodec.decode(env.pendingImage).state().generation() + 1, result.state().generation());
        assertArrayEquals(S3PublicationStateCodec.encode(result.state()), S3PublicationStateCodec.encode(env.local.state()));
        assertEquals(2, result.forcedSequence());
        assertEquals(env.journal.appendedBytes(), env.journal.forcedBytes());
        assertTrue(result.usage().objectReads() > 0);
        assertTrue(result.usage().journalReads() > 0);
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(null, null), value))));
    }

    private record Graph(CheckpointManifest manifest, byte[] manifestImage, SegmentId segmentId, byte[] segmentImage) { }

    private static Graph graph(long generation, List<JournalRecord> records) {
        byte[] segmentImage = SegmentCodec.encode(new ImmutableSegment(DATABASE, records));
        var segmentId = new SegmentId(hash(segmentImage));
        var journalImage = new ByteArrayOutputStream();
        journalImage.writeBytes(JournalCodec.encodeHeader(DATABASE));
        records.forEach(record -> journalImage.writeBytes(JournalCodec.encodeRecord(record)));
        var prefix = new CheckpointManifest.JournalPrefix(records.size(), journalImage.size(), hash(journalImage.toByteArray()));
        var manifest = new CheckpointManifest(DATABASE, generation, prefix, List.of(
                new CheckpointManifest.SegmentReference(segmentId, segmentImage.length, 1, records.size())));
        return new Graph(manifest, ManifestCodec.encode(manifest), segmentId, segmentImage);
    }

    private static ObjectCheckpointHead head(long revision, long publication, Graph graph, ObjectCheckpointHead previous) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, publication), BINDING.lineageId(), revision,
                ObjectReference.forBytes(graph.manifestImage()), graph.manifest().journalPrefix(),
                previous == null ? null : ObjectCheckpointHeadCodec.reference(previous));
    }

    private static String hash(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static String headKey() { return S3CheckpointHeads.headKey(NAMESPACE).value(); }
    private static String intentKey(byte[] image) {
        return S3PublicationPreparer.intentKey(NAMESPACE, ObjectReference.forBytes(image).id()).value();
    }

    private static S3ObjectStore objects(Target target) {
        return new S3ObjectStore(target.endpoint(), Region.of(target.regionId()), target.bucket(), CREDENTIALS, TIMEOUTS);
    }

    private static S3CheckpointHeads heads(Target target, S3ObjectStore.Timeouts timeouts) {
        return new S3CheckpointHeads(target.endpoint(), Region.of(target.regionId()), target.bucket(), CREDENTIALS, BINDING, timeouts);
    }

    private static Path selectedFile(Path home, S3PublicationState state) {
        return home.resolve(String.format(Locale.ROOT, "publication-%019d-%s.state", state.generation(),
                S3PublicationStateCodec.reference(state).id().value()));
    }

    private static final class Environment implements AutoCloseable {
        private Path home;
        private Path localDirectory;
        private Target target;
        private S3PublicationProtocolFixture fixture;
        private LocalCheckpointStore checkpoints;
        private LocalJournal journal;
        private LocalS3PublicationState local;
        private S3ObjectStore objects;
        private S3CheckpointHeads heads;
        private S3HeadObservation original;
        private byte[] candidate;
        private byte[] pendingImage;
        private long initialPuts;

        static Environment create(Path home, boolean replacement) throws IOException {
            var env = new Environment();
            env.home = home;
            try {
                env.fixture = new S3PublicationProtocolFixture();
                env.target = new Target(env.fixture.endpoint(), "us-east-1", S3PublicationProtocolFixture.BUCKET);
                env.localDirectory = Files.createDirectory(home.resolve("publication"));
                env.checkpoints = LocalCheckpointStore.open(Files.createDirectory(home.resolve("checkpoint")));
                env.journal = env.checkpoints.initialize(DATABASE);
                env.journal.append(FIRST);
                env.journal.append(SECOND);
                env.local = LocalS3PublicationState.create(env.localDirectory, env.target, BINDING);
                env.objects = objects(env.target);
                env.heads = heads(env.target, TIMEOUTS);
                var after = graph(7, List.of(FIRST, SECOND));
                env.install(after);
                if (replacement) {
                    var before = graph(19, List.of(FIRST));
                    env.install(before);
                    var previous = head(1, 10, before, null);
                    env.preparer().prepare(ObjectCheckpointHeadCodec.encode(previous), LIMITS);
                    env.original = env.publisher().submit(LIMITS).observation();
                    assertArrayEquals(ObjectCheckpointHeadCodec.encode(previous), env.original.image());
                    env.candidate = ObjectCheckpointHeadCodec.encode(head(2, 11, after, previous));
                } else { env.candidate = ObjectCheckpointHeadCodec.encode(head(1, 11, after, null)); }
                env.preparer().prepare(env.candidate, LIMITS);
                env.pendingImage = S3PublicationStateCodec.encode(env.local.state());
                env.initialPuts = env.fixture.count("PUT", headKey());
                return env;
            } catch (IOException | RuntimeException | Error failure) {
                try { env.close(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
                throw failure;
            }
        }

        void install(Graph graph) throws IOException {
            objects.createNew(ObjectCheckpointGraphs.manifestKey(NAMESPACE, ObjectReference.forBytes(graph.manifestImage()).id()),
                    graph.manifestImage());
            objects.createNew(ObjectCheckpointGraphs.segmentKey(NAMESPACE, graph.segmentId()), graph.segmentImage());
        }

        S3PublicationPreparer preparer() { return new S3PublicationPreparer(objects, heads, local, checkpoints, journal); }
        S3CheckpointPublisher publisher() { return new S3CheckpointPublisher(objects, heads, local, checkpoints, journal); }
        S3CheckpointPublisher publisher(S3CheckpointPublisher.PublicationProbe probe) {
            return new S3CheckpointPublisher(objects, heads, local, checkpoints, journal, probe);
        }

        void reopenLocal() throws IOException {
            local.close();
            local = null;
            local = LocalS3PublicationState.open(localDirectory, target, BINDING);
        }

        void reopenOwners() throws IOException {
            checkpoints = LocalCheckpointStore.open(home.resolve("checkpoint"));
            journal = LocalJournal.open(checkpoints.journalPath());
            local = LocalS3PublicationState.open(localDirectory, target, BINDING);
            objects = objects(target);
            heads = heads(target, TIMEOUTS);
        }

        void closeOwners() throws IOException {
            IOException failed = null;
            for (AutoCloseable resource : new AutoCloseable[] {local, journal, checkpoints, heads, objects}) {
                if (resource == null) { continue; }
                try { resource.close(); }
                catch (Exception failure) {
                    if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                    var wrapped = failure instanceof IOException io ? io : new IOException("Recovery fixture cleanup failed", failure);
                    if (failed == null) { failed = wrapped; }
                    else { failed.addSuppressed(wrapped); }
                }
            }
            local = null; journal = null; checkpoints = null; heads = null; objects = null;
            if (failed != null) { throw failed; }
        }

        @Override public void close() throws IOException {
            try { closeOwners(); }
            finally { if (fixture != null) { fixture.close(); } }
            if (fixture != null) { assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString()); }
        }
    }

    private Child start(Path home, URI endpoint, Boundary boundary) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        var arguments = new ArrayList<>(List.of(java.toString(), "-cp", classpath, PublicationProcess.class.getName(),
                home.toAbsolutePath().toString(), endpoint.toString(), boundary.name()));
        // The log is outside both the checkpoint directory and the publication metadata directory.
        var output = directory.resolve("child-" + UUID.randomUUID() + ".log");
        return new Child(new ProcessBuilder(arguments).redirectErrorStream(true).start(), output);
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Path output;
        private final Thread logger;
        private final AtomicReference<IOException> logFailure = new AtomicReference<>();

        Child(Process process, Path output) {
            this.process = process;
            this.output = output;
            logger = new Thread(() -> {
                try (var input = process.getInputStream(); var log = Files.newOutputStream(output)) {
                    int remaining = MAX_LOG_BYTES;
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        int retained = Math.min(remaining, read);
                        if (retained > 0) { log.write(buffer, 0, retained); remaining -= retained; }
                        if (read != retained) { throw new IOException("Publication child log exceeded 64 KiB"); }
                    }
                } catch (IOException failure) {
                    logFailure.set(failure);
                    process.destroyForcibly();
                }
            }, "publication-child-log");
            logger.setDaemon(true);
            logger.start();
        }

        String diagnostics() {
            try { return Files.readString(output, StandardCharsets.UTF_8); }
            catch (IOException failure) { return "Cannot read child log: " + failure; }
        }

        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    () -> "Publication child did not exit: " + diagnostics());
            logger.join(2000);
            assertFalse(logger.isAlive(), "Publication child log reader did not terminate");
            assertNull(logFailure.get(), () -> "Publication child log failed: " + logFailure.get());
            int status = process.exitValue();
            assumeTrue(status != UNSUPPORTED_STATUS, "Child reports explicit directory-sync capability unavailable: " + diagnostics());
            return status;
        }

        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) { throw new IOException("Publication child did not terminate"); }
                logger.join(2000);
                if (logger.isAlive()) { throw new IOException("Publication child logger did not terminate"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while closing publication child", interrupted);
            } finally {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    /** Parent owns the only loopback HTTP server; this child halts inside the production publisher. */
    public static final class PublicationProcess {
        private PublicationProcess() { }
        public static void main(String[] args) throws Exception {
            var home = Path.of(args[0]);
            var endpoint = URI.create(args[1]);
            if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) {
                throw new IllegalArgumentException("Publication child requires the parent's loopback endpoint");
            }
            var target = new Target(endpoint, "us-east-1", S3PublicationProtocolFixture.BUCKET);
            var stop = Boundary.valueOf(args[2]);
            try (var checkpoints = LocalCheckpointStore.open(home.resolve("checkpoint"));
                 var journal = LocalJournal.open(checkpoints.journalPath());
                 var local = LocalS3PublicationState.open(home.resolve("publication"), target, BINDING);
                 var objects = objects(target);
                 var heads = heads(target, TIMEOUTS)) {
                var publisher = new S3CheckpointPublisher(objects, heads, local, checkpoints, journal, reached -> {
                    if (reached == stop) {
                        System.out.println("HALT " + reached);
                        System.out.flush();
                        Runtime.getRuntime().halt(HALT_STATUS);
                    }
                });
                publisher.submit(LIMITS);
                throw new AssertionError("Requested production publication boundary was not reached");
            } catch (LocalCheckpointStore.DirectorySyncUnsupportedException | LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
                System.out.println("DIRECTORY SYNC UNSUPPORTED: " + unsupported);
                System.out.flush();
                System.exit(UNSUPPORTED_STATUS);
            }
        }
    }
}
