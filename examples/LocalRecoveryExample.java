// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.util.List;
import java.util.OptionalLong;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
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

/** Prepare storage primitives, then recover read-only temporal queries from checkpoint plus tail. */
class LocalRecoveryExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) { throw new IllegalArgumentException("Usage: LocalRecoveryExample.java"); }
        var directory = Files.createTempDirectory("meridiandb-recovery-");
        var lineage = new DatabaseId("example");
        var salary = new EntityId("salary");
        var history = List.of(
                new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                        new WriteOperation.Put(salary, new ValidTimeInterval(10L, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(salary, new ValidTimeInterval(20L, 30L), 120)))),
                new JournalRecord(3, new Transaction(new RequestId("retraction"), List.of(
                        new WriteOperation.Retract(salary, new ValidTimeInterval(22L, 24L))))));
        final LocalCheckpointStore created;
        try { created = LocalCheckpointStore.open(directory); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            Files.delete(directory);
            System.out.println("Local recovery unavailable: directory open/force is unsupported.");
            return;
        }
        try (var store = created; var journal = LocalJournal.create(store.journalPath(), lineage)) {
            for (var record : history.subList(0, 2)) { journal.append(record); }
            var segment = new ImmutableSegment(lineage, history.subList(0, 2));
            var id = store.writeSegment(segment);
            var prefix = journal.prefix(2);
            store.publish(new CheckpointManifest(lineage, 1,
                    new CheckpointManifest.JournalPrefix(2, prefix.byteLength(), prefix.sha256()),
                    List.of(new CheckpointManifest.SegmentReference(id, SegmentCodec.encode(segment).length, 1, 2))), journal);
            journal.append(history.get(2));
            journal.force();
        }
        try (var database = LocalDatabase.open(directory);
                var early = database.openView(new ReadCutoff.At(new CommitPosition(lineage, 1)));
                var current = database.openView(ReadCutoff.Head.INSTANCE)) {
            if (database.head().sequence() != 3 || database.checkpointPosition().orElseThrow().sequence() != 2
                    || !early.query(new TemporalQuery.Get(salary, 25)).equals(new TemporalResult.Point(OptionalLong.of(100)))
                    || !current.query(new TemporalQuery.Get(salary, 23)).equals(new TemporalResult.Point(OptionalLong.empty()))
                    || database.lookupRequest(new RequestId("retraction")).orElseThrow().sequence() != 3) {
                throw new AssertionError("Recovered temporal history differs");
            }
            System.out.println("Recovered checkpoint 2 plus journal tail to head 3 in lineage " + database.head().databaseId().value());
            System.out.println("Historical salary at commit 1: 100; current retracted interval: absent");
        }
        // Clean up only this example's successful temporary files, after all owners close.
        // A failed run retains diagnostic storage. The database never reclaims history.
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) { Files.delete(file); }
        }
        Files.delete(directory);
    }
}
