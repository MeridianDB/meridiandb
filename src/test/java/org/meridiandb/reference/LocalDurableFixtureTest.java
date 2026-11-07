// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointHistory;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.ValuePredicate;
import org.meridiandb.temporal.WriteOperation;
import tools.jackson.databind.JsonNode;

class LocalDurableFixtureTest {
    @TempDir
    Path directory;

    @TestFactory
    Stream<DynamicTest> writesAndReopensEveryCanonicalHistoryWithGenesisAndNonemptyCheckpoints() throws IOException {
        var source = ContractFixtures.load();
        assertEquals("meridiandb-temporal-contract-v0", ContractFixtures.text(source, "format"));
        var histories = ContractFixtures.field(source, "histories");
        assertEquals(5, histories.size());
        int expectations = 0;
        var historyIds = new HashSet<String>();
        var tests = new ArrayList<DynamicTest>();
        for (var history : histories) {
            String id = ContractFixtures.text(history, "id");
            assertTrue(historyIds.add(id), "Duplicate history identity");
            var steps = ContractFixtures.field(history, "steps");
            expectations += steps.size();
            var stepIds = new HashSet<String>();
            for (var step : steps) {
                assertTrue(stepIds.add(ContractFixtures.text(step, "id")), "Duplicate step identity");
            }
            for (boolean checkpoint : new boolean[] {false, true}) {
                String label = (checkpoint ? "checkpoint-tail/" : "genesis-tail/") + id;
                tests.add(dynamicTest(label, () -> replayHistory(
                        directory.resolve((checkpoint ? "checkpoint-" : "genesis-") + id),
                        new DatabaseId(id), checkpoint, steps, label)));
            }
        }
        assertEquals(84, expectations, "Retain all canonical expectations in each storage shape");
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> explicitCheckpointHistoryWritesAndReopensEveryCanonicalHistory() throws IOException {
        var source = ContractFixtures.load();
        assertEquals("meridiandb-temporal-contract-v0", ContractFixtures.text(source, "format"));
        var histories = ContractFixtures.field(source, "histories");
        assertEquals(5, histories.size());
        int expectations = 0;
        var tests = new ArrayList<DynamicTest>();
        for (var history : histories) {
            String id = ContractFixtures.text(history, "id");
            var steps = ContractFixtures.field(history, "steps");
            expectations += steps.size();
            for (boolean checkpoint : new boolean[] {false, true}) {
                String label = "checkpoint-history/" + (checkpoint ? "checkpoint-tail/" : "genesis-tail/") + id;
                tests.add(dynamicTest(label, () -> replayHistory(
                        directory.resolve((checkpoint ? "history-checkpoint-" : "history-genesis-") + id),
                        new DatabaseId(id), checkpoint, steps, label, true)));
            }
        }
        assertEquals(84, expectations, "Explicit history mode preserves every literal canonical expectation");
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> explicitCheckpointHistorySeededWritesAndReadOnlyReopenMatchIndependentHistory() throws IOException {
        var tests = new ArrayList<DynamicTest>();
        for (long seed : ReferencePropertiesTest.seeds().toList()) {
            for (var shape : SeededShape.values()) {
                String label = "checkpoint-history/seed-" + seed + "/" + shape;
                tests.add(dynamicTest(label, () -> replaySeededHistory(
                        directory.resolve("history-seed-" + seed + "-" + shape), seed, shape)));
            }
        }
        return tests.stream();
    }

    private static void replayHistory(Path path, DatabaseId database, boolean checkpoint,
                                      JsonNode steps, String label) throws IOException {
        replayHistory(path, database, checkpoint, steps, label, false);
    }

    private static void replayHistory(Path path, DatabaseId database, boolean checkpoint,
                                      JsonNode steps, String label, boolean explicitHistory) throws IOException {
        try (var target = new WritableFixtureDatabase(path, database, checkpoint, explicitHistory)) {
            var views = new HashMap<String, CommitPosition>();
            for (var step : steps) {
                Object actual;
                try {
                    actual = ContractFixtures.step(target, database, step, views);
                } catch (ReferenceModel.Rejected rejected) {
                    actual = ContractFixtures.fields("error", rejected.code());
                }
                assertEquals(ContractFixtures.field(step, "expected"), ContractFixtures.json(actual),
                        label + "/" + ContractFixtures.text(step, "id"));
            }
            target.checkEveryHistoricalCutoff(steps, label);
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force is unavailable: " + unsupported);
        }
    }

    /** Publish only after the database releases its owners; retain all complete journal suffix bytes. */
    static void publishCheckpoint(Path directory, long cutoff) throws IOException {
        try (var store = LocalCheckpointStore.open(directory); var journal = LocalJournal.open(store.journalPath())) {
            long generation;
            try (var previous = store.readLatest(journal).orElseThrow()) {
                generation = Math.addExact(previous.manifest().generation(), 1);
            }
            var records = new ArrayList<JournalRecord>();
            journal.replay(records::add);
            assertTrue(cutoff > 0 && cutoff <= records.size());
            var segment = new ImmutableSegment(journal.databaseId(), records.subList(0, Math.toIntExact(cutoff)));
            var id = store.writeSegment(segment);
            var reference = new CheckpointManifest.SegmentReference(id, SegmentCodec.encode(segment).length, 1, cutoff);
            var prefix = journal.prefix(cutoff);
            store.publish(new CheckpointManifest(journal.databaseId(), generation,
                    new CheckpointManifest.JournalPrefix(cutoff, prefix.byteLength(), prefix.sha256()),
                    List.of(reference)), journal);
        }
    }

    /** Actual durable writes decide acceptance; the independent reference supplies only later query checks. */
    private static final class WritableFixtureDatabase implements ContractFixtures.Database, AutoCloseable {
        private final Path directory;
        private final boolean nonemptyCheckpoint;
        private final boolean explicitHistory;
        private final ReferenceModel reference;
        private final List<Transaction> committed = new ArrayList<>();
        private LocalDatabase database;
        private boolean checkpointPublished;

        WritableFixtureDatabase(Path directory, DatabaseId lineage, boolean nonemptyCheckpoint, boolean explicitHistory) throws IOException {
            this.directory = directory;
            this.nonemptyCheckpoint = nonemptyCheckpoint;
            this.explicitHistory = explicitHistory;
            reference = new ReferenceModel(lineage);
            Files.createDirectory(directory);
            database = LocalDatabase.create(directory, lineage);
            if (explicitHistory) {
                database.close();
                database = LocalDatabase.openWritableWithCheckpointHistory(directory, LocalCheckpointHistory.Limits.DEFAULT);
            }
        }

        @Override
        public CommitResult transact(Transaction request) {
            return semantic(() -> io(() -> {
                var result = database.transact(request);
                var expected = assertDoesNotThrow(() -> reference.transact(request),
                        "A production-accepted write must not be converted into an oracle rejection");
                assertEquals(expected, result);
                if (!result.replayed()) { committed.add(request); }
                database.close();
                database = null;
                if (nonemptyCheckpoint && !checkpointPublished && result.position().sequence() == 1) {
                    publishCheckpoint(directory, 1);
                    checkpointPublished = true;
                }
                database = explicitHistory
                        ? LocalDatabase.openWritableWithCheckpointHistory(directory, LocalCheckpointHistory.Limits.DEFAULT)
                        : LocalDatabase.openWritable(directory);
                assertEquals(reference.head(), database.head(), "Reopen preserves stored lineage and current head");
                assertEquals(Optional.of(result.position()), database.lookupRequest(request.requestId()));
                return result;
            }));
        }

        @Override
        public CommitPosition openView(ReadCutoff cutoff) {
            return semantic(() -> {
                try (var view = database.openView(cutoff)) { return view.position(); }
            });
        }

        @Override
        public TemporalResult query(CommitPosition position, TemporalQuery query) {
            return semantic(() -> {
                try (var view = database.openView(new ReadCutoff.At(position))) { return view.query(query); }
            });
        }

        @Override
        public Optional<CommitPosition> lookupRequest(RequestId requestId) {
            return database.lookupRequest(requestId);
        }

        void checkEveryHistoricalCutoff(JsonNode steps, String label) {
            var queries = new LinkedHashSet<TemporalQuery>();
            for (var step : steps) {
                if (ContractFixtures.text(step, "kind").equals("query")) {
                    try {
                        queries.add(ContractFixtures.query(ContractFixtures.field(step, "query")));
                    } catch (ReferenceModel.Rejected malformed) {
                        // The malformed query keeps its literal canonical assertion above.
                    }
                }
            }
            var lineage = reference.head().databaseId();
            assertEquals(Optional.of(new CommitPosition(lineage, nonemptyCheckpoint ? 1 : 0)),
                    database.checkpointPosition());
            for (long sequence = 0; sequence <= reference.head().sequence(); sequence++) {
                var position = new CommitPosition(lineage, sequence);
                try (var view = database.openView(new ReadCutoff.At(position))) {
                    assertEquals(position, view.position(), label + "/historical-position");
                    for (var query : queries) {
                        assertEquals(reference.query(position, query), view.query(query),
                                label + "/cutoff-" + sequence + "/" + query);
                    }
                }
            }
            for (int index = 0; index < committed.size(); index++) {
                assertEquals(Optional.of(new CommitPosition(lineage, index + 1L)),
                        database.lookupRequest(committed.get(index).requestId()), label + "/request-" + index);
            }
        }

        @Override
        public void close() throws IOException {
            if (database != null) { database.close(); }
        }
    }

    private enum SeededShape { JOURNAL_ONLY, GENESIS, CHECKPOINT_TAIL }

    private static final List<EntityId> SEEDED_ENTITIES = List.of(new EntityId("a"), new EntityId("b"), new EntityId("c"));
    private static final int SEEDED_LOW = -3;
    private static final int SEEDED_HIGH = 4;

    private static void replaySeededHistory(Path path, long seed, SeededShape shape) throws IOException {
        var lineage = new DatabaseId("history-seed-" + seed + "-" + shape);
        var reference = new ReferenceModel(lineage);
        var random = new Random(seed);
        var committed = new ArrayList<Transaction>();
        var snapshots = new ArrayList<Long[][]>();
        snapshots.add(new Long[SEEDED_ENTITIES.size()][SEEDED_HIGH - SEEDED_LOW]);
        LocalDatabase database = null;
        try {
            Files.createDirectory(path);
            if (shape == SeededShape.JOURNAL_ONLY) {
                try (var store = LocalCheckpointStore.open(path); var journal = LocalJournal.create(store.journalPath(), lineage)) {
                    journal.force();
                }
            } else {
                try (var created = LocalDatabase.create(path, lineage)) { assertEquals(0, created.head().sequence()); }
            }
            database = LocalDatabase.openWritableWithCheckpointHistory(path, LocalCheckpointHistory.Limits.DEFAULT);
            for (int step = 0; step < 32; step++) {
                var request = seededRequest(random, step);
                var oldPosition = reference.head();
                var oldDense = snapshots.getLast();
                var nextDense = copySeededCells(oldDense);
                applySeededCells(nextDense, request);
                try (var pinned = database.openView(ReadCutoff.Head.INSTANCE)) {
                    var actual = database.transact(request);
                    assertEquals(reference.transact(request), actual, "seed=" + seed + "/" + shape + "/commit-" + step);
                    assertFalse(actual.replayed(), "A distinct request identity is a new commit even for no-ops");
                    assertEquals(step + 1L, actual.position().sequence());
                    committed.add(request);
                    snapshots.add(nextDense);
                    assertSeededView(pinned, reference, oldPosition, oldDense, seed);
                    database.close();
                    database = null;
                    assertThrows(IllegalStateException.class, pinned::position);
                }
                // Prefix 1..8 is published only after writer close, with suffix 9..16 already stored.
                if (shape == SeededShape.CHECKPOINT_TAIL && step == 15) { publishCheckpoint(path, 8); }
                database = LocalDatabase.openWritableWithCheckpointHistory(path, LocalCheckpointHistory.Limits.DEFAULT);
                assertEquals(reference.head(), database.head());
                var checkpoint = shape == SeededShape.JOURNAL_ONLY ? Optional.<CommitPosition>empty()
                        : Optional.of(new CommitPosition(lineage, shape == SeededShape.CHECKPOINT_TAIL && step >= 15 ? 8 : 0));
                assertEquals(checkpoint, database.checkpointPosition());
                assertSeededPosition(database, reference, database.head(), nextDense, seed);
                int old = random.nextInt(snapshots.size());
                assertSeededPosition(database, reference, new CommitPosition(lineage, old), snapshots.get(old), seed);
                var retried = committed.get(random.nextInt(committed.size()));
                var beforeRetry = database.head();
                var retry = database.transact(retried);
                assertEquals(reference.transact(retried), retry);
                assertTrue(retry.replayed());
                assertEquals(beforeRetry, database.head());
                assertEquals(Optional.of(retry.position()), database.lookupRequest(retried.requestId()));
                database.close();
                database = null;
                database = LocalDatabase.openWritableWithCheckpointHistory(path, LocalCheckpointHistory.Limits.DEFAULT);
                assertEquals(beforeRetry, database.head());
                if (step % 7 == 0) {
                    var operations = new ArrayList<>(request.operations());
                    operations.add(new WriteOperation.Retract(SEEDED_ENTITIES.getFirst(), new ValidTimeInterval(null, null)));
                    var conflict = new Transaction(request.requestId(), operations);
                    var writer = database;
                    assertEquals(EngineException.Code.REQUEST_ID_REUSED,
                            assertThrows(EngineException.class, () -> writer.transact(conflict)).code());
                    assertEquals("REQUEST_ID_REUSED", assertThrows(ReferenceModel.Rejected.class,
                            () -> reference.transact(conflict)).code());
                    assertEquals(reference.head(), database.head());
                }
                assertEquals(Optional.of(new CommitPosition(lineage, step + 1L)), database.lookupRequest(request.requestId()));
            }
            assertSeededHistory(database, reference, snapshots, committed, seed);
            database.close();
            database = null;
            try (var readOnly = LocalDatabase.openWithCheckpointHistory(path, LocalCheckpointHistory.Limits.DEFAULT)) {
                assertEquals(reference.head(), readOnly.head());
                assertSeededHistory(readOnly, reference, snapshots, committed, seed);
            }
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force is unavailable: " + unsupported);
        } finally {
            if (database != null) { database.close(); }
        }
    }

    private static Transaction seededRequest(Random random, int step) {
        var operations = new ArrayList<WriteOperation>();
        if (step <= 2) {
            for (var entity : SEEDED_ENTITIES) {
                operations.add(new WriteOperation.Put(entity, new ValidTimeInterval(null, null),
                        step < 2 ? Long.MAX_VALUE : Long.MIN_VALUE));
            }
        } else {
            for (int index = 0, count = 1 + random.nextInt(3); index < count; index++) {
                var entity = SEEDED_ENTITIES.get(random.nextInt(SEEDED_ENTITIES.size()));
                long start = SEEDED_LOW + random.nextInt(SEEDED_HIGH - SEEDED_LOW);
                long end = start + 1 + random.nextInt(SEEDED_HIGH - (int) start);
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
        return new Transaction(new RequestId("history-request-" + step), operations);
    }

    private static Long[][] copySeededCells(Long[][] source) {
        var result = new Long[source.length][];
        for (int entity = 0; entity < source.length; entity++) { result[entity] = source[entity].clone(); }
        return result;
    }

    private static void applySeededCells(Long[][] cells, Transaction request) {
        for (var operation : request.operations()) {
            int entity = SEEDED_ENTITIES.indexOf(operation.entity());
            Long from = operation.interval().fromInclusiveMicros();
            Long to = operation.interval().toExclusiveMicros();
            for (int point = SEEDED_LOW; point < SEEDED_HIGH; point++) {
                if ((from == null || point >= from) && (to == null || point < to)) {
                    cells[entity][point - SEEDED_LOW] = operation instanceof WriteOperation.Put put ? put.value() : null;
                }
            }
        }
    }

    private static void assertSeededHistory(LocalDatabase database, ReferenceModel reference, List<Long[][]> snapshots,
                                            List<Transaction> committed, long seed) {
        var lineage = reference.head().databaseId();
        for (int cutoff = 0; cutoff < snapshots.size(); cutoff++) {
            assertSeededPosition(database, reference, new CommitPosition(lineage, cutoff), snapshots.get(cutoff), seed);
        }
        for (int index = 0; index < committed.size(); index++) {
            assertEquals(Optional.of(new CommitPosition(lineage, index + 1L)), database.lookupRequest(committed.get(index).requestId()));
        }
        assertEquals(Optional.empty(), database.lookupRequest(new RequestId("not-committed")));
        assertEquals(EngineException.Code.INVALID_CUTOFF, assertThrows(EngineException.class,
                () -> database.openView(new ReadCutoff.At(new CommitPosition(lineage, 33)))).code());
        assertEquals(EngineException.Code.INVALID_CUTOFF, assertThrows(EngineException.class,
                () -> database.openView(new ReadCutoff.At(new CommitPosition(new DatabaseId("foreign"), 0)))).code());
    }

    private static void assertSeededPosition(LocalDatabase database, ReferenceModel reference, CommitPosition position,
                                             Long[][] dense, long seed) {
        try (var view = database.openView(new ReadCutoff.At(position))) {
            assertSeededView(view, reference, position, dense, seed);
        }
    }

    private static void assertSeededView(LocalDatabase.ReadView view, ReferenceModel reference, CommitPosition position,
                                        Long[][] dense, long seed) {
        Supplier<String> context = () -> "seed=" + seed + "/cutoff=" + position.sequence();
        assertEquals(position, view.position(), context);
        for (int entity = 0; entity < SEEDED_ENTITIES.size(); entity++) {
            var identity = SEEDED_ENTITIES.get(entity);
            for (int point = SEEDED_LOW; point < SEEDED_HIGH; point++) {
                Long value = dense[entity][point - SEEDED_LOW];
                var wanted = new TemporalResult.Point(value == null ? OptionalLong.empty() : OptionalLong.of(value));
                var query = new TemporalQuery.Get(identity, point);
                assertEquals(wanted, reference.query(position, query), context);
                assertEquals(wanted, view.query(query), context);
            }
            for (long edge : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
                var query = new TemporalQuery.Get(identity, edge);
                assertEquals(reference.query(position, query), view.query(query), context);
            }
            for (var query : List.<TemporalQuery>of(
                    new TemporalQuery.Scan(identity, new ValidTimeInterval(null, null)),
                    new TemporalQuery.FilterScan(identity, new ValidTimeInterval((long) SEEDED_LOW, (long) SEEDED_HIGH),
                            new ValuePredicate(ValuePredicate.Operator.GT, 0)))) {
                assertEquals(reference.query(position, query), view.query(query), context);
            }
        }
        for (int point = SEEDED_LOW; point < SEEDED_HIGH; point++) {
            long count = 0;
            var sum = BigInteger.ZERO;
            for (var cells : dense) {
                Long value = cells[point - SEEDED_LOW];
                if (value != null) { count++; sum = sum.add(BigInteger.valueOf(value)); }
            }
            var aggregate = new TemporalResult.Aggregate(count, sum);
            var sumQuery = new TemporalQuery.Sum(SEEDED_ENTITIES, point);
            assertEquals(aggregate, reference.query(position, sumQuery), context);
            assertEquals(aggregate, view.query(sumQuery), context);
            var select = new TemporalQuery.Select(SEEDED_ENTITIES, point, new ValuePredicate(ValuePredicate.Operator.NE, 0));
            assertEquals(reference.query(position, select), view.query(select), context);
        }
    }

    private static <T> T semantic(Supplier<T> action) {
        try {
            return action.get();
        } catch (EngineException rejected) {
            throw new ReferenceModel.Rejected(rejected.code().name());
        }
    }

    private static <T> T io(IoAction<T> action) {
        try {
            return action.run();
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force is unavailable: " + unsupported);
            throw new AssertionError("Unreachable after failed assumption", unsupported);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws IOException;
    }
}
