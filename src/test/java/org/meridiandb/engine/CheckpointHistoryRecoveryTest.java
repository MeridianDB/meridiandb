// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.internal.LocalIo;
import org.meridiandb.storage.internal.LocalIoObservation;
import org.meridiandb.storage.internal.LocalIoObservation.Metric;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointHistory;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestFormatException;
import org.meridiandb.storage.manifest.ManifestId;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Record-container release and explicit recovery conformance; no GC/timing inference. */
@Timeout(120)
class CheckpointHistoryRecoveryTest {
    private static final DatabaseId DATABASE = new DatabaseId("workload-v1");
    private static final EntityId ENTITY = new EntityId("e-0");
    private static final String HISTORY_HASH = "cdbb2a26ffde6b13937bf196fa3fe77630c6b65922696e67073d37050be7a211";
    private static final List<JournalRecord> HISTORY = List.of(
            put(1, Long.MIN_VALUE, 0, 2), put(2, -2131460139842329618L, 1, 2),
            put(3, 8463413780889623691L, 0, 1), put(4, -8675119527219200843L, 1, 2));
    private static final LocalCheckpointHistory.Limits EXACT = new LocalCheckpointHistory.Limits(822, 354, 8);
    private static final LocalCheckpointHistory.Limits ZERO = new LocalCheckpointHistory.Limits(0, 0, 0);

    @TempDir
    Path directory;

