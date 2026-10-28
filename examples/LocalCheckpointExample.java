// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.util.List;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Local checkpoint publication and exact reopen; no engine integration or power-loss claim. */
class LocalCheckpointExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("Usage: LocalCheckpointExample.java");
        }
        var directory = Files.createTempDirectory("meridiandb-checkpoint-");
        var database = new DatabaseId("example");
        var entity = new EntityId("salary");
        var segment = new ImmutableSegment(database, List.of(
                new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(10L, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(20L, 30L), 120))))));
        final LocalCheckpointStore created;
        try {
            created = LocalCheckpointStore.open(directory);
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unavailable) {
            // This capability gate runs before any store file is created.
            Files.delete(directory);
            System.out.println("Local checkpoint publication unavailable: directory open/force is unsupported.");
            return;
        }
        try (var store = created; var journal = LocalJournal.create(store.journalPath(), database)) {
            for (var record : segment.records()) { journal.append(record); }
            var id = store.writeSegment(segment);
            var prefix = journal.prefix(2);
            var manifest = new CheckpointManifest(database, 1,
                    new CheckpointManifest.JournalPrefix(prefix.sequence(), prefix.byteLength(), prefix.sha256()),
                    List.of(new CheckpointManifest.SegmentReference(id, SegmentCodec.encode(segment).length, 1, 2)));
            var published = store.publish(manifest, journal);
            try (var checkpoint = store.readLatest(journal).orElseThrow(); var reader = checkpoint.openSegment(0)) {
                if (!checkpoint.manifestId().equals(published) || !reader.segment().equals(segment)) {
                    throw new AssertionError("Published checkpoint differs");
                }
            }
            System.out.println("Published generation 1 through transaction 2: " + published.value());
        }
        try (var store = LocalCheckpointStore.open(directory); var journal = LocalJournal.open(store.journalPath());
                var checkpoint = store.readLatest(journal).orElseThrow(); var reader = checkpoint.openSegment(0)) {
            if (checkpoint.manifest().generation() != 1 || !reader.segment().equals(segment)) {
                throw new AssertionError("Reopened checkpoint history differs");
            }
            System.out.println("Reopened exact history: " + reader.segment().records().size() + " transactions");
        }
        // Only this example's successful temporary output is removed, after every handle is closed.
        // Failed runs retain their files for inspection; the store itself never reclaims objects.
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) { Files.delete(file); }
        }
        Files.delete(directory);
    }
}
