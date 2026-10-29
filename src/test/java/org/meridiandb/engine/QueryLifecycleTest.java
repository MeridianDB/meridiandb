// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.EntityValue;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.ValuePredicate;
import org.meridiandb.temporal.WriteOperation;

@Timeout(30)
class QueryLifecycleTest {
    private static final EntityId A = new EntityId("a");
    private static final EntityId B = new EntityId("b");
    private static final ValidTimeInterval ALL = new ValidTimeInterval(null, null);
    private static final ValuePredicate PRESENT = new ValuePredicate(ValuePredicate.Operator.GE, Long.MIN_VALUE);

    @Test
    void predicatesUseResolvedStateAndPinnedRowsRemainOwnedAfterViewAndEngineClose() {
        var engine = new InMemoryEngine();
        engine.transact(both("initial", 7));
        var pinned = engine.openView(ReadCutoff.Head.INSTANCE);
        var select = new TemporalQuery.Select(List.of(B, A, new EntityId("missing")), 5,
                new ValuePredicate(ValuePredicate.Operator.NE, 0));
        var original = new TemporalResult.Rows(List.of(new EntityValue(A, 7), new EntityValue(B, 7)));
        var rows = pinned.query(select);
        assertEquals(original, rows);
        engine.transact(new Transaction(new RequestId("replace-and-retract"), List.of(
                new WriteOperation.Put(A, ALL, 0), new WriteOperation.Retract(B, ALL))));
        try (var current = engine.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(new TemporalResult.Rows(List.of()), current.query(select));
            assertEquals(new TemporalResult.Segments(List.of()), current.query(new TemporalQuery.FilterScan(A, ALL,
                    new ValuePredicate(ValuePredicate.Operator.EQ, 7))));
            assertEquals(original, pinned.query(select));
        }
        pinned.close();
        assertThrows(IllegalStateException.class, () -> pinned.query(select));
        engine.close();
        assertEquals(original, rows);
        assertThrows(UnsupportedOperationException.class, () -> ((TemporalResult.Rows) rows).values().clear());
    }

    @Test
    void filteredScansKeepRejectedAndAbsentIntervalsAsGapsAndClipCoordinateEdges() {
        try (var engine = new InMemoryEngine()) {
            engine.transact(new Transaction(new RequestId("history"), List.of(
                    new WriteOperation.Put(A, ALL, 7),
                    new WriteOperation.Put(A, new ValidTimeInterval(0L, 1L), -1),
                    new WriteOperation.Retract(A, new ValidTimeInterval(2L, 3L)))));
            var positive = new ValuePredicate(ValuePredicate.Operator.GT, 0);
            try (var view = engine.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(new TemporalResult.Segments(List.of(
                        segment(null, 0L, 7), segment(1L, 2L, 7), segment(3L, null, 7))),
                        view.query(new TemporalQuery.FilterScan(A, ALL, positive)));
                assertEquals(new TemporalResult.Segments(List.of(segment(Long.MAX_VALUE, null, 7))),
                        view.query(new TemporalQuery.FilterScan(A, new ValidTimeInterval(Long.MAX_VALUE, null), positive)));
                assertEquals(new TemporalResult.Segments(List.of(segment(Long.MIN_VALUE, -1L, 7))),
                        view.query(new TemporalQuery.FilterScan(A, new ValidTimeInterval(Long.MIN_VALUE, -1L), positive)));
            }
        }
    }

    @Test
    void selectionAndFilteredScansShareOneSnapshotDuringConcurrentAtomicWrites() throws Exception {
        try (var engine = new InMemoryEngine(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            engine.transact(both("initial", 0));
            try (var pinned = engine.openView(ReadCutoff.Head.INSTANCE)) {
                var barrier = new CyclicBarrier(2);
                var writer = executor.submit(() -> {
                    for (int i = 1; i <= 64; i++) {
                        barrier.await(10, TimeUnit.SECONDS);
                        engine.transact(both("write-" + i, i));
                        barrier.await(10, TimeUnit.SECONDS);
                    }
                    return null;
                });
                var reader = executor.submit(() -> {
                    var select = new TemporalQuery.Select(List.of(B, A), 0, PRESENT);
                    for (int i = 1; i <= 64; i++) {
                        barrier.await(10, TimeUnit.SECONDS);
                        try (var view = engine.openView(ReadCutoff.Head.INSTANCE)) {
                            var rows = (TemporalResult.Rows) view.query(select);
                            assertEquals(List.of(A, B), rows.values().stream().map(EntityValue::entity).toList());
                            long value = rows.values().getFirst().value();
                            assertEquals(value, rows.values().getLast().value());
                            assertEquals(view.position().sequence() - 1, value);
                            assertEquals(new TemporalResult.Segments(List.of(segment(null, null, value))),
                                    view.query(new TemporalQuery.FilterScan(A, ALL, PRESENT)));
                        }
                        assertEquals(new TemporalResult.Rows(List.of(new EntityValue(A, 0), new EntityValue(B, 0))),
                                pinned.query(select));
                        barrier.await(10, TimeUnit.SECONDS);
                    }
                    return null;
                });
                writer.get(20, TimeUnit.SECONDS);
                reader.get(20, TimeUnit.SECONDS);
            }
        }
    }

    private static Transaction both(String id, long value) {
        return new Transaction(new RequestId(id), List.of(
                new WriteOperation.Put(A, ALL, value), new WriteOperation.Put(B, ALL, value)));
    }

    private static TemporalSegment segment(Long from, Long to, long value) {
        return new TemporalSegment(new ValidTimeInterval(from, to), value);
    }
}