    @Test
    void historySourceRequiresSelectedRecordsAndKeepsCheckpointAuthoritySeparateFromItsDelegate() {
        var delegate = new Source(HISTORY, 4);
        assertThrows(NullPointerException.class, () -> new LocalDatabase.HistorySource(null, OptionalLong.empty(), null));
        assertThrows(NullPointerException.class, () -> new LocalDatabase.HistorySource(delegate, null, null));
        assertThrows(IllegalArgumentException.class, () -> new LocalDatabase.HistorySource(delegate, OptionalLong.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LocalDatabase.HistorySource(delegate, OptionalLong.of(0), null));
        var source = new LocalDatabase.HistorySource(delegate, OptionalLong.of(2), HISTORY.subList(0, 2));
        assertEquals(OptionalLong.of(2), source.checkpointSequence());
        assertEquals(OptionalLong.empty(), delegate.checkpointSequence());
        assertTrue(source.retainsRecords());
        assertEquals(0, delegate.closes);
    }

    @Test
    void successfulWritableRecoveryReleasesRecordsBeforeJournalReplaySynchronizationAndReturn() throws IOException {
        var delegate = new Source(HISTORY, 4);
        var source = new LocalDatabase.HistorySource(delegate, OptionalLong.of(2), HISTORY.subList(0, 2));
        delegate.onJournal = () -> assertFalse(source.retainsRecords(), "Journal pass must not retain checkpoint container");
        delegate.onSynchronize = () -> assertFalse(source.retainsRecords(), "Writable startup must not retain checkpoint container");
        delegate.onClose = () -> assertFalse(source.retainsRecords());
        try (var database = LocalDatabase.recoverWritable(source)) {
            assertFalse(source.retainsRecords());
            assertEquals(List.of("journal", "synchronize"), delegate.events);
            assertHistory(database);
            assertTrue(database.transact(HISTORY.getFirst().transaction()).replayed());
            assertEquals(0, delegate.appends);
            assertEquals(0, delegate.forces);
            assertThrows(IllegalStateException.class, () -> source.replayCheckpoint(ignored -> { }));
        }
        assertEquals(1, delegate.closes);
        assertEquals(List.of("journal", "synchronize", "close"), delegate.events);
    }

    @Test
    void readOnlyRecoveryReleasesTheContainerButDoesNotRunWritableStartupOrAcceptTransactions() throws IOException {
        var delegate = new Source(HISTORY, 4);
        var source = new LocalDatabase.HistorySource(delegate, OptionalLong.of(2), HISTORY.subList(0, 2));
        delegate.onJournal = () -> assertFalse(source.retainsRecords());
        try (var database = LocalDatabase.recover(source)) {
            assertHistory(database);
            assertFalse(source.retainsRecords());
            assertEquals(List.of("journal"), delegate.events);
            assertThrows(IllegalStateException.class, () -> database.transact(HISTORY.getFirst().transaction()));
        }
        assertEquals(1, delegate.closes);
    }

    @Test
    void checkpointConsumerRuntimeAndErrorBothReleaseInFinallyAndCannotReplayAgain() throws IOException {
        for (var failure : List.<Throwable>of(new IllegalStateException("consumer"), new AssertionError("consumer"))) {
            var delegate = new Source(HISTORY, 4);
            var source = new LocalDatabase.HistorySource(delegate, OptionalLong.of(4), HISTORY);
            assertSame(failure, assertThrows(failure.getClass(), () -> source.replayCheckpoint(record -> {
                assertTrue(source.retainsRecords());
                throwUnchecked(failure);
            })));
            assertFalse(source.retainsRecords());
            assertThrows(IllegalStateException.class, () -> source.replayCheckpoint(ignored -> { }));
            delegate.onClose = () -> assertFalse(source.retainsRecords());
            source.close();
            assertEquals(1, delegate.closes);
        }
    }

    @Test
    void explicitCloseDropsUnreplayedRecordsBeforeEveryKindOfDelegateCloseFailure() {
        for (var failure : failures("close")) {
            var delegate = new Source(HISTORY, 4);
            var source = new LocalDatabase.HistorySource(delegate, OptionalLong.of(4), HISTORY);
            delegate.onClose = () -> assertFalse(source.retainsRecords(), "Release must precede delegate cleanup");
            delegate.closeFailure = failure;
            assertSame(failure, assertThrows(failure.getClass(), source::close));
            assertFalse(source.retainsRecords());
            assertEquals(1, delegate.closes);
        }
    }

    @Test
    void journalRuntimeErrorAndIoFailuresRetainPrimaryAndCleanupEvidenceAfterRelease() {
        for (var failure : failures("journal")) {
            var delegate = new Source(HISTORY, 4);
            var source = new LocalDatabase.HistorySource(delegate, OptionalLong.of(2), HISTORY.subList(0, 2));
            delegate.onJournal = () -> assertFalse(source.retainsRecords());
            delegate.onClose = () -> assertFalse(source.retainsRecords());
            delegate.journalFailure = failure;
            delegate.failJournalAfter = 2;
            var cleanup = new IOException("cleanup");
            delegate.closeFailure = cleanup;
            assertSame(failure, assertThrows(failure.getClass(), () -> LocalDatabase.recoverWritable(source)));
            assertFalse(source.retainsRecords());
            assertEquals(1, delegate.closes);
            assertEquals(List.of("journal", "close"), delegate.events);
            assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
        }
    }

    @Test
    void startupSynchronizationFailuresHappenAfterReleaseAndCloseEveryRecoveredOwner() {
        for (var failure : failures("synchronize")) {
            var delegate = new Source(HISTORY, 4);
            var source = new LocalDatabase.HistorySource(delegate, OptionalLong.of(4), HISTORY);
            delegate.onSynchronize = () -> assertFalse(source.retainsRecords());
            delegate.onClose = () -> assertFalse(source.retainsRecords());
            delegate.synchronizeFailure = failure;
            assertSame(failure, assertThrows(failure.getClass(), () -> LocalDatabase.recoverWritable(source)));
            assertFalse(source.retainsRecords());
            assertEquals(1, delegate.closes);
            assertEquals(List.of("journal", "synchronize", "close"), delegate.events);
        }
    }

    @Test
    void completeJournalStillRequiresItsCheckpointedPrefixAndExactFinalCardinality() {
        var cases = List.of(HISTORY.subList(1, 4), HISTORY.subList(0, 3),
                List.of(HISTORY.get(0), HISTORY.get(1), HISTORY.get(1), HISTORY.get(2), HISTORY.get(3)),
                List.of(HISTORY.get(0), HISTORY.get(1), HISTORY.get(2), HISTORY.get(3), put(5, 7, 0, 2)));
        for (var records : cases) {
            var delegate = new Source(records, 4);
            var source = new LocalDatabase.HistorySource(delegate, OptionalLong.of(2), HISTORY.subList(0, 2));
            delegate.onJournal = () -> assertFalse(source.retainsRecords());
            delegate.onClose = () -> assertFalse(source.retainsRecords());
            assertThrows(IOException.class, () -> LocalDatabase.recoverWritable(source));
            assertFalse(source.retainsRecords());
            assertEquals(List.of("journal", "close"), delegate.events);
        }
    }

    @Test
    void incompleteOutOfOrderOrDuplicateCheckpointHistoryFailsWithoutJournalFallback() {
        var duplicate = new JournalRecord(2, HISTORY.getFirst().transaction());
        var cases = List.of(List.of(HISTORY.getFirst()), List.of(HISTORY.get(1), HISTORY.getFirst()),
                List.of(HISTORY.getFirst(), HISTORY.get(2)), List.of(HISTORY.getFirst(), duplicate));
        for (var records : cases) {
            var delegate = new Source(HISTORY, 4);
            var source = new LocalDatabase.HistorySource(delegate, OptionalLong.of(2), records);
            delegate.onClose = () -> assertFalse(source.retainsRecords());
            assertThrows(IOException.class, () -> LocalDatabase.recoverWritable(source));
            assertFalse(source.retainsRecords());
            assertEquals(List.of("close"), delegate.events);
            assertEquals(1, delegate.closes);
        }
    }

    @Test
    void nullPathAndLimitsFailBeforeAnyNativeOrNamespaceEffects() throws IOException {
        assertThrows(NullPointerException.class, () -> LocalDatabase.openWithCheckpointHistory(null, EXACT));
        assertThrows(NullPointerException.class, () -> LocalDatabase.openWritableWithCheckpointHistory(null, EXACT));
        assertThrows(NullPointerException.class, () -> LocalDatabase.openWithCheckpointHistory(directory, null));
        assertThrows(NullPointerException.class, () -> LocalDatabase.openWritableWithCheckpointHistory(directory, null));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test
    void nativeWritableCandidatePreservesLiteralHistoryWhileRemovingOnlyTheDuplicateSegmentPassAndReaderAdmission() throws IOException {
        fixture(directory, 4);
        var candidate = observe(() -> LocalDatabase.openWritableWithCheckpointHistory(directory, EXACT));
        try (var database = candidate.value()) { assertHistory(database); }
        assertBytes(candidate.counters(), 1062, 266, 556, 1884);
        assertEquals(1, candidate.counters().value(LocalIo.Role.JOURNAL, Metric.FORCE_SUCCESSES));
        assertEquals(2, candidate.counters().value(LocalIo.Role.DIRECTORY, Metric.FORCE_SUCCESSES));
        assertEquals(0, candidate.counters().value(LocalIo.Role.CONTROL, Metric.FORCE_SUCCESSES));
        assertEquals(3, candidate.counters().sum(Metric.FORCE_SUCCESSES));
        assertFalse(Files.exists(directory.resolve(".meridiandb-readers.lock")));

        var ordinary = observe(() -> LocalDatabase.openWritable(directory));
        try (var database = ordinary.value()) { assertHistory(database); }
        assertBytes(ordinary.counters(), 1062, 266, 1112, 2440);
        assertEquals(5, ordinary.counters().sum(Metric.FORCE_SUCCESSES));
        assertEquals(1, ordinary.counters().value(LocalIo.Role.CONTROL, Metric.FORCE_SUCCESSES));
        assertEquals(0, candidate.counters().sum(Metric.WRITE_BYTES));
        assertEquals(0, ordinary.counters().sum(Metric.WRITE_BYTES));
    }

    @Test
    void nativeReadOnlyCandidateHasNoWritableStartupAndRetainsExclusiveOwnersUntilClose() throws IOException {
        fixture(directory, 4);
        var opened = observe(() -> LocalDatabase.openWithCheckpointHistory(directory, EXACT));
        try (var database = opened.value()) {
            assertHistory(database);
            assertFalse(Files.exists(directory.resolve(".meridiandb-readers.lock")));
            var heapReads = observe(() -> { assertHistory(database); return null; });
            for (var metric : Metric.values()) { assertEquals(0, heapReads.counters().sum(metric)); }
            assertThrows(IllegalStateException.class, () -> database.transact(HISTORY.getFirst().transaction()));
            assertThrows(IOException.class, () -> {
                try (var unexpected = LocalCheckpointStore.open(directory)) { unexpected.journalPath(); }
            });
            assertThrows(IOException.class, () -> {
                try (var unexpected = LocalJournal.open(directory.resolve("transactions.journal"))) { unexpected.prefix(4); }
            });
        }
        assertBytes(opened.counters(), 1062, 266, 556, 1884);
        assertEquals(1, opened.counters().sum(Metric.FORCE_SUCCESSES));
        assertEquals(1, opened.counters().value(LocalIo.Role.DIRECTORY, Metric.FORCE_SUCCESSES));
        assertEquals(0, opened.counters().value(LocalIo.Role.JOURNAL, Metric.FORCE_SUCCESSES));
        assertOwnershipReleased(directory);
    }

    @Test
    void zeroAcquisitionLimitsAllowJournalOnlyButRejectEvenAnExplicitGenesisWithoutFallback() throws IOException {
        fixture(directory, -1);
        try (var database = LocalDatabase.openWritableWithCheckpointHistory(directory, ZERO)) {
            assertEquals(Optional.empty(), database.checkpointPosition());
            assertHistory(database);
        }
        try (var store = openStore(directory); var journal = LocalJournal.open(store.journalPath())) {
            var prefix = journal.prefix(0);
            store.publish(new CheckpointManifest(DATABASE, 1,
                    new CheckpointManifest.JournalPrefix(0, 42, prefix.sha256()), List.of()), journal);
        }
        assertThrows(IOException.class, () -> LocalDatabase.openWritableWithCheckpointHistory(directory, ZERO));
        assertOwnershipReleased(directory);
        try (var database = LocalDatabase.openWritableWithCheckpointHistory(directory,
                new LocalCheckpointHistory.Limits(154, 42, 0))) {
            assertEquals(Optional.of(new CommitPosition(DATABASE, 0)), database.checkpointPosition());
            assertHistory(database);
        }
    }

    @Test
    void checkpointTailRecoveryKeepsAllHistoricalRetriesAndForcedNewWritesOutsideTheAcquisitionBound() throws IOException {
        fixture(directory, 2);
        var prefixOnly = new LocalCheckpointHistory.Limits(488, 198, 4);
        try (var database = LocalDatabase.openWritableWithCheckpointHistory(directory, prefixOnly)) {
            assertHistory(database);
            assertEquals(Optional.of(new CommitPosition(DATABASE, 2)), database.checkpointPosition());
            assertTrue(database.transact(HISTORY.get(1).transaction()).replayed());
            var committed = database.transact(put(5, 7, 0, 2).transaction());
            assertFalse(committed.replayed());
            assertEquals(new CommitPosition(DATABASE, 5), committed.position());
        }
        try (var database = LocalDatabase.openWritableWithCheckpointHistory(directory, prefixOnly)) {
            assertEquals(new CommitPosition(DATABASE, 5), database.head());
            assertEquals(Optional.of(new CommitPosition(DATABASE, 2)), database.checkpointPosition());
            assertTrue(database.transact(put(5, 7, 0, 2).transaction()).replayed());
            assertEquals(Optional.of(new CommitPosition(DATABASE, 4)), database.lookupRequest(new RequestId("r-4")));
        }
    }

    @Test
    void acquisitionBudgetFailureHasNoFallbackReleasesOwnersAndAllowsFreshExplicitReopen() throws IOException {
        fixture(directory, 4);
        assertThrows(IOException.class, () -> LocalDatabase.openWritableWithCheckpointHistory(directory,
                new LocalCheckpointHistory.Limits(821, 354, 8)));
        assertOwnershipReleased(directory);
        try (var database = LocalDatabase.openWritableWithCheckpointHistory(directory, EXACT)) { assertHistory(database); }
    }

    @Test
    void recoveredAndSynchronizedBoundaryFailuresReleaseActualNativeOwnersForIoRuntimeAndError() throws IOException {
        for (var boundary : List.of(LocalDatabase.Boundary.RECOVERED, LocalDatabase.Boundary.SYNCHRONIZED)) {
            for (var failure : failures(boundary.name())) {
                Path home = Files.createDirectory(directory.resolve(boundary + "-" + failure.getClass().getSimpleName()));
                fixture(home, 4);
                assertSame(failure, assertThrows(failure.getClass(), () -> LocalDatabase.openWithCheckpointHistory(home, EXACT,
                        true, reached -> { if (reached == boundary) { throwProblem(failure); } })));
                assertOwnershipReleased(home);
                try (var database = LocalDatabase.openWithCheckpointHistory(home, EXACT)) { assertHistory(database); }
            }
        }
    }

    @Test
    void corruptNewestMetadataAndDivergentActualPrefixFailClosedWithCleanupAndNoOlderFallback() throws IOException {
        Path newest = Files.createDirectory(directory.resolve("newest"));
        var fixture = fixture(newest, 4);
        ManifestId latest;
        try (var store = openStore(newest); var journal = LocalJournal.open(store.journalPath())) {
            latest = store.publish(new CheckpointManifest(DATABASE, 2, fixture.manifest().journalPrefix(),
                    fixture.manifest().segments()), journal);
        }
        Path selected = newest.resolve(manifestName(2, latest));
        byte[] original = Files.readAllBytes(selected);
        byte[] corrupt = original.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(selected, corrupt);
        try {
            assertThrows(ManifestFormatException.class, () -> LocalDatabase.openWithCheckpointHistory(newest, EXACT));
            assertOwnershipReleased(newest);
        } finally { Files.write(selected, original); }
        try (var database = LocalDatabase.openWithCheckpointHistory(newest, EXACT)) { assertHistory(database); }

        Path mismatch = Files.createDirectory(directory.resolve("prefix"));
        fixture(mismatch, 4);
        Path journalPath = mismatch.resolve("transactions.journal");
        byte[] originalJournal = Files.readAllBytes(journalPath);
        var changed = new ArrayList<>(HISTORY);
        changed.set(3, put(4, 7, 1, 2));
        Files.write(journalPath, image(changed));
        try {
            assertThrows(IOException.class, () -> LocalDatabase.openWritableWithCheckpointHistory(mismatch, EXACT));
            assertOwnershipReleased(mismatch);
        } finally { Files.write(journalPath, originalJournal); }
        try (var database = LocalDatabase.openWithCheckpointHistory(mismatch, EXACT)) { assertHistory(database); }
    }

    private Fixture fixture(Path home, int cutoff) throws IOException {
        try (var store = openStore(home); var journal = LocalJournal.create(store.journalPath(), DATABASE)) {
            int through = Math.max(0, cutoff);
            for (var record : HISTORY) { if (record.sequence() <= through) { journal.append(record); } }
            CheckpointManifest manifest = null;
            if (cutoff >= 0) {
                var references = new ArrayList<CheckpointManifest.SegmentReference>();
                for (int first = 0; first < through; first += 2) {
                    var segment = new ImmutableSegment(DATABASE, HISTORY.subList(first, first + 2));
                    var id = store.writeSegment(segment);
                    references.add(new CheckpointManifest.SegmentReference(id, 278, first + 1L, first + 2L));
                }
                String hash = cutoff == 4 ? HISTORY_HASH : journal.prefix(cutoff).sha256();
                manifest = new CheckpointManifest(DATABASE, 1,
                        new CheckpointManifest.JournalPrefix(cutoff, 42L + cutoff * 78L, hash), references);
                store.publish(manifest, journal);
            }
            for (var record : HISTORY) { if (record.sequence() > through) { journal.append(record); } }
            journal.force();
            return new Fixture(manifest);
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

    private void assertOwnershipReleased(Path home) throws IOException {
        try (var store = openStore(home); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(DATABASE, journal.databaseId());
        }
    }

    private static void assertHistory(LocalDatabase database) {
        assertEquals(new CommitPosition(DATABASE, 4), database.head());
        for (var record : HISTORY) {
            assertEquals(Optional.of(new CommitPosition(DATABASE, record.sequence())),
                    database.lookupRequest(record.transaction().requestId()));
        }
        Long[] left = {null, Long.MIN_VALUE, Long.MIN_VALUE, 8463413780889623691L, 8463413780889623691L};
        Long[] right = {null, Long.MIN_VALUE, -2131460139842329618L, -2131460139842329618L, -8675119527219200843L};
        for (int cutoff = 0; cutoff <= 4; cutoff++) {
            try (var view = database.openView(new ReadCutoff.At(new CommitPosition(DATABASE, cutoff)))) {
                assertEquals(point(left[cutoff]), view.query(new TemporalQuery.Get(ENTITY, 0)));
                assertEquals(point(right[cutoff]), view.query(new TemporalQuery.Get(ENTITY, 1)));
                assertEquals(point(null), view.query(new TemporalQuery.Get(ENTITY, 2)));
            }
        }
    }

    private static TemporalResult.Point point(Long value) {
        return new TemporalResult.Point(value == null ? OptionalLong.empty() : OptionalLong.of(value));
    }

    private static <T> Observed<T> observe(IoCall<T> call) throws IOException {
        var capture = LocalIoObservation.open();
        T value;
        try (capture) { value = call.invoke(); }
        var counters = capture.snapshot();
        assertTrue(counters.complete());
        return new Observed<>(value, counters);
    }

    private static void assertBytes(LocalIoObservation.Snapshot counters, long journal, long manifest, long segments, long total) {
        assertEquals(journal, counters.value(LocalIo.Role.JOURNAL, Metric.READ_BYTES));
        assertEquals(manifest, counters.value(LocalIo.Role.MANIFEST, Metric.READ_BYTES));
        assertEquals(segments, counters.value(LocalIo.Role.SEGMENT, Metric.READ_BYTES));
        assertEquals(total, counters.sum(Metric.READ_BYTES));
    }

    private static JournalRecord put(long sequence, long value, long from, long to) {
        return new JournalRecord(sequence, new Transaction(new RequestId("r-" + sequence),
                List.of(new WriteOperation.Put(ENTITY, new ValidTimeInterval(from, to), value))));
    }

    private static byte[] image(List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(DATABASE));
        for (var record : records) { bytes.writeBytes(JournalCodec.encodeRecord(record)); }
        return bytes.toByteArray();
    }

    private static String manifestName(long generation, ManifestId id) {
        return String.format(Locale.ROOT, "checkpoint-%019d-%s.manifest", generation, id.value());
    }

    private static List<Throwable> failures(String label) {
        return List.of(new IOException(label), new IllegalStateException(label), new AssertionError(label));
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        throw (Error) failure;
    }

    private static void throwProblem(Throwable failure) throws IOException {
        if (failure == null) { return; }
        if (failure instanceof IOException io) { throw io; }
        throwUnchecked(failure);
    }

    private record Fixture(CheckpointManifest manifest) { }
    private record Observed<T>(T value, LocalIoObservation.Snapshot counters) { }
    @FunctionalInterface private interface IoCall<T> { T invoke() throws IOException; }

    private static final class Source implements LocalDatabase.WriteSource {
        private final List<JournalRecord> records;
        private final long head;
        final List<String> events = new ArrayList<>();
        Runnable onJournal = () -> { };
        Runnable onSynchronize = () -> { };
        Runnable onClose = () -> { };
        Throwable journalFailure;
        Throwable synchronizeFailure;
        Throwable closeFailure;
        int failJournalAfter = Integer.MAX_VALUE;
        int closes;
        int appends;
        int forces;

        Source(List<JournalRecord> records, long head) { this.records = List.copyOf(records); this.head = head; }
        @Override public DatabaseId databaseId() { return DATABASE; }
        @Override public OptionalLong checkpointSequence() { return OptionalLong.empty(); }
        @Override public long journalSequence() { return head; }
        @Override public void replayCheckpoint(Consumer<? super JournalRecord> consumer) {
            throw new AssertionError("HistorySource must replay its acquired records instead of its delegate");
        }
        @Override public void replayJournal(Consumer<? super JournalRecord> consumer) throws IOException {
            events.add("journal");
            onJournal.run();
            int count = 0;
            for (var record : records) {
                if (count++ == failJournalAfter) { throwProblem(journalFailure); }
                consumer.accept(record);
            }
            if (count == failJournalAfter) { throwProblem(journalFailure); }
        }
        @Override public void synchronize() throws IOException {
            events.add("synchronize");
            onSynchronize.run();
            throwProblem(synchronizeFailure);
        }
        @Override public void append(JournalRecord record) { appends++; }
        @Override public void force() { forces++; }
        @Override public void close() throws IOException {
            events.add("close");
            closes++;
            onClose.run();
            throwProblem(closeFailure);
        }
    }
}
