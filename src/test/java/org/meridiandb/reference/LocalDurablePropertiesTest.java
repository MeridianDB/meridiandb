// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
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

/** Actual writable/reopen histories checked against forward dense cells and a backward-history oracle. */
class LocalDurablePropertiesTest {
    private static final int LOW = -8;
    private static final int HIGH = 9;
    private static final List<EntityId> ENTITIES = List.of(new EntityId("a"), new EntityId("b"), new EntityId("c"));

    @TempDir
    Path directory;

    static Stream<Long> seeds() throws IOException {
        return ReferencePropertiesTest.seeds();
    }

    @ParameterizedTest(name = "durable reopen history seed={0}")
    @MethodSource("seeds")
    void realWritesRetriesAndHistoricalResultsSurviveEveryWritableReopen(long seed) throws IOException {
        var path = Files.createDirectory(directory.resolve("seed-" + seed));
        var lineage = new DatabaseId("durable-seed-" + seed);
        var reference = new ReferenceModel(lineage);
        var random = new Random(seed);
        var snapshots = new ArrayList<Long[][]>();
        var requests = new ArrayList<Transaction>();
        snapshots.add(new Long[ENTITIES.size()][HIGH - LOW]);
        LocalDatabase database = null;
        try {
            database = LocalDatabase.create(path, lineage);
            for (int step = 0; step < 32; step++) {
                var request = request(random, step);
                var previous = database.head();
                var previousDense = snapshots.getLast();
                var expected = copy(previousDense);
                applyDense(expected, request);
                try (var pinned = database.openView(ReadCutoff.Head.INSTANCE)) {
                    var result = database.transact(request);
                    var wanted = reference.transact(request);
                    requests.add(request);
                    snapshots.add(expected);
                    assertEquals(wanted, result, context(seed, step, previous, requests));
                    assertFalse(result.replayed(), "A new identity commits even when values are unchanged");
                    assertEquals(step + 1L, result.position().sequence());
                    assertQueries(pinned, reference, previous, previousDense, seed, step, requests);
                    assertView(database, reference, result.position(), expected, seed, step, requests);
                    database.close();
                    database = null;
                    assertThrows(IllegalStateException.class, pinned::position);
                }
                // The checkpoint contains commits 1..8 while a complete suffix 9..16 already exists.
                if (step == 15) { LocalDurableFixtureTest.publishCheckpoint(path, 8); }
                database = LocalDatabase.openWritable(path);
                assertEquals(reference.head(), database.head(), "Reopen must preserve every acknowledged commit");
                assertEquals(Optional.of(new CommitPosition(lineage, step < 15 ? 0 : 8)), database.checkpointPosition());
                assertView(database, reference, database.head(), expected, seed, step, requests);
                int old = random.nextInt(snapshots.size());
                assertView(database, reference, new CommitPosition(lineage, old), snapshots.get(old), seed, step, requests);

                int retry = random.nextInt(requests.size());
                var retried = requests.get(retry);
                var beforeRetry = database.head();
                var actualReplay = database.transact(retried);
                var wantedReplay = reference.transact(retried);
                assertEquals(wantedReplay, actualReplay, context(seed, step, beforeRetry, requests));
                assertTrue(actualReplay.replayed());
                assertEquals(new CommitPosition(lineage, retry + 1L), actualReplay.position());
                assertEquals(beforeRetry, database.head());
                database.close();
                database = null;
                database = LocalDatabase.openWritable(path);
                assertEquals(beforeRetry, database.head());
                assertEquals(Optional.of(actualReplay.position()), database.lookupRequest(retried.requestId()));

                if (step % 7 == 0) {
                    var changed = new ArrayList<>(request.operations());
                    changed.add(new WriteOperation.Retract(ENTITIES.getFirst(), new ValidTimeInterval(null, null)));
                    var conflicting = new Transaction(request.requestId(), changed);
                    var writer = database;
                    assertEquals(EngineException.Code.REQUEST_ID_REUSED,
                            assertThrows(EngineException.class, () -> writer.transact(conflicting)).code());
                    assertEquals("REQUEST_ID_REUSED", assertThrows(ReferenceModel.Rejected.class,
                            () -> reference.transact(conflicting)).code());
                    assertEquals(reference.head(), database.head());
                }
                assertView(database, reference, database.head(), expected, seed, step, requests);
                assertEquals(Optional.of(new CommitPosition(lineage, step + 1L)), database.lookupRequest(request.requestId()));
            }
            for (int cutoff = 0; cutoff < snapshots.size(); cutoff++) {
                assertView(database, reference, new CommitPosition(lineage, cutoff), snapshots.get(cutoff),
                        seed, 32, requests);
            }
            for (int index = 0; index < requests.size(); index++) {
                assertEquals(Optional.of(new CommitPosition(lineage, index + 1L)),
                        database.lookupRequest(requests.get(index).requestId()));
            }
            assertEquals(Optional.empty(), database.lookupRequest(new RequestId("never-submitted")));
            var writer = database;
            assertEquals(EngineException.Code.INVALID_CUTOFF, assertThrows(EngineException.class,
                    () -> writer.openView(new ReadCutoff.At(new CommitPosition(lineage, 33)))).code());
            assertEquals(EngineException.Code.INVALID_CUTOFF, assertThrows(EngineException.class,
                    () -> writer.openView(new ReadCutoff.At(new CommitPosition(new DatabaseId("foreign"), 0)))).code());
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force is unavailable: " + unsupported);
        } finally {
            if (database != null) { database.close(); }
        }
    }

