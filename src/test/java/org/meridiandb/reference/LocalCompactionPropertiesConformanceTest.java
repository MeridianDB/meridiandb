// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.manifest.HistoryCompactor;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.CommitPosition;
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

/** Every seed uses actual writable storage and compares maintenance output to two independent oracles. */
@Timeout(120)
class LocalCompactionPropertiesConformanceTest {
    private static final int LOW = -8;
    private static final int HIGH = 9;
    private static final List<EntityId> ENTITIES = List.of(new EntityId("a"), new EntityId("b"), new EntityId("c"));
    private static final List<EntityId> SELECT_ENTITIES = List.of(ENTITIES.get(2), new EntityId("never-written"),
            ENTITIES.getFirst(), ENTITIES.get(1));
    private static final long[] THRESHOLDS = {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE};

    @TempDir
    Path directory;

    static Stream<Long> seeds() throws IOException { return ReferencePropertiesTest.seeds(); }

    @ParameterizedTest(name = "real compaction/removal, all historical cutoffs seed={0}")
    @MethodSource("seeds")
    void writableHistoriesRetainDenseQueriesAndExactIdentityThroughTailAndFullReclamation(long seed) throws IOException {
        Path home = Files.createDirectory(directory.resolve("seed-" + seed));
        var lineage = new DatabaseId("physical-compaction-seed-" + seed);
        var reference = new ReferenceModel(lineage);
        var random = new Random(seed);
        var snapshots = new ArrayList<Long[][]>();
        var records = new ArrayList<JournalRecord>();
        snapshots.add(new Long[ENTITIES.size()][HIGH - LOW]);
        LocalDatabase database = null;
        int cycles = 0;
        try {
            database = LocalDatabase.create(home, lineage);
            for (int step = 0; step < 32; step++) {
                var request = request(random, step);
                var previous = database.head();
                var previousDense = snapshots.getLast();
                var nextDense = copy(previousDense);
                applyDense(nextDense, request);
                try (var pinned = database.openView(ReadCutoff.Head.INSTANCE)) {
                    var result = database.transact(request);
                    assertEquals(reference.transact(request), result, context(seed, step, previous, records));
                    assertFalse(result.replayed());
                    assertEquals(step + 1L, result.position().sequence());
                    records.add(new JournalRecord(result.position().sequence(), request));
                    snapshots.add(nextDense);
                    checkQueries(pinned, reference, previous, previousDense, seed, "pinned-before-write", records);
                    checkView(database, reference, result.position(), nextDense, seed, "new-write", records);
                    if (step == 15 || step == 31) { checkAll(database, reference, snapshots, records, seed, "before", true); }
                    database.close();
                    database = null;
                    assertThrows(IllegalStateException.class, pinned::position);
                }
                if (step == 15 || step == 31) {
                    long cutoff = step == 15 ? 8 : 32;
                    int target = step == 15 ? 1024 : HistoryCompactor.Limits.DEFAULT.targetSegmentBytes();
                    var cycle = LocalCompactionConformanceSupport.cycle(home, cutoff, target, true,
                            (targetDatabase, stage, writable) -> checkAll(targetDatabase, reference, snapshots, records, seed, stage, writable));
                    assertEquals(cutoff, cycle.cutoff());
                    assertEquals(cutoff, cycle.sourceSegments());
                    assertTrue(cycle.outputSegments() < cutoff);
                    assertTrue(cycle.removedBytes() > 0);
                    cycles++;
                }
                database = LocalDatabase.openWritable(home);
                assertEquals(reference.head(), database.head());
                assertEquals(Optional.of(new CommitPosition(lineage, step < 15 ? 0 : step < 31 ? 8 : 32)),
                        database.checkpointPosition());
                checkView(database, reference, database.head(), nextDense, seed, "writable-reopen", records);
                // Keep the persisted-seed generator's historical/retry draws in the same order.
                int old = random.nextInt(snapshots.size());
                checkView(database, reference, new CommitPosition(lineage, old), snapshots.get(old), seed, "old-reopen", records);
                int retry = random.nextInt(records.size());
                var retried = records.get(retry);
                var beforeRetry = database.head();
                var actualReplay = database.transact(retried.transaction());
                assertEquals(reference.transact(retried.transaction()), actualReplay);
                assertTrue(actualReplay.replayed());
                assertEquals(retried.sequence(), actualReplay.position().sequence());
                assertEquals(beforeRetry, database.head());
                database.close();
                database = null;
                database = LocalDatabase.openWritable(home);
                assertEquals(beforeRetry, database.head());
                assertEquals(Optional.of(actualReplay.position()), database.lookupRequest(retried.transaction().requestId()));
            }
            assertEquals(32, records.size(), "Exactly 32 new transactions, with retries counted separately");
            assertEquals(2, cycles, "Both tail/bounded and full/default maintenance execute on the same history");
            assertEquals(records.getFirst().transaction().operations(), records.get(1).transaction().operations(),
                    "A distinct new identity for an unchanged state is retained as commit two");
            assertFalse(records.getFirst().transaction().requestId().equals(records.get(1).transaction().requestId()));
            checkAll(database, reference, snapshots, records, seed, "final", true);
            database.close();
            database = null;
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unavailable) {
            assumeTrue(false, "Required directory synchronization unavailable: " + unavailable);
            return;
        } finally { if (database != null) { database.close(); } }
        var expectedJournal = new ByteArrayOutputStream();
        expectedJournal.writeBytes(JournalCodec.encodeHeader(lineage));
        records.forEach(record -> expectedJournal.writeBytes(JournalCodec.encodeRecord(record)));
        assertArrayEquals(expectedJournal.toByteArray(), Files.readAllBytes(home.resolve("transactions.journal")),
                "All 32 original frames survive actual reclamation and retries unchanged");
    }

