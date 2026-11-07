// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.internal.LocalIo;
import org.meridiandb.storage.internal.LocalIoObservation;
import org.meridiandb.storage.internal.LocalIoObservation.Metric;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Actual local acquisition; expected work comes from independent literal history/image lengths. */
@Timeout(120)
class LocalCheckpointHistoryIntegrationTest {
    private static final DatabaseId DATABASE = new DatabaseId("workload-v1");
    private static final EntityId ENTITY = new EntityId("e-0");
    private static final String HISTORY_HASH = "cdbb2a26ffde6b13937bf196fa3fe77630c6b65922696e67073d37050be7a211";
    private static final List<JournalRecord> HISTORY = List.of(
            put(1, Long.MIN_VALUE, 0, 2), put(2, -2131460139842329618L, 1, 2),
            put(3, 8463413780889623691L, 0, 1), put(4, -8675119527219200843L, 1, 2));
    private static final LocalCheckpointHistory.Limits EXACT = new LocalCheckpointHistory.Limits(822, 354, 8);

    @TempDir
    Path directory;

    @Test
    void literalGraphIsFetchedOnceAndUsageIncludesMetadataActualPrefixAndBothRecordPasses() throws IOException {
        var fixture = fixture(directory);
        try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
            var observed = observe(() -> store.loadLatestHistory(journal, EXACT).orElseThrow());
            var snapshot = observed.value();
            assertEquals(fixture.id(), snapshot.manifestId());
            assertEquals(fixture.manifest(), snapshot.manifest());
            assertEquals(new LocalJournal.Prefix(4, 354, HISTORY_HASH), snapshot.actualPrefix());
            assertEquals(new LocalCheckpointHistory.Usage(822, 354, 8, 3), snapshot.usage());
            assertEquals(HISTORY, snapshot.records());
            assertReadOnly(observed.counters(), 266, 556, 354);
            assertEquals(1, observed.counters().value(LocalIo.Role.MANIFEST, Metric.READ_EOF));
            assertEquals(2, observed.counters().value(LocalIo.Role.SEGMENT, Metric.READ_EOF));
            assertFalse(Files.exists(directory.resolve(LocalReaderBarrier.NAME)));
        }
    }

    @Test
    void absentSelectionAndAnEmptyGenesisHaveDistinctResultsWithoutCreatingReaderProtection() throws IOException {
        try (var store = openStore(directory); var journal = LocalJournal.create(store.journalPath(), DATABASE)) {
            var absent = observe(() -> store.loadLatestHistory(journal, new LocalCheckpointHistory.Limits(0, 0, 0)));
            assertEquals(Optional.empty(), absent.value());
            assertReadOnly(absent.counters(), 0, 0, 0);
            var actual = journal.prefix(0);
            var genesis = new CheckpointManifest(DATABASE, 1,
                    new CheckpointManifest.JournalPrefix(0, 42, actual.sha256()), List.of());
            var id = store.publish(genesis, journal);
            var loaded = observe(() -> store.loadLatestHistory(journal,
                    new LocalCheckpointHistory.Limits(154, 42, 0)).orElseThrow());
            assertEquals(id, loaded.value().manifestId());
            assertEquals(List.of(), loaded.value().records());
            assertEquals(new LocalCheckpointHistory.Usage(154, 42, 0, 1), loaded.value().usage());
            assertReadOnly(loaded.counters(), 154, 0, 42);
            assertFalse(Files.exists(directory.resolve(LocalReaderBarrier.NAME)));
        }
    }

    @Test
    void eachOneBelowBudgetRejectsBeforeSegmentOrJournalReadsAndPoisonsOnlyTheStore() throws IOException {
        var limits = List.of(new LocalCheckpointHistory.Limits(265, 354, 8),
                new LocalCheckpointHistory.Limits(821, 354, 8),
                new LocalCheckpointHistory.Limits(822, 353, 8),
                new LocalCheckpointHistory.Limits(822, 354, 7));
        for (int index = 0; index < limits.size(); index++) {
            Path home = Files.createDirectory(directory.resolve("budget-" + index));
            fixture(home);
            try (var store = openStore(home); var journal = LocalJournal.open(store.journalPath())) {
                var limit = limits.get(index);
                var rejected = observe(() -> assertThrows(IOException.class, () -> store.loadLatestHistory(journal, limit)));
                assertReadOnly(rejected.counters(), index == 0 ? 0 : 266, 0, 0);
                assertPoisonedStoreAndUsableJournal(store, journal, home);
            }
        }
    }

    @Test
    void anOversizedActualSegmentIsRefusedBeforeItsBodyAndActualPrefixAreRead() throws IOException {
        var fixture = fixture(directory);
        Path object = directory.resolve(LocalCheckpointStore.segmentName(fixture.segments().getFirst()));
        byte[] original = Files.readAllBytes(object);
        Files.write(object, new byte[] {1}, StandardOpenOption.APPEND);
        try {
            try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
                var rejected = observe(() -> assertThrows(IOException.class,
                        () -> store.loadLatestHistory(journal, EXACT)));
                assertReadOnly(rejected.counters(), 266, 0, 0);
                assertPoisonedStoreAndUsableJournal(store, journal, directory);
            }
        } finally { Files.write(object, original); }
    }

    @Test
    void aLateCorruptDependencyExposesNoHistoryAndNeverStartsTheActualPrefixPass() throws IOException {
        var fixture = fixture(directory);
        Path object = directory.resolve(LocalCheckpointStore.segmentName(fixture.segments().getLast()));
        byte[] original = Files.readAllBytes(object);
        byte[] corrupt = original.clone();
        corrupt[277] ^= 1;
        Files.write(object, corrupt);
        try {
            try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
                var rejected = observe(() -> assertThrows(SegmentFormatException.class,
                        () -> store.loadLatestHistory(journal, EXACT)));
                // The first valid object cannot escape while the later object fails verification.
                assertReadOnly(rejected.counters(), 266, 556, 0);
                assertPoisonedStoreAndUsableJournal(store, journal, directory);
            }
        } finally { Files.write(object, original); }
    }

    @Test
    void actualJournalMismatchFailsAfterCompleteGraphVerificationAndRetainsSeparateJournalOwnership() throws IOException {
        fixture(directory);
        var changed = new ArrayList<>(HISTORY);
        changed.set(3, put(4, 7, 1, 2));
        // All native owners are closed while replacing this test-owned journal with valid divergent history.
        Files.write(directory.resolve(LocalCheckpointStore.JOURNAL), journalImage(changed));
        try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
            var rejected = observe(() -> assertThrows(IOException.class, () -> store.loadLatestHistory(journal, EXACT)));
            assertReadOnly(rejected.counters(), 266, 556, 354);
            assertThrows(IOException.class, store::journalPath);
            assertNotEquals(HISTORY_HASH, journal.prefix(4).sha256());
            assertEquals(4, journal.force());
            assertThrows(IOException.class, () -> {
                try (var duplicate = LocalJournal.open(directory.resolve(LocalCheckpointStore.JOURNAL))) { duplicate.prefix(4); }
            });
        }
    }

    @Test
    void aSegmentAliasingTheOwnedJournalIsRejectedBeforeOpeningAnotherHandle() throws IOException {
        var fixture = fixture(directory);
        Path object = directory.resolve(LocalCheckpointStore.segmentName(fixture.segments().getFirst()));
        byte[] original = Files.readAllBytes(object);
        try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
            Files.delete(object);
            try {
                createHardLink(object, directory.resolve(LocalCheckpointStore.JOURNAL));
                var rejected = observe(() -> assertThrows(IOException.class,
                        () -> store.loadLatestHistory(journal, EXACT)));
                assertReadOnly(rejected.counters(), 266, 0, 0);
                assertPoisonedStoreAndUsableJournal(store, journal, directory);
            } finally {
                Files.deleteIfExists(object);
                Files.write(object, original);
            }
        }
    }

    @Test
    void immutableHeapHistorySurvivesOwnerCloseAndActualProtectedReclamationWithoutPinningOldFiles() throws IOException {
        var fixture = fixture(directory);
        LocalCheckpointHistory snapshot;
        try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
            snapshot = store.loadLatestHistory(journal, EXACT).orElseThrow();
            assertFalse(Files.exists(directory.resolve(LocalReaderBarrier.NAME)));
        }
        assertEquals(HISTORY, snapshot.records());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.records().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.manifest().segments().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.records().getFirst().transaction().operations().clear());
        var images = Map.of(fixture.segments().getFirst(), SegmentCodec.encode(new ImmutableSegment(DATABASE, HISTORY.subList(0, 2))),
                fixture.segments().getLast(), SegmentCodec.encode(new ImmutableSegment(DATABASE, HISTORY.subList(2, 4))));
        var plan = HistoryCompactor.compact(fixture.manifest(), images::get, HistoryCompactor.Limits.DEFAULT);
        try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
            store.publishCompaction(plan, journal);
            var reclaimed = store.reclaim(Set.of(), journal);
            assertEquals(ReclamationResult.Status.COMPLETED, reclaimed.status());
            assertEquals(1, reclaimed.removedManifests().size());
            assertEquals(2, reclaimed.removedSegments().size());
            assertFalse(Files.exists(directory.resolve(LocalCheckpointStore.generationName(1, fixture.id()))));
            for (var id : fixture.segments()) { assertFalse(Files.exists(directory.resolve(LocalCheckpointStore.segmentName(id)))); }
            assertEquals(fixture.id(), snapshot.manifestId());
            assertEquals(HISTORY, snapshot.records());
            var current = store.loadLatestHistory(journal, LocalCheckpointHistory.Limits.DEFAULT).orElseThrow();
            assertEquals(2, current.manifest().generation());
            assertEquals(HISTORY, current.records());
        }
        assertEquals(new LocalCheckpointHistory.Usage(822, 354, 8, 3), snapshot.usage());
        assertEquals(HISTORY_HASH, snapshot.actualPrefix().sha256());
    }

    @Test
    void freshCallsObserveNewSelectionAndAnUnselectedJournalTailNeverWidensTheDeclaredPrefix() throws IOException {
        var fixture = fixture(directory);
        try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
            var first = store.loadLatestHistory(journal, EXACT).orElseThrow();
            var all = new ArrayList<>(HISTORY);
            all.add(put(5, 7, 0, 2));
            journal.append(all.getLast());
            var stillSelected = observe(() -> store.loadLatestHistory(journal, EXACT).orElseThrow());
            assertEquals(fixture.id(), stillSelected.value().manifestId());
            assertEquals(HISTORY, stillSelected.value().records());
            assertReadOnly(stillSelected.counters(), 266, 556, 354);
            var id = store.writeSegment(new ImmutableSegment(DATABASE, all));
            var prefix = journal.prefix(5);
            var next = new CheckpointManifest(DATABASE, 2,
                    new CheckpointManifest.JournalPrefix(5, 432, prefix.sha256()),
                    List.of(new CheckpointManifest.SegmentReference(id, 512, 1, 5)));
            var manifestId = store.publish(next, journal);
            var latest = store.loadLatestHistory(journal, new LocalCheckpointHistory.Limits(722, 432, 10)).orElseThrow();
            assertEquals(manifestId, latest.manifestId());
            assertNotEquals(first.manifestId(), latest.manifestId());
            assertEquals(all, latest.records());
            assertEquals(new LocalCheckpointHistory.Usage(722, 432, 10, 2), latest.usage());
            assertEquals(HISTORY, first.records());
        }
    }

    @Test
    void legacyLazyReadersStillReadAgainAndRevalidateChangedBytesWhileHeapHistoryRemainsStable() throws IOException {
        var fixture = fixture(directory);
        try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
            var legacyWork = observe(() -> {
                try (var reader = store.readLatest(journal).orElseThrow()) {
                    for (int index = 0; index < 2; index++) {
                        try (var child = reader.openSegment(index)) { assertEquals(HISTORY.subList(index * 2, index * 2 + 2), child.segment().records()); }
                    }
                }
                return null;
            });
            assertEquals(266, legacyWork.counters().value(LocalIo.Role.MANIFEST, Metric.READ_BYTES));
            assertEquals(1112, legacyWork.counters().value(LocalIo.Role.SEGMENT, Metric.READ_BYTES));
            assertEquals(354, legacyWork.counters().value(LocalIo.Role.JOURNAL, Metric.READ_BYTES));
            var heap = observe(() -> store.loadLatestHistory(journal, EXACT).orElseThrow());
            assertReadOnly(heap.counters(), 266, 556, 354);
            try (var legacy = store.readLatest(journal).orElseThrow()) {
                Path object = directory.resolve(LocalCheckpointStore.segmentName(fixture.segments().getFirst()));
                byte[] original = Files.readAllBytes(object);
                byte[] corrupt = original.clone();
                corrupt[277] ^= 1;
                Files.write(object, corrupt);
                try {
                    assertThrows(SegmentFormatException.class, () -> legacy.openSegment(0));
                    assertEquals(HISTORY, heap.value().records());
                } finally { Files.write(object, original); }
                try (var child = legacy.openSegment(0)) { assertEquals(HISTORY.subList(0, 2), child.segment().records()); }
            }
        }
    }

    @Test
    void exactOriginalRecordsRetainNewNoOpsRetractionsAndRetryPositions() throws IOException {
        var window = new ValidTimeInterval(0L, 2L);
        var initial = new Transaction(new RequestId("initial"), List.of(new WriteOperation.Put(ENTITY, window, 7)));
        var noOp = new Transaction(new RequestId("same-value"), List.of(new WriteOperation.Put(ENTITY, window, 7)));
        var retract = new Transaction(new RequestId("remove"), List.of(new WriteOperation.Retract(ENTITY, window)));
        var absent = new Transaction(new RequestId("already-absent"), List.of(new WriteOperation.Retract(ENTITY, window)));
        var originals = List.of(new JournalRecord(1, initial), new JournalRecord(2, noOp),
                new JournalRecord(3, retract), new JournalRecord(4, absent));
        try (var database = createDatabase(directory)) {
            for (var record : originals) {
                var committed = database.transact(record.transaction());
                assertFalse(committed.replayed());
                assertEquals(record.sequence(), committed.position().sequence());
            }
            assertTrue(database.transact(initial).replayed());
            assertTrue(database.transact(absent).replayed());
            assertEquals(new CommitPosition(DATABASE, 4), database.head());
        }
        try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
            var segment = new ImmutableSegment(DATABASE, originals);
            var segmentId = store.writeSegment(segment);
            var prefix = journal.prefix(4);
            var manifest = new CheckpointManifest(DATABASE, 2,
                    new CheckpointManifest.JournalPrefix(4, prefix.byteLength(), prefix.sha256()),
                    List.of(new CheckpointManifest.SegmentReference(segmentId, SegmentCodec.encodedBytes(segment), 1, 4)));
            store.publish(manifest, journal);
            var history = store.loadLatestHistory(journal, LocalCheckpointHistory.Limits.DEFAULT).orElseThrow();
            assertEquals(originals, history.records());
            assertEquals(4, history.records().size());
            assertEquals(8, history.usage().recordWork());
        }
        try (var recovered = LocalDatabase.openWritable(directory)) {
            for (var record : originals) {
                assertEquals(Optional.of(new CommitPosition(DATABASE, record.sequence())),
                        recovered.lookupRequest(record.transaction().requestId()));
            }
        }
    }

    private Fixture fixture(Path home) throws IOException {
        try (var store = openStore(home); var journal = LocalJournal.create(store.journalPath(), DATABASE)) {
            for (var record : HISTORY) { journal.append(record); }
            var first = store.writeSegment(new ImmutableSegment(DATABASE, HISTORY.subList(0, 2)));
            var second = store.writeSegment(new ImmutableSegment(DATABASE, HISTORY.subList(2, 4)));
            var manifest = new CheckpointManifest(DATABASE, 1,
                    new CheckpointManifest.JournalPrefix(4, 354, HISTORY_HASH), List.of(
                    new CheckpointManifest.SegmentReference(first, 278, 1, 2),
                    new CheckpointManifest.SegmentReference(second, 278, 3, 4)));
            var id = store.publish(manifest, journal);
            return new Fixture(manifest, id, List.of(first, second));
        }
    }

    private LocalCheckpointStore openStore(Path home) throws IOException {
        try { return LocalCheckpointStore.open(home); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            if (System.getProperty("os.name").startsWith("Windows")) {
                assumeTrue(false, "Windows directory force unavailable: " + unsupported);
            }
            throw unsupported;
        }
    }

    private LocalDatabase createDatabase(Path home) throws IOException {
        try { return LocalDatabase.create(home, DATABASE); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            if (System.getProperty("os.name").startsWith("Windows")) {
                assumeTrue(false, "Windows directory force unavailable: " + unsupported);
            }
            throw unsupported;
        }
    }

    private static void createHardLink(Path alias, Path target) throws IOException {
        try { Files.createLink(alias, target); }
        catch (UnsupportedOperationException unsupported) {
            assumeTrue(false, "Hard links unsupported: " + unsupported);
        } catch (FileSystemException failure) {
            String reason = failure.getReason();
            if (reason != null && reason.toLowerCase(Locale.ROOT).contains("not supported")) {
                assumeTrue(false, "Hard links unsupported: " + failure);
            }
            throw failure;
        }
    }

    private static void assertPoisonedStoreAndUsableJournal(LocalCheckpointStore store, LocalJournal journal, Path home)
            throws IOException {
        assertThrows(IOException.class, store::journalPath);
        assertThrows(IOException.class, () -> store.loadLatestHistory(journal, EXACT));
        assertEquals(new LocalJournal.Prefix(4, 354, HISTORY_HASH), journal.prefix(4));
        assertEquals(4, journal.force());
        assertThrows(IOException.class, () -> {
            try (var duplicate = LocalJournal.open(home.resolve(LocalCheckpointStore.JOURNAL))) { duplicate.prefix(4); }
        });
    }

    private static <T> Observed<T> observe(IoCall<T> call) throws IOException {
        var capture = LocalIoObservation.open();
        T value;
        try (capture) { value = call.invoke(); }
        var snapshot = capture.snapshot();
        assertTrue(snapshot.complete());
        return new Observed<>(value, snapshot);
    }

    private static void assertReadOnly(LocalIoObservation.Snapshot snapshot, long manifests, long segments, long journal) {
        assertEquals(manifests, snapshot.value(LocalIo.Role.MANIFEST, Metric.READ_BYTES));
        assertEquals(segments, snapshot.value(LocalIo.Role.SEGMENT, Metric.READ_BYTES));
        assertEquals(journal, snapshot.value(LocalIo.Role.JOURNAL, Metric.READ_BYTES));
        assertEquals(manifests + segments + journal, snapshot.sum(Metric.READ_BYTES));
        assertEquals(0, snapshot.sum(Metric.WRITE_ATTEMPTS));
        assertEquals(0, snapshot.sum(Metric.WRITE_BYTES));
        assertEquals(0, snapshot.sum(Metric.FORCE_ATTEMPTS));
        assertEquals(0, snapshot.sum(Metric.FORCE_SUCCESSES));
    }

    private static JournalRecord put(long sequence, long value, long from, long to) {
        return new JournalRecord(sequence, new Transaction(new RequestId("r-" + sequence),
                List.of(new WriteOperation.Put(ENTITY, new ValidTimeInterval(from, to), value))));
    }

    private static byte[] journalImage(List<JournalRecord> records) throws IOException {
        var bytes = new ByteArrayOutputStream();
        bytes.write(JournalCodec.encodeHeader(DATABASE));
        for (var record : records) { bytes.write(JournalCodec.encodeRecord(record)); }
        return bytes.toByteArray();
    }

    private record Fixture(CheckpointManifest manifest, ManifestId id, List<SegmentId> segments) { }
    private record Observed<T>(T value, LocalIoObservation.Snapshot counters) { }
    @FunctionalInterface private interface IoCall<T> { T invoke() throws IOException; }
}
