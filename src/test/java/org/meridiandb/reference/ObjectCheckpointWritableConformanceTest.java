// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.ByteArrayOutputStream;
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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.LocalObjectStore;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
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
import tools.jackson.databind.JsonNode;

/** Actual writable pinned-object opens preserve original literals, dense history and retry identity. */
@Timeout(120)
class ObjectCheckpointWritableConformanceTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final int LOW = -8;
    private static final int HIGH = 9;
    private static final List<EntityId> ENTITIES = List.of(new EntityId("a"), new EntityId("b"), new EntityId("c"));
    private static final List<EntityId> SELECT_ENTITIES = List.of(ENTITIES.get(2), new EntityId("never-written"),
            ENTITIES.getFirst(), ENTITIES.get(1));
    private static final long[] THRESHOLDS = {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE};

    @TempDir Path directory;

    private enum Shape {
        GENESIS, SPLIT, FULL;
        int cutoff(int count) {
            return switch (this) { case GENESIS -> 0; case SPLIT -> count / 2; case FULL -> count; };
        }
    }

    @TestFactory
    Stream<DynamicTest> everyUnchangedTemporalAndQueryLiteralUsesEachWritablePinnedShape() throws IOException {
        var tests = new ArrayList<DynamicTest>();
        add(tests, ContractFixtures.load(), "temporal", 5, 84);
        add(tests, ContractFixtures.load("temporal-query-v0.json"), "query", 3, 90);
        assertEquals(24, tests.size());
        return tests.stream();
    }

    private void add(List<DynamicTest> tests, JsonNode corpus, String kind, int historyCount, int expectedSteps) {
        var histories = ContractFixtures.field(corpus, "histories");
        assertEquals(historyCount, histories.size());
        int total = 0;
        var names = new HashSet<String>();
        for (var history : histories) {
            String name = ContractFixtures.text(history, "id");
            assertTrue(names.add(name));
            var steps = ContractFixtures.field(history, "steps");
            total += steps.size();
            var stepNames = new HashSet<String>();
            for (var step : steps) { assertTrue(stepNames.add(ContractFixtures.text(step, "id"))); }
            for (var shape : Shape.values()) {
                String label = kind + "/" + name + "/" + shape;
                Path home = directory.resolve(kind + "-" + name + "-" + shape);
                tests.add(dynamicTest(label, () -> replay(home, new DatabaseId(label), steps, shape, label)));
            }
        }
        assertEquals(expectedSteps, total, "Keep every unchanged literal expectation in all three shapes");
    }

    private static void replay(Path home, DatabaseId lineage, JsonNode steps, Shape shape, String label) throws IOException {
        var reference = new ReferenceModel(lineage);
        var oracle = ContractFixtures.reference(reference);
        var referenceViews = new HashMap<String, CommitPosition>();
        var recoveredViews = new HashMap<String, CommitPosition>();
        var queries = validQueries(steps);
        try {
            var target = new RecoveringDatabase(home, lineage, shape, reference);
            for (var step : steps) {
                String context = label + "/" + ContractFixtures.text(step, "id");
                Object wanted;
                try { wanted = ContractFixtures.step(oracle, lineage, step, referenceViews); }
                catch (ReferenceModel.Rejected rejected) { wanted = ContractFixtures.fields("error", rejected.code()); }
                assertEquals(ContractFixtures.field(step, "expected"), ContractFixtures.json(wanted), "reference/" + context);
                Object actual;
                try { actual = ContractFixtures.step(target, lineage, step, recoveredViews); }
                catch (ReferenceModel.Rejected rejected) { actual = ContractFixtures.fields("error", rejected.code()); }
                assertEquals(ContractFixtures.field(step, "expected"), ContractFixtures.json(actual), "actual/" + context);
                assertEquals(ContractFixtures.json(wanted), ContractFixtures.json(actual), context);
            }
            try (var baseline = LocalDatabase.open(target.home)) {
                checkLiterals(baseline, reference, queries, target.records, label + "/local-baseline");
            }
            target.recovered(recovered -> {
                checkLiterals(recovered, reference, queries, target.records, label + "/object-writable");
                checkWritableIdentity(recovered, reference, target.records, label);
                return null;
            });
            assertArrayEquals(journalImage(lineage, target.records), Files.readAllBytes(target.home.resolve("transactions.journal")));
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Required directory synchronization unavailable: " + unsupported);
        }
    }

    private static List<TemporalQuery> validQueries(JsonNode steps) {
        var queries = new LinkedHashSet<TemporalQuery>();
        for (var step : steps) {
            if (!ContractFixtures.text(step, "kind").equals("query")) { continue; }
            try { queries.add(ContractFixtures.query(ContractFixtures.field(step, "query"))); }
            catch (ReferenceModel.Rejected malformed) {
                // The unchanged literal stream still checks its exact rejection.
            }
        }
        return List.copyOf(queries);
    }

    private static void checkLiterals(LocalDatabase database, ReferenceModel reference, List<TemporalQuery> queries,
                                      List<JournalRecord> records, String label) {
        assertEquals(reference.head(), database.head(), label + "/lineage and head");
        for (long cutoff = 0; cutoff <= records.size(); cutoff++) {
            var position = new CommitPosition(reference.head().databaseId(), cutoff);
            try (var view = database.openView(new ReadCutoff.At(position))) {
                assertEquals(position, view.position(), label);
                for (var query : queries) {
                    assertEquals(reference.query(position, query), view.query(query), label + "/cutoff=" + cutoff + "/" + query);
                }
            }
        }
        checkIdentity(database, records, label);
    }

    /** Every literal write, retry, conflict and read uses the actual writable object factory. */
    private static final class RecoveringDatabase implements ContractFixtures.Database {
        final Path home;
        final DatabaseId lineage;
        final Shape shape;
        final ReferenceModel reference;
        final LocalObjectStore objects;
        final List<JournalRecord> records = new ArrayList<>();
        ObjectCheckpointHead pin;
        int pinForHead = -1;

        RecoveringDatabase(Path parent, DatabaseId lineage, Shape shape, ReferenceModel reference) throws IOException {
            Files.createDirectory(parent);
            this.home = Files.createDirectory(parent.resolve("database"));
            this.objects = new LocalObjectStore(Files.createDirectory(parent.resolve("objects")));
            this.lineage = lineage;
            this.shape = shape;
            this.reference = reference;
            try (var created = LocalDatabase.create(home, lineage)) { assertEquals(reference.head(), created.head()); }
        }

        @Override public CommitResult transact(Transaction request) {
            return semantic(() -> io(() -> {
                byte[] before = Files.readAllBytes(home.resolve("transactions.journal"));
                final CommitResult result;
                try (var writer = openWriter()) {
                    assertEquals(Optional.of(new CommitPosition(lineage, shape.cutoff(records.size()))), writer.checkpointPosition());
                    var selectedCheckpoint = writer.checkpointPosition();
                    var previous = writer.head();
                    try (var pinned = writer.openView(ReadCutoff.Head.INSTANCE)) {
                        var queries = new ArrayList<TemporalQuery>();
                        request.operations().stream().map(WriteOperation::entity).distinct().forEach(entity -> {
                            queries.add(new TemporalQuery.Get(entity, 0));
                            queries.add(new TemporalQuery.Scan(entity, new ValidTimeInterval(null, null)));
                        });
                        var beforeView = queries.stream().map(pinned::query).toList();
                        result = writer.transact(request);
                        assertEquals(previous, pinned.position(), "A captured view remains at its original commit");
                        assertEquals(beforeView, queries.stream().map(pinned::query).toList(), "Publication preserves the old immutable view");
                    }
                    if (!result.replayed()) { records.add(new JournalRecord(result.position().sequence(), request)); }
                    assertEquals(reference.head(), writer.head());
                    assertEquals(selectedCheckpoint, writer.checkpointPosition(), "A local write does not advance the pinned checkpoint");
                    assertEquals(Optional.of(result.position()), writer.lookupRequest(request.requestId()));
                } catch (EngineException rejected) {
                    assertArrayEquals(before, Files.readAllBytes(home.resolve("transactions.journal")),
                            "Rejected original-ID payload reuse must not append a frame");
                    throw rejected;
                }
                byte[] after = Files.readAllBytes(home.resolve("transactions.journal"));
                assertArrayEquals(journalImage(lineage, records), after);
                if (result.replayed()) { assertArrayEquals(before, after, "An exact retry never appends"); }
                return result;
            }));
        }

        @Override public CommitPosition openView(ReadCutoff cutoff) {
            return semantic(() -> recovered(database -> {
                try (var view = database.openView(cutoff)) { return view.position(); }
            }));
        }

        @Override public TemporalResult query(CommitPosition position, TemporalQuery query) {
            return semantic(() -> recovered(database -> {
                try (var view = database.openView(new ReadCutoff.At(position))) { return view.query(query); }
            }));
        }

        @Override public Optional<CommitPosition> lookupRequest(RequestId requestId) {
            return recovered(database -> database.lookupRequest(requestId));
        }

        <T> T recovered(Function<LocalDatabase, T> action) {
            return io(() -> {
                byte[] before = Files.readAllBytes(home.resolve("transactions.journal"));
                final T result;
                try (var database = openWriter()) {
                    assertEquals(reference.head(), database.head());
                    assertEquals(Optional.of(new CommitPosition(lineage, shape.cutoff(records.size()))), database.checkpointPosition());
                    result = action.apply(database);
                }
                assertArrayEquals(before, Files.readAllBytes(home.resolve("transactions.journal")),
                        "Recovered reads and original-ID retry checks preserve the full journal");
                return result;
            });
        }

        LocalDatabase openWriter() throws IOException {
            if (pinForHead != records.size()) {
                pin = install(objects, lineage, records, shape);
                pinForHead = records.size();
            }
            return LocalDatabase.openWritableFromObjectCheckpoint(home, objects, NAMESPACE, PUBLISHER, pin,
                    ObjectCheckpointGraphs.DEFAULT_LIMITS, records.size());
        }
    }

    static Stream<Long> seeds() throws IOException { return ReferencePropertiesTest.seeds(); }

    @ParameterizedTest(name = "writable object recovery, all cutoffs and three shapes seed={0}")
    @MethodSource("seeds")
    void persistedSeedHistoriesWriteAndRetryThroughEveryPinnedShape(long seed) throws IOException {
        Path parent = Files.createDirectory(directory.resolve("seed-" + seed));
        try {
            for (var shape : Shape.values()) {
                var lineage = new DatabaseId("writable-object-seed-" + seed + "-" + shape);
                var reference = new ReferenceModel(lineage);
                var target = new RecoveringDatabase(parent.resolve(shape.name()), lineage, shape, reference);
                var random = new Random(seed);
                var snapshots = new ArrayList<Long[][]>();
                snapshots.add(new Long[ENTITIES.size()][HIGH - LOW]);
                for (int step = 0; step < 32; step++) {
                    var request = request(random, step);
                    var dense = copy(snapshots.getLast());
                    applyDense(dense, request);
                    var wanted = reference.transact(request);
                    var committed = target.transact(request);
                    assertEquals(wanted, committed, "seed=" + seed + "/shape=" + shape + "/step=" + step);
                    assertFalse(committed.replayed());
                    assertEquals(step + 1L, committed.position().sequence());
                    snapshots.add(dense);
                    // Keep the persisted local generator's historical and retry draws in order.
                    int old = random.nextInt(snapshots.size());
                    target.recovered(writer -> {
                        checkSeedView(writer, reference, writer.head(), dense, seed, "new-write-" + shape, target.records);
                        checkSeedView(writer, reference, new CommitPosition(lineage, old), snapshots.get(old), seed,
                                "old-reopen-" + shape, target.records);
                        return null;
                    });
                    int retry = random.nextInt(target.records.size());
                    var retried = target.records.get(retry);
                    var before = reference.head();
                    var expectedReplay = reference.transact(retried.transaction());
                    var replayed = target.transact(retried.transaction());
                    assertEquals(expectedReplay, replayed);
                    assertTrue(replayed.replayed());
                    assertEquals(retried.sequence(), replayed.position().sequence());
                    assertEquals(before, reference.head());
                    if (step % 7 == 0) {
                        var changed = new ArrayList<>(request.operations());
                        changed.add(new WriteOperation.Retract(ENTITIES.getFirst(), new ValidTimeInterval(null, null)));
                        var conflict = new Transaction(request.requestId(), changed);
                        assertEquals("REQUEST_ID_REUSED", assertThrows(ReferenceModel.Rejected.class,
                                () -> reference.transact(conflict)).code());
                        target.recovered(writer -> {
                            assertEquals(EngineException.Code.REQUEST_ID_REUSED,
                                    assertThrows(EngineException.class, () -> writer.transact(conflict)).code());
                            assertEquals(before, writer.head());
                            assertEquals(Optional.of(committed.position()), writer.lookupRequest(request.requestId()));
                            return null;
                        });
                    }
                }
                assertEquals(32, target.records.size());
                assertEquals(target.records.getFirst().transaction().operations(), target.records.get(1).transaction().operations());
                assertFalse(target.records.getFirst().transaction().requestId().equals(target.records.get(1).transaction().requestId()));
                try (var baseline = LocalDatabase.open(target.home)) {
                    checkSeedAll(baseline, reference, snapshots, target.records, seed, "local-baseline-" + shape);
                }
                target.recovered(recovered -> {
                    checkSeedAll(recovered, reference, snapshots, target.records, seed, "object-writable-" + shape);
                    checkWritableIdentity(recovered, reference, target.records, "seed=" + seed + "/shape=" + shape);
                    return null;
                });
                assertArrayEquals(journalImage(lineage, target.records), Files.readAllBytes(target.home.resolve("transactions.journal")),
                        "Every original frame remains exact through writable recovery and retries");
            }
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Required directory synchronization unavailable: " + unsupported);
        }
    }

    private static ObjectCheckpointHead install(LocalObjectStore objects, DatabaseId lineage, List<JournalRecord> records,
                                                Shape shape) throws IOException {
        int cutoff = shape.cutoff(records.size());
        var prefixRecords = records.subList(0, cutoff);
        var references = new ArrayList<CheckpointManifest.SegmentReference>();
        for (int start = 0; start < cutoff; start += 2) {
            var group = prefixRecords.subList(start, Math.min(start + 2, cutoff));
            byte[] image = SegmentCodec.encode(new ImmutableSegment(lineage, group));
            var object = ObjectReference.forBytes(image);
            var id = new SegmentId(object.id().value());
            assertEquals(object, objects.createOrVerify(ObjectCheckpointGraphs.segmentKey(NAMESPACE, id), image).reference());
            references.add(new CheckpointManifest.SegmentReference(id, image.length, group.getFirst().sequence(), group.getLast().sequence()));
        }
        byte[] prefix = journalImage(lineage, prefixRecords);
        var prefixId = ObjectReference.forBytes(prefix);
        var journalPrefix = new CheckpointManifest.JournalPrefix(cutoff, prefix.length, prefixId.id().value());
        var manifest = new CheckpointManifest(lineage, 17 + shape.ordinal(), journalPrefix, references);
        byte[] image = ManifestCodec.encode(manifest);
        var reference = ObjectReference.forBytes(image);
        assertEquals(reference, objects.createOrVerify(ObjectCheckpointGraphs.manifestKey(NAMESPACE, reference.id()), image).reference());
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 10 + shape.ordinal()),
                ObjectCheckpointHeadVerifier.lineageId(lineage), 1, reference, journalPrefix, null);
    }

    private static void checkSeedAll(LocalDatabase database, ReferenceModel reference, List<Long[][]> snapshots,
                                     List<JournalRecord> records, long seed, String stage) {
        assertEquals(reference.head(), database.head(), stage + "/lineage and head");
        assertEquals(33, snapshots.size());
        for (int cutoff = 0; cutoff < snapshots.size(); cutoff++) {
            checkSeedView(database, reference, new CommitPosition(reference.head().databaseId(), cutoff),
                    snapshots.get(cutoff), seed, stage, records);
        }
        checkIdentity(database, records, stage + "/seed=" + seed);
        var future = new ReadCutoff.At(new CommitPosition(reference.head().databaseId(), records.size() + 1L));
        var foreign = new ReadCutoff.At(new CommitPosition(new DatabaseId("foreign"), 0));
        assertEquals(EngineException.Code.INVALID_CUTOFF, assertThrows(EngineException.class, () -> database.openView(future)).code());
        assertEquals(EngineException.Code.INVALID_CUTOFF, assertThrows(EngineException.class, () -> database.openView(foreign)).code());
    }

    private static void checkIdentity(LocalDatabase database, List<JournalRecord> records, String label) {
        for (var record : records) {
            var expected = new CommitPosition(database.head().databaseId(), record.sequence());
            assertEquals(Optional.of(expected), database.lookupRequest(record.transaction().requestId()), label + "/" + record.transaction().requestId());
        }
        assertEquals(Optional.empty(), database.lookupRequest(new RequestId("never-submitted")), label);
    }

    private static void checkWritableIdentity(LocalDatabase database, ReferenceModel reference,
                                               List<JournalRecord> records, String label) {
        var originalHead = database.head();
        for (var record : records) {
            var replay = io(() -> database.transact(record.transaction()));
            assertEquals(reference.transact(record.transaction()), replay, label);
            assertTrue(replay.replayed(), label + "/original request must remain an exact retry");
            assertEquals(new CommitPosition(originalHead.databaseId(), record.sequence()), replay.position(), label);
            assertEquals(Optional.of(replay.position()), database.lookupRequest(record.transaction().requestId()), label);
            var changed = new ArrayList<>(record.transaction().operations());
            changed.add(new WriteOperation.Retract(ENTITIES.getFirst(), new ValidTimeInterval(null, null)));
            var conflict = new Transaction(record.transaction().requestId(), changed);
            assertEquals(EngineException.Code.REQUEST_ID_REUSED,
                    assertThrows(EngineException.class, () -> database.transact(conflict)).code(), label);
            assertEquals("REQUEST_ID_REUSED", assertThrows(ReferenceModel.Rejected.class,
                    () -> reference.transact(conflict)).code(), label);
            assertEquals(originalHead, database.head(), label + "/retry and conflict must preserve head");
            assertEquals(originalHead, reference.head(), label);
        }
    }

    private static void checkSeedView(LocalDatabase database, ReferenceModel reference, CommitPosition position,
                                      Long[][] dense, long seed, String stage, List<JournalRecord> records) {
        try (var view = database.openView(new ReadCutoff.At(position))) {
            Supplier<String> label = () -> stage + "/seed=" + seed + "/cutoff=" + position.sequence() + "/records=" + records;
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
                    var point = new TemporalQuery.Get(ENTITIES.get(entity), edge);
                    assertEquals(reference.query(position, point), view.query(point), label);
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
    }

    private static void compare(ReferenceModel reference, LocalDatabase.ReadView view, CommitPosition position,
                                TemporalQuery query, TemporalResult expected, Supplier<String> label) {
        assertEquals(expected, reference.query(position, query), () -> "dense/reference/" + label.get() + "/" + query);
        assertEquals(expected, view.query(query), () -> "dense/recovered/" + label.get() + "/" + query);
    }

    // Preserve LocalDurablePropertiesTest's construction and historical/retry draw order.
    private static Transaction request(Random random, int step) {
        var operations = new ArrayList<WriteOperation>();
        if (step <= 2) {
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
        // Forward integer-cell assignments are independent of temporal history/interval algorithms.
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
        var segments = new ArrayList<TemporalSegment>();
        for (int start = 0; start < cells.length;) {
            Long value = cells[start];
            if (value == null || (operator != null && !matches(operator, value, operand))) { start++; continue; }
            int end = start + 1;
            while (end < cells.length && Objects.equals(value, cells[end])) { end++; }
            segments.add(new TemporalSegment(new ValidTimeInterval((long) LOW + start, (long) LOW + end), value));
            start = end;
        }
        return new TemporalResult.Segments(segments);
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

    private static byte[] journalImage(DatabaseId lineage, List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(lineage));
        records.forEach(record -> bytes.writeBytes(JournalCodec.encodeRecord(record)));
        return bytes.toByteArray();
    }

    private static <T> T semantic(Supplier<T> action) {
        try { return action.get(); }
        catch (EngineException rejected) { throw new ReferenceModel.Rejected(rejected.code().name()); }
    }

    private static <T> T io(IoAction<T> action) {
        try { return action.run(); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Required directory synchronization unavailable: " + unsupported);
            throw new AssertionError("Unreachable after failed assumption", unsupported);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    @FunctionalInterface private interface IoAction<T> { T run() throws IOException; }
}
