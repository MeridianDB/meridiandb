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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Inventory is evidence only: every real inspection preserves all existing namespace bytes. */
@Timeout(90)
class LocalReclamationInventoryIntegrationTest {
    private static final String GUARD = ".meridiandb-readers.lock";
    private static final String LOCK = ".meridiandb-checkpoints.lock";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path directory;

    @Test
    void twoToOneCompactionReportsExactSortedCandidatesAndRepeatedVerificationWorkWithoutDeleting() throws IOException {
        var fixture = published("database", true);
        var before = snapshot(fixture.home());
        var inventory = inspect(fixture.home(), Set.of());
        var after = snapshot(fixture.home());
        assertUnchangedExceptPossibleGuard(before, after);
        assertEquals(ReclamationInventory.Status.VERIFIED, inventory.status());
        assertEquals(Optional.of(selectedId(fixture)), inventory.selectedManifestId());
        assertEquals(Set.of(selectedId(fixture)), inventory.retainedManifestIds());
        assertEquals(obsoleteManifestFiles(fixture, before), inventory.obsoleteManifests());
        assertEquals(sourceSegmentFiles(fixture), inventory.obsoleteSegments());
        assertEquals(candidateBytes(inventory.obsoleteManifests(), inventory.obsoleteSegments()), inventory.candidateBytes());
        assertEquals(verifiedBytes(fixture, before), inventory.verifiedBytes());
        assertEquals(4, inventory.verifiedRecords()); // Genesis 0 + original prefix 2 + replacement prefix 2.
        assertPreservedNames(after.names(), inventory);
        assertSameInventory(inventory, inspect(fixture.home(), Set.of()));
        // A completed preview has released its exclusive barrier; it cannot pin later admission.
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath());
             var admitted = store.readLatest(journal).orElseThrow()) { assertEquals(selectedId(fixture), admitted.manifestId()); }
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
        assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
    }

    @Test
    void explicitlyRetainedOldRootProtectsItsSegmentsAndSelectedRootIsAlwaysAdded() throws IOException {
        var fixture = published("database", true);
        var before = snapshot(fixture.home());
        var requested = new HashSet<>(Set.of(fixture.sourceId()));
        var inventory = inspect(fixture.home(), requested);
        requested.clear();
        assertEquals(Set.of(fixture.sourceId(), selectedId(fixture)), inventory.retainedManifestIds());
        assertEquals(List.of(genesisFile(before)), inventory.obsoleteManifests());
        assertTrue(inventory.obsoleteSegments().isEmpty());
        assertEquals(genesisFile(before).byteLength(), inventory.candidateBytes());
        assertEquals(verifiedBytes(fixture, before), inventory.verifiedBytes());
        assertEquals(4, inventory.verifiedRecords());
        assertPreservedNames(snapshot(fixture.home()).names(), inventory);
        assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
        // Explicit roots are per inspection, not persistent backup pins.
        assertEquals(sourceSegmentFiles(fixture), inspect(fixture.home(), Set.of()).obsoleteSegments());
    }

    @Test
    void sharedLayoutCountsEachRootVerificationButNeverReportsALiveSegmentAsObsolete() throws IOException {
        var fixture = published("database", false);
        assertEquals(fixture.source().segments(), fixture.plan().replacementManifest().segments());
        var before = snapshot(fixture.home());
        var inventory = inspect(fixture.home(), Set.of());
        assertEquals(obsoleteManifestFiles(fixture, before), inventory.obsoleteManifests());
        assertTrue(inventory.obsoleteSegments().isEmpty());
        assertEquals(verifiedBytes(fixture, before), inventory.verifiedBytes());
        assertEquals(4, inventory.verifiedRecords());
        assertEquals(inventory.obsoleteManifests().stream().mapToLong(ReclamationInventory.ObjectFile::byteLength).sum(), inventory.candidateBytes());
        assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
    }

    @Test
    void emptySelectionCreatesAtMostPermanentGuardMetadataAndHasNoDeletionCandidates() throws IOException {
        Path home = home("empty");
        try (var store = LocalCheckpointStore.open(home);
             var journal = LocalJournal.create(store.journalPath(), LocalCompactionIntegrationTest.DATABASE)) {
            assertEquals(0, journal.appendedSequence());
        }
        var before = snapshot(home);
        assertFalse(before.names().contains(GUARD));
        var inventory = inspect(home, Set.of());
        var after = snapshot(home);
        assertEquals(ReclamationInventory.Status.VERIFIED, inventory.status());
        assertTrue(inventory.selectedManifestId().isEmpty());
        assertTrue(inventory.retainedManifestIds().isEmpty());
        assertTrue(inventory.obsoleteManifests().isEmpty());
        assertTrue(inventory.obsoleteSegments().isEmpty());
        assertEquals(0, inventory.candidateBytes());
        assertEquals(0, inventory.verifiedBytes());
        assertEquals(0, inventory.verifiedRecords());
        assertEquals(after.names(), inventory.preservedNames());
        assertTrue(after.names().contains(GUARD));
        assertUnchangedExceptPossibleGuard(before, after);
        assertSameInventory(inventory, inspect(home, Set.of()));
    }

    @Test
    void orphanSegmentsUnknownDiagnosticsAndDirectoriesRemainPreservedWithoutBeingDecoded() throws IOException {
        var fixture = published("database", true);
        String orphan = LocalCheckpointStore.segmentName(new SegmentId("d".repeat(64)));
        Files.write(fixture.home().resolve(orphan), new byte[] {4, 1, 8}); // Deliberately not a segment image.
        Files.writeString(fixture.home().resolve("operator-notes.txt"), "retain diagnostic evidence", StandardCharsets.UTF_8);
        Files.write(fixture.home().resolve("unrecognized.manifest"), new byte[] {7, 5});
        Files.createDirectory(fixture.home().resolve("backup-notes"));
        var before = snapshot(fixture.home());
        var inventory = inspect(fixture.home(), Set.of());
        assertTrue(inventory.preservedNames().containsAll(List.of(orphan, "operator-notes.txt", "unrecognized.manifest", "backup-notes")));
        assertEquals(sourceSegmentFiles(fixture), inventory.obsoleteSegments());
        assertEquals(verifiedBytes(fixture, before), inventory.verifiedBytes());
        assertPreservedNames(before.names(), inventory);
        assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
    }

    @Test
    void anyPendingCandidateFailsClosedAndPreservesAllCandidateEvidence() throws IOException {
        var fixture = published("database", true);
        Files.write(fixture.home().resolve("pending-interrupted-publication"), new byte[] {9, 2, 6});
        var before = snapshot(fixture.home());
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), journal));
        }
        assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
    }

    @Test
    void aRequestedUnpublishedRootRejectsRatherThanSilentlyDroppingTheRetentionRequest() throws IOException {
        var fixture = published("database", true);
        var before = snapshot(fixture.home());
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(new ManifestId("f".repeat(64))), journal));
        }
        assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
        assertEquals(ReclamationInventory.Status.VERIFIED, inspect(fixture.home(), Set.of()).status());
    }

    @Test
    void damagedNewestOrObsoletePublishedManifestPreventsEverySuccessfulInventory() throws IOException {
        for (boolean newest : new boolean[] {true, false}) {
            var fixture = published(newest ? "newest" : "obsolete", true);
            Path path = fixture.home().resolve(newest ? selectedName(fixture) : LocalCheckpointStore.generationName(2, fixture.sourceId()));
            byte[] image = Files.readAllBytes(path);
            image[image.length - 1] ^= 1;
            Files.write(path, image);
            var before = snapshot(fixture.home());
            assertInspectionFailure(fixture.home());
            assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
        }
    }

    @Test
    void missingDependencyOfAnObsoleteRootStillPreventsInventory() throws IOException {
        var fixture = published("database", true);
        Files.delete(fixture.home().resolve(LocalCheckpointStore.segmentName(fixture.source().segments().getFirst().id())));
        var before = snapshot(fixture.home());
        assertInspectionFailure(fixture.home());
        assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
    }

    @Test
    void everyPublishedRootMustMatchTheActualOwnedJournalPrefix() throws IOException {
        var fixture = published("database", true);
        var changed = new ArrayList<>(LocalCompactionIntegrationTest.HISTORY);
        changed.set(0, new JournalRecord(1, new Transaction(changed.getFirst().transaction().requestId(), List.of(
                new WriteOperation.Put(LocalCompactionIntegrationTest.SALARY, new ValidTimeInterval(null, null), 999)))));
        Files.write(fixture.home().resolve("transactions.journal"), LocalCompactionIntegrationTest.journalImage(changed));
        var before = snapshot(fixture.home());
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), journal));
        }
        assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
    }

    @Test
    void matchingJournalBytesAtAnotherPathDoNotSubstituteForTheStoreJournalOwner() throws IOException {
        var fixture = published("database", true);
        Path otherPath = directory.resolve("other.journal");
        Files.write(otherPath, fixture.journalBytes());
        var before = snapshot(fixture.home());
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath());
             var other = LocalJournal.open(otherPath)) {
            var prefix = journal.prefix(3);
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), other));
            assertEquals(prefix, journal.prefix(3));
            assertEquals(3, journal.force());
        }
        assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
        assertArrayEquals(fixture.journalBytes(), Files.readAllBytes(otherPath));
    }

    @Test
    void publishedMetadataAndReferencedSegmentSymlinksAreRejectedWithoutFollowingThem() throws IOException {
        for (boolean manifest : new boolean[] {true, false}) {
            var fixture = published(manifest ? "manifest-link" : "segment-link", true);
            Path object = fixture.home().resolve(manifest ? selectedName(fixture) : selectedSegmentName(fixture));
            byte[] expected = Files.readAllBytes(object);
            Path external = directory.resolve(manifest ? "external.manifest" : "external.segment");
            Files.write(external, expected);
            Files.delete(object);
            createSymlink(object, external);
            var before = snapshot(fixture.home());
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), journal));
            }
            assertTrue(Files.isSymbolicLink(object));
            assertArrayEquals(expected, Files.readAllBytes(external));
            assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
        }
    }

    @Test
    void publishedMetadataAndReferencedSegmentHardLinksToUnknownDiagnosticsAreRejectedBeforeObjectReads() throws IOException {
        for (boolean manifest : new boolean[] {true, false}) {
            var fixture = published(manifest ? "manifest-alias" : "segment-alias", true);
            Path object = fixture.home().resolve(manifest ? selectedName(fixture) : selectedSegmentName(fixture));
            createHardLink(fixture.home().resolve("operator-copy"), object);
            var before = snapshot(fixture.home());
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), journal));
            }
            assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
        }
    }

    @Test
    void controlAliasesCannotReleaseEitherOriginalOwnerProcessLock() throws Exception {
        for (boolean toJournal : new boolean[] {true, false}) {
            var fixture = published(toJournal ? "journal-alias" : "store-alias", true);
            var before = snapshot(fixture.home());
            Path alias = fixture.home().resolve(selectedSegmentName(fixture));
            byte[] original = before.files().get(alias.getFileName().toString());
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                var prefix = journal.prefix(3);
                Files.delete(alias);
                createHardLink(alias, toJournal ? store.journalPath() : fixture.home().resolve(LOCK));
                assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), journal));
                // Restore the deliberately replaced object via a new inode, without
                // opening any raw control/alias handle while its owner is live.
                Files.delete(alias);
                Files.write(alias, original);
                assertLocked("contend-store", fixture.home(), "owner-store");
                assertLocked("contend-journal", fixture.home(), "owner-journal");
                assertEquals(prefix, journal.prefix(3));
                assertEquals(3, journal.force());
            }
            assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
            assertEquals(ReclamationInventory.Status.VERIFIED, inspect(fixture.home(), Set.of()).status());
        }
    }

    @Test
    void referencedAliasToAnotherActiveStoreControlCannotDropItsLockOrQuarantineTheValidStore() throws Exception {
        var ownerFixture = published("owner", true);
        var inspectedFixture = published("inspected", true);
        var before = snapshot(inspectedFixture.home());
        Path alias = inspectedFixture.home().resolve(selectedSegmentName(inspectedFixture));
        byte[] original = before.files().get(alias.getFileName().toString());
        try (var owner = LocalCheckpointStore.open(ownerFixture.home())) {
            try (var store = LocalCheckpointStore.open(inspectedFixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                Files.delete(alias);
                createHardLink(alias, ownerFixture.home().resolve(LOCK));
                assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), journal));
                Files.delete(alias);
                Files.write(alias, original);
            }
            assertLocked("contend-store", ownerFixture.home(), "cross-store-control");
            assertEquals(ownerFixture.home().toRealPath().resolve("transactions.journal"), owner.journalPath());
            try (var protection = owner.tryReclamationBarrier().orElseThrow()) { protection.checkValid(); }
        }
        assertUnchangedExceptPossibleGuard(before, snapshot(inspectedFixture.home()));
        assertEquals(ReclamationInventory.Status.VERIFIED, inspect(inspectedFixture.home(), Set.of()).status());
        assertEquals(ReclamationInventory.Status.VERIFIED, inspect(ownerFixture.home(), Set.of()).status());
    }

    @Test
    void sameJvmReaderOutlivesItsStoreAndDefersUntilFinalCloseWhilePublicationRemainsAllowed() throws IOException {
        var fixture = fixture("database", true);
        LocalCheckpointReader old;
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            old = store.readLatest(journal).orElseThrow();
        }
        try {
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                store.publishCompaction(fixture.plan(), journal);
                assertDeferred(store.inspectReclamation(Set.of(fixture.sourceId()), journal));
                try (var first = old.openSegment(0)) { assertEquals(List.of(LocalCompactionIntegrationTest.HISTORY.getFirst()), first.segment().records()); }
                var heap = old.openSegment(1);
                try {
                    old.close();
                    var inventory = store.inspectReclamation(Set.of(), journal);
                    assertEquals(ReclamationInventory.Status.VERIFIED, inventory.status());
                    assertEquals(sourceSegmentFiles(fixture), inventory.obsoleteSegments());
                    assertEquals(List.of(LocalCompactionIntegrationTest.HISTORY.get(1)), heap.segment().records());
                    try (var admitted = store.readLatest(journal).orElseThrow()) { assertEquals(selectedId(fixture), admitted.manifestId()); }
                } finally { heap.close(); }
            }
        } finally { old.close(); }
        LocalCompactionIntegrationTest.assertOriginalObjectsRetained(fixture);
        assertArrayEquals(fixture.journalBytes(), Files.readAllBytes(fixture.home().resolve("transactions.journal")));
    }

    @Test
    void independentReaderProcessDefersInventoryAfterStoreCloseAndReleaseAllowsFreshAdmission() throws Exception {
        var fixture = published("database", true);
        var before = snapshot(fixture.home());
        try (var child = start("hold-reader", fixture.home(), "old-reader")) {
            child.awaitOutput("HELD");
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                assertDeferred(store.inspectReclamation(Set.of(), journal));
                try (var peer = store.readLatest(journal).orElseThrow()) { assertEquals(selectedId(fixture), peer.manifestId()); }
            }
            child.command('R');
            assertEquals(0, child.awaitExit(), child.diagnostics());
            assertTrue(child.hasLine("LAZY HISTORY OK"), child.diagnostics());
        }
        var inventory = inspect(fixture.home(), Set.of());
        assertEquals(ReclamationInventory.Status.VERIFIED, inventory.status());
        assertEquals(sourceSegmentFiles(fixture), inventory.obsoleteSegments());
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath());
             var reader = store.readLatest(journal).orElseThrow()) { assertEquals(selectedId(fixture), reader.manifestId()); }
        assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
    }

    @Test
    void exactRealVerificationBudgetsSucceedAndOneUnitLessFailsWithoutLeakingTheBarrier() throws IOException {
        var fixture = published("database", true);
        var baseline = inspect(fixture.home(), Set.of());
        var before = snapshot(fixture.home());
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertSameInventory(baseline, store.inspectReclamation(Set.of(), journal,
                    new ReclamationInventory.Limits(baseline.verifiedBytes(), baseline.verifiedRecords())));
        }
        for (var limits : List.of(new ReclamationInventory.Limits(baseline.verifiedBytes() - 1, baseline.verifiedRecords()),
                new ReclamationInventory.Limits(baseline.verifiedBytes(), baseline.verifiedRecords() - 1))) {
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), journal, limits));
            }
            assertUnchangedExceptPossibleGuard(before, snapshot(fixture.home()));
            assertSameInventory(baseline, inspect(fixture.home(), Set.of()));
        }
    }

    private LocalCompactionIntegrationTest.Fixture fixture(String name, boolean split) throws IOException {
        return LocalCompactionIntegrationTest.seedFixture(home(name), split);
    }

    private LocalCompactionIntegrationTest.Fixture published(String name, boolean split) throws IOException {
        var fixture = fixture(name, split);
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(selectedId(fixture), store.publishCompaction(fixture.plan(), journal));
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

    private static ReclamationInventory inspect(Path home, Set<ManifestId> retained) throws IOException {
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
            return store.inspectReclamation(retained, journal);
        }
    }

    private static void assertInspectionFailure(Path home) throws IOException {
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
            var failure = assertThrows(Exception.class, () -> store.inspectReclamation(Set.of(), journal));
            assertTrue(failure instanceof IOException || failure instanceof ManifestFormatException || failure instanceof SegmentFormatException,
                    "Inspection must fail with an I/O or format failure: " + failure);
        }
    }

    private static void assertDeferred(ReclamationInventory inventory) {
        assertEquals(ReclamationInventory.Status.DEFERRED_READERS, inventory.status());
        assertTrue(inventory.selectedManifestId().isEmpty());
        assertTrue(inventory.retainedManifestIds().isEmpty());
        assertTrue(inventory.obsoleteManifests().isEmpty());
        assertTrue(inventory.obsoleteSegments().isEmpty());
        assertTrue(inventory.preservedNames().isEmpty());
        assertEquals(0, inventory.candidateBytes());
        assertEquals(0, inventory.verifiedBytes());
        assertEquals(0, inventory.verifiedRecords());
    }

    private static ManifestId selectedId(LocalCompactionIntegrationTest.Fixture fixture) {
        return ManifestCodec.decode(ManifestCodec.encode(fixture.plan().replacementManifest())).id();
    }

    private static String selectedName(LocalCompactionIntegrationTest.Fixture fixture) {
        return LocalCheckpointStore.generationName(3, selectedId(fixture));
    }

    private static String selectedSegmentName(LocalCompactionIntegrationTest.Fixture fixture) {
        return LocalCheckpointStore.segmentName(fixture.plan().replacementManifest().segments().getFirst().id());
    }

    private static ReclamationInventory.ObjectFile genesisFile(Snapshot snapshot) {
        return snapshot.files().entrySet().stream().filter(entry -> entry.getKey().startsWith("checkpoint-"))
                .filter(entry -> ManifestCodec.decode(entry.getValue()).manifest().generation() == 1)
                .map(entry -> new ReclamationInventory.ObjectFile(entry.getKey(), entry.getValue().length)).findFirst().orElseThrow();
    }

    private static List<ReclamationInventory.ObjectFile> obsoleteManifestFiles(LocalCompactionIntegrationTest.Fixture fixture, Snapshot before) {
        String source = LocalCheckpointStore.generationName(2, fixture.sourceId());
        return List.of(genesisFile(before), new ReclamationInventory.ObjectFile(source, before.files().get(source).length)).stream()
                .sorted(Comparator.comparing(ReclamationInventory.ObjectFile::name)).toList();
    }

    private static List<ReclamationInventory.ObjectFile> sourceSegmentFiles(LocalCompactionIntegrationTest.Fixture fixture) {
        return fixture.source().segments().stream().map(reference -> new ReclamationInventory.ObjectFile(
                LocalCheckpointStore.segmentName(reference.id()), reference.byteLength()))
                .sorted(Comparator.comparing(ReclamationInventory.ObjectFile::name)).toList();
    }

    private static long verifiedBytes(LocalCompactionIntegrationTest.Fixture fixture, Snapshot before) {
        long manifests = before.files().entrySet().stream().filter(entry -> entry.getKey().startsWith("checkpoint-"))
                .mapToLong(entry -> entry.getValue().length).sum();
        return manifests + fixture.source().segments().stream().mapToLong(CheckpointManifest.SegmentReference::byteLength).sum()
                + fixture.plan().replacementManifest().segments().stream().mapToLong(CheckpointManifest.SegmentReference::byteLength).sum();
    }

    private static long candidateBytes(List<ReclamationInventory.ObjectFile> manifests, List<ReclamationInventory.ObjectFile> segments) {
        return manifests.stream().mapToLong(ReclamationInventory.ObjectFile::byteLength).sum()
                + segments.stream().mapToLong(ReclamationInventory.ObjectFile::byteLength).sum();
    }

    private static void assertPreservedNames(List<String> entries, ReclamationInventory inventory) {
        var candidates = new HashSet<String>();
        inventory.obsoleteManifests().forEach(file -> candidates.add(file.name()));
        inventory.obsoleteSegments().forEach(file -> candidates.add(file.name()));
        assertEquals(entries.stream().filter(name -> !candidates.contains(name)).sorted().toList(), inventory.preservedNames());
    }

    private static void assertSameInventory(ReclamationInventory expected, ReclamationInventory actual) {
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.selectedManifestId(), actual.selectedManifestId());
        assertEquals(expected.retainedManifestIds(), actual.retainedManifestIds());
        assertEquals(expected.obsoleteManifests(), actual.obsoleteManifests());
        assertEquals(expected.obsoleteSegments(), actual.obsoleteSegments());
        assertEquals(expected.preservedNames(), actual.preservedNames());
        assertEquals(expected.candidateBytes(), actual.candidateBytes());
        assertEquals(expected.verifiedBytes(), actual.verifiedBytes());
        assertEquals(expected.verifiedRecords(), actual.verifiedRecords());
    }

    private record Snapshot(List<String> names, Map<String, byte[]> files, Map<String, Path> symlinks) { }

    /** Call only after every journal/control owner in this namespace has closed. */
    private static Snapshot snapshot(Path home) throws IOException {
        var files = new LinkedHashMap<String, byte[]>();
        var symlinks = new LinkedHashMap<String, Path>();
        List<Path> paths;
        try (var entries = Files.list(home)) { paths = entries.sorted().toList(); }
        for (var path : paths) {
            String name = path.getFileName().toString();
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { files.put(name, Files.readAllBytes(path)); }
            else if (Files.isSymbolicLink(path)) { symlinks.put(name, Files.readSymbolicLink(path)); }
        }
        return new Snapshot(paths.stream().map(path -> path.getFileName().toString()).toList(), files, symlinks);
    }

    private static void assertUnchangedExceptPossibleGuard(Snapshot before, Snapshot after) {
        var expectedNames = new HashSet<>(before.names());
        if (!expectedNames.contains(GUARD) && after.names().contains(GUARD)) {
            expectedNames.add(GUARD);
            assertArrayEquals(new byte[0], after.files().get(GUARD));
        }
        assertEquals(expectedNames.stream().sorted().toList(), after.names());
        assertEquals(before.symlinks(), after.symlinks());
        before.files().forEach((name, bytes) -> assertArrayEquals(bytes, after.files().get(name), name));
    }

    private static void createHardLink(Path link, Path target) throws IOException {
        try { Files.createLink(link, target); }
        catch (UnsupportedOperationException unsupported) { assumeTrue(false, "Hard-link operation unavailable: " + unsupported); }
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException unsupported) { assumeTrue(false, "Symbolic link operation unavailable: " + unsupported); }
    }

    private void assertLocked(String mode, Path home, String label) throws Exception {
        try (var contender = start(mode, home, label)) {
            assertEquals(0, contender.awaitExit(), contender.diagnostics());
            assertTrue(contender.hasLine("LOCKED"), contender.diagnostics());
        }
    }

    private Child start(String mode, Path home, String label) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java.exe"); }
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        Path output = directory.resolve(home.getFileName() + "-" + label + "-" + mode + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, InventoryProcess.class.getName(), mode,
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
                if (!process.isAlive()) { fail("Inventory child exited before " + marker + ": " + diagnostics()); }
                Thread.sleep(10);
            }
            fail("Inventory child did not report " + marker + ": " + diagnostics());
        }
        void command(char value) throws IOException { process.getOutputStream().write(value); process.getOutputStream().flush(); }
        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), () -> "Inventory child did not exit: " + diagnostics());
            return process.exitValue();
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) { throw new IOException("Inventory child did not terminate: " + diagnostics()); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating inventory child", interrupted);
            } finally {
                process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close();
            }
        }
    }

    public static final class InventoryProcess {
        private InventoryProcess() { }
        public static void main(String[] args) throws Exception {
            Path home = Path.of(args[1]);
            switch (args[0]) {
                case "hold-reader" -> {
                    LocalCheckpointReader retained;
                    try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
                        retained = store.readLatest(journal).orElseThrow();
                    }
                    try {
                        System.out.println("HELD"); System.out.flush();
                        if (System.in.read() != 'R') { throw new IOException("Parent did not release retained reader"); }
                        try (var heap = retained.openSegment(0)) {
                            if (!heap.segment().records().equals(LocalCompactionIntegrationTest.HISTORY.subList(0, 2))) {
                                throw new AssertionError("Inventory changed old lazy history");
                            }
                        }
                        System.out.println("LAZY HISTORY OK"); System.out.flush();
                    } finally { retained.close(); }
                }
                case "contend-store" -> {
                    LocalCheckpointStore opened;
                    try { opened = LocalCheckpointStore.open(home); }
                    catch (IOException locked) { System.out.println("LOCKED"); return; }
                    try (opened) { opened.journalPath(); }
                    System.exit(2);
                }
                case "contend-journal" -> {
                    LocalJournal opened;
                    try { opened = LocalJournal.open(home.resolve("transactions.journal")); }
                    catch (IOException locked) { System.out.println("LOCKED"); return; }
                    try (opened) { opened.prefix(0); }
                    System.exit(2);
                }
                default -> throw new IllegalArgumentException("Unknown inventory process mode: " + args[0]);
            }
        }
    }
}
