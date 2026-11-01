// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.engine.S3WritableFaultSupport;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3PublicationState.Target;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/** Real SDK/local-owner composition with existing core probes, not native force faults or power-loss simulation. */
@Timeout(60)
class S3DatabaseWritableLifecycleTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final DatabaseId DATABASE = new DatabaseId("s3-writable-lifecycle-db");
    private static final EntityId SALARY = new EntityId("salary");
    private static final ObjectId LINEAGE = ObjectCheckpointHeadVerifier.lineageId(DATABASE);
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, LINEAGE);
    private static final ObjectCheckpointGraphs.Limits DEFAULTS = ObjectCheckpointGraphs.DEFAULT_LIMITS;
    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("fixture-access", "fixture-secret"));
    private static final S3ObjectStore.Timeouts TIMEOUTS = new S3ObjectStore.Timeouts(
            Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(3));
    private static final Transaction FIRST = transaction("initial", new ValidTimeInterval(null, null), 100);
    private static final Transaction SECOND = transaction("local-tail", new ValidTimeInterval(10L, 20L), 120);
    private static final Transaction CANDIDATE = transaction("candidate", new ValidTimeInterval(12L, 14L), 130);
    private static final Transaction FOLLOWING = transaction("following", new ValidTimeInterval(14L, 16L), 140);
    private static final List<JournalRecord> INITIAL = List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND));
    private static final int HALT_STATUS = 72;
    private static final int UNSUPPORTED_STATUS = 77;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"RECOVERED:io", "RECOVERED:runtime", "RECOVERED:error",
            "SYNCHRONIZED:io", "SYNCHRONIZED:runtime", "SYNCHRONIZED:error"})
    void actualCoreStartupFailureReleasesAllThreeOwnersAndPreservesTheOriginalManagedSelection(String stage) throws IOException {
        String[] parts = stage.split(":");
        String stop = parts[0];
        var expected = failure(parts[1]);
        try (var env = environment()) {
            var evidence = evidence(env);
            var seen = new ArrayList<String>();
            var managed = new ArrayList<S3Database.Boundary>();
            var escaped = new AtomicReference<S3Database>();
            int start = env.fixture.requests().size();
            assertSame(expected, assertThrows(Throwable.class, () -> escaped.set(S3Database.openWritable(env.databaseDirectory, env.stateDirectory,
                    env.target, BINDING, env.objects, env.heads, DEFAULTS, 2, managed::add,
                    env.opener(boundary -> {
                        seen.add(boundary);
                        assertOwnersLocked(env);
                        if (boundary.equals(stop)) { throwFailure(expected); }
                    }, null)))));
            assertNull(escaped.get());
            assertEquals(stop.equals("RECOVERED") ? List.of("RECOVERED") : List.of("RECOVERED", "SYNCHRONIZED"), seen);
            assertEquals(List.of(S3Database.Boundary.HEAD_OBSERVED), managed, "wrapper reconstruction cannot complete before the actual core barrier");
            assertStartupGetsOnly(env, start);
            assertReleasedAndMetadata(env, evidence, 2);
            assertArrayEquals(journalImage(INITIAL), Files.readAllBytes(env.journalPath()));
            assertEquals(env.expectedObservation.token(), env.heads.read().orElseThrow().token(), "caller SDK clients remain usable");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"AFTER_APPEND:io", "AFTER_APPEND:runtime", "AFTER_APPEND:error",
            "AFTER_FORCE:io", "AFTER_FORCE:runtime", "AFTER_FORCE:error",
            "AFTER_PUBLICATION:io", "AFTER_PUBLICATION:runtime", "AFTER_PUBLICATION:error"})
    void postAttemptFailurePoisonsAllWrapperAccessAndResolvesOnlyThroughActualManagedWritableReopen(String stage) throws IOException {
        String[] parts = stage.split(":");
        var expected = failure(parts[1]);
        try (var env = environment()) {
            var evidence = evidence(env);
            var database = env.open(boundary -> { if (boundary.equals(parts[0])) { throwFailure(expected); } }, null);
            var view = database.openView(ReadCutoff.Head.INSTANCE);
            var priorValue = view.query(new TemporalQuery.Get(SALARY, 13));
            var observation = database.observation();
            int requests = env.fixture.requests().size();
            try {
                if (expected instanceof Error) { assertSame(expected, assertThrows(Error.class, () -> database.transact(CANDIDATE))); }
                else {
                    var uncertain = assertThrows(LocalDatabase.UncertainCommitException.class, () -> database.transact(CANDIDATE));
                    assertSame(expected, uncertain.getCause());
                    assertEquals(CANDIDATE.requestId(), uncertain.requestId());
                    assertEquals(position(3), uncertain.proposedPosition());
                }
                assertUnusable(database);
                assertThrows(IllegalStateException.class, () -> view.query(new TemporalQuery.Get(SALARY, 13)));
                assertOwnersLocked(env);
                assertEquals(requests, env.fixture.requests().size(), "local commit and poisoning make no S3 mutation or refresh");
            } finally { database.close(); }
            view.close();
            assertEquals(present(120), priorValue);
            assertArrayEquals(env.expectedObservation.image(), observation.image());
            assertReleasedAndMetadata(env, evidence, 3);
            assertArrayEquals(journalImage(withCandidate()), Files.readAllBytes(env.journalPath()));
            try (var reopened = env.openWritable()) {
                assertEquals(position(3), reopened.head());
                assertEquals(Optional.of(position(3)), reopened.lookupRequest(CANDIDATE.requestId()));
                int beforeRetry = env.fixture.requests().size();
                var retry = reopened.transact(CANDIDATE);
                assertTrue(retry.replayed());
                assertEquals(position(3), retry.position());
                assertEquals(present(130), query(reopened, 13));
                assertEquals(beforeRetry, env.fixture.requests().size());
            }
            assertReleasedAndMetadata(env, evidence, 3);
            assertArrayEquals(journalImage(withCandidate()), Files.readAllBytes(env.journalPath()));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"BEFORE_APPEND", "AFTER_APPEND"})
    void pausedWriteKeepsOldSnapshotsQueryableAndMakesExternalCloseWaitForTheCommit(String pause) throws Exception {
        try (var env = environment(); var workers = Executors.newFixedThreadPool(3)) {
            var evidence = evidence(env);
            var held = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var core = new AtomicReference<LocalDatabase>();
            var database = env.open(boundary -> { if (boundary.equals(pause)) { hold(held, release); } }, core);
            var oldView = database.openView(ReadCutoff.Head.INSTANCE);
            var writing = workers.submit(() -> database.transact(CANDIDATE));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS), "Write did not reach its bounded hold");
                var closeEntered = new CountDownLatch(1);
                var closer = new AtomicReference<Thread>();
                var closing = workers.submit(() -> {
                    closer.set(Thread.currentThread());
                    closeEntered.countDown();
                    database.close();
                    return null;
                });
                assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
                awaitBlockedOn(closer.get(), core.get());
                assertFalse(closing.isDone());
                var snapshot = workers.submit(() -> snapshot(database)).get(2, TimeUnit.SECONDS);
                assertEquals(oldSnapshot(env), snapshot, "fresh captures stay unblocked and hide prepared/unforced work");
                assertEquals(present(120), oldView.query(new TemporalQuery.Get(SALARY, 13)));
                assertOwnersLocked(env);
                release.countDown();
                var committed = writing.get(10, TimeUnit.SECONDS);
                assertEquals(position(3), committed.position());
                assertFalse(committed.replayed());
                closing.get(10, TimeUnit.SECONDS);
                assertUnusable(database);
                assertThrows(IllegalStateException.class, () -> oldView.query(new TemporalQuery.Get(SALARY, 13)));
            } finally {
                release.countDown();
                try { writing.get(15, TimeUnit.SECONDS); }
                finally { database.close(); oldView.close(); }
            }
            assertReleasedAndMetadata(env, evidence, 3);
            assertArrayEquals(journalImage(withCandidate()), Files.readAllBytes(env.journalPath()));
        }
    }

    @Test void reentrantCloseAndTransactionRejectBeforeInvalidationAndTheOuterCommitContinues() throws IOException {
        try (var env = environment()) {
            var evidence = evidence(env);
            var wrapper = new AtomicReference<S3Database>();
            var checked = new AtomicBoolean();
            var database = env.open(boundary -> {
                if (boundary.equals("BEFORE_APPEND")) {
                    var current = wrapper.get();
                    assertThrows(IOException.class, current::close);
                    assertThrows(IllegalStateException.class, () -> current.transact(FOLLOWING));
                    assertEquals(position(2), current.head());
                    assertEquals(Optional.of(position(1)), current.checkpointPosition());
                    assertEquals(Optional.of(position(1)), current.lookupRequest(FIRST.requestId()));
                    assertEquals(env.expectedObservation.token(), current.observation().token());
                    assertEquals(present(120), query(current, 13));
                    assertOwnersLocked(env);
                    checked.set(true);
                }
            }, null);
            wrapper.set(database);
            int requests = env.fixture.requests().size();
            try {
                var result = database.transact(CANDIDATE);
                assertTrue(checked.get());
                assertEquals(position(3), result.position());
                assertFalse(result.replayed());
                assertEquals(position(3), database.head());
                assertEquals(present(130), query(database, 13));
                assertEquals(Optional.empty(), database.lookupRequest(FOLLOWING.requestId()));
                assertEquals(requests, env.fixture.requests().size());
            } finally { database.close(); }
            assertReleasedAndMetadata(env, evidence, 3);
            assertArrayEquals(journalImage(withCandidate()), Files.readAllBytes(env.journalPath()));
        }
    }

    @Test void concurrentWritersSerializeOnTheRealCoreWhileSnapshotQueriesRemainAvailable() throws Exception {
        try (var env = environment(); var workers = Executors.newFixedThreadPool(3)) {
            var evidence = evidence(env);
            var held = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var once = new AtomicBoolean();
            var core = new AtomicReference<LocalDatabase>();
            var database = env.open(boundary -> {
                if (boundary.equals("BEFORE_APPEND") && once.compareAndSet(false, true)) { hold(held, release); }
            }, core);
            var first = workers.submit(() -> database.transact(CANDIDATE));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var entered = new CountDownLatch(1);
                var secondThread = new AtomicReference<Thread>();
                var second = workers.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    entered.countDown();
                    return database.transact(FOLLOWING);
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                awaitBlockedOn(secondThread.get(), core.get());
                assertFalse(second.isDone());
                assertEquals(oldSnapshot(env), workers.submit(() -> snapshot(database)).get(2, TimeUnit.SECONDS));
                release.countDown();
                assertEquals(position(3), first.get(10, TimeUnit.SECONDS).position());
                assertEquals(position(4), second.get(10, TimeUnit.SECONDS).position());
                assertEquals(position(4), database.head());
                assertEquals(Optional.of(position(3)), database.lookupRequest(CANDIDATE.requestId()));
                assertEquals(Optional.of(position(4)), database.lookupRequest(FOLLOWING.requestId()));
                assertEquals(present(130), query(database, 13));
                assertEquals(present(140), query(database, 15));
            } finally {
                release.countDown();
                try { first.get(15, TimeUnit.SECONDS); }
                finally { database.close(); }
            }
            assertReleasedAndMetadata(env, evidence, 4);
            assertArrayEquals(journalImage(List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND),
                    new JournalRecord(3, CANDIDATE), new JournalRecord(4, FOLLOWING))), Files.readAllBytes(env.journalPath()));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"RECOVERED", "SYNCHRONIZED", "AFTER_APPEND", "AFTER_FORCE", "AFTER_PUBLICATION", "ACKNOWLEDGED_RETURN"})
    void childStopsRecoverExactManagedStateAndResolveCandidateIdentityWithoutAnyRemoteWrite(String stage) throws Exception {
        try (var env = environment()) {
            var evidence = evidence(env);
            int start = env.fixture.requests().size();
            try (var child = start(env, stage)) {
                int status = child.awaitExit();
                assumeTrue(status != UNSUPPORTED_STATUS, () -> "Child explicit directory-sync capability: " + child.diagnostics());
                assertEquals(HALT_STATUS, status, child.diagnostics());
                assertTrue(child.diagnostics().contains("HALT " + stage), child.diagnostics());
                if (stage.equals("ACKNOWLEDGED_RETURN")) { assertTrue(child.diagnostics().contains("ACK 3"), child.diagnostics()); }
                else { assertTrue(child.diagnostics().lines().noneMatch(line -> line.startsWith("ACK ")), "Publication halt occurs before a return acknowledgement"); }
            }
            assertStartupGetsOnly(env, start);
            boolean appended = !stage.equals("RECOVERED") && !stage.equals("SYNCHRONIZED");
            assertReleasedAndMetadata(env, evidence, appended ? 3 : 2);
            assertArrayEquals(journalImage(appended ? withCandidate() : INITIAL), Files.readAllBytes(env.journalPath()));
            try (var database = env.openWritable()) {
                assertEquals(position(appended ? 3 : 2), database.head());
                assertEquals(appended ? Optional.of(position(3)) : Optional.empty(), database.lookupRequest(CANDIDATE.requestId()));
                int beforeRetry = env.fixture.requests().size();
                var resolved = database.transact(CANDIDATE);
                assertEquals(position(3), resolved.position());
                assertEquals(appended, resolved.replayed());
                assertEquals(present(130), query(database, 13));
                assertEquals(beforeRetry, env.fixture.requests().size());
            }
            assertReleasedAndMetadata(env, evidence, 3);
            assertArrayEquals(journalImage(withCandidate()), Files.readAllBytes(env.journalPath()));
        }
    }

    private Environment environment() throws IOException {
        try { return Environment.create(Files.createTempDirectory(directory, "s3-writable-")); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException | LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, () -> "Explicit directory-sync capability unavailable: " + unsupported.getMessage());
            throw unsupported;
        }
    }

    private static void hold(CountDownLatch entered, CountDownLatch release) throws IOException {
        entered.countDown();
        try {
            if (!release.await(10, TimeUnit.SECONDS)) { throw new IOException("Bounded write release timed out"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while holding a real core write", interrupted);
        }
    }
    private static void awaitBlockedOn(Thread thread, Object monitor) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var threads = ManagementFactory.getThreadMXBean();
        while (thread.isAlive() && System.nanoTime() < deadline) {
            var info = threads.getThreadInfo(thread.threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                    && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor)
                    && info.getLockInfo().getClassName().equals(monitor.getClass().getName())) { return; }
            Thread.onSpinWait();
        }
        throw new AssertionError("Expected contender blocked on actual core: " + thread.getState());
    }

    private record ReadSnapshot(CommitPosition head, Optional<CommitPosition> checkpoint, Optional<CommitPosition> candidate,
                                ObjectReference observation, TemporalResult value) { }
    private static ReadSnapshot snapshot(S3Database database) {
        return new ReadSnapshot(database.head(), database.checkpointPosition(), database.lookupRequest(CANDIDATE.requestId()),
                database.observation().reference(), query(database, 13));
    }
    private static ReadSnapshot oldSnapshot(Environment env) {
        return new ReadSnapshot(position(2), Optional.of(position(1)), Optional.empty(), env.expectedObservation.reference(), present(120));
    }
    private static void assertUnusable(S3Database database) {
        assertThrows(IllegalStateException.class, database::head);
        assertThrows(IllegalStateException.class, database::checkpointPosition);
        assertThrows(IllegalStateException.class, () -> database.lookupRequest(FIRST.requestId()));
        assertThrows(IllegalStateException.class, () -> database.openView(ReadCutoff.Head.INSTANCE));
        assertThrows(IllegalStateException.class, database::observation);
        assertThrows(IllegalStateException.class, () -> database.transact(CANDIDATE));
    }
    private static void assertOwnersLocked(Environment env) {
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalS3PublicationState.open(env.stateDirectory, env.target, BINDING)) { unexpected.state(); }
        });
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalCheckpointStore.open(env.databaseDirectory)) { unexpected.journalPath(); }
        });
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalJournal.open(env.journalPath())) { unexpected.prefix(0); }
        });
    }

    private record Evidence(Map<String, byte[]> databaseMetadata, Map<String, byte[]> state, byte[] headImage, String token) { }
    private static Evidence evidence(Environment env) throws IOException {
        var metadata = files(env.databaseDirectory);
        metadata.remove("transactions.journal");
        var remote = env.fixture.state(headKey());
        return new Evidence(metadata, files(env.stateDirectory), remote.bytes(), remote.etag());
    }
    private static void assertReleasedAndMetadata(Environment env, Evidence expected, long sequence) throws IOException {
        try (var state = LocalS3PublicationState.open(env.stateDirectory, env.target, BINDING);
             var checkpoints = LocalCheckpointStore.open(env.databaseDirectory);
             var journal = LocalJournal.open(checkpoints.journalPath())) {
            assertArrayEquals(S3PublicationStateCodec.encode(env.expectedState), S3PublicationStateCodec.encode(state.state()));
            assertEquals(DATABASE, journal.databaseId());
            assertEquals(sequence, journal.appendedSequence());
        }
        var actual = evidence(env);
        assertFilesEqual(expected.databaseMetadata(), actual.databaseMetadata());
        assertFilesEqual(expected.state(), actual.state());
        assertArrayEquals(expected.headImage(), actual.headImage());
        assertEquals(expected.token(), actual.token());
    }
    private static Map<String, byte[]> files(Path directory) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var paths = Files.list(directory)) {
            for (var path : paths.toList()) { result.put(path.getFileName().toString(), Files.readAllBytes(path)); }
        }
        return result;
    }
    private static void assertFilesEqual(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, bytes) -> assertArrayEquals(bytes, actual.get(name), name));
    }
    private static void assertStartupGetsOnly(Environment env, int start) {
        var all = env.fixture.requests();
        var requests = all.subList(start, all.size());
        assertEquals(3, requests.size(), "one fresh current head and one bounded manifest/segment pass");
        assertEquals(1, requests.stream().filter(request -> request.key().equals(headKey())).count());
        assertTrue(requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    private static Transaction transaction(String request, ValidTimeInterval interval, long value) {
        return new Transaction(new RequestId(request), List.of(new WriteOperation.Put(SALARY, interval, value)));
    }
    private static List<JournalRecord> withCandidate() {
        return List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND), new JournalRecord(3, CANDIDATE));
    }
    private static byte[] journalImage(List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(DATABASE));
        records.forEach(record -> bytes.writeBytes(JournalCodec.encodeRecord(record)));
        return bytes.toByteArray();
    }
    private static CommitPosition position(long sequence) { return new CommitPosition(DATABASE, sequence); }
    private static TemporalResult.Point present(long value) { return new TemporalResult.Point(OptionalLong.of(value)); }
    private static TemporalResult query(S3Database database, long validAt) {
        try (var view = database.openView(ReadCutoff.Head.INSTANCE)) { return view.query(new TemporalQuery.Get(SALARY, validAt)); }
    }
    private static String headKey() { return S3CheckpointHeads.headKey(NAMESPACE).value(); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
    private static Throwable failure(String kind) {
        return switch (kind) {
            case "io" -> new IOException("real core boundary failure");
            case "runtime" -> new IllegalStateException("real core boundary failure");
            case "error" -> new AssertionError("real core boundary failure");
            default -> throw new AssertionError(kind);
        };
    }
    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        throw new AssertionError(failure);
    }
    private static S3ObjectStore objectClient(Target target) {
        return new S3ObjectStore(target.endpoint(), Region.of(target.regionId()), target.bucket(), CREDENTIALS, TIMEOUTS);
    }
    private static S3CheckpointHeads headClient(Target target) {
        return new S3CheckpointHeads(target.endpoint(), Region.of(target.regionId()), target.bucket(), CREDENTIALS, BINDING, TIMEOUTS);
    }

    private Child start(Environment env, String boundary) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        Path output = directory.resolve("managed-writable-halt-" + boundary + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, WritableManagedProcess.class.getName(),
                env.databaseDirectory.toAbsolutePath().toString(), env.stateDirectory.toAbsolutePath().toString(),
                env.target.endpoint().toString(), boundary).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private static final class Environment implements AutoCloseable {
        private S3PublicationProtocolFixture fixture;
        private Target target;
        private Path databaseDirectory;
        private Path stateDirectory;
        private S3ObjectStore objects;
        private S3CheckpointHeads heads;
        private ObjectCheckpointHead head;
        private S3PublicationState expectedState;
        private S3HeadObservation expectedObservation;

        static Environment create(Path root) throws IOException {
            var env = new Environment();
            try {
                env.fixture = new S3PublicationProtocolFixture();
                env.target = new Target(env.fixture.endpoint(), "us-east-1", S3PublicationProtocolFixture.BUCKET);
                env.objects = objectClient(env.target);
                env.heads = headClient(env.target);
                env.databaseDirectory = Files.createDirectory(root.resolve("database"));
                env.stateDirectory = Files.createDirectory(root.resolve("state"));
                byte[] prefix = journalImage(List.of(INITIAL.getFirst()));
                byte[] segment = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(INITIAL.getFirst())));
                var segmentId = new SegmentId(hash(segment));
                var manifest = new CheckpointManifest(DATABASE, 17, new JournalPrefix(1, prefix.length, hash(prefix)),
                        List.of(new SegmentReference(segmentId, segment.length, 1, 1)));
                byte[] manifestImage = ManifestCodec.encode(manifest);
                env.head = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3), LINEAGE, 1,
                        ObjectReference.forBytes(manifestImage), manifest.journalPrefix(), null);
                env.fixture.put(ObjectCheckpointGraphs.manifestKey(NAMESPACE, env.head.manifest().id()).value(), manifestImage);
                env.fixture.put(ObjectCheckpointGraphs.segmentKey(NAMESPACE, segmentId).value(), segment);
                try (var checkpoints = LocalCheckpointStore.open(env.databaseDirectory);
                     var journal = checkpoints.initialize(DATABASE);
                     var state = LocalS3PublicationState.create(env.stateDirectory, env.target, BINDING)) {
                    for (var record : INITIAL) { journal.append(record); }
                    new S3PublicationPreparer(env.objects, env.heads, state, checkpoints, journal)
                            .prepare(ObjectCheckpointHeadCodec.encode(env.head), DEFAULTS);
                    var selected = new S3CheckpointPublisher(env.objects, env.heads, state, checkpoints, journal).submit(DEFAULTS);
                    env.expectedState = selected.state();
                    env.expectedObservation = selected.observation();
                }
                return env;
            } catch (IOException | RuntimeException | Error failure) {
                try { env.close(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
                throw failure;
            }
        }

        Path journalPath() { return databaseDirectory.resolve("transactions.journal"); }
        S3Database openWritable() throws IOException {
            return S3Database.openWritable(databaseDirectory, stateDirectory, target, BINDING, objects, heads, DEFAULTS, 2);
        }
        S3Database open(S3WritableFaultSupport.Probe probe, AtomicReference<LocalDatabase> core) throws IOException {
            return S3Database.openWritable(databaseDirectory, stateDirectory, target, BINDING, objects, heads, DEFAULTS, 2, null, opener(probe, core));
        }
        S3Database.CoreOpener opener(S3WritableFaultSupport.Probe probe, AtomicReference<LocalDatabase> capture) {
            return (directory, actualObjects, binding, candidate, limits, minimum) -> {
                var core = S3WritableFaultSupport.open(directory, actualObjects, binding.namespaceId(), binding.publisherId(), candidate, limits, minimum, probe);
                if (capture != null) { capture.set(core); }
                return core;
            };
        }

        @Override public void close() throws IOException {
            IOException failed = null;
            for (AutoCloseable resource : new AutoCloseable[] {heads, objects, fixture}) {
                if (resource == null) { continue; }
                try { resource.close(); }
                catch (Exception failure) {
                    if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                    var wrapped = failure instanceof IOException io ? io : new IOException("Fixture cleanup failed", failure);
                    if (failed == null) { failed = wrapped; }
                    else { failed.addSuppressed(wrapped); }
                }
            }
            if (failed != null) { throw failed; }
            if (fixture != null) { assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString()); }
        }
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Path output;
        Child(Process process, Path output) { this.process = process; this.output = output; }
        String diagnostics() {
            try (var input = Files.newInputStream(output)) { return new String(input.readNBytes(8192), StandardCharsets.UTF_8); }
            catch (IOException failure) { return "Cannot read child output: " + failure; }
        }
        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), () -> "Child did not exit: " + diagnostics());
            return process.exitValue();
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) { throw new IOException("Managed writable child did not terminate"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating managed writable child", interrupted);
            } finally {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    /** Real loopback SDK startup, real core boundaries and process exits only. */
    public static final class WritableManagedProcess {
        private WritableManagedProcess() { }
        public static void main(String[] args) throws Exception {
            Path database = Path.of(args[0]);
            Path state = Path.of(args[1]);
            var target = new Target(URI.create(args[2]), "us-east-1", S3PublicationProtocolFixture.BUCKET);
            String stop = args[3];
            try (var objects = objectClient(target); var heads = headClient(target)) {
                S3Database.CoreOpener opener = (directory, actualObjects, binding, candidate, limits, minimum) ->
                        S3WritableFaultSupport.open(directory, actualObjects, binding.namespaceId(), binding.publisherId(), candidate, limits, minimum,
                                reached -> { if (reached.equals(stop)) { halt(stop); } });
                try (var recovered = S3Database.openWritable(database, state, target, BINDING, objects, heads, DEFAULTS, 2, null, opener)) {
                    CommitResult result = recovered.transact(CANDIDATE);
                    if (stop.equals("ACKNOWLEDGED_RETURN")) {
                        System.out.println("ACK " + result.position().sequence());
                        System.out.flush();
                        halt(stop);
                    }
                    throw new AssertionError("Expected halt before returning " + result);
                }
            } catch (LocalS3PublicationState.DirectorySyncUnsupportedException | LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
                System.out.println("UNSUPPORTED " + unsupported.getMessage());
                System.exit(UNSUPPORTED_STATUS);
            }
        }
        private static void halt(String boundary) {
            System.out.println("HALT " + boundary);
            System.out.flush();
            Runtime.getRuntime().halt(HALT_STATUS);
        }
    }
}
