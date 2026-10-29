// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.manifest.LocalCheckpointStore.PublicationBoundary;

/** Real JVM exits verify compaction selection/reopen; the OS remains alive, without power-loss claims. */
@Timeout(45)
class LocalCompactionCrashTest {
    private static final int HALT_STATUS = 76;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path directory;

    @Test
    void exitAfterDependencySyncKeepsOldCompleteGenerationAndUnselectedInstalledOutput() throws Exception {
        exercise(PublicationBoundary.DEPENDENCIES_SYNCED, 2);
    }

    @Test
    void exitAfterCandidateSyncKeepsOldCompleteGenerationAndPendingEvidence() throws Exception {
        exercise(PublicationBoundary.CANDIDATE_SYNCED, 2);
    }

    @Test
    void exitAfterManifestRenameReopensNewCompleteGenerationWithoutLosingJournalTail() throws Exception {
        exercise(PublicationBoundary.MANIFEST_RENAMED, 3);
    }

    @Test
    void exitAfterFinalDirectorySyncReopensNewCompleteGenerationWithoutLosingJournalTail() throws Exception {
        exercise(PublicationBoundary.DIRECTORY_SYNCED, 3);
    }

    private void exercise(PublicationBoundary boundary, long selectedGeneration) throws Exception {
        Path home = freshHome();
        try (var child = start(home, boundary)) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.hasLine("ACKNOWLEDGED 1 2 3"), child.diagnostics());
            assertTrue(child.hasLine("REACHED " + boundary), child.diagnostics());
        }
        assertArrayEquals(LocalCompactionIntegrationTest.journalImage(LocalCompactionIntegrationTest.HISTORY),
                Files.readAllBytes(home.resolve("transactions.journal")));
        // Installation precedes each of the four observed publication boundaries.
        // Verify unselected output directly too: selecting the old generation alone
        // would not detect an incomplete or incorrectly named replacement object.
        byte[] expectedReplacement = SegmentCodec.encode(new ImmutableSegment(
                LocalCompactionIntegrationTest.DATABASE, LocalCompactionIntegrationTest.HISTORY.subList(0, 2)));
        var expectedReplacementId = SegmentCodec.decode(expectedReplacement).id();
        Path replacementPath = home.resolve(LocalCheckpointStore.segmentName(expectedReplacementId));
        assertTrue(Files.isRegularFile(replacementPath, LinkOption.NOFOLLOW_LINKS));
        byte[] storedReplacement = Files.readAllBytes(replacementPath);
        assertArrayEquals(expectedReplacement, storedReplacement);
        assertEquals(LocalCompactionIntegrationTest.HISTORY.subList(0, 2),
                SegmentCodec.decode(storedReplacement, expectedReplacementId).segment().records());
        byte[] prefixBytes = LocalCompactionIntegrationTest.journalImage(LocalCompactionIntegrationTest.HISTORY.subList(0, 2));
        var expectedManifest = new CheckpointManifest(LocalCompactionIntegrationTest.DATABASE, 3,
                new CheckpointManifest.JournalPrefix(2, prefixBytes.length,
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prefixBytes))),
                List.of(new CheckpointManifest.SegmentReference(expectedReplacementId, expectedReplacement.length, 1, 2)));
        byte[] expectedManifestImage = ManifestCodec.encode(expectedManifest);
        var expectedManifestId = ManifestCodec.decode(expectedManifestImage).id();
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath());
             var selected = store.readLatest(journal).orElseThrow()) {
            assertEquals(selectedGeneration, selected.manifest().generation());
            assertEquals(2, selected.manifest().journalPrefix().sequence());
            assertEquals(selectedGeneration == 2 ? 2 : 1, selected.manifest().segments().size());
            var records = new ArrayList<JournalRecord>();
            for (int index = 0; index < selected.manifest().segments().size(); index++) {
                try (var segment = selected.openSegment(index)) { records.addAll(segment.segment().records()); }
            }
            assertEquals(LocalCompactionIntegrationTest.HISTORY.subList(0, 2), records);
            var prefix = journal.prefix(2);
            assertEquals(prefix.byteLength(), selected.manifest().journalPrefix().byteLength());
            assertEquals(prefix.sha256(), selected.manifest().journalPrefix().sha256());
            var replayed = new ArrayList<JournalRecord>();
            journal.replay(replayed::add);
            assertEquals(LocalCompactionIntegrationTest.HISTORY, replayed);
        }
        // Both original segment files and both selected/old complete manifests must
        // remain. This publication stage has no physical reclamation authority.
        for (var record : LocalCompactionIntegrationTest.HISTORY.subList(0, 2)) {
            var image = org.meridiandb.storage.segment.SegmentCodec.encode(
                    new org.meridiandb.storage.segment.ImmutableSegment(LocalCompactionIntegrationTest.DATABASE,
                            java.util.List.of(record)));
            var id = org.meridiandb.storage.segment.SegmentCodec.decode(image).id();
            assertTrue(Files.exists(home.resolve(LocalCheckpointStore.segmentName(id))));
        }
        try (var files = Files.list(home)) {
            String oldPrefix = String.format(java.util.Locale.ROOT, "checkpoint-%019d-", 2);
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith(oldPrefix)),
                    "The original selected manifest remains retained after compaction");
        }
        if (boundary == PublicationBoundary.CANDIDATE_SYNCED) {
            try (var files = Files.list(home)) {
                var candidates = files.filter(path -> path.getFileName().toString().startsWith("pending-3-")).toList();
                assertEquals(1, candidates.size());
                Path candidate = candidates.getFirst();
                assertTrue(Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS));
                byte[] storedCandidate = Files.readAllBytes(candidate);
                assertArrayEquals(expectedManifestImage, storedCandidate);
                var decoded = ManifestCodec.decode(storedCandidate, expectedManifestId);
                assertEquals(expectedManifestId, decoded.id());
                assertEquals(expectedManifest, decoded.manifest());
            }
        }
        LocalCompactionIntegrationTest.verifyWritableHistory(home);
    }

    private Path freshHome() throws IOException {
        Path capability = Files.createDirectory(directory.resolve("capability"));
        try (var store = LocalCheckpointStore.open(capability)) { store.journalPath(); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
        }
        return Files.createDirectory(directory.resolve("database"));
    }

    private Child start(Path home, PublicationBoundary boundary) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java.exe"); }
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        // Initialization requires a fresh store; process logs stay in its parent.
        Path output = directory.resolve("compaction-child-" + boundary + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, CompactionProcess.class.getName(),
                home.toAbsolutePath().toString(), boundary.name())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Path output;
        Child(Process process, Path output) { this.process = process; this.output = output; }
        String diagnostics() {
            try { return Files.readString(output, StandardCharsets.UTF_8); }
            catch (IOException failure) { return "Cannot read child output: " + failure; }
        }
        boolean hasLine(String expected) { return diagnostics().lines().anyMatch(expected::equals); }
        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    () -> "Compaction child did not exit: " + diagnostics());
            return process.exitValue();
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Compaction child did not terminate: " + diagnostics());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating compaction child", interrupted);
            } finally {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    /** Uses real write/checkpoint/compaction paths, halting only after the requested boundary returns. */
    public static final class CompactionProcess {
        private CompactionProcess() { }
        public static void main(String[] args) throws Exception {
            Path home = Path.of(args[0]);
            var requested = PublicationBoundary.valueOf(args[1]);
            var fixture = LocalCompactionIntegrationTest.seedFixture(home, true);
            System.out.println("ACKNOWLEDGED 1 2 3");
            System.out.flush();
            try (var store = LocalCheckpointStore.openObserved(home, reached -> {
                if (reached == requested) {
                    System.out.println("REACHED " + reached);
                    System.out.flush();
                    Runtime.getRuntime().halt(HALT_STATUS);
                }
            }); var journal = LocalJournal.open(store.journalPath())) {
                store.publishCompaction(fixture.plan(), journal);
                throw new AssertionError("Compaction returned before the requested halt boundary");
            }
        }
    }
}