    private static void checkAll(LocalDatabase database, ReferenceModel reference, List<Long[][]> snapshots,
                                 List<JournalRecord> records, long seed, String stage, boolean writable) throws IOException {
        assertEquals(reference.head(), database.head(), stage + "/stored lineage and head");
        assertEquals(records.size() + 1, snapshots.size());
        for (int cutoff = 0; cutoff < snapshots.size(); cutoff++) {
            checkView(database, reference, new CommitPosition(reference.head().databaseId(), cutoff),
                    snapshots.get(cutoff), seed, stage, records);
        }
        LocalCompactionConformanceSupport.checkIdentity(database, records, "seed=" + seed + "/" + stage, writable);
    }

    private static void checkView(LocalDatabase database, ReferenceModel reference, CommitPosition position,
                                  Long[][] dense, long seed, String stage, List<JournalRecord> records) {
        try (var view = database.openView(new ReadCutoff.At(position))) {
            checkQueries(view, reference, position, dense, seed, stage, records);
        }
    }

    private static void checkQueries(LocalDatabase.ReadView view, ReferenceModel reference, CommitPosition position,
                                     Long[][] dense, long seed, String stage, List<JournalRecord> records) {
        Supplier<String> label = () -> stage + "/" + context(seed, records.size(), position, records);
        assertEquals(position, view.position(), label);
        for (int entity = 0; entity < ENTITIES.size(); entity++) {
            for (int instant = LOW; instant < HIGH; instant++) {
                Long value = dense[entity][instant - LOW];
                compare(reference, view, position, new TemporalQuery.Get(ENTITIES.get(entity), instant),
                        new TemporalResult.Point(value == null ? OptionalLong.empty() : OptionalLong.of(value)), label);
            }
            compare(reference, view, position, new TemporalQuery.Scan(ENTITIES.get(entity), new ValidTimeInterval((long) LOW, (long) HIGH)),
                    denseSegments(dense[entity], null, 0), label);
            var unbounded = new TemporalQuery.Scan(ENTITIES.get(entity), new ValidTimeInterval(null, null));
            assertEquals(reference.query(position, unbounded), view.query(unbounded), label);
            for (long edge : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
                var query = new TemporalQuery.Get(ENTITIES.get(entity), edge);
                assertEquals(reference.query(position, query), view.query(query), label);
            }
        }
        for (int instant = LOW; instant < HIGH; instant++) {
            long count = 0;
            var sum = BigInteger.ZERO;
            for (var cells : dense) {
                Long value = cells[instant - LOW];
                if (value != null) { count++; sum = sum.add(BigInteger.valueOf(value)); }
            }
            compare(reference, view, position, new TemporalQuery.Sum(ENTITIES, instant), new TemporalResult.Aggregate(count, sum), label);
        }
        for (var operator : Operator.values()) {
            long operand = THRESHOLDS[(operator.ordinal() + Math.toIntExact(position.sequence())) % THRESHOLDS.length];
            var predicate = new ValuePredicate(operator, operand);
            for (int instant : new int[] {LOW, 0, HIGH - 1}) {
                var rows = new ArrayList<EntityValue>();
                for (int entity = 0; entity < ENTITIES.size(); entity++) {
                    Long value = dense[entity][instant - LOW];
                    if (value != null && matches(operator, value, operand)) { rows.add(new EntityValue(ENTITIES.get(entity), value)); }
                }
                compare(reference, view, position, new TemporalQuery.Select(SELECT_ENTITIES, instant, predicate),
                        new TemporalResult.Rows(rows), label);
            }
            for (int entity = 0; entity < ENTITIES.size(); entity++) {
                compare(reference, view, position, new TemporalQuery.FilterScan(ENTITIES.get(entity),
                                new ValidTimeInterval((long) LOW, (long) HIGH), predicate),
                        denseSegments(dense[entity], operator, operand), label);
            }
        }
    }

