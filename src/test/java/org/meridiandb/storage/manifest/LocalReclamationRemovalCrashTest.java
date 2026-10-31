// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.LocalCheckpointStore.ReclamationBoundary;

/** Real process exits preserve every surviving graph; the OS remains alive, without power-loss claims. */
@Timeout(60)
class LocalReclamationRemovalCrashTest {
    private static final int HALT_STATUS = 78;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path directory;

    static Stream<Arguments> stops() {
        return Stream.of(
                Arguments.of(ReclamationBoundary.LIVE_ROOTS_SYNCED, 1, 0, 0),
                Arguments.of(ReclamationBoundary.MANIFEST_REMOVED, 1, 1, 0),
                Arguments.of(ReclamationBoundary.MANIFEST_REMOVED, 2, 2, 0),
                Arguments.of(ReclamationBoundary.MANIFESTS_SYNCED, 1, 2, 0),
                Arguments.of(ReclamationBoundary.SEGMENT_REMOVED, 1, 2, 1),
                Arguments.of(ReclamationBoundary.SEGMENT_REMOVED, 2, 2, 2),
                Arguments.of(ReclamationBoundary.DIRECTORY_SYNCED, 1, 2, 2));
    }

    @ParameterizedTest
    @MethodSource("stops")
    void eachProcessStopLeavesOnlyCompleteSurvivingGraphsAndRetryPreservesAlreadyOrphanedDependencies(
            ReclamationBoundary boundary, int occurrence, int removedManifests, int removedSegments) throws Exception {
        Path home = freshHome();
        var fixture = LocalCompactionIntegrationTest.seedFixture(home, true);
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
            store.publishCompaction(fixture.plan(), journal);
        }
        var before = LocalReclamationRemovalIntegrationTest.snapshot(home);
        try (var child = start(home, boundary, occurrence)) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.hasLine("REACHED " + boundary + " " + occurrence), child.diagnostics());
        }
        var stopped = LocalReclamationRemovalIntegrationTest.snapshot(home);
        assertEquals(3 - removedManifests, stopped.keySet().stream().filter(name -> name.startsWith("checkpoint-")).count());
        assertEquals(3 - removedSegments, stopped.keySet().stream().filter(name -> name.endsWith(".segment")).count());
        assertTrue(before.keySet().containsAll(stopped.keySet()), "Removal must create no intent, candidate or replacement name");
        stopped.forEach((name, image) -> assertArrayEquals(before.get(name), image, name));
        assertArrayEquals(fixture.journalBytes(), stopped.get(LocalCheckpointStore.JOURNAL));
        assertArrayEquals(before.get(".meridiandb-checkpoints.lock"), stopped.get(".meridiandb-checkpoints.lock"));
        assertArrayEquals(before.get(".meridiandb-readers.lock"), stopped.get(".meridiandb-readers.lock"));
        assertArrayEquals(before.get(LocalReclamationRemovalIntegrationTest.selectedName(fixture)),
                stopped.get(LocalReclamationRemovalIntegrationTest.selectedName(fixture)));
        assertCompleteSurvivingGraphs(home, stopped, fixture);
        LocalCompactionIntegrationTest.verifyWritableHistory(home);
        var orphanedAtStop = unreferencedSegments(stopped);
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
            var retry = store.reclaim(Set.of(), journal);
            assertEquals(ReclamationResult.Status.COMPLETED, retry.status());
            assertEquals(LocalReclamationRemovalIntegrationTest.selectedId(fixture), retry.selectedManifestId().orElseThrow());
            for (var removed : retry.removedSegments()) {
                assertTrue(!orphanedAtStop.contains(removed.name()), "Fresh removal must not adopt a prior orphan as a candidate");
            }
            assertTrue(store.reclaim(Set.of(), journal).removedSegments().isEmpty());
        }
        var retried = LocalReclamationRemovalIntegrationTest.snapshot(home);
        for (String orphan : orphanedAtStop) { assertArrayEquals(stopped.get(orphan), retried.get(orphan), orphan); }
        assertEquals(1, retried.keySet().stream().filter(name -> name.startsWith("checkpoint-")).count());
        assertCompleteSurvivingGraphs(home, retried, fixture);
        LocalCompactionIntegrationTest.verifyWritableHistory(home);
    }

    private static void assertCompleteSurvivingGraphs(Path home, Map<String, byte[]> snapshot,
                                                      LocalCompactionIntegrationTest.Fixture fixture) throws IOException {
        var manifests = snapshot.entrySet().stream().filter(entry -> entry.getKey().startsWith("checkpoint-"))
                .map(entry -> ManifestCodec.decode(entry.getValue()).manifest()).toList();
        for (var manifest : manifests) {
            CheckpointVerifier.verify(manifest, id -> snapshot.get(LocalCheckpointStore.segmentName(id)));
        }
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath());
             var selected = store.readLatest(journal).orElseThrow()) {
            assertEquals(LocalReclamationRemovalIntegrationTest.selectedId(fixture), selected.manifestId());
            assertEquals(fixture.plan().replacementManifest(), selected.manifest());
            for (var manifest : manifests) {
                var actual = journal.prefix(manifest.journalPrefix().sequence());
                assertEquals(manifest.journalPrefix().byteLength(), actual.byteLength());
                assertEquals(manifest.journalPrefix().sha256(), actual.sha256());
            }
            try (var heap = selected.openSegment(0)) {
                assertEquals(LocalCompactionIntegrationTest.HISTORY.subList(0, 2), heap.segment().records());
            }
        }
    }

    private static Set<String> unreferencedSegments(Map<String, byte[]> snapshot) {
        var referenced = new HashSet<String>();
        snapshot.forEach((name, image) -> {
            if (name.startsWith("checkpoint-")) {
                ManifestCodec.decode(image).manifest().segments().forEach(reference -> referenced.add(LocalCheckpointStore.segmentName(reference.id())));
            }
        });
        var orphans = new HashSet<String>();
        snapshot.keySet().stream().filter(name -> name.endsWith(".segment") && !referenced.contains(name)).forEach(orphans::add);
        return orphans;
    }

    private Path freshHome() throws IOException {
        Path capability = Files.createDirectory(directory.resolve("capability"));
        try (var store = LocalCheckpointStore.open(capability)) { store.journalPath(); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Required directory force is unavailable: " + unsupported);
        }
        return Files.createDirectory(directory.resolve("database"));
    }

    private Child start(Path home, ReclamationBoundary boundary, int occurrence) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java.exe"); }
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        Path output = directory.resolve("removal-halt-" + boundary + "-" + occurrence + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, RemovalCrashProcess.class.getName(),
                home.toAbsolutePath().toString(), boundary.name(), Integer.toString(occurrence))
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
                    () -> "Removal crash child did not exit: " + diagnostics());
            return process.exitValue();
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Removal crash child did not terminate: " + diagnostics());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating removal crash child", interrupted);
            } finally {
                process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close();
            }
        }
    }

    public static final class RemovalCrashProcess {
        private RemovalCrashProcess() { }
        public static void main(String[] args) throws Exception {
            Path home = Path.of(args[0]);
            var requested = ReclamationBoundary.valueOf(args[1]);
            int occurrence = Integer.parseInt(args[2]);
            int[] seen = {0};
            try (var store = LocalCheckpointStore.openReclamationObserved(home, (boundary, name) -> {
                if (boundary == requested && ++seen[0] == occurrence) {
                    System.out.println("REACHED " + boundary + " " + occurrence);
                    System.out.flush();
                    Runtime.getRuntime().halt(HALT_STATUS);
                }
            }); var journal = LocalJournal.open(store.journalPath())) {
                store.reclaim(Set.of(), journal);
                throw new AssertionError("Removal returned without reaching the requested halt boundary");
            }
        }
    }
}
