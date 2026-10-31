// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
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
import org.meridiandb.storage.manifest.LocalCheckpointStore.PublicationBoundary;
import org.meridiandb.storage.manifest.LocalCheckpointStore.ReclamationBoundary;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;

/** Combined durable pipeline stops on a twelve-transaction history; process exit is not power loss. */
@Timeout(90)
class LocalCompactionPipelineCrashTest {
    private static final int HALT_STATUS = 79;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path directory;

    static Stream<Arguments> stops() {
        return Stream.of(
                Arguments.of("publication", PublicationBoundary.DEPENDENCIES_SYNCED.name(), 1),
                Arguments.of("publication", PublicationBoundary.CANDIDATE_SYNCED.name(), 1),
                Arguments.of("publication", PublicationBoundary.MANIFEST_RENAMED.name(), 1),
                Arguments.of("publication", PublicationBoundary.DIRECTORY_SYNCED.name(), 1),
                Arguments.of("removal", ReclamationBoundary.LIVE_ROOTS_SYNCED.name(), 1),
                Arguments.of("removal", ReclamationBoundary.MANIFEST_REMOVED.name(), 1),
                Arguments.of("removal", ReclamationBoundary.MANIFEST_REMOVED.name(), 2),
                Arguments.of("removal", ReclamationBoundary.MANIFESTS_SYNCED.name(), 1),
                Arguments.of("removal", ReclamationBoundary.SEGMENT_REMOVED.name(), 1),
                Arguments.of("removal", ReclamationBoundary.SEGMENT_REMOVED.name(), 8),
                Arguments.of("removal", ReclamationBoundary.DIRECTORY_SYNCED.name(), 1));
    }

