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
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ManifestId;
import org.meridiandb.storage.manifest.ManifestFormatException;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class LocalDatabaseTest {
    private static final DatabaseId DATABASE = new DatabaseId("recovered-db");
    private static final EntityId SALARY = new EntityId("salary");
    private static final EntityId BONUS = new EntityId("bonus");
    private static final List<JournalRecord> HISTORY = List.of(
            new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 100)))),
            new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 20L), 120),
                    new WriteOperation.Put(BONUS, new ValidTimeInterval(null, null), 7)))),
            new JournalRecord(3, new Transaction(new RequestId("retraction"), List.of(
                    new WriteOperation.Retract(SALARY, new ValidTimeInterval(12L, 14L))))),
            new JournalRecord(4, new Transaction(new RequestId("same-value"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 12L), 120)))));

    @TempDir
    Path directory;

    @Test
    void realCheckpointAndCompleteTailRecoverExactHistoricalQueriesRequestsAndLineage() throws IOException {
        var fixture = createFiles(DATABASE, HISTORY, OptionalLong.of(2));
        var before = snapshot();
        try (var database = LocalDatabase.open(directory)) {
            assertRecoveredHistory(database, DATABASE);
            assertEquals(Optional.of(position(DATABASE, 2)), database.checkpointPosition());
            assertEquals(fixture.manifestId(), ManifestCodec.decode(Files.readAllBytes(fixture.manifestPath())).id());
        }
        assertFilesEqual(before, snapshot());
    }

    @Test
    void realJournalWithoutCheckpointRetainsGenesisAndAllHistoricalCutoffs() throws IOException {
        createFiles(DATABASE, HISTORY, OptionalLong.empty());
        try (var database = LocalDatabase.open(directory)) {
            assertEquals(Optional.empty(), database.checkpointPosition());
            assertRecoveredHistory(database, DATABASE);
        }
    }

    @Test
    void emptyJournalAndExplicitGenesisCheckpointRemainDistinctStates() throws IOException {
        createFiles(DATABASE, List.of(), OptionalLong.of(0));
        try (var database = LocalDatabase.open(directory); var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(position(DATABASE, 0), database.head());
            assertEquals(Optional.of(position(DATABASE, 0)), database.checkpointPosition());
            assertEquals(absent(), view.query(new TemporalQuery.Get(SALARY, 0)));
            assertEquals(Optional.empty(), database.lookupRequest(new RequestId("missing")));
        }
    }

    @Test
    void exactUnicodeLineageAndRequestIdentitySurviveRealRecovery() throws IOException {
        String exact = "A\u0000é" + (char) 0xd800 + "e" + (char) 0x0301 + (char) 0xdc00;
        var lineage = new DatabaseId(exact);
        var record = new JournalRecord(1, new Transaction(new RequestId(exact), List.of(
                new WriteOperation.Put(new EntityId(exact), new ValidTimeInterval(Long.MIN_VALUE, null), Long.MAX_VALUE))));
        createFiles(lineage, List.of(record), OptionalLong.of(1));
        try (var database = LocalDatabase.open(directory); var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(position(lineage, 1), database.head());
            assertEquals(Optional.of(position(lineage, 1)), database.lookupRequest(record.transaction().requestId()));
            assertEquals(present(Long.MAX_VALUE), view.query(new TemporalQuery.Get(new EntityId(exact), Long.MIN_VALUE)));
        }
    }

    @Test
    void recoveredDatabaseRetainsBothStoreAndJournalExclusiveOwnershipUntilClose() throws IOException {
        createFiles(DATABASE, HISTORY, OptionalLong.of(2));
        try (var database = LocalDatabase.open(directory)) {
            assertEquals(position(DATABASE, 4), database.head());
            assertThrows(IOException.class, () -> {
                try (var unexpected = LocalCheckpointStore.open(directory)) { unexpected.journalPath(); }
            });
            assertThrows(IOException.class, () -> {
                try (var unexpected = LocalJournal.open(directory.resolve("transactions.journal"))) { unexpected.prefix(0); }
            });
            assertThrows(IOException.class, () -> {
                try (var unexpected = LocalDatabase.open(directory)) { unexpected.head(); }
            });
        }
        assertOwnershipReleased();
    }

    @Test
    void ignoredPendingCandidateIsPreservedWhileTheSelectedCheckpointRecovers() throws IOException {
        createFiles(DATABASE, HISTORY, OptionalLong.of(2));
        var pending = directory.resolve("pending-999-abandoned.manifest");
        byte[] evidence = {4, 9, 2};
        Files.write(pending, evidence);
        try (var database = LocalDatabase.open(directory)) { assertEquals(position(DATABASE, 4), database.head()); }
        assertArrayEquals(evidence, Files.readAllBytes(pending));
    }

    @Test
    void incompleteJournalFailsWithoutRepairAndReleasesAllAcquiredOwners() throws IOException {
        createFiles(DATABASE, HISTORY, OptionalLong.of(2));
        var path = directory.resolve("transactions.journal");
        byte[] original = Files.readAllBytes(path);
        byte[] incomplete = Arrays.copyOf(original, original.length - 1);
        Files.write(path, incomplete);
        assertThrows(LocalJournal.IncompleteTailException.class, () -> LocalDatabase.open(directory));
        assertArrayEquals(incomplete, Files.readAllBytes(path));
        Files.write(path, original);
        assertOwnershipReleased();
        try (var database = LocalDatabase.open(directory)) { assertEquals(position(DATABASE, 4), database.head()); }
    }

    @Test
    void corruptNewestManifestAndMissingDependencyNeverExposeAnOlderPrefix() throws IOException {
        var fixture = createFiles(DATABASE, HISTORY, OptionalLong.of(2));
        byte[] original = Files.readAllBytes(fixture.manifestPath());
        byte[] corrupt = original.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(fixture.manifestPath(), corrupt);
        assertThrows(ManifestFormatException.class, () -> LocalDatabase.open(directory));
        assertArrayEquals(corrupt, Files.readAllBytes(fixture.manifestPath()));
        Files.write(fixture.manifestPath(), original);
        assertOwnershipReleased();
        Files.delete(directory.resolve(fixture.segmentId().value() + ".segment"));
        assertThrows(IOException.class, () -> LocalDatabase.open(directory));
        assertOwnershipReleased();
    }

    @Test
    void validButDifferentStoredJournalPrefixCannotSatisfySelectedCheckpoint() throws IOException {
        createFiles(DATABASE, HISTORY, OptionalLong.of(2));
        var different = new ArrayList<>(HISTORY);
        different.set(0, new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 999)))));
        byte[] journal = journalImage(DATABASE, different);
        var path = directory.resolve("transactions.journal");
        Files.write(path, journal);
        assertThrows(IOException.class, () -> LocalDatabase.open(directory));
        assertArrayEquals(journal, Files.readAllBytes(path));
        assertOwnershipReleased();
    }

    @Test
    void missingJournalIsNotCreatedByReadOnlyOpen() throws IOException {
        try (var store = supportedStore()) { assertEquals(directory.toRealPath().resolve("transactions.journal"), store.journalPath()); }
        var before = snapshot();
        assertThrows(IOException.class, () -> LocalDatabase.open(directory));
        assertFalse(Files.exists(directory.resolve("transactions.journal")));
        assertFilesEqual(before, snapshot());
        try (var store = LocalCheckpointStore.open(directory)) { assertEquals(directory.toRealPath().resolve("transactions.journal"), store.journalPath()); }
    }

    @Test
    void seamRecoveryAppliesCheckpointOnceAndOnlyJournalSuffixBeyondItsCutoff() throws IOException {
        var source = new Source(OptionalLong.of(2), HISTORY.subList(0, 2), HISTORY, 4);
        try (var database = LocalDatabase.recover(source)) {
            assertRecoveredHistory(database, DATABASE);
            assertEquals(Optional.of(position(DATABASE, 2)), database.checkpointPosition());
            assertEquals(1, source.checkpointReplays);
            assertEquals(1, source.journalReplays);
            assertEquals(0, source.closes, "Source ownership lasts as long as the recovered database");
        }
        assertEquals(1, source.closes);
    }

    @Test
    void noCheckpointSkipsCheckpointReplayWhileGenesisCheckpointReplaysAnEmptyHistory() throws IOException {
        var noCheckpoint = new Source(OptionalLong.empty(), List.of(), HISTORY, 4);
        try (var database = LocalDatabase.recover(noCheckpoint)) {
            assertEquals(Optional.empty(), database.checkpointPosition());
            assertEquals(0, noCheckpoint.checkpointReplays);
        }
        var genesis = new Source(OptionalLong.of(0), List.of(), HISTORY, 4);
        try (var database = LocalDatabase.recover(genesis)) {
            assertEquals(Optional.of(position(DATABASE, 0)), database.checkpointPosition());
            assertEquals(1, genesis.checkpointReplays);
        }
    }

    @Test
    void invalidCheckpointCardinalityOrderAndCutoffAlwaysCloseSource() {
        for (var source : List.of(
                new Source(OptionalLong.of(-1), List.of(), HISTORY, 4),
                new Source(OptionalLong.of(5), HISTORY, HISTORY, 4),
                new Source(OptionalLong.of(2), HISTORY.subList(0, 1), HISTORY, 4),
                new Source(OptionalLong.of(1), HISTORY.subList(0, 2), HISTORY, 4),
                new Source(OptionalLong.of(2), List.of(HISTORY.get(1), HISTORY.getFirst()), HISTORY, 4),
                new Source(OptionalLong.of(2), List.of(HISTORY.getFirst(), HISTORY.get(2)), HISTORY, 4))) {
            assertThrows(IOException.class, () -> LocalDatabase.recover(source));
            assertEquals(1, source.closes);
        }
    }

    @Test
    void invalidJournalCardinalityOrderAndDeclaredHeadAlwaysCloseSource() {
        for (var source : List.of(
                new Source(OptionalLong.empty(), List.of(), HISTORY, -1),
                new Source(OptionalLong.empty(), List.of(), HISTORY.subList(0, 1), 2),
                new Source(OptionalLong.empty(), List.of(), HISTORY.subList(0, 2), 1),
                new Source(OptionalLong.empty(), List.of(), List.of(HISTORY.getFirst(), HISTORY.get(2)), 3),
                new Source(OptionalLong.empty(), List.of(), List.of(HISTORY.get(1), HISTORY.getFirst()), 2),
                new Source(OptionalLong.empty(), List.of(), HISTORY, Long.MAX_VALUE))) {
            assertThrows(IOException.class, () -> LocalDatabase.recover(source));
            assertEquals(1, source.closes);
        }
    }

    @Test
    void duplicateRequestIdentityInCheckpointOrRecoveredSuffixIsNeverAnIdenticalRetry() {
        var first = HISTORY.getFirst();
        var retry = new JournalRecord(2, first.transaction());
        for (var source : List.of(
                new Source(OptionalLong.of(2), List.of(first, retry), List.of(first, retry), 2),
                new Source(OptionalLong.of(1), List.of(first), List.of(first, retry), 2),
                new Source(OptionalLong.empty(), List.of(), List.of(first, retry), 2))) {
            assertThrows(IOException.class, () -> LocalDatabase.recover(source));
            assertEquals(1, source.closes);
        }
    }

    @Test
    void injectedCheckpointAndJournalIoFailuresReturnNoDatabaseAndCloseSource() {
        for (boolean checkpointFailure : new boolean[] {true, false}) {
            var source = new Source(OptionalLong.of(2), HISTORY.subList(0, 2), HISTORY, 4);
            source.failCheckpointAfter = checkpointFailure ? 1 : Integer.MAX_VALUE;
            source.failJournalAfter = checkpointFailure ? Integer.MAX_VALUE : 3;
            var failure = assertThrows(IOException.class, () -> LocalDatabase.recover(source));
            assertSame(source.readFailure, failure);
            assertEquals(1, source.closes);
        }
    }

    @Test
    void recoveryFailurePreservesPrimaryExceptionAndSuppressesCleanupFailure() {
        var source = new Source(OptionalLong.of(2), HISTORY.subList(0, 2), HISTORY, 4);
        source.failJournalAfter = 1;
        source.failCloseCount = 1;
        var failure = assertThrows(IOException.class, () -> LocalDatabase.recover(source));
        assertSame(source.readFailure, failure);
        assertArrayEquals(new Throwable[] {source.closeFailure}, failure.getSuppressed());
        assertEquals(1, source.closes);
    }

    @Test
    void closeInvalidatesAllViewsBeforeReleasingSourceAndKeepsReturnedValuesUsable() throws IOException {
        var source = new Source(OptionalLong.of(2), HISTORY.subList(0, 2), HISTORY, 4);
        var database = LocalDatabase.recover(source);
        var view = database.openView(ReadCutoff.Head.INSTANCE);
        var result = view.query(new TemporalQuery.Scan(SALARY, new ValidTimeInterval(8L, 16L)));
        var position = view.position();
        source.onClose = () -> {
            assertThrows(IllegalStateException.class, database::head);
            assertThrows(IllegalStateException.class, view::position);
            assertThrows(IllegalStateException.class, () -> view.query(new TemporalQuery.Get(SALARY, 0)));
        };
        database.close();
        database.close();
        assertEquals(1, source.closes);
        assertEquals(position(DATABASE, 4), position);
        assertEquals(expectedScan(), result);
        assertThrows(UnsupportedOperationException.class, () -> ((TemporalResult.Segments) result).values().clear());
        assertThrows(IllegalStateException.class, database::checkpointPosition);
        assertThrows(IllegalStateException.class, () -> database.lookupRequest(new RequestId("initial")));
        assertThrows(IllegalStateException.class, () -> database.openView(ReadCutoff.Head.INSTANCE));
        view.close();
        view.close();
    }

    @Test
    void failedCloseCanBeRetriedButDatabaseAndViewsRemainUnavailable() throws IOException {
        var source = new Source(OptionalLong.empty(), List.of(), HISTORY, 4);
        source.failCloseCount = 1;
        var database = LocalDatabase.recover(source);
        var view = database.openView(ReadCutoff.Head.INSTANCE);
        assertSame(source.closeFailure, assertThrows(IOException.class, database::close));
        assertThrows(IllegalStateException.class, database::head);
        assertThrows(IllegalStateException.class, view::position);
        database.close();
        database.close();
        assertEquals(2, source.closes);
        view.close();
    }

    @Test
    void closingOneViewPreservesOtherViewsAndDatabaseWhileInvalidCutoffsStayRejected() throws IOException {
        var source = new Source(OptionalLong.of(2), HISTORY.subList(0, 2), HISTORY, 4);
        try (var database = LocalDatabase.recover(source); var retained = database.openView(ReadCutoff.Head.INSTANCE)) {
            var closed = database.openView(new ReadCutoff.At(position(DATABASE, 1)));
            closed.close();
            closed.close();
            assertThrows(IllegalStateException.class, closed::position);
            assertThrows(IllegalStateException.class, () -> closed.query(new TemporalQuery.Get(SALARY, 0)));
            assertEquals(position(DATABASE, 4), retained.position());
            assertEquals(present(100), retained.query(new TemporalQuery.Get(SALARY, 0)));
            for (var invalid : List.of(position(new DatabaseId("foreign"), 1), position(DATABASE, 5))) {
                var failure = assertThrows(EngineException.class, () -> database.openView(new ReadCutoff.At(invalid)));
                assertEquals(EngineException.Code.INVALID_CUTOFF, failure.code());
            }
            assertEquals(position(DATABASE, 4), database.head());
        }
    }

    @Test
    @Timeout(30)
    void concurrentReadersShareRecoveredViewWithStablePointScanAndAggregateAnswers() throws Exception {
        var source = new Source(OptionalLong.of(2), HISTORY.subList(0, 2), HISTORY, 4);
        try (var database = LocalDatabase.recover(source);
             var shared = database.openView(ReadCutoff.Head.INSTANCE)) {
            int readers = 8;
            var barrier = new CyclicBarrier(readers);
            var executor = Executors.newFixedThreadPool(readers);
            try {
                var futures = new ArrayList<Future<?>>();
                for (int reader = 0; reader < readers; reader++) {
                    futures.add(executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        for (int round = 0; round < 64; round++) {
                            assertEquals(position(DATABASE, 4), shared.position());
                            assertEquals(absent(), shared.query(new TemporalQuery.Get(SALARY, 13)));
                            assertEquals(present(120), shared.query(new TemporalQuery.Get(SALARY, 11)));
                            assertEquals(expectedScan(), shared.query(new TemporalQuery.Scan(
                                    SALARY, new ValidTimeInterval(8L, 16L))));
                            assertEquals(new TemporalResult.Aggregate(1, BigInteger.valueOf(7)),
                                    shared.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
                        }
                        return null;
                    }));
                }
                for (var future : futures) { future.get(10, TimeUnit.SECONDS); }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Reader workers must terminate");
            }
            assertEquals(position(DATABASE, 4), database.head());
        }
    }

    @Test
    void nullRecoverySourceIsRejected() { assertThrows(NullPointerException.class, () -> LocalDatabase.recover(null)); }

    private void assertRecoveredHistory(LocalDatabase database, DatabaseId lineage) {
        assertEquals(position(lineage, 4), database.head());
        for (var record : HISTORY) {
            assertEquals(Optional.of(position(lineage, record.sequence())), database.lookupRequest(record.transaction().requestId()));
        }
        assertEquals(Optional.empty(), database.lookupRequest(new RequestId("missing")));
        for (int sequence = 0; sequence <= 4; sequence++) {
            try (var view = database.openView(new ReadCutoff.At(position(lineage, sequence)))) {
                assertEquals(position(lineage, sequence), view.position());
                var expected = sequence == 0 ? absent() : sequence == 1 ? present(100)
                        : sequence == 2 ? present(120) : absent();
                assertEquals(expected, view.query(new TemporalQuery.Get(SALARY, 13)));
            }
        }
        try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(expectedScan(), view.query(new TemporalQuery.Scan(SALARY, new ValidTimeInterval(8L, 16L))));
            assertEquals(new TemporalResult.Aggregate(1, BigInteger.valueOf(7)),
                    view.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
        }
    }

    private static TemporalResult.Segments expectedScan() {
        return new TemporalResult.Segments(List.of(
                new TemporalSegment(new ValidTimeInterval(8L, 10L), 100),
                new TemporalSegment(new ValidTimeInterval(10L, 12L), 120),
                new TemporalSegment(new ValidTimeInterval(14L, 16L), 120)));
    }

    private static TemporalResult.Point present(long value) { return new TemporalResult.Point(OptionalLong.of(value)); }
    private static TemporalResult.Point absent() { return new TemporalResult.Point(OptionalLong.empty()); }
    private static CommitPosition position(DatabaseId database, long sequence) { return new CommitPosition(database, sequence); }

    private FileFixture createFiles(DatabaseId lineage, List<JournalRecord> history, OptionalLong checkpoint) throws IOException {
        ManifestId manifestId = null;
        Path manifestPath = null;
        SegmentId segmentId = null;
        try (var store = supportedStore(); var journal = LocalJournal.create(store.journalPath(), lineage)) {
            long cutoff = checkpoint.orElse(0);
            for (var record : history) { if (record.sequence() <= cutoff) { journal.append(record); } }
            if (checkpoint.isPresent()) {
                var prefix = journal.prefix(cutoff);
                var references = new ArrayList<SegmentReference>();
                if (cutoff > 0) {
                    var segment = new ImmutableSegment(lineage, history.subList(0, Math.toIntExact(cutoff)));
                    segmentId = store.writeSegment(segment);
                    references.add(new SegmentReference(segmentId, SegmentCodec.encode(segment).length, 1, cutoff));
                }
                var manifest = new CheckpointManifest(lineage, 1,
                        new JournalPrefix(cutoff, prefix.byteLength(), prefix.sha256()), references);
                manifestId = store.publish(manifest, journal);
                manifestPath = directory.resolve(String.format(java.util.Locale.ROOT,
                        "checkpoint-%019d-%s.manifest", 1, manifestId.value()));
            }
            for (var record : history) { if (record.sequence() > cutoff) { journal.append(record); } }
            journal.force();
        }
        return new FileFixture(manifestId, manifestPath, segmentId);
    }

    private record FileFixture(ManifestId manifestId, Path manifestPath, SegmentId segmentId) { }

    private LocalCheckpointStore supportedStore() throws IOException {
        try { return LocalCheckpointStore.open(directory); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
            throw new AssertionError("Assumption did not abort", unsupported);
        }
    }

    private void assertOwnershipReleased() throws IOException {
        try (var store = LocalCheckpointStore.open(directory); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(DATABASE, journal.databaseId());
        }
    }

    private Map<String, byte[]> snapshot() throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var paths = Files.list(directory)) {
            for (var path : paths.toList()) { if (Files.isRegularFile(path)) { result.put(path.getFileName().toString(), Files.readAllBytes(path)); } }
        }
        return result;
    }

    private static void assertFilesEqual(Map<String, byte[]> before, Map<String, byte[]> after) {
        assertEquals(before.keySet(), after.keySet());
        before.forEach((name, bytes) -> assertArrayEquals(bytes, after.get(name), name));
    }

    private static byte[] journalImage(DatabaseId lineage, List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(lineage));
        for (var record : records) { bytes.writeBytes(JournalCodec.encodeRecord(record)); }
        return bytes.toByteArray();
    }

    private static final class Source implements LocalDatabase.RecoverySource {
        final OptionalLong checkpoint;
        final List<JournalRecord> checkpointRecords;
        final List<JournalRecord> journalRecords;
        final long head;
        int checkpointReplays;
        int journalReplays;
        int closes;
        int failCheckpointAfter = Integer.MAX_VALUE;
        int failJournalAfter = Integer.MAX_VALUE;
        int failCloseCount;
        Runnable onClose;
        final IOException readFailure = new IOException("Injected recovery read failure");
        final IOException closeFailure = new IOException("Injected source close failure");
        Source(OptionalLong checkpoint, List<JournalRecord> checkpointRecords, List<JournalRecord> journalRecords, long head) {
            this.checkpoint = checkpoint;
            this.checkpointRecords = checkpointRecords;
            this.journalRecords = journalRecords;
            this.head = head;
        }
        @Override public DatabaseId databaseId() { return DATABASE; }
        @Override public OptionalLong checkpointSequence() { return checkpoint; }
        @Override public long journalSequence() { return head; }
        @Override public void replayCheckpoint(Consumer<? super JournalRecord> consumer) throws IOException {
            checkpointReplays++;
            replay(checkpointRecords, consumer, failCheckpointAfter);
        }
        @Override public void replayJournal(Consumer<? super JournalRecord> consumer) throws IOException {
            journalReplays++;
            replay(journalRecords, consumer, failJournalAfter);
        }
        private void replay(List<JournalRecord> records, Consumer<? super JournalRecord> consumer, int failAfter) throws IOException {
            int count = 0;
            for (var record : records) {
                if (count == failAfter) { throw readFailure; }
                consumer.accept(record);
                count++;
            }
            if (count == failAfter) { throw readFailure; }
        }
        @Override public void close() throws IOException {
            closes++;
            if (onClose != null) { onClose.run(); }
            if (failCloseCount > 0) { failCloseCount--; throw closeFailure; }
        }
    }
}
