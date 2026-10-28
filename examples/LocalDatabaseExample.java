// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.util.List;
import java.util.OptionalLong;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Local forced writes, pinned history and retry after reopen; no device power-loss simulation. */
class LocalDatabaseExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) { throw new IllegalArgumentException("Usage: LocalDatabaseExample.java"); }
        var directory = Files.createTempDirectory("meridiandb-database-");
        var salary = new EntityId("salary");
        var original = new Transaction(new RequestId("initial"), List.of(
                new WriteOperation.Put(salary, new ValidTimeInterval(10L, null), 100)));
        var correction = new Transaction(new RequestId("correction"), List.of(
                new WriteOperation.Put(salary, new ValidTimeInterval(20L, 30L), 120)));
        final LocalDatabase created;
        try { created = LocalDatabase.create(directory, new DatabaseId("example")); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            Files.delete(directory);
            System.out.println("Local writes unavailable: directory open/force is unsupported.");
            return;
        }
        try (var database = created) {
            var first = database.transact(original);
            try (var pinned = database.openView(ReadCutoff.Head.INSTANCE)) {
                var second = database.transact(correction);
                var query = new TemporalQuery.Get(salary, 25);
                if (first.position().sequence() != 1 || second.position().sequence() != 2
                        || !pinned.query(query).equals(new TemporalResult.Point(OptionalLong.of(100)))) {
                    throw new AssertionError("Pinned history changed");
                }
                System.out.println("Forced commits: 1, 2; pinned salary before correction: 100");
            }
        }
        try (var database = LocalDatabase.openWritable(directory)) {
            var retry = database.transact(correction);
            try (var current = database.openView(ReadCutoff.Head.INSTANCE)) {
                if (!retry.replayed() || retry.position().sequence() != 2 || database.head().sequence() != 2
                        || !current.query(new TemporalQuery.Get(salary, 25))
                            .equals(new TemporalResult.Point(OptionalLong.of(120)))) {
                    throw new AssertionError("Reopened write or retry differs");
                }
            }
            System.out.println("Reopened salary: 120; identical retry returns commit 2 without another append");
        }
        // Only this example's successful temporary output is removed after all owners close.
        // Failures retain diagnostic storage; LocalDatabase never reclaims files automatically.
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) { Files.delete(file); }
        }
        Files.delete(directory);
    }
}
