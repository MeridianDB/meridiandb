// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.HistoryCompactor;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ReclamationInventory;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Inspect physical retention and reader deferral without deleting checkpoint objects. */
class ReclamationInventoryExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) { throw new IllegalArgumentException("Usage: ReclamationInventoryExample.java"); }
        var directory = Files.createTempDirectory("meridiandb-inventory-");
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
            System.out.println("Reclamation inventory unavailable: directory open/force is unsupported.");
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
            var sourceId = store.publish(source, journal);
            var plan = HistoryCompactor.compact(source, images::get, HistoryCompactor.Limits.DEFAULT);
            var selectedId = store.publishCompaction(plan, journal);
            try (var reader = store.readLatest(journal).orElseThrow()) {
                var deferred = store.inspectReclamation(Set.of(), journal);
                if (deferred.status() != ReclamationInventory.Status.DEFERRED_READERS
                        || reader.manifest().generation() != 2) {
                    throw new AssertionError("Live reader was not protected");
                }
                System.out.println("Live reader: inspection deferred");
            }
            var inventory = store.inspectReclamation(Set.of(), journal);
            if (inventory.status() != ReclamationInventory.Status.VERIFIED
                    || !inventory.selectedManifestId().orElseThrow().equals(selectedId)
                    || inventory.obsoleteManifests().size() != 1 || inventory.obsoleteSegments().size() != 2) {
                throw new AssertionError("Obsolete-object inventory differs");
            }
            var retained = store.inspectReclamation(Set.of(sourceId), journal);
            if (!retained.obsoleteManifests().isEmpty() || !retained.obsoleteSegments().isEmpty()) {
                throw new AssertionError("Explicit retained root lost its dependencies");
            }
            for (var file : inventory.obsoleteManifests()) {
                if (!Files.exists(directory.resolve(file.name()))) { throw new AssertionError("Preview removed metadata"); }
            }
            for (var file : inventory.obsoleteSegments()) {
                if (!Files.exists(directory.resolve(file.name()))) { throw new AssertionError("Preview removed a segment"); }
            }
            System.out.println("Selected-only inventory: 1 obsolete manifest, 2 obsolete segments");
            System.out.println("Explicit retained source: no obsolete objects; every file retained");
        }
        // Only this successful example's temporary output is removed after all handles close.
        // The storage API retains every old/failed object; failed examples retain their evidence.
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) { Files.delete(file); }
        }
        Files.delete(directory);
    }
}
