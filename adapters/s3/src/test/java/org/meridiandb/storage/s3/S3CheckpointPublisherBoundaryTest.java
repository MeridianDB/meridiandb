// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.temporal.DatabaseId;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

@Timeout(30)
class S3CheckpointPublisherBoundaryTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final DatabaseId DATABASE = new DatabaseId("publisher-boundaries");
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, ObjectCheckpointHeadVerifier.lineageId(DATABASE));
    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(AwsBasicCredentials.create("fixture-access", "fixture-secret"));
    private static final ObjectCheckpointGraphs.Limits LIMITS = ObjectCheckpointGraphs.DEFAULT_LIMITS;
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"write-before", "write-after", "rename-before", "rename-after", "directory-before", "directory-after"})
    void failedLocalResultInstallationRetainsRemoteCandidateAndReopensExactLocalOutcome(String fault) throws Exception {
        Path localHome = Files.createDirectory(directory.resolve("publisher"));
        try (var fixture = new S3PublicationProtocolFixture();
             var checkpoints = supportedCheckpoints(Files.createDirectory(directory.resolve("database")));
             var journal = LocalJournal.create(checkpoints.journalPath(), DATABASE);
             var objects = objects(fixture); var heads = heads(fixture)) {
            var candidate = graph(objects, journal);
            S3PublicationState pending;
            try (var local = supportedLocal(localHome, target(fixture))) {
                pending = new S3PublicationPreparer(objects, heads, local, checkpoints, journal).prepare(candidate.image(), LIMITS).pending();
            }
            var nativeNamespace = LocalS3PublicationState.NioNamespace.acquire(localHome, false, channel -> channel.force(true));
            try (var failed = new LocalS3PublicationState(new FaultNamespace(nativeNamespace, fault), pending, null)) {
                var publisher = new S3CheckpointPublisher(objects, heads, failed, checkpoints, journal);
                var failure = assertThrows(S3CheckpointPublicationException.class, () -> publisher.submit(LIMITS));
                assertEquals(S3CheckpointPublicationException.Code.STILL_PENDING, failure.code());
                assertTrue(failure.writeAttempted()); assertThrows(IOException.class, failed::state);
                assertArrayEquals(candidate.image(), fixture.bytes(S3CheckpointHeads.headKey(NAMESPACE).value()));
                assertArrayEquals(candidate.image(), fixture.bytes(S3PublicationPreparer.intentKey(NAMESPACE, ObjectReference.forBytes(candidate.image()).id()).value()));
            }
            try (var reopened = LocalS3PublicationState.open(localHome, target(fixture), BINDING)) {
                boolean renamed = fault.equals("rename-after") || fault.startsWith("directory");
                assertEquals(renamed ? 3 : 2, reopened.state().generation());
                if (renamed) {
                    assertTrue(reopened.state().pending().isEmpty());
                    assertArrayEquals(candidate.image(), reopened.state().expected().orElseThrow().image());
                } else {
                    assertArrayEquals(candidate.image(), reopened.state().pending().orElseThrow().image());
                    var recovered = new S3CheckpointPublisher(objects, heads, reopened, checkpoints, journal).reconcile(LIMITS);
                    assertFalse(recovered.writeAttempted()); assertTrue(recovered.state().pending().isEmpty());
                }
            }
            assertEquals(1, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
            assertArrayEquals(candidate.manifest(), fixture.bytes(candidate.manifestKey().value()));
            assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString());
        }
    }

    @Test void corruptionAfterPutFailsClosedWithoutClearingPendingOrFallingBack() throws Exception {
        try (var fixture = new S3PublicationProtocolFixture();
             var checkpoints = supportedCheckpoints(Files.createDirectory(directory.resolve("database")));
             var journal = LocalJournal.create(checkpoints.journalPath(), DATABASE);
             var local = supportedLocal(Files.createDirectory(directory.resolve("publisher")), target(fixture));
             var objects = objects(fixture); var heads = heads(fixture)) {
            var candidate = graph(objects, journal);
            new S3PublicationPreparer(objects, heads, local, checkpoints, journal).prepare(candidate.image(), LIMITS);
            var publisher = new S3CheckpointPublisher(objects, heads, local, checkpoints, journal, boundary -> {
                if (boundary == S3CheckpointPublisher.Boundary.WRITE_FINISHED) {
                    byte[] damaged = candidate.manifest().clone(); damaged[0] ^= 1;
                    fixture.forceState(candidate.manifestKey().value(), damaged, "\"damaged\"");
                }
            });
            var failure = assertThrows(S3CheckpointPublicationException.class, () -> publisher.submit(LIMITS));
            assertTrue(failure.writeAttempted()); assertNotNull(failure.getCause());
            assertArrayEquals(candidate.image(), local.state().pending().orElseThrow().image());
            fixture.forceState(candidate.manifestKey().value(), candidate.manifest(), "\"restored\""); // Clear only the injected fixture fault.
            var resolved = new S3CheckpointPublisher(objects, heads, local, checkpoints, journal).reconcile(LIMITS);
            assertFalse(resolved.writeAttempted()); assertTrue(resolved.state().pending().isEmpty());
            assertEquals(1, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
            assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString());
        }
    }

    @Test void allFiveResourceClosesWaitUntilVerifiedSelectionAndLocalResultComplete() throws Exception {
        Path localHome = Files.createDirectory(directory.resolve("publisher"));
        try (var fixture = new S3PublicationProtocolFixture();
             var checkpoints = supportedCheckpoints(Files.createDirectory(directory.resolve("database")));
             var journal = LocalJournal.create(checkpoints.journalPath(), DATABASE);
             var local = supportedLocal(localHome, target(fixture));
             var objects = objects(fixture); var heads = heads(fixture)) {
            var candidate = graph(objects, journal);
            new S3PublicationPreparer(objects, heads, local, checkpoints, journal).prepare(candidate.image(), LIMITS);
            var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
            var publisher = new S3CheckpointPublisher(objects, heads, local, checkpoints, journal, boundary -> {
                if (boundary == S3CheckpointPublisher.Boundary.BEFORE_WRITE) { reached.countDown(); await(release); }
            });
            var publication = new FutureTask<>(() -> publisher.submit(LIMITS));
            var worker = new Thread(publication, "publisher");
            var closers = new ArrayList<Thread>(); var closures = new ArrayList<FutureTask<Void>>(); worker.start();
            try {
                assertTrue(reached.await(5, TimeUnit.SECONDS));
                for (AutoCloseable resource : List.of(local, checkpoints, journal, heads, objects)) {
                    var close = new FutureTask<Void>(() -> { resource.close(); return null; });
                    var thread = new Thread(close, "publisher-resource-close"); closures.add(close); closers.add(thread); thread.start();
                }
                for (var thread : closers) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.nanoTime() < deadline) { Thread.sleep(1); }
                    assertEquals(Thread.State.BLOCKED, thread.getState());
                }
            } finally { release.countDown(); }
            var result = publication.get(10, TimeUnit.SECONDS);
            for (var close : closures) { close.get(5, TimeUnit.SECONDS); }
            worker.join(1000); for (var thread : closers) { thread.join(1000); }
            assertTrue(result.state().pending().isEmpty()); assertTrue(result.writeAcknowledged());
            try (var reopened = LocalS3PublicationState.open(localHome, target(fixture), BINDING)) {
                assertArrayEquals(candidate.image(), reopened.state().expected().orElseThrow().image());
            }
            assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString());
        }
    }

    @Test void reentrantSubmissionAndReconciliationCannotIssueAnotherRequest() throws Exception {
        try (var fixture = new S3PublicationProtocolFixture();
             var checkpoints = supportedCheckpoints(Files.createDirectory(directory.resolve("database")));
             var journal = LocalJournal.create(checkpoints.journalPath(), DATABASE);
             var local = supportedLocal(Files.createDirectory(directory.resolve("publisher")), target(fixture));
             var objects = objects(fixture); var heads = heads(fixture)) {
            var candidate = graph(objects, journal);
            new S3PublicationPreparer(objects, heads, local, checkpoints, journal).prepare(candidate.image(), LIMITS);
            var holder = new S3CheckpointPublisher[1];
            var publisher = new S3CheckpointPublisher(objects, heads, local, checkpoints, journal, boundary -> {
                assertThrows(IOException.class, () -> holder[0].submit(LIMITS));
                assertThrows(IOException.class, () -> holder[0].reconcile(LIMITS));
            });
            holder[0] = publisher; assertTrue(publisher.submit(LIMITS).writeAcknowledged());
            assertEquals(1, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
        }
    }

    private record Candidate(byte[] image, byte[] manifest, ObjectKey manifestKey) { }
    private static Candidate graph(S3ObjectStore objects, LocalJournal journal) throws IOException {
        var actual = journal.prefix(0);
        var prefix = new CheckpointManifest.JournalPrefix(0, actual.byteLength(), actual.sha256());
        byte[] manifest = ManifestCodec.encode(new CheckpointManifest(DATABASE, 7, prefix, List.of()));
        var reference = ObjectReference.forBytes(manifest); var key = ObjectCheckpointGraphs.manifestKey(NAMESPACE, reference.id()); objects.createNew(key, manifest);
        byte[] head = ObjectCheckpointHeadCodec.encode(new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3), BINDING.lineageId(), 1, reference, prefix, null));
        return new Candidate(head, manifest, key);
    }
    private static S3ObjectStore objects(S3PublicationProtocolFixture fixture) { return new S3ObjectStore(fixture.endpoint(), Region.US_EAST_1, "bucket", CREDENTIALS); }
    private static S3CheckpointHeads heads(S3PublicationProtocolFixture fixture) { return new S3CheckpointHeads(fixture.endpoint(), Region.US_EAST_1, "bucket", CREDENTIALS, BINDING); }
    private static S3PublicationState.Target target(S3PublicationProtocolFixture fixture) { return new S3PublicationState.Target(fixture.endpoint(), "us-east-1", "bucket"); }
    private static LocalCheckpointStore supportedCheckpoints(Path path) throws IOException {
        try { return LocalCheckpointStore.open(path); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) { assumeTrue(false, unsupported.toString()); throw unsupported; }
    }
    private static LocalS3PublicationState supportedLocal(Path path, S3PublicationState.Target target) throws IOException {
        try { return LocalS3PublicationState.create(path, target, BINDING); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) { assumeTrue(false, unsupported.toString()); throw unsupported; }
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) { throw new IOException("Publisher barrier timed out"); } }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }
    private static final class FaultNamespace implements LocalS3PublicationState.Namespace {
        private final LocalS3PublicationState.Namespace delegate; private final String fault;
        FaultNamespace(LocalS3PublicationState.Namespace delegate, String fault) { this.delegate = delegate; this.fault = fault; }
        private void fail(String at) throws IOException { if (fault.equals(at)) { throw new IOException("Injected " + at); } }
        @Override public List<String> entries() throws IOException { return delegate.entries(); }
        @Override public byte[] read(String name) throws IOException { return delegate.read(name); }
        @Override public void forceFile(String name) throws IOException { delegate.forceFile(name); }
        @Override public void writeNew(String name, byte[] image) throws IOException { fail("write-before"); delegate.writeNew(name, image); fail("write-after"); }
        @Override public void atomicMove(String from, String to) throws IOException { fail("rename-before"); delegate.atomicMove(from, to); fail("rename-after"); }
        @Override public void forceDirectory() throws IOException { fail("directory-before"); delegate.forceDirectory(); fail("directory-after"); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
