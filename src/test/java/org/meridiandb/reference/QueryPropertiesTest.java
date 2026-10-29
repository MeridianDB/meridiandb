// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.meridiandb.engine.InMemoryEngine;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
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
import org.meridiandb.temporal.ValuePredicate.Operator;
import org.meridiandb.temporal.WriteOperation;

/** Forward cell assignment independently checks resolved-value predicates and clipped intervals. */
@Timeout(90)
class QueryPropertiesTest {
    private static final int LOW = -8;
    private static final int HIGH = 9;
    private static final int GRID_OFFSET = 2;
    // Deliberately not sorted. Distinct composed/decomposed strings, a zero code unit
    // and a lone surrogate make UTF-16 identity/order observable through actual I/O.
    private static final List<EntityId> WRITABLE_ENTITIES = List.of(new EntityId("z"),
            new EntityId("é"), new EntityId("e" + (char) 0x0301),
            new EntityId(String.valueOf((char) 0xd800)), new EntityId("A\u0000"));
    private static final List<EntityId> SELECTION = List.of(WRITABLE_ENTITIES.get(3),
            WRITABLE_ENTITIES.get(1), new EntityId("always-absent"), WRITABLE_ENTITIES.get(0),
            WRITABLE_ENTITIES.get(4), WRITABLE_ENTITIES.get(2));
    private static final long[] THRESHOLDS = {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE};
    private static final long[] POINTS = samplePoints();

    @TempDir
    Path directory;

    static Stream<Long> seeds() throws IOException { return ReferencePropertiesTest.seeds(); }

    @ParameterizedTest(name = "memory filtered-query seed={0}")
    @MethodSource("seeds")
    void memoryAndReferenceMatchIndependentForwardCells(long seed) throws IOException {
        try (var target = Target.memory()) { checkHistory(seed, target); }
    }

    @ParameterizedTest(name = "durable filtered-query seed={0}")
    @MethodSource("seeds")
    void writableReopenAndCheckpointQueriesMatchReferenceAndForwardCells(long seed) throws IOException {
        Path home = Files.createDirectory(directory.resolve("query-seed-" + seed));
        try (var target = Target.durable(home, new DatabaseId("query-seed-" + seed))) {
            checkHistory(seed, target);
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            // This method is separate from the memory/reference method: a provider
            // capability skip cannot remove independent semantic evidence for a seed.
            assumeTrue(false, "Directory force is unavailable: " + unsupported);
        }
    }

    private static void checkHistory(long seed, Target target) throws IOException {
        var history = transactions(seed);
        var model = new ReferenceModel(target.head().databaseId());
        var snapshots = new ArrayList<Long[][]>();
        snapshots.add(new Long[WRITABLE_ENTITIES.size()][POINTS.length]);
        var choices = new Random(seed ^ 0x51c0ffeedL);
        for (int step = 0; step < history.size(); step++) {
            var request = history.get(step);
            int previous = step;
            int currentStep = step;
            var pinnedQuery = new TemporalQuery.Select(SELECTION, 0, new ValuePredicate(Operator.NE, 0));
            var pinnedExpected = selected(snapshots.get(previous), pointIndex(0), Operator.NE, 0);
            try (var pinned = target.view(previous)) {
                var dense = copy(snapshots.getLast());
                apply(dense, request);
                var committed = target.transact(request);
                var referenceCommit = model.transact(request);
                assertEquals(referenceCommit, committed, () -> context(seed, currentStep, previous, history));
                assertFalse(committed.replayed());
                assertEquals(step + 1L, committed.position().sequence());
                snapshots.add(dense);
                assertEquals(pinnedExpected, pinned.query(pinnedQuery), "A write cannot change a pinned older view");
                sampleQueries(target, model, snapshots.getLast(), step + 1, step, seed, history, choices);
                target.reopen(step + 1);
                if (target.isDurable()) {
                    assertThrows(IllegalStateException.class, () -> pinned.query(pinnedQuery),
                            "Closing the durable owner invalidates its old views");
                    assertEquals(Optional.of(new CommitPosition(target.head().databaseId(), step + 1 >= 16 ? 8 : 0)),
                            target.checkpointPosition());
                } else {
                    assertEquals(pinnedExpected, pinned.query(pinnedQuery));
                }
            }
            int historical = choices.nextInt(snapshots.size());
            sampleQueries(target, model, snapshots.get(historical), historical, step, seed, history, choices);
            int retried = choices.nextInt(step + 1);
            var expectedRetry = model.transact(history.get(retried));
            assertEquals(expectedRetry, target.transact(history.get(retried)),
                    "A persisted retry keeps its original position and allocates no new state");
            assertTrue(expectedRetry.replayed());
            assertEquals(model.head(), target.head());
            assertEquals(Optional.of(expectedRetry.position()), target.lookupRequest(history.get(retried).requestId()));
            assertEquals(step + 1L, target.head().sequence());
        }
        for (int cutoff : new int[] {0, 1, 8, 16, 24, 32}) {
            finalSweep(target, model, snapshots.get(cutoff), cutoff, seed, history);
        }
    }

