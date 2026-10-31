// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.object.LocalObjectStore;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.storage.segment.SegmentObjects;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Run after verification. An optional existing directory retains successful output. */
class ObjectStoreExample {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) { throw new IllegalArgumentException("Usage: ObjectStoreExample.java [existing-directory]"); }
        Path temporary = args.length == 0 ? Files.createTempDirectory("meridiandb-object-") : null;
        Path directory = temporary == null ? Path.of(args[0]) : temporary;
        var store = new LocalObjectStore(directory);
        var key = new ObjectKey("history.segment");
        var entity = new EntityId("salary");
        var segment = new ImmutableSegment(new DatabaseId("object-example"), List.of(
                new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(10L, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(20L, 30L), 120))))));
        var first = SegmentObjects.createOrVerify(store, key, segment);
        var again = SegmentObjects.createOrVerify(store, key, segment);
        if (again.created() || !first.reference().equals(again.reference())) { throw new AssertionError("Reuse differs"); }
        var reference = first.reference();
        var recovered = SegmentObjects.read(store, key, new SegmentId(reference.id().value()), reference.byteLength());
        if (!recovered.segment().equals(segment)) { throw new AssertionError("History differs"); }
        byte[] header;
        try (var reader = store.read(key, reference)) { header = reader.readRange(0, 8); }
        if (header.length != 8) { throw new AssertionError("Range differs"); }
        System.out.println("Verified original transactions: " + recovered.segment().records().size());
        System.out.println("Identical object reused: " + !again.created());
        System.out.println("Header bytes retained after reader close: " + header.length);
        // Only the successful example cleans up its own temporary files. Failed candidates remain.
        if (temporary != null) { Files.delete(directory.resolve(key.value())); Files.delete(temporary); }
    }
}
