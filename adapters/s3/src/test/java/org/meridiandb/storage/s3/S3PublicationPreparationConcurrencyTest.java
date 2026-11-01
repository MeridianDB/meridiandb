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
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.temporal.DatabaseId;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

@Timeout(30)
class S3PublicationPreparationConcurrencyTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final DatabaseId DATABASE = new DatabaseId("preparation-concurrency");
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(
            NAMESPACE, PUBLISHER, ObjectCheckpointHeadVerifier.lineageId(DATABASE));
    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(AwsBasicCredentials.create("fixture-access", "fixture-secret"));
    @TempDir Path directory;

    @Test void allFiveResourceClosesWaitForPreparationAndPendingSurvivesTheirCompletion() throws Exception {
        Path localPath = Files.createDirectory(directory.resolve("publisher"));
        try (var fixture = new S3ProtocolFixture();
             var checkpoints = supportedCheckpoints(Files.createDirectory(directory.resolve("database")));
             var journal = LocalJournal.create(checkpoints.journalPath(), DATABASE);
             var local = supportedLocal(localPath, target(fixture));
             var objects = S3ObjectStoreTest.connect(fixture);
             var heads = new S3CheckpointHeads(fixture.endpoint(), Region.US_EAST_1, "bucket", CREDENTIALS, BINDING)) {
            byte[] candidate = installGraph(objects, journal);
            var reached = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var preparer = new S3PublicationPreparer(objects, heads, local, checkpoints, journal, boundary -> {
                if (boundary == S3PublicationPreparer.Boundary.GRAPH_VERIFIED) { reached.countDown(); await(release); }
            });
            var preparation = new FutureTask<>(() -> preparer.prepare(candidate, ObjectCheckpointGraphs.DEFAULT_LIMITS));
            var writer = new Thread(preparation, "preparation");
            var closers = new ArrayList<Thread>();
            var closures = new ArrayList<FutureTask<Void>>();
            writer.start();
            try {
                assertTrue(reached.await(5, TimeUnit.SECONDS));
                for (AutoCloseable resource : List.of(local, checkpoints, journal, heads, objects)) {
                    var close = new FutureTask<Void>(() -> { resource.close(); return null; });
                    var thread = new Thread(close, "cooperating-resource-close");
                    closures.add(close); closers.add(thread); thread.start();
                }
                for (var thread : closers) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.nanoTime() < deadline) { Thread.sleep(1); }
                    assertEquals(Thread.State.BLOCKED, thread.getState(), "Close must wait for the held resource monitor");
                }
            } finally { release.countDown(); }
            var result = preparation.get(10, TimeUnit.SECONDS);
            for (var close : closures) { close.get(5, TimeUnit.SECONDS); }
            writer.join(1000); for (var thread : closers) { thread.join(1000); }
            assertArrayEquals(candidate, result.pending().pending().orElseThrow().image());
            assertThrows(IOException.class, local::state);
            assertEquals(0, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
            try (var reopened = LocalS3PublicationState.open(localPath, target(fixture), BINDING)) {
                assertArrayEquals(candidate, reopened.state().pending().orElseThrow().image());
                assertEquals(2, reopened.state().generation());
            }
            assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString());
        }
    }

    @Test void reentrantPreparationCannotStartAnotherGraphOrHeadRequest() throws Exception {
        try (var fixture = new S3ProtocolFixture();
             var checkpoints = supportedCheckpoints(Files.createDirectory(directory.resolve("database")));
             var journal = LocalJournal.create(checkpoints.journalPath(), DATABASE);
             var local = supportedLocal(Files.createDirectory(directory.resolve("publisher")), target(fixture));
             var objects = S3ObjectStoreTest.connect(fixture);
             var heads = new S3CheckpointHeads(fixture.endpoint(), Region.US_EAST_1, "bucket", CREDENTIALS, BINDING)) {
            byte[] candidate = installGraph(objects, journal);
            var holder = new S3PublicationPreparer[1];
            var preparer = new S3PublicationPreparer(objects, heads, local, checkpoints, journal,
                    boundary -> assertThrows(IOException.class, () -> holder[0].prepare(candidate, ObjectCheckpointGraphs.DEFAULT_LIMITS)));
            holder[0] = preparer;
            var result = preparer.prepare(candidate, ObjectCheckpointGraphs.DEFAULT_LIMITS);
            assertEquals(2, result.pending().generation());
            assertEquals(1, fixture.count("GET", S3CheckpointHeads.headKey(NAMESPACE).value()));
            assertEquals(0, fixture.count("PUT", S3CheckpointHeads.headKey(NAMESPACE).value()));
            assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString());
        }
    }

    private static byte[] installGraph(S3ObjectStore objects, LocalJournal journal) throws IOException {
        var actual = journal.prefix(0);
        var prefix = new CheckpointManifest.JournalPrefix(actual.sequence(), actual.byteLength(), actual.sha256());
        byte[] manifest = ManifestCodec.encode(new CheckpointManifest(DATABASE, 7, prefix, List.of()));
        var reference = ObjectReference.forBytes(manifest);
        objects.createNew(ObjectCheckpointGraphs.manifestKey(NAMESPACE, reference.id()), manifest);
        return ObjectCheckpointHeadCodec.encode(new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3),
                BINDING.lineageId(), 1, reference, prefix, null));
    }
    private static S3PublicationState.Target target(S3ProtocolFixture fixture) { return new S3PublicationState.Target(fixture.endpoint(), "us-east-1", "bucket"); }
    private static LocalCheckpointStore supportedCheckpoints(Path path) throws IOException {
        try { return LocalCheckpointStore.open(path); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) { assumeTrue(false, unsupported.toString()); throw unsupported; }
    }
    private static LocalS3PublicationState supportedLocal(Path path, S3PublicationState.Target target) throws IOException {
        try { return LocalS3PublicationState.create(path, target, BINDING); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) { assumeTrue(false, unsupported.toString()); throw unsupported; }
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) { throw new IOException("Preparation barrier timed out"); } }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }
}