    private static void sampleQueries(Target target, ReferenceModel model, Long[][] dense, int cutoff,
            int step, long seed, List<Transaction> history, Random choices) {
        try (var actual = target.view(cutoff)) {
            for (int sample = 0; sample < 2; sample++) {
                Operator operator = Operator.values()[(step + sample * 3) % Operator.values().length];
                long threshold = THRESHOLDS[choices.nextInt(THRESHOLDS.length)];
                int point = choices.nextInt(POINTS.length);
                var selection = new TemporalQuery.Select(SELECTION, POINTS[point], new ValuePredicate(operator, threshold));
                compare(target, model, actual, selected(dense, point, operator, threshold), selection,
                        cutoff, seed, step, history);
                int entity = choices.nextInt(WRITABLE_ENTITIES.size());
                int from = LOW + choices.nextInt(HIGH - LOW);
                int to = from + 1 + choices.nextInt(HIGH - from);
                var scan = new TemporalQuery.FilterScan(WRITABLE_ENTITIES.get(entity),
                        new ValidTimeInterval((long) from, (long) to), new ValuePredicate(operator, threshold));
                compare(target, model, actual, filtered(dense[entity], from, to, operator, threshold), scan,
                        cutoff, seed, step, history);
            }
        }
    }

    private static void finalSweep(Target target, ReferenceModel model, Long[][] dense, int cutoff,
            long seed, List<Transaction> history) {
        try (var actual = target.view(cutoff)) {
            for (var operator : Operator.values()) {
                for (int thresholdIndex = 0; thresholdIndex < THRESHOLDS.length; thresholdIndex++) {
                    long threshold = THRESHOLDS[thresholdIndex];
                    var predicate = new ValuePredicate(operator, threshold);
                    for (int point : new int[] {0, GRID_OFFSET + (cutoff + thresholdIndex) % (HIGH - LOW), POINTS.length - 1}) {
                        var query = new TemporalQuery.Select(SELECTION, POINTS[point], predicate);
                        compare(target, model, actual, selected(dense, point, operator, threshold), query,
                                cutoff, seed, 32, history);
                    }
                    int entity = (operator.ordinal() + thresholdIndex + cutoff) % WRITABLE_ENTITIES.size();
                    int from = thresholdIndex % 2 == 0 ? LOW : -5;
                    int to = thresholdIndex % 2 == 0 ? HIGH : 6;
                    var query = new TemporalQuery.FilterScan(WRITABLE_ENTITIES.get(entity),
                            new ValidTimeInterval((long) from, (long) to), predicate);
                    compare(target, model, actual, filtered(dense[entity], from, to, operator, threshold), query,
                            cutoff, seed, 32, history);
                }
                // The independent history/BigInteger reference supplies the whole-domain
                // partition; the dense oracle separately checks both extreme point values.
                int entity = (operator.ordinal() + cutoff) % WRITABLE_ENTITIES.size();
                for (var window : List.of(new ValidTimeInterval(null, null),
                        new ValidTimeInterval(Long.MIN_VALUE, Long.MAX_VALUE))) {
                    var query = new TemporalQuery.FilterScan(WRITABLE_ENTITIES.get(entity), window,
                            new ValuePredicate(operator, 0));
                    var expected = model.query(new CommitPosition(model.head().databaseId(), cutoff), query);
                    assertEquals(expected, actual.query(query), () -> target.label() + "/unbounded/" + query
                            + "/" + context(seed, 32, cutoff, history));
                }
            }
        }
    }

