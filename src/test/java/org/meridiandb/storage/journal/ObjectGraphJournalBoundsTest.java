// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphException;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ImmutableObjectStore;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.object.VerifiedObject;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class ObjectGraphJournalBoundsTest {
    private static final DatabaseId DATABASE = new DatabaseId("graph-bounds");
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final JournalRecord FIRST = record(1, 100, false);
    private static final JournalRecord SECOND = record(2, 200, false);
    private static final int HEADER_BYTES = JournalCodec.encodeHeader(DATABASE).length;

    @Test void longerActualRecordStopsBeforeBodyAndDoesNotPoisonOwner() throws IOException {
        var fixture = fixture(List.of(FIRST), List.of(FIRST));
        var file = new MemoryFile(journalImage(List.of(record(1, 100, true))));
        try (var journal = open(file)) {
            file.reads.clear();
            file.maxEnd = HEADER_BYTES + 16;
            var failure = assertThrows(ObjectCheckpointGraphException.class,
                    () -> verifier(fixture.store(), journal).verify(fixture.head(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
            assertEquals(ObjectCheckpointGraphException.Code.JOURNAL_MISMATCH, failure.code());
            assertInstanceOf(LocalJournal.PrefixLimitException.class, failure.getCause());
            assertTrue(file.reads.stream().allMatch(end -> end <= HEADER_BYTES + 16));
            assertEquals(0, journal.forcedBytes());
            assertEquals(1, journal.appendedSequence());
            file.maxEnd = Long.MAX_VALUE;
            assertEquals(file.image.length, journal.prefix(1, file.image.length).byteLength());
            assertClosed(fixture.store());
        }
    }

    @Test void shorterActualRecordFailsExactComparisonWithoutPoisoningOwner() throws IOException {
        var larger = record(1, 100, true);
        var fixture = fixture(List.of(larger), List.of(larger));
        var file = new MemoryFile(journalImage(List.of(FIRST)));
        try (var journal = open(file)) {
            var failure = assertThrows(ObjectCheckpointGraphException.class,
                    () -> verifier(fixture.store(), journal).verify(fixture.head(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
            assertEquals(ObjectCheckpointGraphException.Code.JOURNAL_MISMATCH, failure.code());
            assertEquals(file.image.length, journal.prefix(1).byteLength());
            assertEquals(0, journal.forcedSequence());
            assertClosed(fixture.store());
        }
    }

    @Test void observedActualJournalIoStillPoisonsWithoutExposingVerification() throws IOException {
        var fixture = fixture(List.of(FIRST), List.of(FIRST));
        var file = new MemoryFile(journalImage(List.of(FIRST)));
        try (var journal = open(file)) {
            var injected = new IOException("Injected owned-journal read failure");
            file.failure = injected;
            assertSame(injected, assertThrows(IOException.class,
                    () -> verifier(fixture.store(), journal).verify(fixture.head(), ObjectCheckpointGraphs.DEFAULT_LIMITS)));
            file.failure = null;
            assertThrows(IOException.class, () -> journal.prefix(0));
            assertClosed(fixture.store());
        }
    }

    @Test void largerStoredSegmentRangeCannotSpendUndeclaredRecordWork() throws IOException {
        var fixture = fixture(List.of(FIRST, SECOND), List.of(FIRST));
        var file = new MemoryFile(journalImage(List.of(FIRST)));
        try (var journal = open(file)) {
            file.reads.clear();
            var limits = new ObjectCheckpointGraphs.Limits(ObjectCheckpointGraphs.MAX_BYTES,
                    ObjectCheckpointGraphs.MAX_BYTES, 2, ObjectCheckpointGraphs.MAX_OBJECT_READS);
            var failure = assertThrows(ObjectCheckpointGraphException.class,
                    () -> verifier(fixture.store(), journal).verify(fixture.head(), limits));
            assertEquals(ObjectCheckpointGraphException.Code.INVALID_GRAPH, failure.code());
            assertEquals(SegmentFormatException.Code.INVALID_HISTORY,
                    assertInstanceOf(SegmentFormatException.class, failure.getCause()).code());
            assertTrue(file.reads.isEmpty());
            assertEquals(1, journal.prefix(1).sequence());
            assertClosed(fixture.store());
        }
    }

    @Test void wrongAdapterReferenceIsRejectedAndItsHeapSnapshotClosed() throws IOException {
        var fixture = fixture(List.of(FIRST), List.of(FIRST));
        fixture.store().substitute = true;
        var file = new MemoryFile(journalImage(List.of(FIRST)));
        try (var journal = open(file)) {
            file.reads.clear();
            assertThrows(IOException.class,
                    () -> verifier(fixture.store(), journal).verify(fixture.head(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
            assertTrue(file.reads.isEmpty());
            assertClosed(fixture.store());
        }
    }

    @Test void adapterCleanupFailurePropagatesWithoutActualJournalReadOrWrites() throws IOException {
        var fixture = fixture(List.of(FIRST), List.of(FIRST));
        var failure = new IOException("Adapter native cleanup failed before snapshot return");
        fixture.store().failure = failure;
        var file = new MemoryFile(journalImage(List.of(FIRST)));
        try (var journal = open(file)) {
            file.reads.clear();
            assertSame(failure, assertThrows(IOException.class,
                    () -> verifier(fixture.store(), journal).verify(fixture.head(), ObjectCheckpointGraphs.DEFAULT_LIMITS)));
            assertTrue(file.reads.isEmpty());
            assertEquals(0, journal.forcedSequence());
            assertEquals(1, journal.prefix(1).sequence());
        }
    }

    private static ObjectCheckpointGraphs verifier(Store store, LocalJournal journal) {
        return new ObjectCheckpointGraphs(store, journal, NAMESPACE, PUBLISHER);
    }
    private static void assertClosed(Store store) {
        assertFalse(store.snapshots.isEmpty());
        store.snapshots.forEach(snapshot -> assertThrows(IllegalStateException.class, snapshot::reference));
    }
    private static LocalJournal open(MemoryFile file) throws IOException {
        return LocalJournal.open(file, LocalJournal.TailPolicy.REJECT,
                (source, start, length) -> { throw new AssertionError("Unexpected repair"); });
    }
    private record Fixture(ObjectCheckpointHead head, Store store) { }
    private static Fixture fixture(List<JournalRecord> storedRecords, List<JournalRecord> declaredRecords) {
        byte[] segment = SegmentCodec.encode(new ImmutableSegment(DATABASE, storedRecords));
        var segmentId = SegmentCodec.decode(segment).id();
        byte[] prefix = journalImage(declaredRecords);
        var manifest = new CheckpointManifest(DATABASE, 17,
                new CheckpointManifest.JournalPrefix(declaredRecords.size(), prefix.length, ObjectReference.forBytes(prefix).id().value()),
                List.of(new CheckpointManifest.SegmentReference(segmentId, segment.length, 1, declaredRecords.size())));
        byte[] manifestImage = ManifestCodec.encode(manifest);
        var head = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3),
                ObjectCheckpointHeadVerifier.lineageId(DATABASE), 1, ObjectReference.forBytes(manifestImage), manifest.journalPrefix(), null);
        var store = new Store();
        store.images.put(ObjectCheckpointGraphs.manifestKey(NAMESPACE, head.manifest().id()), manifestImage);
        store.images.put(ObjectCheckpointGraphs.segmentKey(NAMESPACE, segmentId), segment);
        return new Fixture(head, store);
    }
    private static byte[] journalImage(List<JournalRecord> records) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(JournalCodec.encodeHeader(DATABASE));
        records.forEach(record -> output.writeBytes(JournalCodec.encodeRecord(record)));
        return output.toByteArray();
    }
    private static JournalRecord record(long sequence, long value, boolean extra) {
        var operations = new ArrayList<WriteOperation>();
        operations.add(new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(null, null), value));
        if (extra) { operations.add(new WriteOperation.Put(new EntityId("allowance"), new ValidTimeInterval(null, null), value)); }
        return new JournalRecord(sequence, new Transaction(new RequestId("request-" + sequence), operations));
    }
    private static final class Store implements ImmutableObjectStore {
        final Map<ObjectKey, byte[]> images = new HashMap<>();
        final List<VerifiedObject> snapshots = new ArrayList<>();
        boolean substitute;
        IOException failure;
        @Override public ObjectReference createNew(ObjectKey key, byte[] image) { throw new AssertionError("Verification must not write"); }
        @Override public VerifiedObject read(ObjectKey key, ObjectReference expected) throws IOException {
            if (failure != null) { throw failure; }
            byte[] image = substitute ? new byte[] {1} : images.get(key);
            var snapshot = VerifiedObject.verify(image, substitute ? ObjectReference.forBytes(image) : expected);
            snapshots.add(snapshot);
            return snapshot;
        }
    }
    private static final class MemoryFile implements LocalJournal.FileAccess {
        final byte[] image;
        final List<Long> reads = new ArrayList<>();
        long maxEnd = Long.MAX_VALUE;
        IOException failure;
        MemoryFile(byte[] image) { this.image = image.clone(); }
        @Override public int read(ByteBuffer target, long offset) throws IOException {
            if (failure != null) { throw failure; }
            long end = offset + target.remaining();
            reads.add(end);
            if (end > maxEnd) { throw new AssertionError("Read exceeds the permitted frame/header boundary"); }
            if (offset >= image.length) { return -1; }
            int count = Math.min(target.remaining(), image.length - Math.toIntExact(offset));
            target.put(image, Math.toIntExact(offset), count);
            return count;
        }
        @Override public int write(ByteBuffer source, long offset) { throw new AssertionError("Unexpected write"); }
        @Override public long size() { return image.length; }
        @Override public void force() { throw new AssertionError("Unexpected force"); }
        @Override public void truncate(long length) { throw new AssertionError("Unexpected truncate"); }
        @Override public void close() { }
    }
}
