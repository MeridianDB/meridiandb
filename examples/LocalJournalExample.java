// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Run after Maven verification; an optional new path retains the journal for inspection. */
class LocalJournalExample {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: LocalJournalExample.java [new-journal-path]");
        }
        Path temporary = args.length == 0 ? Files.createTempDirectory("meridiandb-journal-") : null;
        Path path = temporary == null ? Path.of(args[0]) : temporary.resolve("transactions.journal");
        try {
            var transaction = new Transaction(new RequestId("salary-1"), List.of(
                    new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(10L, 20L), 100L)));
            try (var journal = LocalJournal.create(path, new DatabaseId("example"))) {
                journal.append(new JournalRecord(1, transaction));
                System.out.println("Appended sequence: " + journal.appendedSequence());
                System.out.println("Forced sequence before force: " + journal.forcedSequence());
                System.out.println("Forced sequence after force: " + journal.force());
            }
            try (var journal = LocalJournal.open(path)) {
                var records = new ArrayList<JournalRecord>();
                journal.replay(records::add);
                if (!records.equals(List.of(new JournalRecord(1, transaction)))) {
                    throw new AssertionError("Recovered transaction differs");
                }
                System.out.println("Recovered exact transactions: " + records.size());
            }
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(path);
                Files.delete(temporary);
            }
        }
    }
}
