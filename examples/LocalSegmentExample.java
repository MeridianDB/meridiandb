// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.LocalSegmentFile;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Run after Maven verification; an optional new path retains the segment for inspection. */
class LocalSegmentExample {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: LocalSegmentExample.java [new-segment-path]");
        }
        Path temporary = args.length == 0 ? Files.createTempDirectory("meridiandb-segment-") : null;
        Path path = temporary == null ? Path.of(args[0]) : temporary.resolve("history.segment");
        var entity = new EntityId("salary");
        var original = new ImmutableSegment(new DatabaseId("example"), List.of(
                new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(10L, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(20L, 30L), 120))))));
        var expected = LocalSegmentFile.writeNew(path, original);
        ImmutableSegment retained;
        try (var reader = LocalSegmentFile.open(path, expected)) {
            retained = reader.segment();
            if (!retained.equals(original) || !reader.id().equals(expected)) {
                throw new AssertionError("Recovered segment differs");
            }
            System.out.println("Verified segment ID: " + reader.id().value());
            System.out.println("Recovered range: " + retained.firstSequence() + ".." + retained.lastSequence());
        }
        System.out.println("Immutable transactions retained after close: " + retained.records().size());
        // The example cleans up its own temporary successful output; the adapter never deletes files.
        // A failed run leaves the candidate in place for inspection.
        if (temporary != null) {
            Files.delete(path);
            Files.delete(temporary);
        }
    }
}