    private static void compare(Target target, ReferenceModel model, Handle actual, TemporalResult expected,
            TemporalQuery query, int cutoff, long seed, int step, List<Transaction> history) {
        var position = new CommitPosition(model.head().databaseId(), cutoff);
        assertEquals(expected, model.query(position, query),
                () -> "reference/" + query + "/" + context(seed, step, cutoff, history));
        assertEquals(expected, actual.query(query),
                () -> target.label() + "/" + query + "/" + context(seed, step, cutoff, history));
    }

    private static List<Transaction> transactions(long seed) {
        var random = new Random(seed);
        var history = new ArrayList<Transaction>();
        for (int step = 0; step < 32; step++) {
            var operations = new ArrayList<WriteOperation>();
            for (int operation = 0, count = 1 + random.nextInt(3); operation < count; operation++) {
                var entity = WRITABLE_ENTITIES.get(random.nextInt(WRITABLE_ENTITIES.size()));
                long start = LOW + random.nextInt(HIGH - LOW);
                long end = start + 1 + random.nextInt(HIGH - (int) start);
                var interval = switch (random.nextInt(12)) {
                    case 0 -> new ValidTimeInterval(null, null);
                    case 1 -> new ValidTimeInterval(Long.MIN_VALUE, end);
                    case 2 -> new ValidTimeInterval(start, null);
                    case 3 -> new ValidTimeInterval(Long.MAX_VALUE, null);
                    case 4 -> new ValidTimeInterval(null, Long.MAX_VALUE);
                    default -> new ValidTimeInterval(start, end);
                };
                long value = switch ((step + operation) % 8) {
                    case 0 -> Long.MIN_VALUE;
                    case 1 -> Long.MAX_VALUE;
                    default -> random.nextInt(7) - 3;
                };
                operations.add(random.nextInt(4) == 0 ? new WriteOperation.Retract(entity, interval)
                        : new WriteOperation.Put(entity, interval, value));
            }
            history.add(new Transaction(new RequestId("query-request-" + step), operations));
        }
        return history;
    }

    private static Long[][] copy(Long[][] source) {
        var result = new Long[source.length][];
        for (int entity = 0; entity < source.length; entity++) { result[entity] = source[entity].clone(); }
        return result;
    }

    private static void apply(Long[][] state, Transaction transaction) {
        // Forward assignment, without production predicates, interval splitting,
        // backward history search or ValidTimeInterval.contains().
        for (var operation : transaction.operations()) {
            int entity = WRITABLE_ENTITIES.indexOf(operation.entity());
            Long from = operation.interval().fromInclusiveMicros();
            Long to = operation.interval().toExclusiveMicros();
            for (int point = 0; point < POINTS.length; point++) {
                long instant = POINTS[point];
                if ((from == null || instant >= from) && (to == null || instant < to)) {
                    state[entity][point] = operation instanceof WriteOperation.Put put ? put.value() : null;
                }
            }
        }
    }

    private static TemporalResult.Rows selected(Long[][] cells, int point, Operator operator, long threshold) {
        var rows = new ArrayList<EntityValue>();
        for (int entity = 0; entity < WRITABLE_ENTITIES.size(); entity++) {
            Long value = cells[entity][point];
            if (value != null && accepts(operator, value, threshold)) {
                rows.add(new EntityValue(WRITABLE_ENTITIES.get(entity), value));
            }
        }
        rows.sort((a, b) -> a.entity().value().compareTo(b.entity().value()));
        return new TemporalResult.Rows(rows);
    }

