// SPDX-License-Identifier: Apache-2.0
import java.util.List;
import org.meridiandb.engine.InMemoryEngine;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Run with JDK 25 after Maven verification; this database exists only in memory. */
public class EmbeddedExample {
    public static void main(String[] args) {
        long january = 1767225600000000L; // 2026-01-01 midnight UTC, epoch microseconds
        long february = 1769904000000000L;
        var alice = new EntityId("salary/alice");
        var salaryInFebruary = new TemporalQuery.Get(alice, february);
        try (var engine = new InMemoryEngine()) {
            engine.transact(new Transaction(new RequestId("initial-salary"), List.of(
                    new WriteOperation.Put(alice, new ValidTimeInterval(january, null), 100))));
            try (var before = engine.openView(ReadCutoff.Head.INSTANCE)) {
                var correction = new Transaction(new RequestId("salary-correction"), List.of(
                        new WriteOperation.Put(alice, new ValidTimeInterval(february, null), 120)));
                engine.transact(correction);
                try (var after = engine.openView(ReadCutoff.Head.INSTANCE)) {
                    var oldSalary = (TemporalResult.Point) before.query(salaryInFebruary);
                    var correctedSalary = (TemporalResult.Point) after.query(salaryInFebruary);
                    System.out.println("February salary before correction: " + oldSalary.value().orElseThrow());
                    System.out.println("February salary after correction: " + correctedSalary.value().orElseThrow());
                    var retry = engine.transact(correction);
                    System.out.println("Identical retry: commit " + retry.position().sequence()
                            + ", replayed=" + retry.replayed());
                }
            }
        }
    }
}