    private static Transaction request(Random random, int step) {
        var operations = new ArrayList<WriteOperation>();
        if (step <= 2) {
            // Commit two deliberately repeats commit one: an acknowledged state no-op with a new ID.
            long value = step < 2 ? Long.MAX_VALUE : Long.MIN_VALUE;
            for (var entity : ENTITIES) {
                operations.add(new WriteOperation.Put(entity, new ValidTimeInterval(null, null), value));
            }
        } else {
            for (int operation = 0, count = 1 + random.nextInt(3); operation < count; operation++) {
                var entity = ENTITIES.get(random.nextInt(ENTITIES.size()));
                long start = LOW + random.nextInt(HIGH - LOW);
                long end = start + 1 + random.nextInt(HIGH - (int) start);
                Long from = switch (random.nextInt(8)) {
                    case 0 -> null;
                    case 1 -> Long.MIN_VALUE;
                    default -> start;
                };
                Long to = switch (random.nextInt(8)) {
                    case 0 -> null;
                    case 1 -> Long.MAX_VALUE;
                    default -> end;
                };
                var interval = new ValidTimeInterval(from, to);
                long value = switch (random.nextInt(16)) {
                    case 0 -> Long.MIN_VALUE;
                    case 1 -> Long.MAX_VALUE;
                    default -> random.nextInt(7) - 3;
                };
                operations.add(random.nextInt(4) == 0 ? new WriteOperation.Retract(entity, interval)
                        : new WriteOperation.Put(entity, interval, value));
            }
        }
        return new Transaction(new RequestId("request-" + step), operations);
    }

    private static Long[][] copy(Long[][] source) {
        var result = new Long[source.length][];
        for (int entity = 0; entity < source.length; entity++) { result[entity] = source[entity].clone(); }
        return result;
    }

    private static void applyDense(Long[][] state, Transaction request) {
        // Each integer cell receives forward assignments directly, without interval splitting,
        // history searches, BigInteger boundary partitioning or the production contains() method.
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

    private static void assertView(LocalDatabase database, ReferenceModel reference, CommitPosition position,
                                   Long[][] dense, long seed, int step, List<Transaction> history) {
        try (var view = database.openView(new ReadCutoff.At(position))) {
            assertQueries(view, reference, position, dense, seed, step, history);
        }
    }

    private static void assertQueries(LocalDatabase.ReadView view, ReferenceModel reference, CommitPosition position,
                                      Long[][] dense, long seed, int step, List<Transaction> history) {
        Supplier<String> context = () -> context(seed, step, position, history);
        assertEquals(position, view.position(), context);
        for (int entity = 0; entity < ENTITIES.size(); entity++) {
            for (int instant = LOW; instant < HIGH; instant++) {
                Long value = dense[entity][instant - LOW];
                var wanted = new TemporalResult.Point(value == null ? OptionalLong.empty() : OptionalLong.of(value));
                var query = new TemporalQuery.Get(ENTITIES.get(entity), instant);
                assertEquals(wanted, reference.query(position, query), context);
                assertEquals(wanted, view.query(query), context);
            }
            var scan = new TemporalQuery.Scan(ENTITIES.get(entity), new ValidTimeInterval((long) LOW, (long) HIGH));
            var wanted = new TemporalResult.Segments(denseSegments(dense[entity]));
            assertEquals(wanted, reference.query(position, scan), context);
            assertEquals(wanted, view.query(scan), context);
            var fullScan = new TemporalQuery.Scan(ENTITIES.get(entity), new ValidTimeInterval(null, null));
            assertEquals(reference.query(position, fullScan), view.query(fullScan), context);
            for (long edge : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
                var point = new TemporalQuery.Get(ENTITIES.get(entity), edge);
                assertEquals(reference.query(position, point), view.query(point), context);
            }
        }
        for (int instant = LOW; instant < HIGH; instant++) {
            long count = 0;
            var sum = BigInteger.ZERO;
            for (var cells : dense) {
                Long value = cells[instant - LOW];
                if (value != null) {
                    count++;
                    sum = sum.add(BigInteger.valueOf(value));
                }
            }
            var wanted = new TemporalResult.Aggregate(count, sum);
            var query = new TemporalQuery.Sum(ENTITIES, instant);
            assertEquals(wanted, reference.query(position, query), context);
            assertEquals(wanted, view.query(query), context);
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
            while (end < cells.length && Objects.equals(cells[start], cells[end])) { end++; }
            result.add(new TemporalSegment(new ValidTimeInterval((long) LOW + start, (long) LOW + end), cells[start]));
            start = end;
        }
        return result;
    }

    private static String context(long seed, int step, CommitPosition position, List<Transaction> history) {
        return "seed=" + seed + " step=" + step + " cutoff=" + position.sequence() + " history=" + history;
    }
}