    private static TemporalResult.Segments filtered(Long[] cells, int from, int to, Operator operator, long threshold) {
        var result = new ArrayList<TemporalSegment>();
        for (int start = from; start < to;) {
            Long value = cells[pointIndex(start)];
            if (value == null || !accepts(operator, value, threshold)) { start++; continue; }
            int end = start + 1;
            while (end < to && Objects.equals(value, cells[pointIndex(end)])) { end++; }
            result.add(new TemporalSegment(new ValidTimeInterval((long) start, (long) end), value));
            start = end;
        }
        return new TemporalResult.Segments(result);
    }

    private static boolean accepts(Operator operator, long value, long operand) {
        // Explicit scalar comparisons avoid both production matches() and subtract/overflow logic.
        return switch (operator) {
            case EQ -> value == operand;
            case NE -> value != operand;
            case LT -> value < operand;
            case LE -> value <= operand;
            case GT -> value > operand;
            case GE -> value >= operand;
        };
    }

    private static long[] samplePoints() {
        long[] points = new long[HIGH - LOW + 4];
        points[0] = Long.MIN_VALUE;
        points[1] = Long.MIN_VALUE + 1;
        for (int instant = LOW; instant < HIGH; instant++) { points[GRID_OFFSET + instant - LOW] = instant; }
        points[points.length - 2] = Long.MAX_VALUE - 1;
        points[points.length - 1] = Long.MAX_VALUE;
        return points;
    }

    private static int pointIndex(int instant) { return GRID_OFFSET + instant - LOW; }
    private static String context(long seed, int step, int cutoff, List<Transaction> history) {
        return "seed=" + seed + " step=" + step + " cutoff=" + cutoff + " history=" + history;
    }

    private interface Handle extends AutoCloseable {
        TemporalResult query(TemporalQuery query);
        @Override void close();
    }

    private static final class Target implements AutoCloseable {
        private final InMemoryEngine memory;
        private final Path directory;
        private LocalDatabase durable;
        private Target(InMemoryEngine memory, Path directory, LocalDatabase durable) {
            this.memory = memory;
            this.directory = directory;
            this.durable = durable;
        }
        static Target memory() { return new Target(new InMemoryEngine(), null, null); }
        static Target durable(Path path, DatabaseId database) throws IOException {
            return new Target(null, path, LocalDatabase.create(path, database));
        }
        boolean isDurable() { return directory != null; }
        String label() { return isDurable() ? "durable" : "memory"; }
        CommitPosition head() { return isDurable() ? durable.head() : memory.head(); }
        Optional<CommitPosition> checkpointPosition() { return durable.checkpointPosition(); }
        Optional<CommitPosition> lookupRequest(RequestId request) {
            return isDurable() ? durable.lookupRequest(request) : memory.lookupRequest(request);
        }
        CommitResult transact(Transaction transaction) throws IOException {
            return isDurable() ? durable.transact(transaction) : memory.transact(transaction);
        }
        Handle view(long cutoff) {
            var position = new CommitPosition(head().databaseId(), cutoff);
            if (isDurable()) {
                var view = durable.openView(new ReadCutoff.At(position));
                return new Handle() {
                    @Override public TemporalResult query(TemporalQuery query) { return view.query(query); }
                    @Override public void close() { view.close(); }
                };
            }
            var view = memory.openView(new ReadCutoff.At(position));
            return new Handle() {
                @Override public TemporalResult query(TemporalQuery query) { return view.query(query); }
                @Override public void close() { view.close(); }
            };
        }
        void reopen(int head) throws IOException {
            if (!isDurable()) { return; }
            durable.close();
            durable = null;
            if (head == 16) { LocalDurableFixtureTest.publishCheckpoint(directory, 8); }
            durable = LocalDatabase.openWritable(directory);
        }
        @Override public void close() throws IOException {
            if (memory != null) { memory.close(); }
            if (durable != null) { durable.close(); }
        }
    }
}
