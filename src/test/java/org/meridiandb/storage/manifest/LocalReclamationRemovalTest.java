// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Faults exercise the protocol; this fake never stands in for filesystem durability evidence. */
class LocalReclamationRemovalTest {
    private static final DatabaseId DATABASE = new DatabaseId("removal-db");
    private static final List<JournalRecord> RECORDS = List.of(record(1), record(2), record(3));
    private static final ReclamationInventory.Limits LIMITS = ReclamationInventory.Limits.DEFAULT;

    @TempDir
    Path directory;

    @Test
    void removesOnlyVerifiedObsoleteMetadataAndDependenciesAndForcesEveryRetainedObjectFirst() throws IOException {
        var fixture = history();
        var before = fixture.namespace.snapshot();
        try (var store = fixture.store()) {
            var result = store.reclaim(Set.of(), fixture.journal, LIMITS);
            assertEquals(ReclamationResult.Status.COMPLETED, result.status());
            assertEquals(Optional.of(id(fixture.roots.get(2))), result.selectedManifestId());
            assertEquals(Set.of(id(fixture.roots.get(2))), result.retainedManifestIds());
            assertEquals(List.of(name(fixture.roots.get(0)), name(fixture.roots.get(1))), names(result.removedManifests()));
            assertEquals(List.of(fixture.oldSecond), names(result.removedSegments()));
            var removed = new HashSet<>(names(result.removedManifests()));
            removed.addAll(names(result.removedSegments()));
            assertEquals(removed.stream().mapToLong(entry -> before.get(entry).length).sum(), result.removedBytes());
            assertEquals(before.keySet().stream().filter(entry -> !removed.contains(entry)).collect(java.util.stream.Collectors.toSet()),
                    fixture.namespace.files.keySet());
            before.forEach((entry, bytes) -> {
                if (!removed.contains(entry)) { assertArrayEquals(bytes, fixture.namespace.files.get(entry)); }
            });
            assertTrue(fixture.namespace.files.containsKey(fixture.sharedFirst));
            assertTrue(fixture.namespace.files.containsKey(fixture.unreferenced));
            assertProtocolOrder(fixture, Set.of(id(fixture.roots.get(2))));
            assertAllGraphsComplete(fixture.namespace.files);
            assertAllGraphsComplete(fixture.namespace.durableNames);
            assertThrows(UnsupportedOperationException.class, () -> result.retainedManifestIds().clear());
            assertThrows(UnsupportedOperationException.class, () -> result.removedManifests().clear());
            assertThrows(UnsupportedOperationException.class, () -> result.removedSegments().clear());
            assertEquals(directory.resolve(LocalCheckpointStore.JOURNAL), store.journalPath());
        }
    }

    @Test
    void explicitOldRootProtectsSharedAndUniqueDependenciesWhileSelectedIsAlwaysRetained() throws IOException {
        var fixture = history();
        var oldRoot = id(fixture.roots.get(1));
        var requested = new HashSet<>(Set.of(oldRoot));
        try (var store = fixture.store()) {
            var result = store.reclaim(requested, fixture.journal, LIMITS);
            requested.clear();
            assertEquals(Set.of(oldRoot, id(fixture.roots.get(2))), result.retainedManifestIds());
            assertEquals(List.of(name(fixture.roots.getFirst())), names(result.removedManifests()));
            assertTrue(result.removedSegments().isEmpty());
            assertTrue(fixture.namespace.files.containsKey(fixture.oldSecond));
            assertProtocolOrder(fixture, result.retainedManifestIds());
            assertAllGraphsComplete(fixture.namespace.files);
        }
    }

    @Test
    void retainingEveryRootPerformsNoRemovalOrForceAndDoesNotPersistPins() throws IOException {
        var fixture = history();
        var requested = fixture.roots.stream().map(LocalReclamationRemovalTest::id).collect(java.util.stream.Collectors.toSet());
        var before = fixture.namespace.snapshot();
        try (var store = fixture.store()) {
            var retained = store.reclaim(requested, fixture.journal, LIMITS);
            assertEquals(ReclamationResult.Status.COMPLETED, retained.status());
            assertTrue(retained.removedManifests().isEmpty());
            assertTrue(retained.removedSegments().isEmpty());
            assertEquals(0, retained.removedBytes());
            assertNoMutationOrForce(fixture.namespace);
            assertSameBytes(before, fixture.namespace.files);
            fixture.namespace.events.clear();
            var next = store.reclaim(Set.of(), fixture.journal, LIMITS);
            assertEquals(2, next.removedManifests().size());
            assertEquals(List.of(fixture.oldSecond), names(next.removedSegments()));
        }
    }

