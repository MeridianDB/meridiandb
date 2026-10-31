// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.internal.LocalFileOwnership;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.LocalCheckpointStore.ReclamationBoundary;
import org.meridiandb.storage.segment.SegmentId;

/** Real ordered removal, owner/reader exclusion and exact surviving object evidence. */
@Timeout(90)
class LocalReclamationRemovalIntegrationTest {
    private static final String GUARD = ".meridiandb-readers.lock";
    private static final String LOCK = ".meridiandb-checkpoints.lock";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path directory;

    @Test
    void successfulRemovalMatchesFreshInventoryAndPreservesExactSelectedGraphJournalAndControls() throws IOException {
        var fixture = published("database", true);
        var before = snapshot(fixture.home());
        ReclamationInventory inventory;
        ReclamationResult result;
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            inventory = store.inspectReclamation(Set.of(), journal);
            result = store.reclaim(Set.of(), journal);
            assertEquals(ReclamationResult.Status.COMPLETED, result.status());
            assertEquals(inventory.selectedManifestId(), result.selectedManifestId());
            assertEquals(inventory.retainedManifestIds(), result.retainedManifestIds());
            assertEquals(inventory.obsoleteManifests(), result.removedManifests());
            assertEquals(inventory.obsoleteSegments(), result.removedSegments());
            assertEquals(inventory.candidateBytes(), result.removedBytes());
            assertThrows(UnsupportedOperationException.class, () -> result.removedManifests().clear());
            assertThrows(UnsupportedOperationException.class, () -> result.removedSegments().clear());
            assertThrows(UnsupportedOperationException.class, () -> result.retainedManifestIds().clear());
            try (var latest = store.readLatest(journal).orElseThrow(); var heap = latest.openSegment(0)) {
                assertEquals(selectedId(fixture), latest.manifestId());
                assertEquals(LocalCompactionIntegrationTest.HISTORY.subList(0, 2), heap.segment().records());
            }
        }
        var after = snapshot(fixture.home());
        assertRemovedOnly(before, after, result);
        assertTrue(after.containsKey(GUARD));
        assertTrue(after.containsKey(LOCK));
        assertArrayEquals(fixture.journalBytes(), after.get(LocalCheckpointStore.JOURNAL));
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
        assertImagesEqual(after, snapshot(fixture.home()));
    }

    @Test
    void explicitRetainedBackupRootKeepsEveryDependencyUntilItIsOmittedFromAFreshRemoval() throws IOException {
        var fixture = published("database", true);
        ReclamationResult first;
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            first = store.reclaim(Set.of(fixture.sourceId()), journal);
            assertEquals(Set.of(fixture.sourceId(), selectedId(fixture)), first.retainedManifestIds());
            assertEquals(1, first.removedManifests().size()); // Genesis alone.
            assertTrue(first.removedSegments().isEmpty());
            for (var reference : fixture.source().segments()) {
                assertTrue(Files.exists(fixture.home().resolve(LocalCheckpointStore.segmentName(reference.id()))));
            }
            var second = store.reclaim(Set.of(), journal);
            assertEquals(1, second.removedManifests().size());
            assertEquals(2, second.removedSegments().size());
            var repeated = store.reclaim(Set.of(), journal);
            assertEquals(ReclamationResult.Status.COMPLETED, repeated.status());
            assertTrue(repeated.removedManifests().isEmpty());
            assertTrue(repeated.removedSegments().isEmpty());
            assertEquals(0, repeated.removedBytes());
        }
        assertFalse(first.removedManifests().isEmpty());
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
    }

    @Test
    void unchangedLayoutRemovesOnlyMetadataAndNeverDeletesSharedLiveSegments() throws IOException {
        var fixture = published("database", false);
        var before = snapshot(fixture.home());
        ReclamationResult result;
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            result = store.reclaim(Set.of(), journal);
            assertEquals(2, result.removedManifests().size());
            assertTrue(result.removedSegments().isEmpty());
        }
        assertRemovedOnly(before, snapshot(fixture.home()), result);
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
    }

    @Test
    void genesisGenerationsRequireNoSegmentDeletionAndKeepExactJournalLineage() throws IOException {
        Path home = home("genesis");
        try (var database = LocalDatabase.create(home, LocalCompactionIntegrationTest.DATABASE)) { assertEquals(0, database.head().sequence()); }
        var before = snapshot(home);
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
            CheckpointManifest source;
            try (var current = store.readLatest(journal).orElseThrow()) { source = current.manifest(); }
            var plan = HistoryCompactor.compact(source, ignored -> { throw new AssertionError("Genesis has no segment"); },
                    HistoryCompactor.Limits.DEFAULT);
            var selected = store.publishCompaction(plan, journal);
            var result = store.reclaim(Set.of(), journal);
            assertEquals(selected, result.selectedManifestId().orElseThrow());
            assertEquals(1, result.removedManifests().size());
            assertTrue(result.removedSegments().isEmpty());
        }
        assertArrayEquals(before.get(LocalCheckpointStore.JOURNAL), Files.readAllBytes(home.resolve(LocalCheckpointStore.JOURNAL)));
        try (var database = LocalDatabase.openWritable(home)) { assertEquals(0, database.head().sequence()); }
    }

    @Test
    void emptySelectionLeavesUnreferencedDiagnosticFilesAndReturnsNoRemoval() throws IOException {
        Path home = home("empty");
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.create(store.journalPath(), LocalCompactionIntegrationTest.DATABASE)) {
            assertEquals(0, journal.appendedSequence());
        }
        Files.write(home.resolve("unknown.segment"), new byte[] {8, 3});
        var before = snapshot(home);
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
            var result = store.reclaim(Set.of(), journal);
            assertEquals(ReclamationResult.Status.COMPLETED, result.status());
            assertTrue(result.selectedManifestId().isEmpty());
            assertTrue(result.removedManifests().isEmpty());
            assertTrue(result.removedSegments().isEmpty());
            assertEquals(0, result.removedBytes());
        }
        var after = snapshot(home);
        before.forEach((name, image) -> assertArrayEquals(image, after.get(name), name));
        assertEquals(new HashSet<>(before.keySet()), withoutGuard(after.keySet(), before.containsKey(GUARD)));
    }

    @Test
    void unknownOrphanAndOperatorEvidenceSurviveEvenWhenTheirFilenameLooksLikeASegment() throws IOException {
        var fixture = published("database", true);
        String orphan = LocalCheckpointStore.segmentName(new SegmentId("d".repeat(64)));
        Files.write(fixture.home().resolve(orphan), new byte[] {9, 2, 4});
        Files.writeString(fixture.home().resolve("operator-notes.txt"), "preserve failed-run evidence", StandardCharsets.UTF_8);
        Files.write(fixture.home().resolve("unrecognized.manifest"), new byte[] {5, 6});
        var before = snapshot(fixture.home());
        ReclamationResult result;
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            result = store.reclaim(Set.of(), journal);
            assertEquals(2, result.removedSegments().size());
        }
        assertRemovedOnly(before, snapshot(fixture.home()), result);
    }

    @Test
    void pendingEvidenceRefusesRemovalAndPreservesEveryOriginalByte() throws IOException {
        var fixture = published("database", true);
        Files.write(fixture.home().resolve("pending-interrupted-publication"), new byte[] {4, 8, 1});
        var before = snapshot(fixture.home());
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(IOException.class, () -> store.reclaim(Set.of(), journal));
        }
        assertImagesEqual(before, snapshot(fixture.home()));
    }

    @Test
    void damagedNewestStateFailsClosedWithoutFallingBackOrRemovingOldEvidence() throws IOException {
        var fixture = published("database", true);
        Path newest = fixture.home().resolve(selectedName(fixture));
        byte[] corrupt = Files.readAllBytes(newest);
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(newest, corrupt);
        var before = snapshot(fixture.home());
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(ManifestFormatException.class, () -> store.reclaim(Set.of(), journal));
        }
        assertImagesEqual(before, snapshot(fixture.home()));
    }

    @Test
    void recomputationProtectsANewerSelectedLayoutEvenWhenAPreviousPreviewMarkedItObsolete() throws IOException {
        var fixture = published("database", true);
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            var preview = store.inspectReclamation(Set.of(), journal);
            assertEquals(2, preview.obsoleteSegments().size());
            var reselected = new CheckpointManifest(LocalCompactionIntegrationTest.DATABASE, 4,
                    fixture.source().journalPrefix(), fixture.source().segments());
            var selected = store.publish(reselected, journal);
            var result = store.reclaim(Set.of(), journal);
            assertEquals(selected, result.selectedManifestId().orElseThrow());
            assertEquals(1, result.removedSegments().size());
            assertEquals(LocalCheckpointStore.segmentName(fixture.plan().replacementManifest().segments().getFirst().id()),
                    result.removedSegments().getFirst().name());
            for (var obsoleteAtPreview : preview.obsoleteSegments()) { assertTrue(Files.exists(fixture.home().resolve(obsoleteAtPreview.name()))); }
            try (var latest = store.readLatest(journal).orElseThrow()) { assertEquals(4, latest.manifest().generation()); }
        }
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
    }

    @Test
    void sameJvmOldReaderOutlivesStoreAndDefersRemovalUntilCloseWhileHeapChildrenSurvive() throws IOException {
        var fixture = fixture("database", true);
        LocalCheckpointReader old;
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            old = store.readLatest(journal).orElseThrow();
        }
        try {
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                store.publishCompaction(fixture.plan(), journal);
                assertDeferred(store.reclaim(Set.of(), journal));
                try (var first = old.openSegment(0)) { assertEquals(List.of(LocalCompactionIntegrationTest.HISTORY.getFirst()), first.segment().records()); }
                var heap = old.openSegment(1);
                try {
                    old.close();
                    var result = store.reclaim(Set.of(), journal);
                    assertEquals(2, result.removedSegments().size());
                    assertEquals(List.of(LocalCompactionIntegrationTest.HISTORY.get(1)), heap.segment().records());
                    assertThrows(IllegalStateException.class, old::manifest);
                } finally { heap.close(); }
            }
        } finally { old.close(); }
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
    }

    @Test
    void independentOldReaderProcessDefersActualRemovalAndRetainsLazyAccessAcrossPublication() throws Exception {
        var fixture = fixture("database", true);
        try (var child = start("hold-old-reader", fixture.home(), "old-reader")) {
            child.awaitOutput("HELD");
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                store.publishCompaction(fixture.plan(), journal);
                assertDeferred(store.reclaim(Set.of(), journal));
                for (var reference : fixture.source().segments()) {
                    assertTrue(Files.exists(fixture.home().resolve(LocalCheckpointStore.segmentName(reference.id()))));
                }
            }
            child.command('R');
            assertEquals(0, child.awaitExit(), child.diagnostics());
            assertTrue(child.hasLine("OLD LAZY HISTORY OK"), child.diagnostics());
        }
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(2, store.reclaim(Set.of(), journal).removedSegments().size());
            try (var latest = store.readLatest(journal).orElseThrow()) { assertEquals(selectedId(fixture), latest.manifestId()); }
        }
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
    }

    @Test
    void removalRetainsBothExclusiveOwnersSoAnotherProcessCannotAdmitAReaderAtTheDeletionBoundary() throws Exception {
        var fixture = published("database", true);
        boolean[] checked = {false};
        try (var store = LocalCheckpointStore.openReclamationObserved(fixture.home(), (boundary, name) -> {
            if (boundary == ReclamationBoundary.MANIFEST_REMOVED && !checked[0]) {
                checked[0] = true;
                try (var child = start("contend-admission", fixture.home(), "delete-boundary")) {
                    try { assertEquals(0, child.awaitExit(), child.diagnostics()); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                    assertTrue(child.hasLine("STORE LOCKED"), child.diagnostics());
                    assertTrue(child.hasLine("READER BARRIER LOCKED"), child.diagnostics());
                }
            }
        }); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(ReclamationResult.Status.COMPLETED, store.reclaim(Set.of(), journal).status());
            assertTrue(checked[0]);
            try (var admitted = store.readLatest(journal).orElseThrow()) { assertEquals(selectedId(fixture), admitted.manifestId()); }
        }
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
    }

    @Test
    void aLateDiagnosticHardLinkIsRejectedAtDeleteBeforeUnlinkingItsVerifiedTarget() throws IOException {
        var fixture = published("database", true);
        var before = snapshot(fixture.home());
        Path alias = fixture.home().resolve("late-operator-copy");
        boolean[] linked = {false};
        try (var store = LocalCheckpointStore.openReclamationObserved(fixture.home(), (boundary, name) -> {
            if (boundary == ReclamationBoundary.LIVE_ROOTS_SYNCED) {
                String first = before.keySet().stream().filter(entry -> entry.startsWith("checkpoint-0000000000000000001-")).findFirst().orElseThrow();
                createHardLink(alias, fixture.home().resolve(first));
                linked[0] = true;
            }
        }); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(LocalCheckpointStore.UncertainReclamationException.class, () -> store.reclaim(Set.of(), journal));
            assertTrue(linked[0]);
        }
        var after = snapshot(fixture.home());
        before.forEach((name, image) -> assertArrayEquals(image, after.get(name), name));
        assertEquals(before.size() + 1, after.size());
        Files.delete(alias); // Remove only the deliberately injected fixture alias, after all owners close.
        assertImagesEqual(before, snapshot(fixture.home()));
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(ReclamationResult.Status.COMPLETED, store.reclaim(Set.of(), journal).status());
        }
    }

    @Test
    @SuppressWarnings("try") // The reservation itself is the held lifetime; it intentionally opens no channel.
    void anExistingSameJvmDataReservationBlocksDeletionWithoutClosingAnAliasHandle() throws IOException {
        var fixture = published("database", true);
        var before = snapshot(fixture.home());
        String first = before.keySet().stream().filter(entry -> entry.startsWith("checkpoint-0000000000000000001-")).findFirst().orElseThrow();
        try (var held = LocalFileOwnership.read(fixture.home().resolve(first));
             var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(LocalCheckpointStore.UncertainReclamationException.class, () -> store.reclaim(Set.of(), journal));
            assertEquals(3, journal.prefix(3).sequence());
        }
        assertImagesEqual(before, snapshot(fixture.home()));
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(ReclamationResult.Status.COMPLETED, store.reclaim(Set.of(), journal).status());
        }
    }

    @Test
    void entryCapacityCanBeRecoveredWithoutCreatingAReclamationIntentOrTemporaryObject() throws IOException {
        var fixture = published("database", true);
        int existing;
        try (var entries = Files.list(fixture.home())) { existing = (int) entries.count(); }
        for (int index = existing; index < LocalCheckpointStore.MAX_DIRECTORY_ENTRIES; index++) {
            Files.createFile(fixture.home().resolve("preserved-capacity-" + index));
        }
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(IOException.class, () -> store.publish(new CheckpointManifest(LocalCompactionIntegrationTest.DATABASE, 4,
                    fixture.source().journalPrefix(), fixture.source().segments()), journal));
        }
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            var result = store.reclaim(Set.of(), journal);
            assertEquals(2, result.removedManifests().size());
            assertEquals(2, result.removedSegments().size());
            var replacement = fixture.plan().replacementManifest();
            store.publish(new CheckpointManifest(LocalCompactionIntegrationTest.DATABASE, 4,
                    replacement.journalPrefix(), replacement.segments()), journal);
        }
        try (var entries = Files.list(fixture.home())) {
            assertEquals(LocalCheckpointStore.MAX_DIRECTORY_ENTRIES - 3, entries.count());
        }
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
    }

    private LocalCompactionIntegrationTest.Fixture fixture(String name, boolean split) throws IOException {
        return LocalCompactionIntegrationTest.seedFixture(home(name), split);
    }

    private LocalCompactionIntegrationTest.Fixture published(String name, boolean split) throws IOException {
        var fixture = fixture(name, split);
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            store.publishCompaction(fixture.plan(), journal);
        }
        return fixture;
    }

    private Path home(String name) throws IOException {
        Path capability = Files.createDirectory(directory.resolve("capability-" + name));
        try (var store = LocalCheckpointStore.open(capability)) { store.journalPath(); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Required directory force is unavailable: " + unsupported);
        }
        return Files.createDirectory(directory.resolve(name));
    }

    static ManifestId selectedId(LocalCompactionIntegrationTest.Fixture fixture) {
        return ManifestCodec.decode(ManifestCodec.encode(fixture.plan().replacementManifest())).id();
    }

    static String selectedName(LocalCompactionIntegrationTest.Fixture fixture) {
        return LocalCheckpointStore.generationName(3, selectedId(fixture));
    }

    private static Set<String> withoutGuard(Set<String> names, boolean originallyPresent) {
        var result = new HashSet<>(names);
        if (!originallyPresent) { result.remove(GUARD); }
        return result;
    }

    /** Inspect raw journal/control bytes only after every namespace owner has closed. */
    static Map<String, byte[]> snapshot(Path home) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var entries = Files.list(home)) {
            for (var path : entries.sorted().toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    result.put(path.getFileName().toString(), Files.readAllBytes(path));
                }
            }
        }
        return result;
    }

    private static void assertImagesEqual(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, bytes) -> assertArrayEquals(bytes, actual.get(name), name));
    }

    private static void assertRemovedOnly(Map<String, byte[]> before, Map<String, byte[]> after, ReclamationResult result) {
        var removed = new HashSet<String>();
        result.removedManifests().forEach(file -> removed.add(file.name()));
        result.removedSegments().forEach(file -> removed.add(file.name()));
        var expected = new HashSet<>(before.keySet());
        expected.removeAll(removed);
        assertEquals(expected, after.keySet());
        after.forEach((name, bytes) -> assertArrayEquals(before.get(name), bytes, name));
        assertEquals(removed.stream().mapToLong(name -> before.get(name).length).sum(), result.removedBytes());
    }

    private static void assertDeferred(ReclamationResult result) {
        assertEquals(ReclamationResult.Status.DEFERRED_READERS, result.status());
        assertTrue(result.selectedManifestId().isEmpty());
        assertTrue(result.retainedManifestIds().isEmpty());
        assertTrue(result.removedManifests().isEmpty());
        assertTrue(result.removedSegments().isEmpty());
        assertEquals(0, result.removedBytes());
    }

    private static void createHardLink(Path link, Path target) throws IOException {
        try { Files.createLink(link, target); }
        catch (UnsupportedOperationException unsupported) { assumeTrue(false, "Hard-link operation unavailable: " + unsupported); }
    }

    private Child start(String mode, Path home, String label) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java.exe"); }
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        Path output = directory.resolve(home.getFileName() + "-" + label + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, RemovalProcess.class.getName(), mode,
                home.toAbsolutePath().toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
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
        void awaitOutput(String marker) throws InterruptedException {
            long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (hasLine(marker)) { return; }
                if (!process.isAlive()) { fail("Removal child exited before " + marker + ": " + diagnostics()); }
                Thread.sleep(10);
            }
            fail("Removal child did not report " + marker + ": " + diagnostics());
        }
        void command(char value) throws IOException { process.getOutputStream().write(value); process.getOutputStream().flush(); }
        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), () -> "Removal child did not exit: " + diagnostics());
            return process.exitValue();
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) { throw new IOException("Removal child did not terminate: " + diagnostics()); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating removal child", interrupted);
            } finally {
                process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close();
            }
        }
    }

    public static final class RemovalProcess {
        private RemovalProcess() { }
        public static void main(String[] args) throws Exception {
            Path home = Path.of(args[1]);
            switch (args[0]) {
                case "hold-old-reader" -> {
                    LocalCheckpointReader retained;
                    try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
                        retained = store.readLatest(journal).orElseThrow();
                    }
                    try {
                        System.out.println("HELD"); System.out.flush();
                        if (System.in.read() != 'R') { throw new IOException("Parent did not release retained reader"); }
                        var history = new ArrayList<org.meridiandb.storage.journal.JournalRecord>();
                        for (int index = 0; index < retained.manifest().segments().size(); index++) {
                            try (var heap = retained.openSegment(index)) { history.addAll(heap.segment().records()); }
                        }
                        if (!history.equals(LocalCompactionIntegrationTest.HISTORY.subList(0, 2))) {
                            throw new AssertionError("Removal changed old lazy history");
                        }
                        System.out.println("OLD LAZY HISTORY OK"); System.out.flush();
                    } finally { retained.close(); }
                }
                case "contend-admission" -> {
                    LocalCheckpointStore opened;
                    try { opened = LocalCheckpointStore.open(home); }
                    catch (IOException locked) { opened = null; System.out.println("STORE LOCKED"); }
                    if (opened != null) {
                        var admitted = opened;
                        try (admitted) { admitted.journalPath(); }
                        throw new AssertionError("Store admitted a reader during deletion");
                    }
                    var shared = LocalReaderBarrier.acquire(home, true);
                    if (shared.isPresent()) {
                        try (var unexpected = shared.orElseThrow()) { unexpected.checkValid(); }
                        throw new AssertionError("Exclusive removal barrier admitted another reader");
                    }
                    System.out.println("READER BARRIER LOCKED");
                }
                default -> throw new IllegalArgumentException("Unknown removal process mode: " + args[0]);
            }
        }
    }
}
