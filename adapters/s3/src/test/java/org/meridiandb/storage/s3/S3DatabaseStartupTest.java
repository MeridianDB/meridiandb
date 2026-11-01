// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.meridiandb.storage.s3.S3Database.Boundary;
import org.meridiandb.storage.s3.S3PublicationState.Target;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/** Startup failure and process evidence, without simulating native uncertain-close or power loss. */
@Timeout(60)
class S3DatabaseStartupTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final DatabaseId DATABASE = new DatabaseId("s3-startup-db");
    private static final EntityId SALARY = new EntityId("salary");
    private static final ObjectId LINEAGE = ObjectCheckpointHeadVerifier.lineageId(DATABASE);
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, LINEAGE);
    private static final ObjectCheckpointGraphs.Limits DEFAULTS = ObjectCheckpointGraphs.DEFAULT_LIMITS;
    private static final S3ObjectStore.Timeouts TIMEOUTS = new S3ObjectStore.Timeouts(
            Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(3));
    private static final int HALT_STATUS = 71;
    private static final int UNSUPPORTED_STATUS = 77;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);
    private static final JournalRecord FIRST = new JournalRecord(1, new Transaction(new RequestId("first"), List.of(
            new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 100))));
    private static final JournalRecord SECOND = new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
            new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 20L), 120))));

    @TempDir Path directory;

    @Test void allPureInvalidArgumentsFailBeforeAnyFilesystemOrHttpEffect() throws IOException {
        Path database = Files.createDirectory(directory.resolve("database"));
        Path state = Files.createDirectory(directory.resolve("state"));
        try (var fixture = new S3PublicationProtocolFixture(); var credentials = new CallerCredentials()) {
            var target = new Target(fixture.endpoint(), "us-east-1", S3PublicationProtocolFixture.BUCKET);
            try (var objects = objectClient(target, credentials); var heads = headClient(target, credentials)) {
                int resolutions = credentials.resolutions;
                assertThrows(NullPointerException.class, () -> S3Database.open(null, state, target, BINDING, objects, heads, DEFAULTS));
                assertThrows(NullPointerException.class, () -> S3Database.open(database, null, target, BINDING, objects, heads, DEFAULTS));
                assertThrows(NullPointerException.class, () -> S3Database.open(database, state, null, BINDING, objects, heads, DEFAULTS));
                assertThrows(NullPointerException.class, () -> S3Database.open(database, state, target, null, objects, heads, DEFAULTS));
                assertThrows(NullPointerException.class, () -> S3Database.open(database, state, target, BINDING, null, heads, DEFAULTS));
                assertThrows(NullPointerException.class, () -> S3Database.open(database, state, target, BINDING, objects, null, DEFAULTS));
                assertThrows(NullPointerException.class, () -> S3Database.open(database, state, target, BINDING, objects, heads, null));
                assertThrows(IllegalArgumentException.class, () -> S3Database.open(database, state, target, BINDING, objects, heads, DEFAULTS, -1));
                assertEmpty(database);
                assertEmpty(state);
                assertTrue(fixture.requests().isEmpty());
                assertEquals(resolutions, credentials.resolutions);
                assertFalse(credentials.closed);
            }
            assertFalse(credentials.closed, "SDK cleanup must not own the caller credential provider");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"same", "dot-alias"})
    void colocatedDirectoriesIncludingLexicalAliasesRejectBeforeCreatingMetadata(String alias) throws IOException {
        Path home = Files.createDirectory(directory.resolve("shared"));
        Path state = alias.equals("same") ? home : home.resolve(".");
        try (var fixture = new S3PublicationProtocolFixture(); var credentials = new CallerCredentials()) {
            var target = new Target(fixture.endpoint(), "us-east-1", S3PublicationProtocolFixture.BUCKET);
            try (var objects = objectClient(target, credentials); var heads = headClient(target, credentials)) {
                int resolutions = credentials.resolutions;
                assertThrows(IllegalArgumentException.class, () -> S3Database.open(home, state, target, BINDING, objects, heads, DEFAULTS));
                assertEmpty(home);
                assertTrue(fixture.requests().isEmpty());
                assertEquals(resolutions, credentials.resolutions);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"HEAD_OBSERVED:io", "HEAD_OBSERVED:runtime", "HEAD_OBSERVED:error",
            "RECOVERED:io", "RECOVERED:runtime", "RECOVERED:error"})
    void probeFailureBeforeExposurePreservesTheThrowableReleasesOwnersAndRetainsExactState(String stage) throws IOException {
        String[] parts = stage.split(":");
        var stop = Boundary.valueOf(parts[0]);
        var expected = failure(parts[1]);
        try (var env = environment()) {
            var before = snapshots(env);
            var seen = new ArrayList<Boundary>();
            var escaped = new AtomicReference<S3Database>();
            int start = env.fixture.requests().size();
            assertSame(expected, assertThrows(Throwable.class, () -> escaped.set(S3Database.open(env.databaseDirectory, env.stateDirectory,
                    env.target, BINDING, env.objects, env.heads, DEFAULTS, 2, boundary -> {
                        seen.add(boundary);
                        assertMetadataLocked(env);
                        if (boundary == Boundary.RECOVERED) { assertCoreLocked(env); }
                        if (boundary == stop) { throwFailure(expected); }
                    }))));
            assertEquals(stop == Boundary.HEAD_OBSERVED ? List.of(Boundary.HEAD_OBSERVED) : List.of(Boundary.HEAD_OBSERVED, Boundary.RECOVERED), seen);
            assertNull(escaped.get());
            assertEquals(1, countSince(env, start, "GET", headKey()));
            assertTrue(requestsSince(env, start).stream().allMatch(request -> request.method().equals("GET")));
            assertEquals(stop == Boundary.HEAD_OBSERVED ? 1 : 3, requestsSince(env, start).size());
            assertReleasedAndUnchanged(env, before);
            assertCallerClientsUsable(env);
        }
    }

    @Test void successfulWrapperRetainsAllThreeOwnersAndCloseInvalidatesViewsButPreservesReturnedValues() throws IOException {
        try (var env = environment()) {
            var before = snapshots(env);
            int start = env.fixture.requests().size();
            var database = env.open();
            var old = database.openView(new ReadCutoff.At(position(1)));
            var current = database.openView(ReadCutoff.Head.INSTANCE);
            var originalAnswer = old.query(new TemporalQuery.Get(SALARY, 13));
            var intervals = (TemporalResult.Segments) current.query(new TemporalQuery.Scan(SALARY, new ValidTimeInterval(0L, 30L)));
            var observation = database.observation();
            var request = database.lookupRequest(SECOND.transaction().requestId());
            try {
                assertEquals(position(2), database.head());
                assertEquals(Optional.of(position(1)), database.checkpointPosition());
                assertEquals(present(120), current.query(new TemporalQuery.Get(SALARY, 13)));
                assertEquals(env.expectedObservation.token(), observation.token());
                assertArrayEquals(env.expectedObservation.image(), observation.image());
                assertMetadataLocked(env);
                assertCoreLocked(env);
                assertEquals(1, countSince(env, start, "GET", headKey()));
                assertTrue(requestsSince(env, start).stream().allMatch(item -> item.method().equals("GET")));
                assertFalse(env.credentials.closed);
            } finally { database.close(); }
            assertThrows(IllegalStateException.class, database::head);
            assertThrows(IllegalStateException.class, database::observation);
            assertThrows(IllegalStateException.class, () -> database.checkpointPosition());
            assertThrows(IllegalStateException.class, () -> database.lookupRequest(FIRST.transaction().requestId()));
            assertThrows(IllegalStateException.class, () -> database.openView(ReadCutoff.Head.INSTANCE));
            assertThrows(IllegalStateException.class, () -> old.query(new TemporalQuery.Get(SALARY, 13)));
            assertThrows(IllegalStateException.class, () -> current.query(new TemporalQuery.Get(SALARY, 13)));
            old.close();
            current.close();
            database.close();
            assertEquals(present(100), originalAnswer);
            assertEquals(Optional.of(position(2)), request);
            assertEquals(List.of(new TemporalSegment(new ValidTimeInterval(0L, 10L), 100),
                    new TemporalSegment(new ValidTimeInterval(10L, 20L), 120),
                    new TemporalSegment(new ValidTimeInterval(20L, 30L), 100)), intervals.values());
            assertThrows(UnsupportedOperationException.class, () -> intervals.values().clear());
            byte[] copy = observation.image();
            copy[0] ^= 1;
            assertArrayEquals(env.expectedObservation.image(), observation.image());
            assertReleasedAndUnchanged(env, before);
            assertCallerClientsUsable(env);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"HEAD_OBSERVED", "RECOVERED"})
    void haltedStartupReleasesProcessOwnersAndReopensTheExactOriginalSelection(String name) throws Exception {
        var boundary = Boundary.valueOf(name);
        try (var env = environment()) {
            var before = snapshots(env);
            int start = env.fixture.requests().size();
            try (var child = startHaltProcess(env, boundary)) {
                int status = child.awaitExit();
                assumeTrue(status != UNSUPPORTED_STATUS, () -> "Child explicit directory-sync capability: " + child.diagnostics());
                assertEquals(HALT_STATUS, status, child.diagnostics());
                assertTrue(child.diagnostics().contains("HALT " + boundary), child.diagnostics());
            }
            assertEquals(1, countSince(env, start, "GET", headKey()));
            assertEquals(boundary == Boundary.HEAD_OBSERVED ? 1 : 3, requestsSince(env, start).size());
            assertTrue(requestsSince(env, start).stream().allMatch(request -> request.method().equals("GET")));
            assertReleasedAndUnchanged(env, before);
            try (var recovered = env.open(); var view = recovered.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(position(2), recovered.head());
                assertEquals(Optional.of(position(1)), recovered.checkpointPosition());
                assertEquals(present(120), view.query(new TemporalQuery.Get(SALARY, 13)));
                assertEquals(env.expectedObservation.token(), recovered.observation().token());
                assertArrayEquals(env.expectedObservation.image(), recovered.observation().image());
            }
            assertReleasedAndUnchanged(env, before);
        }
    }

    @Test void callerClientCloseWaitsForStartupAndRecoveredQueriesKeepWorkingAfterBothClientsClose() throws Exception {
        try (var env = environment(); var workers = Executors.newFixedThreadPool(3)) {
            var before = snapshots(env);
            var observed = new CountDownLatch(1);
            var continueRecovery = new CountDownLatch(1);
            var closeEntered = new CountDownLatch(2);
            var headThread = new AtomicReference<Thread>();
            var objectThread = new AtomicReference<Thread>();
            var opening = workers.submit(() -> S3Database.open(env.databaseDirectory, env.stateDirectory, env.target,
                    BINDING, env.objects, env.heads, DEFAULTS, 2, boundary -> {
                        if (boundary == Boundary.HEAD_OBSERVED) {
                            observed.countDown();
                            try {
                                if (!continueRecovery.await(10, TimeUnit.SECONDS)) { throw new IOException("Test recovery release timed out"); }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Interrupted while holding startup", interrupted);
                            }
                        }
                    }));
            try {
                assertTrue(observed.await(5, TimeUnit.SECONDS), "Startup did not reach its held observation");
                var closingHeads = workers.submit(() -> {
                    headThread.set(Thread.currentThread());
                    closeEntered.countDown();
                    env.heads.close();
                });
                var closingObjects = workers.submit(() -> {
                    objectThread.set(Thread.currentThread());
                    closeEntered.countDown();
                    env.objects.close();
                });
                assertTrue(closeEntered.await(5, TimeUnit.SECONDS), "Both caller close operations must enter");
                awaitBlockedOn(headThread.get(), env.heads);
                awaitBlockedOn(objectThread.get(), env.objects);
                assertFalse(closingHeads.isDone());
                assertFalse(closingObjects.isDone());
                continueRecovery.countDown();
                var recovered = opening.get(10, TimeUnit.SECONDS);
                closingHeads.get(10, TimeUnit.SECONDS);
                closingObjects.get(10, TimeUnit.SECONDS);
                try (var view = recovered.openView(ReadCutoff.Head.INSTANCE)) {
                    assertEquals(position(2), recovered.head());
                    assertEquals(present(120), view.query(new TemporalQuery.Get(SALARY, 13)));
                    assertArrayEquals(env.expectedObservation.image(), recovered.observation().image());
                }
                assertThrows(IllegalStateException.class, env.heads::read);
                assertThrows(IllegalStateException.class,
                        () -> env.objects.read(ObjectCheckpointGraphs.manifestKey(NAMESPACE, env.head.manifest().id()), env.head.manifest()));
                assertFalse(env.credentials.closed);
                assertEquals("fixture-access", env.credentials.resolveCredentials().accessKeyId());
            } finally {
                continueRecovery.countDown();
                // Obtain/close even a result that completed after a failed assertion, before worker cleanup.
                opening.get(15, TimeUnit.SECONDS).close();
            }
            assertReleasedAndUnchanged(env, before);
        }
    }

    private Environment environment() throws IOException {
        try { return Environment.create(Files.createTempDirectory(directory, "startup-")); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException | LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, () -> "Explicit directory-sync capability unavailable: " + unsupported.getMessage());
            throw unsupported;
        }
    }

    private Child startHaltProcess(Environment env, Boundary boundary) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        Path output = directory.resolve("halt-startup-" + boundary + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, StartupHaltProcess.class.getName(),
                env.databaseDirectory.toAbsolutePath().toString(), env.stateDirectory.toAbsolutePath().toString(), env.target.endpoint().toString(), boundary.name())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private static void assertMetadataLocked(Environment env) {
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalS3PublicationState.open(env.stateDirectory, env.target, BINDING)) { unexpected.state(); }
        });
    }

    private static void assertCoreLocked(Environment env) {
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalCheckpointStore.open(env.databaseDirectory)) { unexpected.journalPath(); }
        });
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalJournal.open(env.databaseDirectory.resolve("transactions.journal"))) { unexpected.prefix(0); }
        });
    }

    private static void assertReleasedAndUnchanged(Environment env, Map<String, Map<String, byte[]>> expected) throws IOException {
        try (var local = LocalS3PublicationState.open(env.stateDirectory, env.target, BINDING);
             var checkpoints = LocalCheckpointStore.open(env.databaseDirectory);
             var journal = LocalJournal.open(checkpoints.journalPath())) {
            assertArrayEquals(S3PublicationStateCodec.encode(env.expectedState), S3PublicationStateCodec.encode(local.state()));
            assertEquals(DATABASE, journal.databaseId());
            assertEquals(2, journal.appendedSequence());
        }
        var actual = snapshots(env);
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((scope, files) -> {
            assertEquals(files.keySet(), actual.get(scope).keySet());
            files.forEach((name, bytes) -> assertArrayEquals(bytes, actual.get(scope).get(name), scope + "/" + name));
        });
    }

    private static void assertCallerClientsUsable(Environment env) throws IOException {
        assertFalse(env.credentials.closed);
        assertEquals(env.expectedObservation.token(), env.heads.read().orElseThrow().token());
        try (var object = env.objects.read(ObjectCheckpointGraphs.manifestKey(NAMESPACE, env.head.manifest().id()), env.head.manifest())) {
            assertArrayEquals(env.manifestImage, object.bytes());
        }
        assertFalse(env.credentials.closed);
    }

    private static Map<String, Map<String, byte[]>> snapshots(Environment env) throws IOException {
        return Map.of("database", files(env.databaseDirectory), "state", files(env.stateDirectory));
    }
    private static Map<String, byte[]> files(Path directory) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var paths = Files.list(directory)) {
            for (var path : paths.toList()) { result.put(path.getFileName().toString(), Files.readAllBytes(path)); }
        }
        return result;
    }
    private static void assertEmpty(Path directory) throws IOException {
        try (var paths = Files.list(directory)) { assertTrue(paths.findAny().isEmpty()); }
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
        throw new AssertionError("Caller close did not block on held " + monitor.getClass().getSimpleName() + ": " + thread.getState());
    }

    private static List<S3PublicationProtocolFixture.Request> requestsSince(Environment env, int start) {
        var requests = env.fixture.requests();
        return requests.subList(start, requests.size());
    }
    private static long countSince(Environment env, int start, String method, String key) {
        return requestsSince(env, start).stream().filter(request -> request.method().equals(method) && request.key().equals(key)).count();
    }
    private static String headKey() { return S3CheckpointHeads.headKey(NAMESPACE).value(); }
    private static CommitPosition position(long sequence) { return new CommitPosition(DATABASE, sequence); }
    private static TemporalResult.Point present(long value) { return new TemporalResult.Point(OptionalLong.of(value)); }

    private static S3ObjectStore objectClient(Target target, AwsCredentialsProvider credentials) {
        return new S3ObjectStore(target.endpoint(), Region.of(target.regionId()), target.bucket(), credentials, TIMEOUTS);
    }
    private static S3CheckpointHeads headClient(Target target, AwsCredentialsProvider credentials) {
        return new S3CheckpointHeads(target.endpoint(), Region.of(target.regionId()), target.bucket(), credentials, BINDING, TIMEOUTS);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
    private static Throwable failure(String kind) {
        return switch (kind) {
            case "io" -> new IOException("startup probe failure");
            case "runtime" -> new IllegalStateException("startup probe failure");
            case "error" -> new AssertionError("startup probe failure");
            default -> throw new AssertionError(kind);
        };
    }
    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        throw new AssertionError(failure);
    }

    private static final class CallerCredentials implements AwsCredentialsProvider, AutoCloseable {
        private int resolutions;
        private boolean closed;
        @Override public AwsCredentials resolveCredentials() {
            if (closed) { throw new IllegalStateException("Caller credentials are closed"); }
            resolutions++;
            return AwsBasicCredentials.create("fixture-access", "fixture-secret");
        }
        @Override public void close() { closed = true; }
    }

    private static final class Environment implements AutoCloseable {
        private S3PublicationProtocolFixture fixture;
        private CallerCredentials credentials;
        private Target target;
        private Path databaseDirectory;
        private Path stateDirectory;
        private S3ObjectStore objects;
        private S3CheckpointHeads heads;
        private ObjectCheckpointHead head;
        private byte[] manifestImage;
        private S3PublicationState expectedState;
        private S3HeadObservation expectedObservation;

        static Environment create(Path root) throws IOException {
            var env = new Environment();
            try {
                env.fixture = new S3PublicationProtocolFixture();
                env.credentials = new CallerCredentials();
                env.target = new Target(env.fixture.endpoint(), "us-east-1", S3PublicationProtocolFixture.BUCKET);
                env.objects = objectClient(env.target, env.credentials);
                env.heads = headClient(env.target, env.credentials);
                env.databaseDirectory = Files.createDirectory(root.resolve("database"));
                env.stateDirectory = Files.createDirectory(root.resolve("state"));
                var prefix = new ByteArrayOutputStream();
                prefix.writeBytes(JournalCodec.encodeHeader(DATABASE));
                prefix.writeBytes(JournalCodec.encodeRecord(FIRST));
                var segment = new ImmutableSegment(DATABASE, List.of(FIRST));
                byte[] segmentImage = SegmentCodec.encode(segment);
                var segmentId = new SegmentId(hash(segmentImage));
                var manifest = new CheckpointManifest(DATABASE, 17,
                        new JournalPrefix(1, prefix.size(), hash(prefix.toByteArray())),
                        List.of(new SegmentReference(segmentId, segmentImage.length, 1, 1)));
                env.manifestImage = ManifestCodec.encode(manifest);
                env.head = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3), LINEAGE, 1,
                        ObjectReference.forBytes(env.manifestImage), manifest.journalPrefix(), null);
                env.fixture.put(ObjectCheckpointGraphs.manifestKey(NAMESPACE, env.head.manifest().id()).value(), env.manifestImage);
                env.fixture.put(ObjectCheckpointGraphs.segmentKey(NAMESPACE, segmentId).value(), segmentImage);
                try (var checkpoints = LocalCheckpointStore.open(env.databaseDirectory);
                     var journal = checkpoints.initialize(DATABASE);
                     var local = LocalS3PublicationState.create(env.stateDirectory, env.target, BINDING)) {
                    journal.append(FIRST);
                    journal.append(SECOND);
                    new S3PublicationPreparer(env.objects, env.heads, local, checkpoints, journal)
                            .prepare(ObjectCheckpointHeadCodec.encode(env.head), DEFAULTS);
                    var selected = new S3CheckpointPublisher(env.objects, env.heads, local, checkpoints, journal).submit(DEFAULTS);
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

        S3Database open() throws IOException { return S3Database.open(databaseDirectory, stateDirectory, target, BINDING, objects, heads, DEFAULTS); }

        @Override public void close() throws IOException {
            IOException failed = null;
            for (AutoCloseable resource : new AutoCloseable[] {heads, objects, fixture, credentials}) {
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
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) { throw new IOException("Startup child did not terminate"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating startup child", interrupted);
            } finally {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    /** Invoked only by this file's bounded real-HTTP startup/exit tests. */
    public static final class StartupHaltProcess {
        private StartupHaltProcess() { }
        public static void main(String[] args) throws Exception {
            Path database = Path.of(args[0]);
            Path state = Path.of(args[1]);
            var target = new Target(URI.create(args[2]), "us-east-1", S3PublicationProtocolFixture.BUCKET);
            var boundary = Boundary.valueOf(args[3]);
            try (var credentials = new CallerCredentials(); var objects = objectClient(target, credentials); var heads = headClient(target, credentials)) {
                try (var recovered = S3Database.open(database, state, target, BINDING, objects, heads, DEFAULTS, 2, reached -> {
                    if (reached == boundary) {
                        System.out.println("HALT " + reached);
                        System.out.flush();
                        Runtime.getRuntime().halt(HALT_STATUS);
                    }
                })) {
                    throw new AssertionError("Expected startup halt before returning " + recovered.head());
                }
            } catch (LocalS3PublicationState.DirectorySyncUnsupportedException | LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
                System.out.println("UNSUPPORTED " + unsupported.getMessage());
                System.exit(UNSUPPORTED_STATUS);
            }
        }
    }
}
