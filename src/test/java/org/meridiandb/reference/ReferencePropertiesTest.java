// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Forward dense-grid state checks the history-search reference through observable queries. */
class ReferencePropertiesTest {
    private static final int LOW = -8;
    private static final int HIGH = 9;
    private static final List<EntityId> ENTITIES = List.of(new EntityId("a"), new EntityId("b"), new EntityId("c"));

    static Stream<Long> seeds() throws IOException {
        String selected = System.getProperty("meridiandb.seed");
        if (selected != null) {
            return Stream.of(Long.parseLong(selected));
        }
        var input = ReferencePropertiesTest.class.getResourceAsStream("/reference-seeds.txt");
        if (input == null) {
            throw new IllegalStateException("Persisted seed corpus missing");
        }
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .map(Long::parseLong).toList().stream();
        }
    }

    @ParameterizedTest(name = "dense history seed={0}")
    @MethodSource("seeds")
    void matchesDenseSnapshotsAndPreservesEveryHistoricalPrefix(long seed) {
        var random = new Random(seed);
        var model = new ReferenceModel(new DatabaseId("seed-" + seed));
        var snapshots = new ArrayList<Long[][]>();
        var views = new ArrayList<CommitPosition>();
        var requests = new ArrayList<Transaction>();
        snapshots.add(new Long[ENTITIES.size()][HIGH - LOW]);
        views.add(model.openView(ReadCutoff.Head.INSTANCE));
        for (int step = 0; step < 32; step++) {
            var operations = new ArrayList<WriteOperation>();
            for (int i = 0, size = 1 + random.nextInt(3); i < size; i++) {
                var entity = ENTITIES.get(random.nextInt(ENTITIES.size()));
                long start = LOW + random.nextInt(HIGH - LOW);
                long end = start + 1 + random.nextInt(HIGH - (int) start);
                Long from = random.nextInt(5) == 0 ? null : start;
                Long to = random.nextInt(5) == 0 ? null : end;
                var interval = new ValidTimeInterval(from, to);
                long value = switch (random.nextInt(16)) {
                    case 0 -> Long.MIN_VALUE;
                    case 1 -> Long.MAX_VALUE;
                    default -> random.nextInt(7) - 3;
                };
                operations.add(random.nextInt(4) == 0 ? new WriteOperation.Retract(entity, interval)
                        : new WriteOperation.Put(entity, interval, value));
            }
            var request = new Transaction(new RequestId("request-" + step), operations);
            var expected = copy(snapshots.getLast());
            applyDense(expected, request);
            var committed = model.transact(request);
            requests.add(request);
            snapshots.add(expected);
            views.add(model.openView(ReadCutoff.Head.INSTANCE));
            assertEquals(step + 1L, committed.position().sequence(), "seed=" + seed);
            assertTrue(!committed.replayed(), "A new ID must commit, including a no-op");
            assertView(model, views.getLast(), expected, seed, step, requests);

            int old = random.nextInt(views.size());
            assertView(model, views.get(old), snapshots.get(old), seed, step, requests);
            int retry = random.nextInt(requests.size());
            var replay = model.transact(requests.get(retry));
            assertTrue(replay.replayed(), "seed=" + seed);
            assertEquals(views.get(retry + 1), replay.position());
            assertEquals(views.getLast(), model.head(), "Retry must not allocate a commit");
            assertEquals(replay.position(), model.lookupRequest(requests.get(retry).requestId()).orElseThrow());

            if (step % 7 == 0) {
                var changed = new ArrayList<>(request.operations());
                changed.add(new WriteOperation.Retract(ENTITIES.getFirst(), new ValidTimeInterval(null, null)));
                var conflict = new Transaction(request.requestId(), changed);
                assertEquals("REQUEST_ID_REUSED", assertThrows(ReferenceModel.Rejected.class,
                        () -> model.transact(conflict)).code());
                assertEquals(views.getLast(), model.head());
            }
            // Check state again after both replay and conflicting reuse.
            assertView(model, views.getLast(), expected, seed, step, requests);
        }
        for (int c = 0; c < views.size(); c++) {
            assertView(model, views.get(c), snapshots.get(c), seed, 32, requests);
        }
    }

    private static Long[][] copy(Long[][] source) {
        var result = new Long[source.length][];
        for (int e = 0; e < source.length; e++) {
            result[e] = source[e].clone();
        }
        return result;
    }

    private static void applyDense(Long[][] state, Transaction transaction) {
        // Forward assignment to each integer cell, without history lookup, splitting,
        // BigInteger boundary partitioning or ValidTimeInterval.contains().
        for (var operation : transaction.operations()) {
            int entity = ENTITIES.indexOf(operation.entity());
            Long from = operation.interval().fromInclusiveMicros();
            Long to = operation.interval().toExclusiveMicros();
            for (int t = LOW; t < HIGH; t++) {
                if ((from == null || t >= from) && (to == null || t < to)) {
                    state[entity][t - LOW] = operation instanceof WriteOperation.Put put ? put.value() : null;
                }
            }
        }
    }

    private static void assertView(ReferenceModel model, CommitPosition view, Long[][] expected,
                                   long seed, int step, List<Transaction> history) {
        for (int e = 0; e < ENTITIES.size(); e++) {
            var entity = ENTITIES.get(e);
            for (int t = LOW; t < HIGH; t++) {
                Long wanted = expected[e][t - LOW];
                var value = wanted == null ? OptionalLong.empty() : OptionalLong.of(wanted);
                assertEquals(new TemporalResult.Point(value), model.query(view, new TemporalQuery.Get(entity, t)),
                        () -> context(seed, step, view, history));
            }
            var scan = new TemporalQuery.Scan(entity, new ValidTimeInterval((long) LOW, (long) HIGH));
            assertEquals(new TemporalResult.Segments(denseSegments(expected[e])), model.query(view, scan),
                    () -> context(seed, step, view, history));
        }
        for (int t = LOW; t < HIGH; t++) {
            long count = 0;
            var sum = BigInteger.ZERO;
            for (var values : expected) {
                Long value = values[t - LOW];
                if (value != null) {
                    count++;
                    sum = sum.add(BigInteger.valueOf(value));
                }
            }
            assertEquals(new TemporalResult.Aggregate(count, sum), model.query(view, new TemporalQuery.Sum(ENTITIES, t)),
                    () -> context(seed, step, view, history));
        }
    }

    private static List<TemporalSegment> denseSegments(Long[] cells) {
        var result = new ArrayList<TemporalSegment>();
        for (int start = 0; start < cells.length;) {
            if (cells[start] == null) {
                start++;
                continue;
            }
            int end = start + 1;
            while (end < cells.length && Objects.equals(cells[start], cells[end])) {
                end++;
            }
            result.add(new TemporalSegment(new ValidTimeInterval((long) LOW + start, (long) LOW + end), cells[start]));
            start = end;
        }
        return result;
    }

    private static String context(long seed, int step, CommitPosition view, List<Transaction> history) {
        return "seed=" + seed + " step=" + step + " cutoff=" + view.sequence() + " history=" + history;
    }
}