    @Test
    void emptySelectionHasNoPhysicalCandidatesAndTouchesNoJournalForce() throws IOException {
        var fixture = empty();
        fixture.namespace.files.put("operator-note", new byte[] {9});
        try (var store = fixture.store()) {
            var result = store.reclaim(Set.of(), fixture.journal, new ReclamationInventory.Limits(0, 0));
            assertEquals(ReclamationResult.Status.COMPLETED, result.status());
            assertTrue(result.selectedManifestId().isEmpty());
            assertTrue(result.retainedManifestIds().isEmpty());
            assertTrue(result.removedManifests().isEmpty());
            assertTrue(result.removedSegments().isEmpty());
            assertEquals(0, result.removedBytes());
            assertNoMutationOrForce(fixture.namespace);
            assertTrue(fixture.journal.prefixCalls.isEmpty());
        }
    }

    @Test
    void genesisGenerationGapsAreReclaimedWithoutLosingTheHighestSelection() throws IOException {
        var fixture = empty();
        fixture.add(manifest(1, 0, List.of()));
        fixture.add(manifest(8, 0, List.of()));
        try (var store = fixture.store()) {
            var result = store.reclaim(Set.of(), fixture.journal, LIMITS);
            assertEquals(Optional.of(id(fixture.roots.get(1))), result.selectedManifestId());
            assertEquals(List.of(name(fixture.roots.getFirst())), names(result.removedManifests()));
            assertTrue(result.removedSegments().isEmpty());
            assertAllGraphsComplete(fixture.namespace.files);
            fixture.namespace.events.clear();
            var repeated = store.reclaim(Set.of(), fixture.journal, LIMITS);
            assertTrue(repeated.removedManifests().isEmpty());
            assertEquals(0, repeated.removedBytes());
            assertNoMutationOrForce(fixture.namespace);
        }
    }

    @Test
    void secondSuccessfulRemovalIsAnIdempotentFreshInspectionWithNoFurtherEffects() throws IOException {
        var fixture = history();
        try (var store = fixture.store()) {
            store.reclaim(Set.of(), fixture.journal, LIMITS);
            var after = fixture.namespace.snapshot();
            fixture.namespace.events.clear();
            var repeated = store.reclaim(Set.of(), fixture.journal, LIMITS);
            assertEquals(ReclamationResult.Status.COMPLETED, repeated.status());
            assertTrue(repeated.removedManifests().isEmpty());
            assertTrue(repeated.removedSegments().isEmpty());
            assertEquals(0, repeated.removedBytes());
            assertSameBytes(after, fixture.namespace.files);
            assertNoMutationOrForce(fixture.namespace);
        }
    }

    @Test
    void freshInventoryProtectsAnObjectThatBecameLiveAfterAnEarlierPreview() throws IOException {
        var fixture = history();
        try (var store = fixture.store()) {
            var stale = store.inspectReclamation(Set.of(), fixture.journal, LIMITS);
            assertEquals(List.of(fixture.oldSecond), names(stale.obsoleteSegments()));
            var third = fixture.segment(List.of(RECORDS.get(2)));
            fixture.add(manifest(4, 3, List.of(fixture.first, fixture.second, third)));
            fixture.namespace.events.clear();
            var result = store.reclaim(Set.of(), fixture.journal, LIMITS);
            assertEquals(Optional.of(id(fixture.roots.get(3))), result.selectedManifestId());
            assertEquals(List.of(fixture.mergedSecondThird), names(result.removedSegments()));
            assertTrue(fixture.namespace.files.containsKey(fixture.oldSecond));
            assertFalse(fixture.namespace.files.containsKey(fixture.mergedSecondThird));
            assertAllGraphsComplete(fixture.namespace.files);
        }
    }

