// SPDX-License-Identifier: Apache-2.0
import java.math.BigInteger;
import java.util.List;
import org.meridiandb.engine.InMemoryEngine;
import org.meridiandb.temporal.*;

/** Experimental typed queries; the same query values also work on LocalDatabase.ReadView. */
class QueryExample {
    public static void main(String[] args) {
        var alice = new EntityId("alice");
        var bob = new EntityId("bob");
        try (var database = new InMemoryEngine()) {
            database.transact(new Transaction(new RequestId("initial"), List.of(
                    new WriteOperation.Put(alice, new ValidTimeInterval(null, null), 100),
                    new WriteOperation.Put(bob, new ValidTimeInterval(null, null), 50))));
            try (var before = database.openView(ReadCutoff.Head.INSTANCE)) {
                database.transact(new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(alice, new ValidTimeInterval(10L, 20L), 120),
                        new WriteOperation.Retract(bob, new ValidTimeInterval(10L, 20L)))));
                try (var after = database.openView(ReadCutoff.Head.INSTANCE)) {
                    var selected = new TemporalQuery.Select(List.of(bob, alice), 15,
                            new ValuePredicate(ValuePredicate.Operator.GT, 75));
                    var oldRows = before.query(selected);
                    var currentRows = after.query(selected);
                    require(new TemporalResult.Rows(List.of(new EntityValue(alice, 100))), oldRows);
                    require(new TemporalResult.Rows(List.of(new EntityValue(alice, 120))), currentRows);
                    var intervals = after.query(new TemporalQuery.FilterScan(alice,
                            new ValidTimeInterval(0L, 30L), new ValuePredicate(ValuePredicate.Operator.GE, 110)));
                    require(new TemporalResult.Segments(List.of(
                            new TemporalSegment(new ValidTimeInterval(10L, 20L), 120))), intervals);
                    var sum = new TemporalQuery.Sum(List.of(alice, bob), 15);
                    require(new TemporalResult.Aggregate(2, BigInteger.valueOf(150)), before.query(sum));
                    require(new TemporalResult.Aggregate(1, BigInteger.valueOf(120)), after.query(sum));
                    System.out.println("Selected Alice before/after correction: 100 / 120");
                    System.out.println("Matching Alice intervals: [10, 20) = 120");
                    System.out.println("Sum before/after correction and retraction: 150 / 120");
                }
            }
        }
    }

    private static void require(Object expected, Object actual) {
        if (!expected.equals(actual)) { throw new AssertionError("Expected " + expected + "; got " + actual); }
    }
}
