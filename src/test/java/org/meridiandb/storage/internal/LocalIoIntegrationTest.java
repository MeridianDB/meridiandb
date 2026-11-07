// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.internal.LocalIo.Counter;
import org.meridiandb.storage.internal.LocalIo.Role;
import org.meridiandb.storage.internal.LocalIo.Snapshot;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.HistoryCompactor;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.object.LocalObjectStore;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.LocalSegmentFile;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.EntityValue;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.ValuePredicate;
import org.meridiandb.temporal.WriteOperation;

/** Native adapter accounting, with literal codec vectors and independently enumerated protocol work. */
class LocalIoIntegrationTest {
    private static final DatabaseId DATABASE = new DatabaseId("db");
    private static final EntityId ENTITY = new EntityId("e");
    private static final ValidTimeInterval ALL_TIME = new ValidTimeInterval(null, null);
    private static final JournalRecord FIRST = record(1, "r", 7);
    private static final JournalRecord SECOND = record(2, "s", 8);
    private static final ImmutableSegment SINGLE = new ImmutableSegment(DATABASE, List.of(FIRST));
    private static final SegmentId SINGLE_ID = new SegmentId(
            "d313d28a39ce9900e61fdead724735a02e09ab194861553910fe941d64b26ace");
    private static final String FIRST_PREFIX_HASH = "fc0bccc3589bcab2f55535bf1acf6199dab723cbdbd1526329f511413e9e0dc3";

    // Existing independently assembled codec goldens: header 24, frame 54, segment 158 bytes.
    // Expected accounting below uses these literals and the publication protocol, never file sizes
    // or observed counters as the source of an expected byte count.
    private static final byte[] HEADER = HexFormat.of().parseHex(
            "4d52444e4a4e4c00000000010000000200640062a9cf7c87");
    private static final byte[] FRAME = HexFormat.of().parseHex(
            "4d54584e0000000100000022182a4007"
            + "00000000000000010000000100720000000101000000010065000000000000000007"
            + "538cd524");
    private static final byte[] SEGMENT = HexFormat.of().parseHex(
            "4d52444e534547000000000100000018"
            + "4d52444e4a4e4c00000000010000000200640062a9cf7c87"
            + "0000000000000001000000000000000100000001000000000000003621f423b2"
            + "4d54584e0000000100000022182a4007"
            + "00000000000000010000000100720000000101000000010065000000000000000007"
            + "538cd524"
            + "af1a2406823229a1e45d59ada79103d9b9f834e1c0171ef6a3e83478ba0e28fa");

    @TempDir
    Path directory;