    @Test
    void liveReaderDefersWithNoInspectionOrEffectsAndStoreRemainsUsable() throws IOException {
        var fixture = history();
        fixture.namespace.deferred = true;
        var before = fixture.namespace.snapshot();
        try (var store = fixture.store()) {
            var result = store.reclaim(Set.of(), fixture.journal, LIMITS);
            assertEquals(ReclamationResult.Status.DEFERRED_READERS, result.status());
            assertTrue(result.selectedManifestId().isEmpty());
            assertTrue(result.retainedManifestIds().isEmpty());
            assertTrue(result.removedManifests().isEmpty());
            assertTrue(result.removedSegments().isEmpty());
            assertEquals(0, result.removedBytes());
            assertEquals(List.of("barrier-acquire"), fixture.namespace.events);
            assertSameBytes(before, fixture.namespace.files);
            assertEquals(directory.resolve(LocalCheckpointStore.JOURNAL), store.journalPath());
            fixture.namespace.deferred = false;
            assertEquals(ReclamationResult.Status.COMPLETED, store.reclaim(Set.of(), fixture.journal, LIMITS).status());
        }
    }

    @Test
    void removalNeedsNoNewObjectSlotWhenTheExistingGuardAndDirectoryAreAtCapacity() throws IOException {
        var fixture = history();
        while (fixture.namespace.files.size() < LocalCheckpointStore.MAX_DIRECTORY_ENTRIES) {
            fixture.namespace.files.put("unknown-" + fixture.namespace.files.size(), new byte[0]);
        }
        try (var store = fixture.store()) {
            var result = store.reclaim(Set.of(), fixture.journal, LIMITS);
            assertEquals(2, result.removedManifests().size());
            assertEquals(1, result.removedSegments().size());
            assertEquals(LocalCheckpointStore.MAX_DIRECTORY_ENTRIES - 3, fixture.namespace.files.size());
            assertAllGraphsComplete(fixture.namespace.files);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"alias", "newest", "obsolete", "prefix", "pending", "missing-retained", "budget"})
    void failedPreflightPreservesEverythingBeforeAnyDurabilityOrRemovalEffects(String defect) throws IOException {
        var fixture = history();
        Set<ManifestId> retained = Set.of();
        var limits = LIMITS;
        switch (defect) {
            case "alias" -> fixture.namespace.aliasFailure = new IOException("Alias cannot be classified");
            case "newest" -> fixture.namespace.files.get(name(fixture.roots.get(2)))[0] ^= 1;
            case "obsolete" -> fixture.namespace.files.get(fixture.oldSecond)[0] ^= 1;
            case "prefix" -> fixture.journal.wrongPrefix = true;
            case "pending" -> fixture.namespace.files.put("pending-inspect-first", new byte[] {4});
            case "missing-retained" -> retained = Set.of(new ManifestId("f".repeat(64)));
            case "budget" -> limits = new ReclamationInventory.Limits(0, 0);
            default -> throw new AssertionError(defect);
        }
        var chosenRoots = retained;
        var chosenLimits = limits;
        var before = fixture.namespace.snapshot();
        try (var store = fixture.store()) {
            assertThrows(Exception.class, () -> store.reclaim(chosenRoots, fixture.journal, chosenLimits));
            assertPoisoned(store);
            assertNoMutationOrForce(fixture.namespace);
            assertSameBytes(before, fixture.namespace.files);
        }
    }

    @ParameterizedTest
    @MethodSource("storageFaults")
    void beforeAndAfterStorageFailuresNeverLeaveDanglingGraphsAndClassifyAttemptedRemoval(Fault fault) throws IOException {
        var fixture = history();
        String event = fixture.resolve(fault.phase());
        var primary = failure(fault.kind(), "Injected " + event);
        fixture.namespace.fault = new Injected(event, fault.after(), primary);
        fixture.journal.fault = fixture.namespace.fault;
        var store = fixture.store();
        try {
            var thrown = assertThrows(Throwable.class, () -> store.reclaim(Set.of(), fixture.journal, LIMITS));
            boolean attempted = fixture.namespace.events.stream().anyMatch(value -> value.startsWith("delete:"));
            if (primary instanceof Error) { assertSame(primary, thrown); }
            else if (attempted) {
                assertTrue(thrown instanceof LocalCheckpointStore.UncertainReclamationException, thrown.toString());
                assertSame(primary, thrown.getCause());
            } else { assertSame(primary, thrown); }
            assertPoisoned(store);
            assertAllGraphsComplete(fixture.namespace.files);
            assertAllGraphsComplete(fixture.namespace.durableNames);
        } finally { store.close(); }
        // Reopening recomputes from surviving manifests, without adopting a removed root's orphan.
        var reopened = fixture.reopen();
        try (var next = reopened.store()) {
            next.reclaim(Set.of(), reopened.journal, LIMITS);
            assertAllGraphsComplete(reopened.namespace.files);
        }
    }

