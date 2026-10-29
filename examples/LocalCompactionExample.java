// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.HistoryCompactor;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Install and publish an exact-history replacement, then verify reuse and local reopen. */
class LocalCompactionExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) { throw new IllegalArgumentException("Usage: LocalCompactionExample.java"); }
        var directory = Files.createTempDirectory("meridiandb-compaction-");
        var database = new DatabaseId("example");
        var entity = new EntityId("salary");
        var records = List.of(
                new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(10L, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(20L, 30L), 120)))));
        final LocalCheckpointStore created;
        try { created = LocalCheckpointStore.open(directory); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unavailable) {
            Files.delete(directory);
            System.out.println("Local compaction unavailable: directory open/force is unsupported.");
            return;
        }
        try (var store = created; var journal = LocalJournal.create(store.journalPath(), database)) {
            var references = new ArrayList<CheckpointManifest.SegmentReference>();
            var images = new HashMap<SegmentId, byte[]>();
            for (var record : records) {
                journal.append(record);
                var segment = new ImmutableSegment(database, List.of(record));
                var id = store.writeSegment(segment);
                byte[] image = SegmentCodec.encode(segment);
                images.put(id, image);
                references.add(new CheckpointManifest.SegmentReference(id, image.length, record.sequence(), record.sequence()));
            }
            var prefix = journal.prefix(2);
            var source = new CheckpointManifest(database, 1,
                    new CheckpointManifest.JournalPrefix(2, prefix.byteLength(), prefix.sha256()), references);
            store.publish(source, journal);
            var plan = HistoryCompactor.compact(source, images::get, HistoryCompactor.Limits.DEFAULT);
            store.publishCompaction(plan, journal);
            try (var checkpoint = store.readLatest(journal).orElseThrow(); var reader = checkpoint.openSegment(0)) {
                if (!reader.segment().records().equals(records)) { throw new AssertionError("Compacted history differs"); }
                images.clear();
                images.put(checkpoint.manifest().segments().getFirst().id(), SegmentCodec.encode(reader.segment()));
                var unchanged = HistoryCompactor.compact(checkpoint.manifest(), images::get, HistoryCompactor.Limits.DEFAULT);
                store.publishCompaction(unchanged, journal); // Verify existing content; publish generation three.
            }
            if (journal.appendedSequence() != 2) { throw new AssertionError("Compaction changed the journal"); }
            System.out.println("Published compacted generation 2: 2 segments -> 1; cutoff 2");
            System.out.println("Verified unchanged-layout reuse in generation 3; old files retained");
        }
        try (var store = LocalCheckpointStore.open(directory); var journal = LocalJournal.open(store.journalPath());
                var checkpoint = store.readLatest(journal).orElseThrow(); var reader = checkpoint.openSegment(0)) {
            if (checkpoint.manifest().generation() != 3 || !reader.segment().records().equals(records)) {
                throw new AssertionError("Reopened history differs");
            }
            System.out.println("Reopened exact history: 2 transactions; no objects reclaimed");
        }
        // Only this successful example's temporary output is removed after all handles close.
        // The storage API retains every old/failed object; failed examples retain their evidence.
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) { Files.delete(file); }
        }
        Files.delete(directory);
    }
}