    @ParameterizedTest
    @MethodSource("stops")
    void actualPublicationAndRemovalStopsPreserveEveryHistoricalAnswerIdentityAndSurvivingGraph(
            String phase, String boundary, int occurrence) throws Exception {
        Path home = freshHome();
        var fixture = LocalCompactionPipelineHistory.seed(home, LocalCompactionPipelineHistory.Layout.COMBINE);
        if (phase.equals("removal")) {
            try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
                store.publishCompaction(fixture.plan(), journal);
            }
        }
        var before = LocalCompactionPipelineHistory.images(home);
        try (var child = start(home, phase, boundary, occurrence)) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.hasLine("REACHED " + phase + " " + boundary + " " + occurrence), child.diagnostics());
        }
        var stopped = LocalCompactionPipelineHistory.images(home);
        LocalCompactionPipelineHistory.assertDiagnosticsAndJournal(stopped);
        ManifestId selected;
        if (phase.equals("publication")) {
            selected = boundary.equals(PublicationBoundary.DEPENDENCIES_SYNCED.name())
                    || boundary.equals(PublicationBoundary.CANDIDATE_SYNCED.name())
                    ? fixture.sourceId() : LocalCompactionPipelineHistory.selectedId(fixture);
            assertPublicationObjects(before, stopped, fixture, boundary);
        } else {
            selected = LocalCompactionPipelineHistory.selectedId(fixture);
            assertRemovalObjects(before, stopped, fixture, boundary, occurrence);
        }
        LocalCompactionPipelineHistory.assertGraphs(home, stopped, selected);
        LocalCompactionPipelineHistory.assertAnswers(home); // Every cutoff/query/request through actual writable reopen.
        assertArrayEquals(LocalCompactionPipelineHistory.journalImage(), Files.readAllBytes(home.resolve(LocalCheckpointStore.JOURNAL)));
        var orphans = unreferencedSegments(stopped);
        boolean diagnosticHold = stopped.keySet().stream().anyMatch(name -> name.startsWith("pending-"));
        if (diagnosticHold) {
            try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
                assertThrows(IOException.class, () -> store.reclaim(Set.of(), journal));
            }
            assertImagesEqual(stopped, LocalCompactionPipelineHistory.images(home));
            // Pending images remain complete exact evidence. No implicit inspection/deletion policy is supplied.
        } else {
            try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
                if (selected.equals(fixture.sourceId())) {
                    // Retry actual installation/publication; any complete unselected output is verified/reused.
                    assertEquals(LocalCompactionPipelineHistory.selectedId(fixture), store.publishCompaction(fixture.plan(), journal));
                }
                var result = store.reclaim(Set.of(), journal);
                assertEquals(ReclamationResult.Status.COMPLETED, result.status());
                assertEquals(LocalCompactionPipelineHistory.selectedId(fixture), result.selectedManifestId().orElseThrow());
                for (var removed : result.removedSegments()) {
                    assertTrue(!orphans.contains(removed.name()), "Fresh removal cannot adopt a prior orphan by its filename");
                }
                assertEquals(0, store.reclaim(Set.of(), journal).removedBytes());
            }
            var retried = LocalCompactionPipelineHistory.images(home);
            for (String orphan : orphans) { assertArrayEquals(stopped.get(orphan), retried.get(orphan), orphan); }
            LocalCompactionPipelineHistory.assertDiagnosticsAndJournal(retried);
            LocalCompactionPipelineHistory.assertGraphs(home, retried, LocalCompactionPipelineHistory.selectedId(fixture));
            LocalCompactionPipelineHistory.assertAnswers(home);
            assertEquals(1, retried.keySet().stream().filter(name -> name.startsWith("checkpoint-")).count());
        }
        System.out.println("PIPELINE_CRASH_JSON {\"dataset\":\"" + LocalCompactionPipelineHistory.DATASET
                + "\",\"phase\":\"" + phase + "\",\"boundary\":\"" + boundary + "\",\"occurrence\":" + occurrence
                + ",\"queryCasesPerCutoff\":" + LocalCompactionPipelineHistory.QUERIES.size()
                + ",\"orphanSegmentsAtStop\":" + orphans.size() + ",\"diagnosticCleanupHold\":" + diagnosticHold + "}");
    }

    private static void assertPublicationObjects(Map<String, byte[]> before, Map<String, byte[]> after,
                                                 LocalCompactionPipelineHistory.Fixture fixture, String boundary) {
        before.forEach((name, image) -> assertArrayEquals(image, after.get(name), name));
        var expectedNames = new HashSet<>(before.keySet());
        for (int index = 0; index < fixture.plan().segments().size(); index++) {
            var reference = fixture.plan().replacementManifest().segments().get(index);
            String name = LocalCheckpointStore.segmentName(reference.id());
            expectedNames.add(name);
            assertArrayEquals(SegmentCodec.encode(fixture.plan().segments().get(index)), after.get(name), name);
            assertEquals(reference.id(), SegmentCodec.decode(after.get(name), reference.id()).id());
        }
        byte[] manifestImage = ManifestCodec.encode(fixture.plan().replacementManifest());
        if (boundary.equals(PublicationBoundary.CANDIDATE_SYNCED.name())) {
            var pending = after.keySet().stream().filter(name -> name.startsWith("pending-3-")).toList();
            assertEquals(1, pending.size());
            expectedNames.add(pending.getFirst());
            assertArrayEquals(manifestImage, after.get(pending.getFirst()));
            assertEquals(fixture.plan().replacementManifest(),
                    ManifestCodec.decode(after.get(pending.getFirst()), LocalCompactionPipelineHistory.selectedId(fixture)).manifest());
        } else if (!boundary.equals(PublicationBoundary.DEPENDENCIES_SYNCED.name())) {
            expectedNames.add(LocalCompactionPipelineHistory.selectedName(fixture));
            assertArrayEquals(manifestImage, after.get(LocalCompactionPipelineHistory.selectedName(fixture)));
        }
        assertEquals(expectedNames, after.keySet());
    }

    private static void assertRemovalObjects(Map<String, byte[]> before, Map<String, byte[]> after,
                                             LocalCompactionPipelineHistory.Fixture fixture, String boundary,
                                             int occurrence) {
        assertTrue(before.keySet().containsAll(after.keySet()), "Removal creates no new name or intent");
        after.forEach((name, image) -> assertArrayEquals(before.get(name), image, name));
        int metadataRemoved = boundary.equals(ReclamationBoundary.LIVE_ROOTS_SYNCED.name()) ? 0
                : boundary.equals(ReclamationBoundary.MANIFEST_REMOVED.name()) ? occurrence : 2;
        int segmentsRemoved = boundary.equals(ReclamationBoundary.SEGMENT_REMOVED.name()) ? occurrence
                : boundary.equals(ReclamationBoundary.DIRECTORY_SYNCED.name()) ? 8 : 0;
        assertEquals(3 - metadataRemoved, after.keySet().stream().filter(name -> name.startsWith("checkpoint-")).count());
        assertEquals(before.keySet().stream().filter(name -> name.endsWith(".segment")).count() - segmentsRemoved,
                after.keySet().stream().filter(name -> name.endsWith(".segment")).count());
        assertArrayEquals(before.get(LocalCompactionPipelineHistory.selectedName(fixture)),
                after.get(LocalCompactionPipelineHistory.selectedName(fixture)));
        if (segmentsRemoved > 0) {
            assertEquals(Set.of(LocalCompactionPipelineHistory.selectedName(fixture)), after.keySet().stream()
                    .filter(name -> name.startsWith("checkpoint-")).collect(java.util.stream.Collectors.toSet()));
        }
    }

    private static Set<String> unreferencedSegments(Map<String, byte[]> images) {
        var referenced = new HashSet<String>();
        images.forEach((name, image) -> {
            if (name.startsWith("checkpoint-")) {
                ManifestCodec.decode(image).manifest().segments().forEach(reference -> referenced.add(LocalCheckpointStore.segmentName(reference.id())));
            }
        });
        return images.keySet().stream().filter(name -> name.endsWith(".segment") && !referenced.contains(name))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void assertImagesEqual(Map<String, byte[]> before, Map<String, byte[]> after) {
        assertEquals(before.keySet(), after.keySet());
        before.forEach((name, image) -> assertArrayEquals(image, after.get(name), name));
    }

    private Path freshHome() throws IOException {
        Path capability = Files.createDirectory(directory.resolve("capability"));
        try (var store = LocalCheckpointStore.open(capability)) { store.journalPath(); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Required directory force is unavailable: " + unsupported);
        }
        return Files.createDirectory(directory.resolve("database"));
    }

    private Child start(Path home, String phase, String boundary, int occurrence) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java.exe"); }
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        Path output = directory.resolve("pipeline-" + phase + "-" + boundary + "-" + occurrence + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, PipelineProcess.class.getName(),
                home.toAbsolutePath().toString(), phase, boundary, Integer.toString(occurrence))
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
                    () -> "Pipeline child did not exit: " + diagnostics());
            return process.exitValue();
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Pipeline child did not terminate: " + diagnostics());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating pipeline child", interrupted);
            } finally {
                process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close();
            }
        }
    }

    public static final class PipelineProcess {
        private PipelineProcess() { }
        public static void main(String[] args) throws Exception {
            Path home = Path.of(args[0]);
            String phase = args[1];
            String requested = args[2];
            int occurrence = Integer.parseInt(args[3]);
            if (phase.equals("publication")) {
                var requestedBoundary = PublicationBoundary.valueOf(requested);
                try (var store = LocalCheckpointStore.openObserved(home, boundary -> {
                    if (boundary == requestedBoundary) { halt(phase, requested, occurrence); }
                }); var journal = LocalJournal.open(store.journalPath())) {
                    CheckpointManifest source;
                    var images = new HashMap<SegmentId, byte[]>();
                    try (var current = store.readLatest(journal).orElseThrow()) {
                        source = current.manifest();
                        for (int index = 0; index < source.segments().size(); index++) {
                            try (var heap = current.openSegment(index)) { images.put(source.segments().get(index).id(), SegmentCodec.encode(heap.segment())); }
                        }
                    }
                    var plan = HistoryCompactor.compact(source, images::get, HistoryCompactor.Limits.DEFAULT);
                    store.publishCompaction(plan, journal);
                    throw new AssertionError("Publication did not halt at its requested boundary");
                }
            } else if (phase.equals("removal")) {
                var requestedBoundary = ReclamationBoundary.valueOf(requested);
                int[] seen = {0};
                try (var store = LocalCheckpointStore.openReclamationObserved(home, (boundary, name) -> {
                    if (boundary == requestedBoundary && ++seen[0] == occurrence) { halt(phase, requested, occurrence); }
                }); var journal = LocalJournal.open(store.journalPath())) {
                    store.reclaim(Set.of(), journal);
                    throw new AssertionError("Removal did not halt at its requested boundary");
                }
            } else { throw new IllegalArgumentException("Unknown pipeline phase: " + phase); }
        }
        private static void halt(String phase, String boundary, int occurrence) {
            System.out.println("REACHED " + phase + " " + boundary + " " + occurrence);
            System.out.flush();
            Runtime.getRuntime().halt(HALT_STATUS);
        }
    }
}