    private static void compare(ReferenceModel reference, LocalDatabase.ReadView view, CommitPosition position,
                                TemporalQuery query, TemporalResult expected, Supplier<String> label) {
        assertEquals(expected, reference.query(position, query), () -> "dense/reference/" + label.get() + "/" + query);
        assertEquals(expected, view.query(query), () -> "dense/real/" + label.get() + "/" + query);
    }

    private static Transaction request(Random random, int step) {
        var operations = new ArrayList<WriteOperation>();
        if (step <= 2) {
            long value = step < 2 ? Long.MAX_VALUE : Long.MIN_VALUE;
            for (var entity : ENTITIES) { operations.add(new WriteOperation.Put(entity, new ValidTimeInterval(null, null), value)); }
        } else {
            for (int operation = 0, count = 1 + random.nextInt(3); operation < count; operation++) {
                var entity = ENTITIES.get(random.nextInt(ENTITIES.size()));
                long start = LOW + random.nextInt(HIGH - LOW);
                long end = start + 1 + random.nextInt(HIGH - (int) start);
                Long from = switch (random.nextInt(8)) { case 0 -> null; case 1 -> Long.MIN_VALUE; default -> start; };
                Long to = switch (random.nextInt(8)) { case 0 -> null; case 1 -> Long.MAX_VALUE; default -> end; };
                var interval = new ValidTimeInterval(from, to);
                long value = switch (random.nextInt(16)) { case 0 -> Long.MIN_VALUE; case 1 -> Long.MAX_VALUE; default -> random.nextInt(7) - 3; };
                operations.add(random.nextInt(4) == 0 ? new WriteOperation.Retract(entity, interval)
                        : new WriteOperation.Put(entity, interval, value));
            }
        }
        return new Transaction(new RequestId("request-" + step), operations);
    }

    private static Long[][] copy(Long[][] input) {
        var result = new Long[input.length][];
        for (int entity = 0; entity < input.length; entity++) { result[entity] = input[entity].clone(); }
        return result;
    }

    private static void applyDense(Long[][] state, Transaction request) {
        // Forward integer-cell assignment, independent of interval splitting and history searches.
        for (var operation : request.operations()) {
            int entity = ENTITIES.indexOf(operation.entity());
            Long from = operation.interval().fromInclusiveMicros();
            Long to = operation.interval().toExclusiveMicros();
            for (int instant = LOW; instant < HIGH; instant++) {
                if ((from == null || instant >= from) && (to == null || instant < to)) {
                    state[entity][instant - LOW] = operation instanceof WriteOperation.Put put ? put.value() : null;
                }
            }
        }
    }

    private static TemporalResult.Segments denseSegments(Long[] cells, Operator operator, long operand) {
        var result = new ArrayList<TemporalSegment>();
        for (int start = 0; start < cells.length;) {
            Long value = cells[start];
            if (value == null || (operator != null && !matches(operator, value, operand))) { start++; continue; }
            int end = start + 1;
            while (end < cells.length && Objects.equals(value, cells[end])) { end++; }
            result.add(new TemporalSegment(new ValidTimeInterval((long) LOW + start, (long) LOW + end), value));
            start = end;
        }
        return new TemporalResult.Segments(result);
    }

    private static boolean matches(Operator operator, long value, long operand) {
        return switch (operator) {
            case EQ -> value == operand;
            case NE -> value != operand;
            case LT -> value < operand;
            case LE -> value <= operand;
            case GT -> value > operand;
            case GE -> value >= operand;
        };
    }

    private static String context(long seed, int step, CommitPosition position, List<JournalRecord> records) {
        return "seed=" + seed + " step=" + step + " cutoff=" + position.sequence() + " records=" + records;
    }
}