    static Stream<Arguments> storageFaults() {
        var cases = new ArrayList<Arguments>();
        for (String phase : List.of("journal", "live-manifest", "live-segment", "directory-1", "old-manifest-1",
                "old-manifest-2", "directory-2", "old-segment", "directory-3")) {
            for (boolean after : List.of(false, true)) { cases.add(Arguments.of(new Fault(phase, after, "io"))); }
        }
        for (String phase : List.of("old-manifest-1", "old-segment", "directory-3")) {
            for (boolean after : List.of(false, true)) {
                for (String kind : List.of("runtime", "error")) { cases.add(Arguments.of(new Fault(phase, after, kind))); }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("probeFaults")
    void everyObservedBoundaryFailurePreservesCompleteSurvivorsAndReturnsNoReceipt(String boundary, String kind) throws IOException {
        var fixture = history();
        var primary = failure(kind, "Probe failed");
        var store = new LocalCheckpointStore(fixture.namespace, null, (reached, name) -> {
            fixture.namespace.events.add("probe:" + reached.name());
            if (reached.name().equals(boundary)) { throwFailure(primary); }
        });
        try {
            var thrown = assertThrows(Throwable.class, () -> store.reclaim(Set.of(), fixture.journal, LIMITS));
            boolean attempted = fixture.namespace.events.stream().anyMatch(value -> value.startsWith("delete:"));
            if (primary instanceof Error) { assertSame(primary, thrown); }
            else if (attempted) {
                assertTrue(thrown instanceof LocalCheckpointStore.UncertainReclamationException, thrown.toString());
                assertSame(primary, thrown.getCause());
            } else { assertSame(primary, thrown); }
            assertPoisoned(store);
            assertAllGraphsComplete(fixture.namespace.files);
            assertAllGraphsComplete(fixture.namespace.durableNames);
        } finally { store.close(); }
    }

    static Stream<Arguments> probeFaults() {
        var cases = new ArrayList<Arguments>();
        for (String boundary : List.of("LIVE_ROOTS_SYNCED", "MANIFEST_REMOVED", "MANIFESTS_SYNCED", "SEGMENT_REMOVED", "DIRECTORY_SYNCED")) {
            for (String kind : List.of("io", "runtime", "error")) { cases.add(Arguments.of(boundary, kind)); }
        }
        return cases.stream();
    }

    @Test
    void interruptedMetadataRetirementLeavesItsUnreferencedSegmentAsEvidenceOnReopen() throws IOException {
        var fixture = history();
        var sourceImage = fixture.namespace.files.get(fixture.oldSecond).clone();
        var store = new LocalCheckpointStore(fixture.namespace, null, (boundary, name) -> {
            if (boundary.name().equals("MANIFESTS_SYNCED")) { throw new IOException("Stop after metadata retirement"); }
        });
        try {
            assertThrows(LocalCheckpointStore.UncertainReclamationException.class,
                    () -> store.reclaim(Set.of(), fixture.journal, LIMITS));
        } finally { store.close(); }
        assertTrue(fixture.namespace.files.containsKey(fixture.oldSecond));
        assertEquals(1, fixture.namespace.files.keySet().stream().filter(value -> value.startsWith("checkpoint-")).count());
        var reopened = fixture.reopen();
        try (var next = reopened.store()) {
            var result = next.reclaim(Set.of(), reopened.journal, LIMITS);
            assertTrue(result.removedManifests().isEmpty());
            assertTrue(result.removedSegments().isEmpty());
            assertArrayEquals(sourceImage, reopened.namespace.files.get(fixture.oldSecond));
            assertNoMutationOrForce(reopened.namespace);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void protectionReleaseFailureAfterDeletionProducesNoSuccessAndCloseRetriesProtectionIndependently(String kind) throws IOException {
        var fixture = history();
        var release = failure(kind, "Protection close failed");
        fixture.namespace.protection.closeFailure = release;
        var store = fixture.store();
        try {
            var thrown = assertThrows(Throwable.class, () -> store.reclaim(Set.of(), fixture.journal, LIMITS));
            if (release instanceof Error) { assertSame(release, thrown); }
            else {
                assertTrue(thrown instanceof LocalCheckpointStore.UncertainReclamationException, thrown.toString());
                assertSame(release, thrown.getCause());
            }
            assertPoisoned(store);
            assertEquals(1, fixture.namespace.protection.closes);
            var namespaceFailure = new IOException("Publisher close failed");
            fixture.namespace.closeFailure = namespaceFailure;
            var closeThrown = assertThrows(Throwable.class, store::close);
            assertSame(release, closeThrown);
            assertTrue(List.of(closeThrown.getSuppressed()).contains(namespaceFailure));
            assertEquals(1, fixture.namespace.closes);
            fixture.namespace.closeFailure = null;
            fixture.namespace.protection.closeFailure = null;
            store.close();
            store.close();
            assertEquals(3, fixture.namespace.protection.closes);
            assertEquals(2, fixture.namespace.closes);
            assertAllGraphsComplete(fixture.namespace.files);
        } finally {
            fixture.namespace.closeFailure = null;
            fixture.namespace.protection.closeFailure = null;
            store.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void primaryRemovalFailureRetainsSuppressedProtectionCleanupAndReleasesBothOwnersOnRetry(String kind) throws IOException {
        var fixture = history();
        var primary = failure(kind, "Delete failed after effect");
        var release = new IOException("Release also failed");
        fixture.namespace.fault = new Injected(fixture.resolve("old-manifest-1"), true, primary);
        fixture.namespace.protection.closeFailure = release;
        var store = fixture.store();
        try {
            var thrown = assertThrows(Throwable.class, () -> store.reclaim(Set.of(), fixture.journal, LIMITS));
            var cause = thrown instanceof LocalCheckpointStore.UncertainReclamationException ? thrown.getCause() : thrown;
            assertSame(primary, cause);
            assertEquals(List.of(release), List.of(primary.getSuppressed()));
            assertAllGraphsComplete(fixture.namespace.files);
            fixture.namespace.protection.closeFailure = null;
            store.close();
            assertEquals(1, fixture.namespace.closes);
        } finally {
            fixture.namespace.protection.closeFailure = null;
            store.close();
        }
    }

    @Test
    void invalidProtectionFailsBeforeAnyObjectAccessAndRemainsCloseable() throws IOException {
        var fixture = history();
        fixture.namespace.protection.invalid = true;
        try (var store = fixture.store()) {
            assertThrows(IOException.class, () -> store.reclaim(Set.of(), fixture.journal, LIMITS));
            assertEquals(List.of("barrier-acquire", "barrier-close"), fixture.namespace.events);
            assertFalse(fixture.namespace.protection.held, "Invalid protection still receives cleanup before failure escapes");
            assertPoisoned(store);
        }
        assertEquals(1, fixture.namespace.closes);
    }

    @Test
    void reentrantCloseAndRemovalInsideTheDurabilityObserverCannotInvalidateOuterOwnership() throws IOException {
        var fixture = history();
        var holder = new LocalCheckpointStore[1];
        holder[0] = new LocalCheckpointStore(fixture.namespace, null, (boundary, name) -> {
            assertThrows(IOException.class, holder[0]::close);
            assertThrows(IOException.class, () -> holder[0].reclaim(Set.of(), fixture.journal, LIMITS));
        });
        try (var store = holder[0]) {
            assertEquals(ReclamationResult.Status.COMPLETED, store.reclaim(Set.of(), fixture.journal, LIMITS).status());
            assertAllGraphsComplete(fixture.namespace.files);
        }
    }

    @Test
    void invalidPureArgumentsDoNotTouchStorageOrPoisonTheStore() throws IOException {
        var fixture = history();
        try (var store = fixture.store()) {
            assertThrows(NullPointerException.class, () -> store.reclaim(null, fixture.journal, LIMITS));
            assertThrows(NullPointerException.class, () -> store.reclaim(Set.of(), (LocalCheckpointStore.JournalAccess) null, LIMITS));
            assertThrows(NullPointerException.class, () -> store.reclaim(Set.of(), fixture.journal, null));
            assertTrue(fixture.namespace.events.isEmpty());
            assertEquals(directory.resolve(LocalCheckpointStore.JOURNAL), store.journalPath());
            assertEquals(ReclamationResult.Status.COMPLETED, store.reclaim(Set.of(), fixture.journal, LIMITS).status());
        }
    }

    private Fixture history() {
        var fixture = empty();
        fixture.first = fixture.segment(List.of(RECORDS.getFirst()));
        fixture.second = fixture.segment(List.of(RECORDS.get(1)));
        var merged = fixture.segment(RECORDS.subList(1, 3));
        fixture.add(manifest(1, 1, List.of(fixture.first)));
        fixture.add(manifest(2, 2, List.of(fixture.first, fixture.second)));
        fixture.add(manifest(3, 3, List.of(fixture.first, merged)));
        fixture.sharedFirst = LocalCheckpointStore.segmentName(fixture.first.id());
        fixture.oldSecond = LocalCheckpointStore.segmentName(fixture.second.id());
        fixture.mergedSecondThird = LocalCheckpointStore.segmentName(merged.id());
        fixture.unreferenced = LocalCheckpointStore.segmentName(fixture.segment(RECORDS).id());
        fixture.namespace.files.put("operator-notes", new byte[] {7, 2, 4});
        fixture.namespace.files.put("unrecognized.segment", new byte[] {8});
        fixture.namespace.durableNames = fixture.namespace.snapshot();
        return fixture;
    }

    private Fixture empty() {
        var namespace = new MemoryNamespace(directory);
        namespace.files.put(LocalCheckpointStore.LOCK, new byte[0]);
        namespace.files.put(LocalReaderBarrier.NAME, new byte[0]);
        namespace.files.put(LocalCheckpointStore.JOURNAL, new byte[] {1, 2, 3});
        return new Fixture(namespace);
    }

    private static JournalRecord record(long sequence) {
        return new JournalRecord(sequence, new Transaction(new RequestId("request-" + sequence), List.of(
                new WriteOperation.Put(new EntityId("balance"), new ValidTimeInterval(null, null), sequence))));
    }

    private static LocalJournal.Prefix prefix(long sequence) {
        var image = new ByteArrayOutputStream();
        image.writeBytes(JournalCodec.encodeHeader(DATABASE));
        for (var record : RECORDS) {
            if (record.sequence() <= sequence) { image.writeBytes(JournalCodec.encodeRecord(record)); }
        }
        var bytes = image.toByteArray();
        return new LocalJournal.Prefix(sequence, bytes.length, HexFormat.of().formatHex(ManifestCodec.sha256().digest(bytes)));
    }

    private static CheckpointManifest manifest(long generation, long cutoff, List<SegmentReference> references) {
        var prefix = prefix(cutoff);
        return new CheckpointManifest(DATABASE, generation, new JournalPrefix(cutoff, prefix.byteLength(), prefix.sha256()), references);
    }

    private static ManifestId id(CheckpointManifest manifest) { return ManifestCodec.decode(ManifestCodec.encode(manifest)).id(); }
    private static String name(CheckpointManifest manifest) { return LocalCheckpointStore.generationName(manifest.generation(), id(manifest)); }
    private static List<String> names(List<ReclamationInventory.ObjectFile> objects) {
        return objects.stream().map(ReclamationInventory.ObjectFile::name).toList();
    }

    private static void assertProtocolOrder(Fixture fixture, Set<ManifestId> retained) {
        var events = fixture.namespace.events;
        int firstDelete = indexStarting(events, "delete:");
        int journalForce = events.indexOf("journal-force");
        int baselineSync = events.indexOf("directory-force:1");
        int retirementSync = events.indexOf("directory-force:2");
        int completionSync = events.indexOf("directory-force:3");
        assertTrue(journalForce >= 0 && journalForce < baselineSync && baselineSync < firstDelete);
        var expectedForced = new HashSet<String>();
        for (var manifest : fixture.roots) {
            if (!retained.contains(id(manifest))) { continue; }
            expectedForced.add(name(manifest));
            manifest.segments().forEach(reference -> expectedForced.add(LocalCheckpointStore.segmentName(reference.id())));
        }
        var forced = events.stream().filter(event -> event.startsWith("force:")).map(event -> event.substring(6)).toList();
        assertEquals(expectedForced, new HashSet<>(forced));
        assertEquals(expectedForced.size(), forced.size(), "Shared retained objects need one force each");
        for (String object : expectedForced) {
            int position = events.indexOf("force:" + object);
            assertTrue(journalForce < position && position < baselineSync, "Retained image precedes the first removal: " + object);
        }
        for (int index = 0; index < events.size(); index++) {
            var event = events.get(index);
            if (!event.startsWith("delete:")) { continue; }
            if (event.endsWith(".manifest")) { assertTrue(firstDelete <= index && index < retirementSync); }
            else { assertTrue(retirementSync < index && index < completionSync); }
        }
        assertTrue(events.indexOf("barrier-close") > completionSync);
    }

    private static int indexStarting(List<String> events, String prefix) {
        for (int index = 0; index < events.size(); index++) { if (events.get(index).startsWith(prefix)) { return index; } }
        throw new AssertionError("Missing event prefix: " + prefix);
    }

    private static void assertAllGraphsComplete(Map<String, byte[]> files) throws IOException {
        for (var entry : files.entrySet()) {
            if (!entry.getKey().startsWith("checkpoint-")) { continue; }
            var manifest = ManifestCodec.decode(entry.getValue()).manifest();
            CheckpointVerifier.verify(manifest, id -> files.get(LocalCheckpointStore.segmentName(id)));
            var actual = prefix(manifest.journalPrefix().sequence());
            assertEquals(actual.byteLength(), manifest.journalPrefix().byteLength());
            assertEquals(actual.sha256(), manifest.journalPrefix().sha256());
        }
    }

    private static void assertSameBytes(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, image) -> assertArrayEquals(image, actual.get(name)));
    }

    private static void assertNoMutationOrForce(MemoryNamespace namespace) {
        assertTrue(namespace.events.stream().noneMatch(event -> event.startsWith("force:") || event.startsWith("delete:")
                || event.startsWith("directory-force:") || event.equals("journal-force")), namespace.events.toString());
    }

    private static void assertPoisoned(LocalCheckpointStore store) { assertThrows(IOException.class, store::journalPath); }

    private static Throwable failure(String kind, String message) {
        return switch (kind) {
            case "io" -> new IOException(message);
            case "runtime" -> new IllegalStateException(message);
            case "error" -> new AssertionError(message);
            default -> throw new AssertionError(kind);
        };
    }

    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }

    private record Fault(String phase, boolean after, String kind) { }
    private record Injected(String event, boolean after, Throwable failure) { }

    private static final class Fixture {
        final MemoryNamespace namespace;
        final MemoryJournal journal;
        final List<CheckpointManifest> roots = new ArrayList<>();
        SegmentReference first;
        SegmentReference second;
        String sharedFirst;
        String oldSecond;
        String mergedSecondThird;
        String unreferenced;
        Fixture(MemoryNamespace namespace) { this.namespace = namespace; this.journal = new MemoryJournal(namespace); }
        LocalCheckpointStore store() { return new LocalCheckpointStore(namespace); }
        void add(CheckpointManifest manifest) { roots.add(manifest); namespace.files.put(name(manifest), ManifestCodec.encode(manifest)); }
        SegmentReference segment(List<JournalRecord> records) {
            var segment = new ImmutableSegment(DATABASE, records);
            var image = SegmentCodec.encode(segment);
            var decoded = SegmentCodec.decode(image);
            namespace.files.put(LocalCheckpointStore.segmentName(decoded.id()), image);
            return new SegmentReference(decoded.id(), image.length, segment.firstSequence(), segment.lastSequence());
        }
        String resolve(String phase) {
            return switch (phase) {
                case "journal" -> "journal-force";
                case "live-manifest" -> "force:" + name(roots.get(2));
                case "live-segment" -> "force:" + sharedFirst;
                case "directory-1" -> "directory-force:1";
                case "old-manifest-1" -> "delete:" + name(roots.getFirst());
                case "old-manifest-2" -> "delete:" + name(roots.get(1));
                case "directory-2" -> "directory-force:2";
                case "old-segment" -> "delete:" + oldSecond;
                case "directory-3" -> "directory-force:3";
                default -> throw new AssertionError(phase);
            };
        }
        Fixture reopen() {
            var namespace = new MemoryNamespace(this.namespace.directory);
            namespace.files.putAll(this.namespace.snapshot());
            namespace.durableNames = namespace.snapshot();
            return new Fixture(namespace);
        }
    }

    private static final class MemoryJournal implements LocalCheckpointStore.JournalAccess {
        final MemoryNamespace namespace;
        final List<Long> prefixCalls = new ArrayList<>();
        boolean wrongPrefix;
        Injected fault;
        MemoryJournal(MemoryNamespace namespace) { this.namespace = namespace; }
        @Override public DatabaseId databaseId() { return DATABASE; }
        @Override public LocalJournal.Prefix prefix(long sequence) throws IOException {
            prefixCalls.add(sequence);
            var actual = LocalReclamationRemovalTest.prefix(sequence);
            return wrongPrefix ? new LocalJournal.Prefix(sequence, actual.byteLength(), "0".repeat(64)) : actual;
        }
        @Override public void force() throws IOException {
            assertTrue(namespace.protection.held);
            namespace.events.add("journal-force");
            fail(false);
            fail(true);
        }
        private void fail(boolean after) throws IOException {
            if (fault != null && fault.event().equals("journal-force") && fault.after() == after) { throwFailure(fault.failure()); }
        }
    }

    private static final class MemoryNamespace implements LocalCheckpointStore.Namespace {
        final Path directory;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final MemoryProtection protection = new MemoryProtection(events);
        Map<String, byte[]> durableNames = Map.of();
        boolean deferred;
        Injected fault;
        IOException aliasFailure;
        IOException closeFailure;
        int directoryForces;
        int closes;
        MemoryNamespace(Path directory) { this.directory = directory; }
        @Override public Path directory() { return directory; }
        @Override public LocalReaderBarrier.Protection acquireReader() { throw new AssertionError("Removal needs exclusive protection"); }
        @Override public Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() {
            events.add("barrier-acquire");
            if (deferred) { return Optional.empty(); }
            protection.held = true;
            return Optional.of(protection);
        }
        @Override public List<String> entries() { assertTrue(protection.held); events.add("entries"); return new ArrayList<>(files.keySet()).reversed(); }
        @Override public void checkObjectAliases(String name, List<String> entries) throws IOException {
            assertTrue(protection.held);
            events.add("alias:" + name);
            if (aliasFailure != null) { throw aliasFailure; }
        }
        @Override public byte[] read(String name, int maxBytes) throws IOException {
            assertTrue(protection.held);
            events.add("read:" + name);
            var image = files.get(name);
            if (image == null || image.length > maxBytes) { throw new IOException("Missing or oversized image: " + name); }
            return image.clone();
        }
        @Override public void writeNew(String name, byte[] image) { throw new AssertionError("Removal creates no objects"); }
        @Override public void atomicMove(String from, String to) { throw new AssertionError("Removal moves no objects"); }
        @Override public void forceFile(String name) throws IOException {
            assertTrue(protection.held);
            String event = "force:" + name;
            events.add(event);
            fail(event, false);
            assertNotNull(files.get(name));
            fail(event, true);
        }
        @Override public void forceDirectory() throws IOException {
            assertTrue(protection.held);
            String event = "directory-force:" + ++directoryForces;
            events.add(event);
            fail(event, false);
            durableNames = snapshot();
            fail(event, true);
        }
        @Override public void delete(String name) throws IOException {
            assertTrue(protection.held);
            String event = "delete:" + name;
            events.add(event);
            fail(event, false);
            if (files.remove(name) == null) { throw new IOException("Verified name unexpectedly disappeared"); }
            fail(event, true);
        }
        @Override public void close() throws IOException {
            events.add("namespace-close");
            closes++;
            if (closeFailure != null) { throw closeFailure; }
        }
        private void fail(String event, boolean after) throws IOException {
            if (fault != null && fault.event().equals(event) && fault.after() == after) { throwFailure(fault.failure()); }
        }
        Map<String, byte[]> snapshot() {
            var result = new LinkedHashMap<String, byte[]>();
            files.forEach((name, bytes) -> result.put(name, bytes.clone()));
            return result;
        }
    }

    private static final class MemoryProtection implements LocalReaderBarrier.Protection {
        final List<String> events;
        boolean held;
        boolean invalid;
        Throwable closeFailure;
        int closes;
        MemoryProtection(List<String> events) { this.events = events; }
        @Override public void checkValid() throws IOException {
            if (!held || invalid) { throw new IOException("Exclusive protection invalid"); }
        }
        @Override public void close() throws IOException {
            events.add("barrier-close");
            closes++;
            if (closeFailure != null) { throwFailure(closeFailure); }
            held = false;
        }
    }
}