    @Test
    void databaseCreationCountsLiteralGenesisAndEveryRequiredNativeForce() throws IOException {
        var capture = LocalIo.start();
        LocalDatabase database;
        try (capture) { database = createDatabase(); }
        try (database) {
            var observed = finished(capture);
            // Header reads: initialize prefix, genesis verify, selected-genesis verify, replay.
            assertWork(observed, Role.JOURNAL, 96, 24, 3);
            assertWork(observed, Role.MANIFEST, 136, 136, 1);
            assertWork(observed, Role.CONTROL, 0, 0, 1);
            // Acquisition probe, dependency/candidate publication, reader admission, writable sync.
            assertWork(observed, Role.DIRECTORY, 0, 0, 5);
            assertOnlyRoles(observed, Role.JOURNAL, Role.MANIFEST, Role.CONTROL, Role.DIRECTORY);
            assertEquals(new CommitPosition(DATABASE, 0), database.head());
            assertEquals(Optional.of(new CommitPosition(DATABASE, 0)), database.checkpointPosition());
            assertZero(observe(() -> {
                try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
                    assertEquals(new TemporalResult.Point(OptionalLong.empty()), view.query(new TemporalQuery.Get(ENTITY, 0)));
                }
            }));
        }
    }

    @Test
    void anOwnerOpenedWithoutCaptureUsesEachLaterCommitRetryAndQueryScope() throws IOException {
        try (var database = createDatabase()) {
            var commit = observe(() -> assertFalse(database.transact(FIRST.transaction()).replayed()));
            assertWork(commit, Role.JOURNAL, 0, 54, 1);
            assertOnlyRoles(commit, Role.JOURNAL);

            var noOp = observe(() -> assertFalse(database.transact(record(2, "s", 7).transaction()).replayed()));
            assertWork(noOp, Role.JOURNAL, 0, 54, 1);
            assertOnlyRoles(noOp, Role.JOURNAL);

            var heap = observe(() -> {
                var retry = database.transact(FIRST.transaction());
                assertTrue(retry.replayed());
                assertEquals(new CommitPosition(DATABASE, 1), retry.position());
                assertEquals(Optional.of(new CommitPosition(DATABASE, 1)), database.lookupRequest(new RequestId("r")));
                var predicate = new ValuePredicate(ValuePredicate.Operator.EQ, 7);
                try (var view = database.openView(new ReadCutoff.At(new CommitPosition(DATABASE, 1)))) {
                    assertEquals(new TemporalResult.Point(OptionalLong.of(7)), view.query(new TemporalQuery.Get(ENTITY, 0)));
                    assertEquals(new TemporalResult.Segments(List.of(new TemporalSegment(ALL_TIME, 7))),
                            view.query(new TemporalQuery.Scan(ENTITY, ALL_TIME)));
                    assertEquals(new TemporalResult.Rows(List.of(new EntityValue(ENTITY, 7))),
                            view.query(new TemporalQuery.Select(List.of(ENTITY), 0, predicate)));
                    assertEquals(new TemporalResult.Segments(List.of(new TemporalSegment(ALL_TIME, 7))),
                            view.query(new TemporalQuery.FilterScan(ENTITY, ALL_TIME, predicate)));
                    assertEquals(new TemporalResult.Aggregate(1, BigInteger.valueOf(7)),
                            view.query(new TemporalQuery.Sum(List.of(ENTITY), 0)));
                }
            });
            assertZero(heap);
            // The completed first snapshot cannot absorb bytes from later scopes on the same owner.
            assertWork(commit, Role.JOURNAL, 0, 54, 1);
        }
    }

    @Test
    void journalOpenReplayAndPrefixCountEachActualPassRatherThanUniqueFileBytes() throws IOException {
        Path path = directory.resolve("transactions.journal");
        Files.write(path, concat(HEADER, FRAME));
        var opening = LocalIo.start();
        LocalJournal journal;
        try (opening) { journal = LocalJournal.open(path); }
        try (journal) {
            assertWork(finished(opening), Role.JOURNAL, 78, 0, 0);
            var records = new ArrayList<JournalRecord>();
            var replay = observe(() -> journal.replay(records::add));
            assertEquals(List.of(FIRST), records);
            assertWork(replay, Role.JOURNAL, 78, 0, 0);
            assertOnlyRoles(replay, Role.JOURNAL);
            var prefix = observe(() -> {
                var value = journal.prefix(1);
                assertEquals(78, value.byteLength());
                assertEquals(FIRST_PREFIX_HASH, value.sha256());
            });
            assertWork(prefix, Role.JOURNAL, 78, 0, 0);
            assertOnlyRoles(prefix, Role.JOURNAL);
            var genesis = observe(() -> assertEquals(24, journal.prefix(0).byteLength()));
            assertWork(genesis, Role.JOURNAL, 24, 0, 0);
            assertOnlyRoles(genesis, Role.JOURNAL);
        }
    }

    @Test
    void journalOnlyWritableRecoveryCountsBothOpenValidationAndEngineReplay() throws IOException {
        requireDirectorySync();
        Files.write(directory.resolve("transactions.journal"), concat(HEADER, FRAME));
        var opening = LocalIo.start();
        LocalDatabase database;
        try (opening) { database = LocalDatabase.openWritable(directory); }
        try (database) {
            var observed = finished(opening);
            assertWork(observed, Role.JOURNAL, 156, 0, 1);
            assertWork(observed, Role.DIRECTORY, 0, 0, 2);
            assertOnlyRoles(observed, Role.JOURNAL, Role.DIRECTORY);
            assertEquals(Optional.empty(), database.checkpointPosition());
            assertEquals(new CommitPosition(DATABASE, 1), database.head());
        }
    }

    @Test
    void segmentWriteAndLazyHeapReaderUseLiteralBytesAndSeparateEofWithoutNativeOwnershipLeak() throws IOException {
        Path path = directory.resolve("history.segment");
        var written = observe(() -> assertEquals(SINGLE_ID, LocalSegmentFile.writeNew(path, SINGLE)));
        assertWork(written, Role.SEGMENT, 0, 158, 1);
        assertOnlyRoles(written, Role.SEGMENT);
        assertArrayEquals(SEGMENT, Files.readAllBytes(path));

        var opening = LocalIo.start();
        org.meridiandb.storage.segment.LocalSegmentReader reader;
        try (opening) { reader = LocalSegmentFile.open(path, SINGLE_ID); }
        var read = finished(opening);
        assertWork(read, Role.SEGMENT, 158, 0, 0);
        assertEquals(1, read.value(Role.SEGMENT, Counter.READ_EOF));
        assertOnlyRoles(read, Role.SEGMENT);
        assertZero(observe(() -> {
            assertEquals(SINGLE, reader.segment());
            reader.close();
        }));
    }

    @Test
    void failedSegmentIntegrityRetainsTransferredBytesWithoutInventingAnIoFailure() throws IOException {
        Path path = directory.resolve("bad.segment");
        byte[] corrupt = SEGMENT.clone();
        corrupt[157] ^= 1;
        Files.write(path, corrupt);
        var observed = observe(() -> assertThrows(SegmentFormatException.class, () -> LocalSegmentFile.open(path, SINGLE_ID)));
        assertWork(observed, Role.SEGMENT, 158, 0, 0);
        assertEquals(1, observed.value(Role.SEGMENT, Counter.READ_EOF));
        assertOnlyRoles(observed, Role.SEGMENT);
        assertArrayEquals(corrupt, Files.readAllBytes(path));
    }

    @Test
    void checkpointNamespaceCountsPendingManifestSourceVerificationAndReaderControlReuse() throws IOException {
        try (var store = openStore(); var journal = LocalJournal.create(store.journalPath(), DATABASE)) {
            journal.append(FIRST);
            var segment = observe(() -> assertEquals(SINGLE_ID, store.writeSegment(SINGLE)));
            assertWork(segment, Role.SEGMENT, 0, 158, 1);
            assertOnlyRoles(segment, Role.SEGMENT);

            var first = manifest(1);
            var published = observe(() -> store.publish(first, journal));
            assertWork(published, Role.JOURNAL, 78, 0, 1);
            assertWork(published, Role.SEGMENT, 158, 0, 1);
            assertWork(published, Role.MANIFEST, 0, 192, 1);
            assertWork(published, Role.DIRECTORY, 0, 0, 2);
            assertOnlyRoles(published, Role.JOURNAL, Role.SEGMENT, Role.MANIFEST, Role.DIRECTORY);

            var admission = LocalIo.start();
            org.meridiandb.storage.manifest.LocalCheckpointReader reader;
            try (admission) { reader = store.readLatest(journal).orElseThrow(); }
            try (reader) {
                var admitted = finished(admission);
                assertWork(admitted, Role.MANIFEST, 192, 0, 0);
                assertWork(admitted, Role.SEGMENT, 158, 0, 0);
                assertWork(admitted, Role.JOURNAL, 78, 0, 0);
                assertWork(admitted, Role.CONTROL, 0, 0, 1);
                assertWork(admitted, Role.DIRECTORY, 0, 0, 1);
                assertOnlyRoles(admitted, Role.MANIFEST, Role.SEGMENT, Role.JOURNAL, Role.CONTROL, Role.DIRECTORY);

                var lazy = observe(() -> {
                    try (var child = reader.openSegment(0)) { assertEquals(SINGLE, child.segment()); }
                });
                assertWork(lazy, Role.SEGMENT, 158, 0, 0);
                assertOnlyRoles(lazy, Role.SEGMENT);

                var shared = observe(() -> {
                    try (var secondReader = store.readLatest(journal).orElseThrow()) {
                        assertEquals(first, secondReader.manifest());
                    }
                });
                assertWork(shared, Role.CONTROL, 0, 0, 0);
                assertWork(shared, Role.DIRECTORY, 0, 0, 1);
                assertWork(shared, Role.MANIFEST, 192, 0, 0);
                assertWork(shared, Role.SEGMENT, 158, 0, 0);
                assertWork(shared, Role.JOURNAL, 78, 0, 0);
                assertOnlyRoles(shared, Role.MANIFEST, Role.SEGMENT, Role.JOURNAL, Role.DIRECTORY);

                var reused = observe(() -> store.publish(manifest(2), journal));
                assertWork(reused, Role.JOURNAL, 156, 0, 1);
                assertWork(reused, Role.SEGMENT, 316, 0, 1);
                assertWork(reused, Role.MANIFEST, 192, 192, 1);
                assertWork(reused, Role.DIRECTORY, 0, 0, 2);
                assertOnlyRoles(reused, Role.JOURNAL, Role.SEGMENT, Role.MANIFEST, Role.DIRECTORY);
                assertEquals(first, reader.manifest());
            }
        }
    }

    @Test
    void checkpointRecoveryCountsNamespaceVerificationLazySegmentAndCompleteJournalReplay() throws IOException {
        try (var store = openStore(); var journal = LocalJournal.create(store.journalPath(), DATABASE)) {
            journal.append(FIRST);
            store.writeSegment(SINGLE);
            store.publish(manifest(1), journal);
        }
        var opening = LocalIo.start();
        LocalDatabase database;
        try (opening) { database = LocalDatabase.openWritable(directory); }
        try (database) {
            var observed = finished(opening);
            // Journal open scan + selected-prefix validation + complete journal replay.
            assertWork(observed, Role.JOURNAL, 234, 0, 1);
            // Namespace verifier reads once; checkpoint replay's lazy segment reader reads again.
            assertWork(observed, Role.SEGMENT, 316, 0, 0);
            assertWork(observed, Role.MANIFEST, 192, 0, 0);
            assertWork(observed, Role.CONTROL, 0, 0, 1);
            assertWork(observed, Role.DIRECTORY, 0, 0, 3);
            assertOnlyRoles(observed, Role.JOURNAL, Role.SEGMENT, Role.MANIFEST, Role.CONTROL, Role.DIRECTORY);
            assertEquals(Optional.of(new CommitPosition(DATABASE, 1)), database.checkpointPosition());
            assertZero(observe(() -> {
                assertTrue(database.transact(FIRST.transaction()).replayed());
                try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
                    assertEquals(new TemporalResult.Point(OptionalLong.of(7)), view.query(new TemporalQuery.Get(ENTITY, 0)));
                }
            }));
        }
    }

    @Test
    void compactionKeepsPurePlanningSeparateFromRepeatedVerificationAndReplacementInstallation() throws IOException {
        var latter = new ImmutableSegment(DATABASE, List.of(SECOND));
        byte[] latterImage = SegmentCodec.encode(latter);
        assertEquals(158, latterImage.length);
        try (var store = openStore(); var journal = LocalJournal.create(store.journalPath(), DATABASE)) {
            journal.append(FIRST);
            journal.append(SECOND);
            var firstId = store.writeSegment(SINGLE);
            var secondId = store.writeSegment(latter);
            var prefix = journal.prefix(2);
            var source = new CheckpointManifest(DATABASE, 1,
                    new CheckpointManifest.JournalPrefix(2, 132, prefix.sha256()), List.of(
                    new CheckpointManifest.SegmentReference(firstId, 158, 1, 1),
                    new CheckpointManifest.SegmentReference(secondId, 158, 2, 2)));
            store.publish(source, journal);
            var images = Map.of(firstId, SEGMENT, secondId, latterImage);
            var planning = LocalIo.start();
            HistoryCompactor.Plan plan;
            try (planning) { plan = HistoryCompactor.compact(source, images::get, HistoryCompactor.Limits.DEFAULT); }
            assertZero(finished(planning));
            assertEquals(316, plan.inputBytes());
            assertEquals(212, plan.outputBytes());
            assertEquals(1, plan.segments().size());
            try (var pinned = store.readLatest(journal).orElseThrow()) {
                var published = observe(() -> store.publishCompaction(plan, journal));
                assertWork(published, Role.JOURNAL, 264, 0, 1);
                assertWork(published, Role.SEGMENT, 528, 212, 2);
                assertWork(published, Role.MANIFEST, 248, 192, 1);
                assertWork(published, Role.DIRECTORY, 0, 0, 2);
                assertOnlyRoles(published, Role.JOURNAL, Role.SEGMENT, Role.MANIFEST, Role.DIRECTORY);
                try (var oldFirst = pinned.openSegment(0); var oldSecond = pinned.openSegment(1);
                     var selected = store.readLatest(journal).orElseThrow(); var replacement = selected.openSegment(0)) {
                    assertEquals(List.of(FIRST), oldFirst.segment().records());
                    assertEquals(List.of(SECOND), oldSecond.segment().records());
                    assertEquals(List.of(FIRST, SECOND), replacement.segment().records());
                    assertEquals(2, selected.manifest().generation());
                }
            }
        }
    }

    @Test
    void tailRepairCountsDiagnosticCopyBeforeTruncationWithoutClaimingDiscardedBytesWereRewritten() throws IOException {
        Path path = directory.resolve("transactions.journal");
        byte[] tail = {4, 9, 2, 7, 1, 6, 3};
        Files.write(path, concat(HEADER, FRAME, tail));
        var opening = LocalIo.start();
        LocalJournal journal;
        try (opening) { journal = LocalJournal.open(path, LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE); }
        LocalJournal.TailRepair repair;
        try (journal) {
            repair = journal.repair().orElseThrow();
            var observed = finished(opening);
            // Complete-history scan reads 78, then preservation reads exactly the seven-byte suffix.
            assertWork(observed, Role.JOURNAL, 85, 0, 1);
            assertWork(observed, Role.TAIL_EVIDENCE, 0, 7, 1);
            assertOnlyRoles(observed, Role.JOURNAL, Role.TAIL_EVIDENCE);
            assertEquals(78, repair.startOffset());
            assertEquals(85, repair.originalSize());
            assertEquals(1, journal.forcedSequence());
        }
        assertArrayEquals(tail, Files.readAllBytes(repair.evidence()));
        assertArrayEquals(concat(HEADER, FRAME), Files.readAllBytes(path));
    }

    @Test
    void localObjectAdapterCountsItsOwnBytesWhileVerifiedHeapRangesDoNoFileWork() throws IOException {
        var objects = new LocalObjectStore(directory);
        var key = new ObjectKey("example");
        byte[] bytes = {1, 2, 3, 4, 5};
        var reference = ObjectReference.forBytes(bytes);
        var written = observe(() -> assertEquals(reference, objects.createNew(key, bytes)));
        assertWork(written, Role.OBJECT, 0, 5, 1);
        assertOnlyRoles(written, Role.OBJECT);
        var opening = LocalIo.start();
        org.meridiandb.storage.object.VerifiedObject object;
        try (opening) { object = objects.read(key, reference); }
        var read = finished(opening);
        assertWork(read, Role.OBJECT, 5, 0, 0);
        assertEquals(1, read.value(Role.OBJECT, Counter.READ_EOF));
        assertOnlyRoles(read, Role.OBJECT);
        assertZero(observe(() -> {
            assertArrayEquals(bytes, object.bytes());
            assertArrayEquals(new byte[] {2, 3}, object.readRange(1, 2));
            object.close();
        }));
    }

    private LocalDatabase createDatabase() throws IOException {
        try { return LocalDatabase.create(directory, DATABASE); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            skipUnsupportedWindows(unsupported);
            throw unsupported;
        }
    }

    private LocalCheckpointStore openStore() throws IOException {
        try { return LocalCheckpointStore.open(directory); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            skipUnsupportedWindows(unsupported);
            throw unsupported;
        }
    }

    private void requireDirectorySync() throws IOException {
        try (var store = openStore()) { assertEquals(directory.toRealPath().resolve("transactions.journal"), store.journalPath()); }
    }

    private static void skipUnsupportedWindows(LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            assumeTrue(false, "Windows directory force unavailable: " + unsupported);
        }
    }

    private static CheckpointManifest manifest(long generation) {
        return new CheckpointManifest(DATABASE, generation,
                new CheckpointManifest.JournalPrefix(1, 78, FIRST_PREFIX_HASH),
                List.of(new CheckpointManifest.SegmentReference(SINGLE_ID, 158, 1, 1)));
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request),
                List.of(new WriteOperation.Put(ENTITY, ALL_TIME, value))));
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) { length = Math.addExact(length, part.length); }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, result, offset, part.length); offset += part.length; }
        return result;
    }

    private static Snapshot observe(IoAction action) throws IOException {
        var capture = LocalIo.start();
        try (capture) { action.run(); }
        return finished(capture);
    }

    private static Snapshot finished(LocalIo.Capture capture) {
        var observed = capture.snapshot();
        assertEquals(1, observed.schemaVersion());
        assertFalse(observed.overflow());
        assertFalse(observed.invalidResult());
        for (var role : Role.values()) {
            assertEquals(0, observed.value(role, Counter.READ_FAILURES), role + " read failures");
            assertEquals(0, observed.value(role, Counter.WRITE_FAILURES), role + " write failures");
            assertEquals(0, observed.value(role, Counter.FORCE_FAILURES), role + " force failures");
            assertEquals(observed.value(role, Counter.READ_ATTEMPTS), observed.value(role, Counter.READ_RETURNS));
            assertEquals(observed.value(role, Counter.WRITE_ATTEMPTS), observed.value(role, Counter.WRITE_RETURNS));
            assertEquals(observed.value(role, Counter.FORCE_ATTEMPTS), observed.value(role, Counter.FORCE_SUCCESSES));
        }
        return observed;
    }

    private static void assertWork(Snapshot observed, Role role, long reads, long writes, long forces) {
        assertEquals(reads, observed.value(role, Counter.READ_BYTES), role + " read bytes");
        assertEquals(writes, observed.value(role, Counter.WRITE_BYTES), role + " written bytes");
        assertEquals(forces, observed.value(role, Counter.FORCE_SUCCESSES), role + " successful forces");
    }

    private static void assertOnlyRoles(Snapshot observed, Role... active) {
        var expected = List.of(active);
        for (var role : Role.values()) {
            if (!expected.contains(role)) {
                for (var counter : Counter.values()) { assertEquals(0, observed.value(role, counter), role + " " + counter); }
            }
        }
    }

    private static void assertZero(Snapshot observed) { assertOnlyRoles(observed); }

    @FunctionalInterface
    private interface IoAction { void run() throws IOException; }
}
