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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectIntegrityException;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3PublicationPreparer.Boundary;
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

/** Real SDK/filesystem boundaries and JVM exits, without mutable-head publication or power-loss claims. */
@Timeout(90)
class S3PublicationPreparerBoundaryTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final DatabaseId DATABASE = new DatabaseId("s3-preparer-boundary");
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER,
            ObjectCheckpointHeadVerifier.lineageId(DATABASE));
    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("fixture-access", "fixture-secret"));
    private static final S3ObjectStore.Timeouts TIMEOUTS = new S3ObjectStore.Timeouts(
            Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(2));
    private static final JournalRecord RECORD = new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
            new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(null, null), 100))));
    private static final int HALT_STATUS = 74;
    private static final int UNSUPPORTED_STATUS = 77;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(25);
    @TempDir Path directory;
    private S3ProtocolFixture fixture;

    @BeforeEach void start() throws IOException { fixture = new S3ProtocolFixture(); }
    @AfterEach void stop() throws IOException {
        try { assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString()); }
        finally { fixture.close(); }
    }

    static Stream<Arguments> preparationBoundaries() {
        return Stream.of(Boundary.values()).flatMap(boundary -> Stream.of(false, true)
                .map(replacement -> Arguments.of(boundary, replacement)));
    }

    @ParameterizedTest(name = "{0}, replacement={1}") @MethodSource("preparationBoundaries")
    void probeFailuresRetainOnlyEffectsCompletedAtThatBoundaryAndLeaveCallersUsable(
            Boundary boundary, boolean replacement) throws Exception {
        try (var scenario = setup(replacement)) {
            var context = scenario.context();
            var original = context.local.state();
            var injected = new IOException("Stop after " + boundary);
            var reachedBoundaries = new ArrayList<Boundary>();
            var preparer = new S3PublicationPreparer(context.objects, context.heads, context.local,
                    context.checkpoints, context.journal, reached -> {
                        reachedBoundaries.add(reached);
                        if (reached == boundary) { throw injected; }
                    });
            assertSame(injected, assertThrows(IOException.class,
                    () -> preparer.prepare(scenario.candidate().image(), ObjectCheckpointGraphs.DEFAULT_LIMITS)));
            var requiredOrder = List.of(Boundary.GRAPH_VERIFIED, Boundary.JOURNAL_SYNCED,
                    Boundary.INTENT_INSTALLED, Boundary.STATE_SYNCED);
            assertEquals(requiredOrder.subList(0, requiredOrder.indexOf(boundary) + 1), reachedBoundaries);
            boolean forced = boundary != Boundary.GRAPH_VERIFIED;
            boolean uploaded = boundary == Boundary.INTENT_INSTALLED || boundary == Boundary.STATE_SYNCED;
            boolean pending = boundary == Boundary.STATE_SYNCED;
            assertEquals(forced ? 1 : 0, context.journal.forcedSequence());
            if (uploaded) {
                assertArrayEquals(scenario.candidate().image(), fixture.bytes(intentKey(scenario.candidate()).value()));
            } else { assertNull(fixture.bytes(intentKey(scenario.candidate()).value())); }
            var actual = context.local.state();
            assertArrayEquals(S3PublicationStateCodec.encode(pending ? original.prepare(scenario.candidate().image()) : original),
                    S3PublicationStateCodec.encode(actual));
            if (pending) { assertPending(actual, scenario.candidate().image(), replacement); }
            assertEquals(0, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
            assertCallerResourcesUsable(context, scenario.candidate(), replacement);
        }
    }

    @Test void anExistingIdenticalIntentIsCompletelyVerifiedAndReused() throws Exception {
        try (var scenario = setup(false)) {
            var context = scenario.context();
            var key = intentKey(scenario.candidate());
            var reference = ObjectReference.forBytes(scenario.candidate().image());
            assertEquals(reference, context.objects.createNew(key, scenario.candidate().image()));
            var prepared = preparer(context).prepare(scenario.candidate().image(), ObjectCheckpointGraphs.DEFAULT_LIMITS);
            assertFalse(prepared.intentCreated());
            assertEquals(reference, prepared.intentReference());
            assertEquals(1, prepared.forcedSequence());
            assertPending(prepared.pending(), scenario.candidate().image(), false);
            assertEquals(2, fixture.count("PUT", key.value()));
            assertEquals(1, fixture.count("GET", key.value()));
            assertEquals(0, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
            assertCallerResourcesUsable(context, scenario.candidate(), false);
        }
    }

    @Test void aConflictingIntentObjectIsNeverReplacedOrConvertedIntoLocalPendingState() throws Exception {
        try (var scenario = setup(false)) {
            var context = scenario.context();
            var key = intentKey(scenario.candidate());
            var changed = scenario.candidate().image().clone();
            changed[changed.length - 1] ^= 1;
            fixture.put(key.value(), changed);
            var failure = assertThrows(ObjectIntegrityException.class,
                    () -> preparer(context).prepare(scenario.candidate().image(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
            assertEquals(ObjectIntegrityException.Code.ID_MISMATCH, failure.code());
            assertArrayEquals(changed, fixture.bytes(key.value()));
            assertEquals(1, context.journal.forcedSequence());
            assertTrue(context.local.state().pending().isEmpty());
            assertEquals(1, context.local.state().generation());
            assertEquals(1, fixture.count("PUT", key.value()));
            assertEquals(1, fixture.count("GET", key.value()));
            assertEquals(0, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
            assertCallerResourcesUsable(context, scenario.candidate(), false);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostImmutableIntentResponseRequiresExplicitVerifiedRetryBeforeLocalPending(boolean afterEffect) throws Exception {
        try (var scenario = setup(true)) {
            var context = scenario.context();
            var key = intentKey(scenario.candidate());
            var original = context.local.state();
            fixture.fault("PUT", key.value(), afterEffect ? S3ProtocolFixture.Fault.DROP_AFTER : S3ProtocolFixture.Fault.DROP_BEFORE);
            assertThrows(IOException.class,
                    () -> preparer(context).prepare(scenario.candidate().image(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
            assertEquals(1, fixture.count("PUT", key.value()), "An uncertain immutable create must not be retried implicitly");
            assertArrayEquals(S3PublicationStateCodec.encode(original), S3PublicationStateCodec.encode(context.local.state()));
            assertEquals(1, context.journal.forcedSequence());
            if (afterEffect) { assertArrayEquals(scenario.candidate().image(), fixture.bytes(key.value())); }
            else { assertNull(fixture.bytes(key.value())); }
            fixture.clearFault("PUT", key.value());
            var retried = preparer(context).prepare(scenario.candidate().image(), ObjectCheckpointGraphs.DEFAULT_LIMITS);
            assertEquals(!afterEffect, retried.intentCreated());
            assertEquals(ObjectReference.forBytes(scenario.candidate().image()), retried.intentReference());
            assertPending(retried.pending(), scenario.candidate().image(), true);
            assertEquals(afterEffect ? 1 : 0, fixture.count("GET", key.value()));
            assertEquals(0, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
            assertCallerResourcesUsable(context, scenario.candidate(), true);
        }
    }

    @Test void exhaustedLocalGenerationCapacityRejectsBeforeAnyRemoteRequestJournalForceOrIntentUpload() throws Exception {
        try (var scenario = setup(false)) {
            var context = scenario.context();
            context.local.close();
            var target = target(fixture.endpoint());
            for (int generation = 2; generation <= LocalS3PublicationState.MAX_GENERATIONS; generation++) {
                var state = S3PublicationState.restore(generation, target, BINDING, null, null);
                byte[] image = S3PublicationStateCodec.encode(state);
                String name = String.format(Locale.ROOT, "publication-%019d-%s.state", generation,
                        ObjectReference.forBytes(image).id().value());
                Files.write(context.localHome.resolve(name), image, StandardOpenOption.CREATE_NEW);
            }
            context.local = LocalS3PublicationState.open(context.localHome, target, BINDING);
            int before = fixture.requests().size();
            var failure = assertThrows(IOException.class,
                    () -> preparer(context).prepare(scenario.candidate().image(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
            assertTrue(failure.getMessage().contains("capacity"), failure.getMessage());
            assertEquals(before, fixture.requests().size());
            assertEquals(0, context.journal.forcedSequence());
            assertNull(fixture.bytes(intentKey(scenario.candidate()).value()));
            context.local.close();
            context.local = LocalS3PublicationState.open(context.localHome, target, BINDING);
            assertEquals(LocalS3PublicationState.MAX_GENERATIONS, context.local.state().generation());
            assertTrue(context.local.state().pending().isEmpty());
            assertEquals(0, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
        }
    }

    @ParameterizedTest @EnumSource(value = Boundary.class, names = {"INTENT_INSTALLED", "STATE_SYNCED"})
    void abruptChildExitPreservesExactIntentAndOnlyDurablyInstalledPendingState(Boundary boundary) throws Exception {
        Graph candidate;
        Path checkpoints;
        Path local;
        S3PublicationState before;
        try (var scenario = setup(true)) {
            candidate = scenario.candidate();
            checkpoints = scenario.context().checkpointHome;
            local = scenario.context().localHome;
            before = scenario.context().local.state();
        }
        // The loopback server remains parent-owned while the child owns all native/SDK resources.
        var candidateFile = directory.resolve("supplied-candidate.bin");
        Files.write(candidateFile, candidate.image(), StandardOpenOption.CREATE_NEW);
        try (var child = startChild(checkpoints, local, candidateFile, boundary)) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            String marker = "HALT " + boundary + " FORCED 1";
            assertTrue(child.diagnostics().lines().anyMatch(marker::equals), child.diagnostics());
        }
        assertArrayEquals(candidate.image(), fixture.bytes(intentKey(candidate).value()));
        try (var context = openExisting(checkpoints, local)) {
            var reopened = context.local.state();
            var expected = boundary == Boundary.STATE_SYNCED ? before.prepare(candidate.image()) : before;
            assertArrayEquals(S3PublicationStateCodec.encode(expected), S3PublicationStateCodec.encode(reopened));
            assertEquals(S3ProtocolFixture.ETAG, reopened.expected().orElseThrow().token().value());
            if (boundary == Boundary.INTENT_INSTALLED) {
                assertTrue(reopened.pending().isEmpty());
                var resumed = preparer(context).prepare(candidate.image(), ObjectCheckpointGraphs.DEFAULT_LIMITS);
                assertFalse(resumed.intentCreated());
                assertPending(resumed.pending(), candidate.image(), true);
            } else {
                assertPending(reopened, candidate.image(), true);
                int requests = fixture.requests().size();
                var failure = assertThrows(S3PublicationStateException.class,
                        () -> preparer(context).prepare(candidate.image(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
                assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION, failure.code());
                assertEquals(requests, fixture.requests().size(), "An unresolved durable intent cannot start another preparation");
            }
            assertEquals(1, context.journal.prefix(1).sequence());
        }
        assertEquals(0, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
    }

    private Scenario setup(boolean replacement) throws IOException {
        var checkpointHome = Files.createDirectory(directory.resolve("checkpoints"));
        var localHome = Files.createDirectory(directory.resolve("metadata"));
        var context = acquire(fixture.endpoint(), checkpointHome, localHome, true);
        try {
            context.journal.append(RECORD);
            var previous = graph(17, 1, null, context.journal.prefix(1));
            var candidate = replacement ? graph(18, 2, previous.head(), context.journal.prefix(1)) : previous;
            context.objects.createNew(previous.segmentKey(), previous.segmentImage());
            context.objects.createNew(previous.manifestKey(), previous.manifestImage());
            if (replacement) {
                context.objects.createNew(candidate.manifestKey(), candidate.manifestImage());
                context.local.prepare(previous.image());
                context.local.recordSelected(observation(fixture.endpoint(), previous.image()));
                fixture.put(S3CheckpointHeads.headKey(NAMESPACE).value(), previous.image());
            }
            return new Scenario(context, candidate);
        } catch (IOException | RuntimeException | Error failure) {
            try { context.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private Context openExisting(Path checkpoints, Path local) throws IOException {
        return acquire(fixture.endpoint(), checkpoints, local, false);
    }

    private static Context acquire(URI endpoint, Path checkpoints, Path local, boolean create) throws IOException {
        try { return Context.acquire(endpoint, checkpoints, local, create); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException | LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Required native directory force unavailable: " + unsupported);
            throw new AssertionError("Unreachable assumption continuation", unsupported);
        }
    }

    private void assertCallerResourcesUsable(Context context, Graph graph, boolean replacement) throws IOException {
        context.local.state();
        assertEquals(context.checkpoints.journalPath(), context.journal.path().orElseThrow());
        assertEquals(1, context.journal.prefix(1).sequence());
        assertEquals(replacement, context.heads.read().isPresent());
        try (var reader = context.objects.read(graph.manifestKey(), ObjectReference.forBytes(graph.manifestImage()))) {
            assertArrayEquals(graph.manifestImage(), reader.bytes());
        }
    }

    private static void assertPending(S3PublicationState state, byte[] candidate, boolean replacement) {
        var pending = state.pending().orElseThrow();
        assertArrayEquals(candidate, pending.image());
        assertEquals(replacement ? S3PublicationState.Operation.REPLACE_IF_MATCH : S3PublicationState.Operation.CREATE_IF_ABSENT,
                pending.operation());
        assertEquals(replacement ? S3ProtocolFixture.ETAG : "*", pending.precondition());
        assertEquals(replacement ? 4 : 2, state.generation());
        if (replacement) { assertEquals(S3ProtocolFixture.ETAG, state.expected().orElseThrow().token().value()); }
        else { assertTrue(state.expected().isEmpty()); }
    }

    private static S3PublicationPreparer preparer(Context context) {
        return new S3PublicationPreparer(context.objects, context.heads, context.local, context.checkpoints, context.journal);
    }
    private static S3PublicationState.Target target(URI endpoint) {
        return new S3PublicationState.Target(endpoint, Region.US_EAST_1.id(), S3ProtocolFixture.BUCKET);
    }
    private static S3HeadObservation observation(URI endpoint, byte[] image) {
        return S3HeadObservation.restore(endpoint, S3ProtocolFixture.BUCKET, image, new S3HeadToken(S3ProtocolFixture.ETAG));
    }
    private static ObjectKey intentKey(Graph graph) {
        return S3PublicationPreparer.intentKey(NAMESPACE, ObjectReference.forBytes(graph.image()).id());
    }

    private static Graph graph(long generation, long revision, ObjectCheckpointHead predecessor, LocalJournal.Prefix actual) {
        byte[] segmentImage = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(RECORD)));
        var id = new SegmentId(ObjectReference.forBytes(segmentImage).id().value());
        var prefix = new CheckpointManifest.JournalPrefix(actual.sequence(), actual.byteLength(), actual.sha256());
        var manifest = new CheckpointManifest(DATABASE, generation, prefix,
                List.of(new CheckpointManifest.SegmentReference(id, segmentImage.length, 1, 1)));
        byte[] manifestImage = ManifestCodec.encode(manifest);
        var manifestReference = ObjectReference.forBytes(manifestImage);
        var head = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, revision + 100), BINDING.lineageId(),
                revision, manifestReference, prefix, predecessor == null ? null : ObjectCheckpointHeadCodec.reference(predecessor));
        return new Graph(head, ObjectCheckpointHeadCodec.encode(head),
                ObjectCheckpointGraphs.segmentKey(NAMESPACE, id), segmentImage,
                ObjectCheckpointGraphs.manifestKey(NAMESPACE, manifestReference.id()), manifestImage);
    }

    private Child startChild(Path checkpoints, Path local, Path candidate, Boundary boundary) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        var output = directory.resolve("child-" + UUID.randomUUID() + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, PreparationProcess.class.getName(),
                fixture.endpoint().toString(), checkpoints.toAbsolutePath().toString(), local.toAbsolutePath().toString(),
                candidate.toAbsolutePath().toString(), boundary.name()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private record Graph(ObjectCheckpointHead head, byte[] image, ObjectKey segmentKey, byte[] segmentImage,
                         ObjectKey manifestKey, byte[] manifestImage) { }
    private record Scenario(Context context, Graph candidate) implements AutoCloseable {
        @Override public void close() throws IOException { context.close(); }
    }

    private static final class Context implements AutoCloseable {
        final Path checkpointHome;
        final Path localHome;
        S3ObjectStore objects;
        S3CheckpointHeads heads;
        LocalS3PublicationState local;
        LocalCheckpointStore checkpoints;
        LocalJournal journal;
        private Context(Path checkpointHome, Path localHome) { this.checkpointHome = checkpointHome; this.localHome = localHome; }
        static Context acquire(URI endpoint, Path checkpointHome, Path localHome, boolean create) throws IOException {
            var context = new Context(checkpointHome, localHome);
            try {
                context.objects = new S3ObjectStore(endpoint, Region.US_EAST_1, S3ProtocolFixture.BUCKET, CREDENTIALS, TIMEOUTS);
                context.heads = new S3CheckpointHeads(endpoint, Region.US_EAST_1, S3ProtocolFixture.BUCKET, CREDENTIALS, BINDING, TIMEOUTS);
                context.checkpoints = LocalCheckpointStore.open(checkpointHome);
                context.journal = create ? LocalJournal.create(context.checkpoints.journalPath(), DATABASE)
                        : LocalJournal.open(context.checkpoints.journalPath());
                context.local = create ? LocalS3PublicationState.create(localHome, target(endpoint), BINDING)
                        : LocalS3PublicationState.open(localHome, target(endpoint), BINDING);
                return context;
            } catch (IOException | RuntimeException | Error failure) {
                try { context.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        @Override public void close() throws IOException {
            IOException primary = null;
            for (var resource : new AutoCloseable[] {local, journal, checkpoints, heads, objects}) {
                if (resource == null) { continue; }
                try { resource.close(); }
                catch (Exception failure) {
                    var cleanup = failure instanceof IOException io ? io : new IOException("Context cleanup failed", failure);
                    if (primary == null) { primary = cleanup; } else { primary.addSuppressed(cleanup); }
                }
            }
            if (primary != null) { throw primary; }
        }
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Path output;
        Child(Process process, Path output) { this.process = process; this.output = output; }
        String diagnostics() {
            try { return Files.readString(output, StandardCharsets.UTF_8); }
            catch (IOException failure) { return "Cannot read child output: " + failure; }
        }
        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    () -> "Child did not exit: " + diagnostics());
            int status = process.exitValue();
            assumeTrue(status != UNSUPPORTED_STATUS, "Required child native directory force unavailable: " + diagnostics());
            return status;
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Child did not terminate: " + diagnostics());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating preparation child", interrupted);
            } finally {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    /** Child owns local/SDK resources; the parent owns the unchanged loopback server. */
    public static final class PreparationProcess {
        private PreparationProcess() { }
        public static void main(String[] args) throws Exception {
            var endpoint = URI.create(args[0]);
            var checkpointHome = Path.of(args[1]);
            var localHome = Path.of(args[2]);
            byte[] candidate = Files.readAllBytes(Path.of(args[3]));
            var boundary = Boundary.valueOf(args[4]);
            try (var context = Context.acquire(endpoint, checkpointHome, localHome, false)) {
                var preparer = new S3PublicationPreparer(context.objects, context.heads, context.local,
                        context.checkpoints, context.journal, reached -> {
                            if (reached == boundary) {
                                System.out.println("HALT " + reached + " FORCED " + context.journal.forcedSequence());
                                System.out.flush();
                                Runtime.getRuntime().halt(HALT_STATUS);
                            }
                        });
                preparer.prepare(candidate, ObjectCheckpointGraphs.DEFAULT_LIMITS);
                throw new AssertionError("Requested production halt boundary was not reached");
            } catch (LocalCheckpointStore.DirectorySyncUnsupportedException | LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
                System.out.println("DIRECTORY SYNC UNSUPPORTED: " + unsupported);
                System.out.flush();
                System.exit(UNSUPPORTED_STATUS);
            }
        }
    }
}
