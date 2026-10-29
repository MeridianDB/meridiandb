// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class LocalReclamationInventoryTest {
    private static final DatabaseId DATABASE = new DatabaseId("inventory-db");
    private static final List<JournalRecord> RECORDS = List.of(record(1), record(2), record(3));
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long MAX_RECORDS = 262_144;
    private static final ReclamationInventory.Limits LIMITS = new ReclamationInventory.Limits(MAX_BYTES, MAX_RECORDS);

    @TempDir
    Path directory;

    @Test
    void inventorySelectsOnlyObsoleteVerifiedObjectsAndPreservesSharedAndUnreferencedFiles() throws IOException {
        var fixture = history();
        fixture.namespace.reverseEntries = true;
        var before = fixture.namespace.snapshot();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var inventory = store.inspectReclamation(Set.of(), fixture.journal, LIMITS);
            assertEquals(ReclamationInventory.Status.VERIFIED, inventory.status());
            assertEquals(Optional.of(id(fixture.roots.get(2))), inventory.selectedManifestId());
            assertEquals(Set.of(id(fixture.roots.get(2))), inventory.retainedManifestIds());
            assertObjects(inventory.obsoleteManifests(), fixture.namespace,
                    List.of(name(fixture.roots.get(0)), name(fixture.roots.get(1))));
            assertObjects(inventory.obsoleteSegments(), fixture.namespace, List.of(fixture.oldSecond));
            var obsolete = new HashSet<String>();
            inventory.obsoleteManifests().forEach(file -> obsolete.add(file.name()));
            inventory.obsoleteSegments().forEach(file -> obsolete.add(file.name()));
            assertEquals(before.keySet().stream().filter(entry -> !obsolete.contains(entry)).sorted().toList(),
                    inventory.preservedNames());
            assertEquals(bytesOf(fixture.namespace, obsolete), inventory.candidateBytes());
            assertEquals(verifiedBytes(fixture), inventory.verifiedBytes());
            assertEquals(6, inventory.verifiedRecords(), "Each generation's original prefix is counted independently");
            assertTrue(inventory.preservedNames().contains(fixture.sharedFirst));
            assertTrue(inventory.preservedNames().contains(fixture.unreferenced));
            assertEquals(1, fixture.namespace.barrier.closes);
            assertEquals(directory.resolve(LocalCheckpointStore.JOURNAL), store.journalPath());
            assertAliasChecksPrecedeReads(fixture.namespace);
            assertNoMutations(before, fixture.namespace);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void explicitRetainedRootsProtectTheirCompleteDependencyUnion(int retainedIndex) throws IOException {
        var fixture = history();
        var retained = id(fixture.roots.get(retainedIndex));
        var requested = new HashSet<>(Set.of(retained));
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var inventory = store.inspectReclamation(requested, fixture.journal, LIMITS);
            var expectedRoots = new HashSet<>(Set.of(id(fixture.roots.get(2))));
            expectedRoots.add(retained);
            assertEquals(expectedRoots, inventory.retainedManifestIds());
            var expectedManifests = fixture.roots.stream().filter(root -> !expectedRoots.contains(id(root)))
                    .map(LocalReclamationInventoryTest::name).sorted().toList();
            assertObjects(inventory.obsoleteManifests(), fixture.namespace, expectedManifests);
            assertObjects(inventory.obsoleteSegments(), fixture.namespace,
                    retainedIndex == 1 ? List.of() : List.of(fixture.oldSecond));
            requested.clear();
            assertEquals(expectedRoots, inventory.retainedManifestIds(), "Caller set is copied");
            assertThrows(UnsupportedOperationException.class, () -> inventory.retainedManifestIds().clear());
            assertThrows(UnsupportedOperationException.class, () -> inventory.obsoleteManifests().clear());
            assertThrows(UnsupportedOperationException.class, () -> inventory.obsoleteSegments().clear());
            assertThrows(UnsupportedOperationException.class, () -> inventory.preservedNames().clear());
        }
    }

    @Test
    void multipleGenesisGenerationsHaveOnlyMetadataCandidatesAndNoRecordWork() throws IOException {
        var fixture = empty();
        fixture.add(manifest(1, 0, List.of()));
        fixture.add(manifest(2, 0, List.of()));
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var inventory = store.inspectReclamation(Set.of(), fixture.journal, LIMITS);
            assertObjects(inventory.obsoleteManifests(), fixture.namespace, List.of(name(fixture.roots.getFirst())));
            assertTrue(inventory.obsoleteSegments().isEmpty());
            assertEquals(0, inventory.verifiedRecords());
            assertEquals(verifiedBytes(fixture), inventory.verifiedBytes());
            assertEquals(List.of(0L, 0L), fixture.journal.prefixCalls.stream().sorted().toList());
        }
    }

    @Test
    void legalPhysicalGenerationGapsSelectTheHighestAndHonorAnExplicitOlderRoot() throws IOException {
        var fixture = empty();
        var first = manifest(1, 0, List.of());
        var fourth = manifest(4, 0, List.of());
        fixture.add(first);
        fixture.add(fourth);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var inventory = store.inspectReclamation(Set.of(), fixture.journal, LIMITS);
            assertEquals(ReclamationInventory.Status.VERIFIED, inventory.status());
            assertEquals(Optional.of(id(fourth)), inventory.selectedManifestId());
            assertEquals(Set.of(id(fourth)), inventory.retainedManifestIds());
            assertObjects(inventory.obsoleteManifests(), fixture.namespace, List.of(name(first)));
            assertTrue(inventory.obsoleteSegments().isEmpty());
            var retained = store.inspectReclamation(Set.of(id(first)), fixture.journal, LIMITS);
            assertEquals(Optional.of(id(fourth)), retained.selectedManifestId());
            assertEquals(Set.of(id(first), id(fourth)), retained.retainedManifestIds());
            assertTrue(retained.obsoleteManifests().isEmpty());
            assertTrue(retained.obsoleteSegments().isEmpty());
            assertEquals(fixture.namespace.files.keySet().stream().sorted().toList(), retained.preservedNames());
            assertEquals(0, retained.candidateBytes());
        }
    }

    @Test
    void noSelectedGenerationProducesNoCandidatesAndDoesNotReadUnknownObjects() throws IOException {
        var fixture = empty();
        fixture.namespace.files.put("unknown.segment", new byte[] {9});
        fixture.namespace.files.put("notes.txt", new byte[] {8});
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var inventory = store.inspectReclamation(Set.of(), fixture.journal, new ReclamationInventory.Limits(0, 0));
            assertEquals(ReclamationInventory.Status.VERIFIED, inventory.status());
            assertEquals(Optional.empty(), inventory.selectedManifestId());
            assertTrue(inventory.retainedManifestIds().isEmpty());
            assertTrue(inventory.obsoleteManifests().isEmpty());
            assertTrue(inventory.obsoleteSegments().isEmpty());
            assertEquals(fixture.namespace.files.keySet().stream().sorted().toList(), inventory.preservedNames());
            assertEquals(0, inventory.candidateBytes());
            assertEquals(0, inventory.verifiedBytes());
            assertEquals(0, inventory.verifiedRecords());
            assertTrue(fixture.namespace.reads.isEmpty());
            assertTrue(fixture.journal.prefixCalls.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnresolvableExplicitRetainedRootFailsClosed(boolean selectedExists) throws IOException {
        var fixture = selectedExists ? history() : empty();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(new ManifestId("f".repeat(64))), fixture.journal, LIMITS));
            assertPoisoned(store);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"newest-metadata", "old-metadata", "old-segment", "old-prefix", "lineage"})
    void everyGenerationMustVerifyIncludingOldUnretainedGraphsAndActualOwnerPrefix(String damage) throws IOException {
        var fixture = history();
        Class<? extends Throwable> expected = IOException.class;
        switch (damage) {
            case "newest-metadata" -> {
                fixture.namespace.files.get(name(fixture.roots.get(2)))[0] ^= 1;
                expected = ManifestFormatException.class;
            }
            case "old-metadata" -> {
                fixture.namespace.files.get(name(fixture.roots.getFirst()))[0] ^= 1;
                expected = ManifestFormatException.class;
            }
            case "old-segment" -> {
                fixture.namespace.files.get(fixture.oldSecond)[0] ^= 1;
                expected = SegmentFormatException.class;
            }
            case "old-prefix" -> fixture.journal.badPrefixAt = 1;
            case "lineage" -> fixture.journal.database = new DatabaseId("foreign");
            default -> throw new AssertionError(damage);
        }
        var before = fixture.namespace.snapshot();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(expected, () -> store.inspectReclamation(Set.of(), fixture.journal, LIMITS));
            assertPoisoned(store);
            assertEquals(1, fixture.namespace.barrier.closes);
            assertNoMutations(before, fixture.namespace);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending-", "pending-abandoned.manifest", "pending-123.segment"})
    void pendingNamesRefuseInspectionBeforeReadingAnyObject(String pending) throws IOException {
        var fixture = history();
        fixture.namespace.files.put(pending, new byte[] {1, 2, 3});
        var before = fixture.namespace.snapshot();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), fixture.journal, LIMITS));
            assertTrue(fixture.namespace.reads.isEmpty());
            assertTrue(fixture.journal.prefixCalls.isEmpty());
            assertPoisoned(store);
            assertNoMutations(before, fixture.namespace);
        }
    }

    @Test
    void completeByteAndRecordLimitsPermitExactlyTheDeclaredWork() throws IOException {
        var fixture = history();
        long bytes = verifiedBytes(fixture);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var inventory = store.inspectReclamation(Set.of(), fixture.journal, new ReclamationInventory.Limits(bytes, 6));
            assertEquals(bytes, inventory.verifiedBytes());
            assertEquals(6, inventory.verifiedRecords());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"one-byte", "one-record"})
    void insufficientAggregateBudgetRejectsBeforeAnySegmentRead(String budget) throws IOException {
        var fixture = history();
        var limits = new ReclamationInventory.Limits(verifiedBytes(fixture) - (budget.equals("one-byte") ? 1 : 0),
                budget.equals("one-record") ? 5 : 6);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), fixture.journal, limits));
            assertTrue(fixture.namespace.reads.stream().noneMatch(read -> read.name().endsWith(".segment")));
            assertTrue(fixture.journal.prefixCalls.isEmpty());
            assertPoisoned(store);
        }
    }

    @Test
    void oversizedStoredSegmentIsReadWithItsDeclaredLengthWithinTheExactInspectionBudget() throws IOException {
        var fixture = empty();
        var reference = fixture.segment(List.of(RECORDS.getFirst()));
        var root = manifest(1, 1, List.of(reference));
        fixture.add(root);
        String segmentName = LocalCheckpointStore.segmentName(reference.id());
        byte[] original = fixture.namespace.files.get(segmentName);
        byte[] oversized = new byte[original.length + 1];
        System.arraycopy(original, 0, oversized, 0, original.length);
        fixture.namespace.files.put(segmentName, oversized);
        long exactBudget = fixture.namespace.files.get(name(root)).length + reference.byteLength();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), fixture.journal,
                    new ReclamationInventory.Limits(exactBudget, 1)));
            assertEquals(List.of(new ReadCall(segmentName, Math.toIntExact(reference.byteLength()))),
                    fixture.namespace.reads.stream().filter(read -> read.name().equals(segmentName)).toList());
            assertTrue(fixture.journal.prefixCalls.isEmpty());
            assertPoisoned(store);
        }
    }

    @Test
    void metadataReadsRespectTheRemainingBudgetBeforeReturningAnImage() throws IOException {
        var fixture = empty();
        fixture.add(manifest(1, 0, List.of()));
        int available = fixture.namespace.files.get(name(fixture.roots.getFirst())).length - 1;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), fixture.journal,
                    new ReclamationInventory.Limits(available, 0)));
            assertTrue(fixture.namespace.reads.stream().allMatch(read -> read.maxBytes() <= available));
            assertTrue(fixture.journal.prefixCalls.isEmpty());
            assertPoisoned(store);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes", "records"})
    void hugeDeclaredWorkIsRejectedFromMetadataWithoutResolvingAnyDependency(String budget) throws IOException {
        var fixture = empty();
        var references = new ArrayList<SegmentReference>();
        if (budget.equals("bytes")) {
            references.add(new SegmentReference(new SegmentId("1".repeat(64)), SegmentCodec.MAX_SEGMENT_BYTES, 1, 1));
            references.add(new SegmentReference(new SegmentId("2".repeat(64)), SegmentCodec.MAX_SEGMENT_BYTES, 2, 2));
        } else {
            for (int index = 0; index < 5; index++) {
                long first = (long) index * SegmentCodec.MAX_RECORDS + 1;
                references.add(new SegmentReference(new SegmentId(Integer.toHexString(index + 1).repeat(64)), 1,
                        first, first + SegmentCodec.MAX_RECORDS - 1));
            }
        }
        long cutoff = references.getLast().lastSequence();
        fixture.add(new CheckpointManifest(DATABASE, 1, new JournalPrefix(cutoff, 100, "0".repeat(64)), references));
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), fixture.journal, LIMITS));
            assertTrue(fixture.namespace.reads.stream().noneMatch(read -> read.name().endsWith(".segment")));
            assertTrue(fixture.journal.prefixCalls.isEmpty());
            assertPoisoned(store);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "zero", "overflow", "duplicate-number", "duplicate-entry", "too-many-generations", "too-many-entries"})
    void invalidNamespaceMetadataIsRejectedBeforeObjectReads(String defect) throws IOException {
        var fixture = history();
        switch (defect) {
            case "malformed" -> fixture.namespace.extraEntries.add("checkpoint-invalid.manifest");
            case "zero" -> fixture.namespace.extraEntries.add("checkpoint-0000000000000000000-" + "0".repeat(64) + ".manifest");
            case "overflow" -> fixture.namespace.extraEntries.add("checkpoint-9999999999999999999-" + "0".repeat(64) + ".manifest");
            case "duplicate-number" -> fixture.add(manifest(1, 0, List.of()));
            case "duplicate-entry" -> fixture.namespace.extraEntries.add(name(fixture.roots.getFirst()));
            case "too-many-generations" -> {
                for (int generation = 4; generation <= LocalCheckpointStore.MAX_GENERATIONS + 1; generation++) {
                    fixture.add(manifest(generation, 0, List.of()));
                }
            }
            case "too-many-entries" -> fixture.namespace.fillTo(LocalCheckpointStore.MAX_DIRECTORY_ENTRIES + 1);
            default -> throw new AssertionError(defect);
        }
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), fixture.journal, LIMITS));
            assertTrue(fixture.namespace.reads.isEmpty());
            assertPoisoned(store);
        }
    }

    @Test
    void aggregateAliasWorkLimitStopsBeforeCrossingTheBoundOrReadingObjects() throws IOException {
        var fixture = empty();
        for (int generation = 1; generation <= 101; generation++) { fixture.add(manifest(generation, 0, List.of())); }
        fixture.namespace.fillTo(10_002);
        assertEquals(1_000_000, ReclamationInventory.MAX_ALIAS_COMPARISONS);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), fixture.journal, LIMITS));
            assertTrue((long) fixture.namespace.aliasChecks.size() * (10_002 - 1)
                    <= ReclamationInventory.MAX_ALIAS_COMPARISONS);
            assertTrue(fixture.namespace.reads.isEmpty());
            assertPoisoned(store);
        }
    }

    @Test
    void liveReaderContentionReturnsAnEmptyDeferredResultWithoutReadsOrPoisoning() throws IOException {
        var fixture = history();
        fixture.namespace.deferred = true;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var deferred = store.inspectReclamation(Set.of(), fixture.journal, LIMITS);
            assertEquals(ReclamationInventory.Status.DEFERRED_READERS, deferred.status());
            assertEquals(Optional.empty(), deferred.selectedManifestId());
            assertTrue(deferred.retainedManifestIds().isEmpty());
            assertTrue(deferred.obsoleteManifests().isEmpty());
            assertTrue(deferred.obsoleteSegments().isEmpty());
            assertTrue(deferred.preservedNames().isEmpty());
            assertEquals(0, deferred.candidateBytes());
            assertEquals(0, deferred.verifiedBytes());
            assertEquals(0, deferred.verifiedRecords());
            assertTrue(fixture.namespace.reads.isEmpty());
            assertTrue(fixture.namespace.aliasChecks.isEmpty());
            assertTrue(fixture.journal.prefixCalls.isEmpty());
            assertEquals(0, fixture.namespace.barrier.closes);
            assertEquals(directory.resolve(LocalCheckpointStore.JOURNAL), store.journalPath());
            fixture.namespace.deferred = false;
            assertEquals(ReclamationInventory.Status.VERIFIED, store.inspectReclamation(Set.of(), fixture.journal, LIMITS).status());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void protectionMustRemainValidBeforeAndAfterTheCompleteInspection(int checkFailureAt) throws IOException {
        var fixture = history();
        fixture.namespace.barrier.checkFailureAt = checkFailureAt;
        var failure = new IOException("Protection validity failed");
        fixture.namespace.barrier.checkFailure = failure;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertSame(failure, assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), fixture.journal, LIMITS)));
            assertEquals(checkFailureAt, fixture.namespace.barrier.checks);
            assertEquals(checkFailureAt == 1, fixture.namespace.reads.isEmpty());
            assertEquals(1, fixture.namespace.barrier.closes);
            assertPoisoned(store);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"entries", "barrier", "alias-before", "alias-after", "read-before", "read-after", "prefix-before", "prefix-after"})
    void storageFailuresBeforeAndAfterObservationsPoisonWithoutMutation(String phase) throws IOException {
        var fixture = history();
        fixture.namespace.phase = phase;
        fixture.journal.phase = phase;
        var before = fixture.namespace.snapshot();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), fixture.journal, LIMITS));
            assertPoisoned(store);
            if (phase.startsWith("alias")) { assertTrue(fixture.namespace.reads.isEmpty()); }
            assertNoMutations(before, fixture.namespace);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void primaryVerificationFailureSuppressesReleaseFailureAndStoreCloseRetriesRetainedProtection(String kind) throws IOException {
        var fixture = history();
        Throwable primary = failure(kind, "Read failed");
        var release = new IOException("Barrier release failed");
        fixture.namespace.readFailure = primary;
        fixture.namespace.barrier.closeFailure = release;
        var store = new LocalCheckpointStore(fixture.namespace);
        try {
            assertSame(primary, assertThrows(primary.getClass(), () -> store.inspectReclamation(Set.of(), fixture.journal, LIMITS)));
            assertArrayEquals(new Throwable[] {release}, primary.getSuppressed());
            assertEquals(1, fixture.namespace.barrier.closes);
            assertPoisoned(store);
            assertSame(release, assertThrows(IOException.class, store::close));
            assertEquals(2, fixture.namespace.barrier.closes);
            assertEquals(1, fixture.namespace.closes, "Namespace close is independent of failed protection release");
            fixture.namespace.barrier.closeFailure = null;
            store.close();
            store.close();
            assertEquals(3, fixture.namespace.barrier.closes);
            assertEquals(1, fixture.namespace.closes);
        } finally {
            fixture.namespace.barrier.closeFailure = null;
            store.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void releaseFailureAfterSuccessfulVerificationReturnsNoInventoryAndBothCloseFailuresRemainRetryable(String kind) throws IOException {
        var fixture = history();
        var release = failure(kind, "Release failed");
        var namespaceClose = new IOException("Namespace close failed");
        fixture.namespace.barrier.closeFailure = release;
        var store = new LocalCheckpointStore(fixture.namespace);
        try {
            assertSame(release, assertThrows(release.getClass(), () -> store.inspectReclamation(Set.of(), fixture.journal, LIMITS)));
            assertPoisoned(store);
            fixture.namespace.closeFailure = namespaceClose;
            assertSame(release, assertThrows(release.getClass(), store::close));
            assertArrayEquals(new Throwable[] {namespaceClose}, release.getSuppressed());
            assertEquals(2, fixture.namespace.barrier.closes);
            assertEquals(1, fixture.namespace.closes);
            fixture.namespace.barrier.closeFailure = null;
            fixture.namespace.closeFailure = null;
            store.close();
            store.close();
            assertEquals(3, fixture.namespace.barrier.closes);
            assertEquals(2, fixture.namespace.closes);
        } finally {
            fixture.namespace.barrier.closeFailure = null;
            fixture.namespace.closeFailure = null;
            store.close();
        }
    }

    @Test
    void reentrantOperationsInsideAliasChecksAreRejectedWhileTheOuterInspectionCompletes() throws IOException {
        var fixture = history();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            fixture.namespace.aliasHook = () -> {
                assertThrows(IOException.class, store::close);
                assertThrows(IOException.class, store::journalPath);
                assertThrows(IOException.class, () -> store.inspectReclamation(Set.of(), fixture.journal, LIMITS));
            };
            assertEquals(ReclamationInventory.Status.VERIFIED, store.inspectReclamation(Set.of(), fixture.journal, LIMITS).status());
        }
    }

    @Test
    void invalidPureArgumentsDoNotTouchStorageOrPoisonTheStore() throws IOException {
        var fixture = history();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(NullPointerException.class, () -> store.inspectReclamation(null, fixture.journal, LIMITS));
            assertThrows(NullPointerException.class, () -> store.inspectReclamation(Set.of(), (LocalCheckpointStore.JournalAccess) null, LIMITS));
            assertThrows(NullPointerException.class, () -> store.inspectReclamation(Set.of(), fixture.journal, null));
            assertThrows(IllegalArgumentException.class, () -> new ReclamationInventory.Limits(-1, 0));
            assertThrows(IllegalArgumentException.class, () -> new ReclamationInventory.Limits(0, -1));
            assertThrows(IllegalArgumentException.class, () -> new ReclamationInventory.Limits(MAX_BYTES + 1, 0));
            assertThrows(IllegalArgumentException.class, () -> new ReclamationInventory.Limits(0, MAX_RECORDS + 1));
            assertTrue(fixture.namespace.events.isEmpty());
            assertEquals(ReclamationInventory.Status.VERIFIED, store.inspectReclamation(Set.of(), fixture.journal, LIMITS).status());
        }
    }

    private Fixture history() {
        var fixture = empty();
        var first = fixture.segment(List.of(RECORDS.getFirst()));
        var second = fixture.segment(List.of(RECORDS.get(1)));
        var secondThird = fixture.segment(RECORDS.subList(1, 3));
        fixture.add(manifest(1, 1, List.of(first)));
        fixture.add(manifest(2, 2, List.of(first, second)));
        fixture.add(manifest(3, 3, List.of(first, secondThird)));
        fixture.sharedFirst = LocalCheckpointStore.segmentName(first.id());
        fixture.oldSecond = LocalCheckpointStore.segmentName(second.id());
        fixture.unreferenced = LocalCheckpointStore.segmentName(fixture.segment(RECORDS).id());
        fixture.namespace.files.put("notes.txt", new byte[] {4, 2});
        fixture.namespace.files.put("unknown.segment", new byte[] {9});
        return fixture;
    }

    private Fixture empty() {
        var namespace = new MemoryNamespace(directory);
        namespace.files.put(LocalCheckpointStore.LOCK, new byte[0]);
        namespace.files.put(LocalReaderBarrier.NAME, new byte[0]);
        namespace.files.put(LocalCheckpointStore.JOURNAL, new byte[] {1});
        return new Fixture(namespace, new MemoryJournal(namespace));
    }

    private static JournalRecord record(long sequence) {
        return new JournalRecord(sequence, new Transaction(new RequestId("request-" + sequence),
                List.of(new WriteOperation.Put(new EntityId("account"), new ValidTimeInterval(null, null), sequence))));
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

    private static long verifiedBytes(Fixture fixture) {
        long bytes = 0;
        for (var root : fixture.roots) {
            bytes += fixture.namespace.files.get(name(root)).length;
            for (var reference : root.segments()) { bytes += reference.byteLength(); }
        }
        return bytes;
    }

    private static long bytesOf(MemoryNamespace namespace, Set<String> names) {
        return names.stream().mapToLong(entry -> namespace.files.get(entry).length).sum();
    }

    private static void assertObjects(List<ReclamationInventory.ObjectFile> actual, MemoryNamespace namespace, List<String> expected) {
        var ordered = expected.stream().sorted().toList();
        assertEquals(ordered, actual.stream().map(ReclamationInventory.ObjectFile::name).toList());
        for (var file : actual) { assertEquals(namespace.files.get(file.name()).length, file.byteLength()); }
    }

    private static void assertNoMutations(Map<String, byte[]> expected, MemoryNamespace namespace) {
        assertEquals(expected.keySet(), namespace.files.keySet());
        expected.forEach((entry, bytes) -> assertArrayEquals(bytes, namespace.files.get(entry)));
        assertTrue(namespace.events.stream().noneMatch(event -> event.startsWith("write:") || event.startsWith("force:")
                || event.startsWith("move:") || event.equals("directory-force")));
    }

    private static void assertAliasChecksPrecedeReads(MemoryNamespace namespace) {
        for (var read : namespace.reads) {
            int check = namespace.events.indexOf("alias:" + read.name());
            assertTrue(check >= 0 && check < namespace.events.indexOf("read:" + read.name()),
                    "Object identity is checked before opening " + read.name());
        }
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

    private static final class Fixture {
        final MemoryNamespace namespace;
        final MemoryJournal journal;
        final List<CheckpointManifest> roots = new ArrayList<>();
        String sharedFirst;
        String oldSecond;
        String unreferenced;
        Fixture(MemoryNamespace namespace, MemoryJournal journal) { this.namespace = namespace; this.journal = journal; }
        void add(CheckpointManifest manifest) { roots.add(manifest); namespace.files.put(name(manifest), ManifestCodec.encode(manifest)); }
        SegmentReference segment(List<JournalRecord> records) {
            var segment = new ImmutableSegment(DATABASE, records);
            var image = SegmentCodec.encode(segment);
            var decoded = SegmentCodec.decode(image);
            namespace.files.put(LocalCheckpointStore.segmentName(decoded.id()), image);
            return new SegmentReference(decoded.id(), image.length, segment.firstSequence(), segment.lastSequence());
        }
    }

    private static final class MemoryJournal implements LocalCheckpointStore.JournalAccess {
        final MemoryNamespace namespace;
        final List<Long> prefixCalls = new ArrayList<>();
        DatabaseId database = DATABASE;
        long badPrefixAt = -1;
        String phase = "";
        MemoryJournal(MemoryNamespace namespace) { this.namespace = namespace; }
        @Override public DatabaseId databaseId() { return database; }
        @Override public LocalJournal.Prefix prefix(long sequence) throws IOException {
            if (phase.equals("prefix-before")) { throw new IOException("Prefix failed before observation"); }
            namespace.events.add("prefix:" + sequence);
            prefixCalls.add(sequence);
            var actual = LocalReclamationInventoryTest.prefix(sequence);
            if (phase.equals("prefix-after")) { throw new IOException("Prefix failed after observation"); }
            return sequence == badPrefixAt ? new LocalJournal.Prefix(sequence, actual.byteLength(), "0".repeat(64)) : actual;
        }
        @Override public void force() { namespace.events.add("journal-force"); throw new AssertionError("Inventory must not force the journal"); }
    }

    private record ReadCall(String name, int maxBytes) { }

    private static final class MemoryNamespace implements LocalCheckpointStore.Namespace {
        final Path directory;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<String> extraEntries = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<ReadCall> reads = new ArrayList<>();
        final List<String> aliasChecks = new ArrayList<>();
        final MemoryProtection barrier = new MemoryProtection();
        boolean deferred;
        boolean reverseEntries;
        String phase = "";
        Throwable readFailure;
        IOException closeFailure;
        Runnable aliasHook;
        int closes;
        MemoryNamespace(Path directory) { this.directory = directory; }
        @Override public Path directory() { return directory; }
        @Override public LocalReaderBarrier.Protection acquireReader() { throw new AssertionError("Inventory acquires exclusive protection"); }
        @Override public Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() throws IOException {
            events.add("barrier-acquire");
            if (phase.equals("barrier")) { throw new IOException("Barrier acquisition failed"); }
            return deferred ? Optional.empty() : Optional.of(barrier);
        }
        @Override public List<String> entries() throws IOException {
            events.add("entries");
            if (phase.equals("entries")) { throw new IOException("Directory enumeration failed"); }
            var names = new ArrayList<>(files.keySet());
            names.addAll(extraEntries);
            return reverseEntries ? names.reversed() : names;
        }
        @Override public void checkObjectAliases(String name, List<String> entries) throws IOException {
            if (phase.equals("alias-before")) { throw new IOException("Alias check failed before observation"); }
            events.add("alias:" + name);
            aliasChecks.add(name);
            assertTrue(entries.contains(name));
            if (aliasHook != null) { aliasHook.run(); }
            if (phase.equals("alias-after")) { throw new IOException("Alias check failed after observation"); }
        }
        @Override public byte[] read(String name, int maxBytes) throws IOException {
            if (phase.equals("read-before")) { throw new IOException("Read failed before observation"); }
            events.add("read:" + name);
            reads.add(new ReadCall(name, maxBytes));
            if (readFailure != null) { throwFailure(readFailure); }
            var image = files.get(name);
            if (image == null || image.length > maxBytes) { throw new IOException("Missing or oversized object: " + name); }
            var result = image.clone();
            if (phase.equals("read-after")) { throw new IOException("Read failed after observation"); }
            return result;
        }
        @Override public void writeNew(String name, byte[] image) { events.add("write:" + name); throw new AssertionError("Inventory must not write"); }
        @Override public void forceFile(String name) { events.add("force:" + name); throw new AssertionError("Inventory must not force files"); }
        @Override public void atomicMove(String from, String to) { events.add("move:" + from); throw new AssertionError("Inventory must not rename"); }
        @Override public void forceDirectory() { events.add("directory-force"); throw new AssertionError("Inventory must not force directories"); }
        @Override public void close() throws IOException {
            events.add("namespace-close");
            closes++;
            if (closeFailure != null) { throw closeFailure; }
        }
        void fillTo(int size) { while (files.size() + extraEntries.size() < size) { extraEntries.add("unknown-" + extraEntries.size()); } }
        Map<String, byte[]> snapshot() {
            var snapshot = new LinkedHashMap<String, byte[]>();
            files.forEach((name, image) -> snapshot.put(name, image.clone()));
            return snapshot;
        }
    }

    private static final class MemoryProtection implements LocalReaderBarrier.Protection {
        Throwable closeFailure;
        IOException checkFailure;
        int checkFailureAt;
        int checks;
        int closes;
        @Override public void checkValid() throws IOException {
            checks++;
            if (checks == checkFailureAt) { throw checkFailure; }
        }
        @Override public void close() throws IOException {
            closes++;
            if (closeFailure != null) { throwFailure(closeFailure); }
        }
    }
}
