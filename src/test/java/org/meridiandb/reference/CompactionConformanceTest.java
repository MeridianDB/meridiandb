// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.InMemoryEngine;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.CheckpointVerifier;
import org.meridiandb.storage.manifest.HistoryCompactor;
import org.meridiandb.storage.manifest.ManifestCodec;
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

/** Semantic conformance for pure history repacking; no local publication or reclamation occurs. */
@Timeout(60)
class CompactionConformanceTest {
    private static final int LOW = -4;
    private static final int HIGH = 5;
    private static final List<EntityId> ENTITIES = List.of(new EntityId("a"), new EntityId("b"), new EntityId("c"));
    private static final List<EntityId> SELECT_ENTITIES = List.of(ENTITIES.get(2), ENTITIES.getFirst(),
            new EntityId("never-written"), ENTITIES.get(1));
    private static final long[] THRESHOLDS = {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE};

    @TestFactory
    Stream<DynamicTest> allOriginalAndQueryFixtureExpectationsSurviveActualCompactorOutput() throws IOException {
        var tests = new ArrayList<DynamicTest>();
        addFixtureTests(tests, ContractFixtures.load(), "temporal", 5, 84);
        addFixtureTests(tests, ContractFixtures.load("temporal-query-v0.json"), "query", 3, 90);
        assertEquals(8, tests.size());
        return tests.stream();
    }

    private static void addFixtureTests(List<DynamicTest> tests, JsonNode corpus, String prefix,
            int historyCount, int expectationCount) {
        var histories = ContractFixtures.field(corpus, "histories");
        assertEquals(historyCount, histories.size());
        int expectations = 0;
        for (var history : histories) {
            var steps = ContractFixtures.field(history, "steps");
            expectations += steps.size();
            String label = prefix + "/" + ContractFixtures.text(history, "id");
            tests.add(dynamicTest(label, () -> checkFixture(steps, label)));
        }
        assertEquals(expectationCount, expectations, "Keep every literal expectation in the original corpus");
    }

    private static void checkFixture(JsonNode steps, String label) throws IOException {
        try (var original = new CollectingDatabase()) {
            replayLiterals(original, original.engine.head().databaseId(), steps, label + "/before");
            var records = List.copyOf(original.records);
            var source = source(new DatabaseId("compaction-" + label), records);
            var plan = HistoryCompactor.compact(source.manifest(), source.images()::get, HistoryCompactor.Limits.DEFAULT);
            var compacted = assertPlan(source, records, plan, label);
            try (var rebuilt = new OutputDatabase(compacted)) {
                // New fixture commits consume/transact compacted records, not fixture requests.
                // Fixture requests are assertions against those records; retries/conflicts are
                // subsequently exercised against the actual rebuilt request-identity map.
                replayLiterals(rebuilt, rebuilt.engine.head().databaseId(), steps, label + "/after");
                assertEquals(compacted.size(), rebuilt.consumed);
                compareEveryHistoricalLiteralQuery(original.engine, rebuilt.engine, source.manifest().databaseId(),
                        records, steps, label);
                checkRequestIdentity(original.engine, rebuilt.engine, compacted, label);
            }
        }
    }

    private static void replayLiterals(ContractFixtures.Database target, DatabaseId lineage,
            JsonNode steps, String label) {
        var views = new HashMap<String, CommitPosition>();
        for (var step : steps) {
            Object actual;
            try { actual = ContractFixtures.step(target, lineage, step, views); }
            catch (ReferenceModel.Rejected rejected) { actual = ContractFixtures.fields("error", rejected.code()); }
            assertEquals(ContractFixtures.field(step, "expected"), ContractFixtures.json(actual),
                    label + "/" + ContractFixtures.text(step, "id"));
        }
    }

    private static void compareEveryHistoricalLiteralQuery(InMemoryEngine before, InMemoryEngine after,
            DatabaseId lineage, List<JournalRecord> original, JsonNode steps, String label) {
        var reference = new ReferenceModel(lineage);
        for (var record : original) { reference.transact(record.transaction()); }
        var queries = new LinkedHashSet<TemporalQuery>();
        for (var step : steps) {
            if (ContractFixtures.text(step, "kind").equals("query")) {
                try { queries.add(ContractFixtures.query(ContractFixtures.field(step, "query"))); }
                catch (ReferenceModel.Rejected malformed) {
                    // Its exact literal rejection is already checked in both step streams.
                }
            }
        }
        for (int cutoff = 0; cutoff <= original.size(); cutoff++) {
            var expectedPosition = new CommitPosition(lineage, cutoff);
            try (var oldView = before.openView(new ReadCutoff.At(position(before, cutoff)));
                 var newView = after.openView(new ReadCutoff.At(position(after, cutoff)))) {
                for (var query : queries) {
                    var expected = reference.query(expectedPosition, query);
                    assertEquals(expected, oldView.query(query), label + "/before/cutoff=" + cutoff + "/" + query);
                    assertEquals(expected, newView.query(query), label + "/after/cutoff=" + cutoff + "/" + query);
                }
            }
        }
    }

    static Stream<Long> seeds() throws IOException { return ReferencePropertiesTest.seeds(); }

    @ParameterizedTest(name = "compaction query/history seed={0}")
    @MethodSource("seeds")
    void seededRepackedHistoryMatchesBothEnginesReferenceAndForwardDenseCells(long seed) throws IOException {
        var random = new Random(seed);
        var records = new ArrayList<JournalRecord>();
        var snapshots = new ArrayList<Long[][]>();
        snapshots.add(new Long[ENTITIES.size()][HIGH - LOW]);
        var lineage = new DatabaseId("compaction-seed-" + seed);
        var reference = new ReferenceModel(lineage);
        try (var before = new InMemoryEngine(); var after = new InMemoryEngine()) {
            for (int step = 0; step < 32; step++) {
                var operations = new ArrayList<WriteOperation>();
                for (int operation = 0, count = 1 + random.nextInt(3); operation < count; operation++) {
                    var entity = ENTITIES.get(random.nextInt(ENTITIES.size()));
                    long start = LOW + random.nextInt(HIGH - LOW);
                    long end = start + 1 + random.nextInt(HIGH - (int) start);
                    var interval = new ValidTimeInterval(random.nextInt(4) == 0 ? null : start,
                            random.nextInt(4) == 0 ? null : end);
                    long value = switch ((step + operation) % 8) {
                        case 0 -> Long.MIN_VALUE;
                        case 1 -> Long.MAX_VALUE;
                        default -> random.nextInt(7) - 3;
                    };
                    operations.add(random.nextInt(4) == 0 ? new WriteOperation.Retract(entity, interval)
                            : new WriteOperation.Put(entity, interval, value));
                }
                var request = new Transaction(new RequestId("compaction-request-" + step), operations);
                var accepted = before.transact(request);
                assertFalse(accepted.replayed());
                records.add(new JournalRecord(accepted.position().sequence(), request));
                reference.transact(request);
                var state = copy(snapshots.getLast());
                applyDense(state, request);
                snapshots.add(state);
            }
            var source = source(lineage, records);
            var defaults = HistoryCompactor.Limits.DEFAULT;
            var limits = new HistoryCompactor.Limits(1024, defaults.maxInputBytes(), defaults.maxOutputBytes(), defaults.maxRecords());
            var plan = HistoryCompactor.compact(source.manifest(), source.images()::get, limits);
            var compacted = assertPlan(source, records, plan, "seed=" + seed);
            assertTrue(plan.segments().size() < records.size(), "The seed must actually change its physical segmentation");
            for (var record : compacted) {
                var restored = after.transact(record.transaction());
                assertEquals(record.sequence(), restored.position().sequence());
                assertFalse(restored.replayed());
            }
            for (int cutoff : new int[] {0, 1, 8, 16, 24, 32}) {
                try (var oldView = before.openView(new ReadCutoff.At(position(before, cutoff)));
                     var newView = after.openView(new ReadCutoff.At(position(after, cutoff)))) {
                    for (int instant : new int[] {LOW, 0, HIGH - 1}) {
                        for (int entity = 0; entity < ENTITIES.size(); entity++) {
                            Long value = snapshots.get(cutoff)[entity][instant - LOW];
                            var expected = new TemporalResult.Point(value == null ? OptionalLong.empty() : OptionalLong.of(value));
                            compare(seed, cutoff, records, reference, oldView, newView, expected,
                                    new TemporalQuery.Get(ENTITIES.get(entity), instant));
                        }
                        long count = 0;
                        var sum = BigInteger.ZERO;
                        for (var entity : snapshots.get(cutoff)) {
                            Long value = entity[instant - LOW];
                            if (value != null) { count++; sum = sum.add(BigInteger.valueOf(value)); }
                        }
                        compare(seed, cutoff, records, reference, oldView, newView, new TemporalResult.Aggregate(count, sum),
                                new TemporalQuery.Sum(ENTITIES, instant));
                    }
                    for (var operator : Operator.values()) {
                        long operand = THRESHOLDS[(operator.ordinal() + cutoff) % THRESHOLDS.length];
                        var predicate = new ValuePredicate(operator, operand);
                        for (int instant : new int[] {LOW, 0, HIGH - 1}) {
                            compare(seed, cutoff, records, reference, oldView, newView,
                                    denseRows(snapshots.get(cutoff), instant, operator, operand),
                                    new TemporalQuery.Select(SELECT_ENTITIES, instant, predicate));
                        }
                        for (int entity = 0; entity < ENTITIES.size(); entity++) {
                            compare(seed, cutoff, records, reference, oldView, newView,
                                    denseSegments(snapshots.get(cutoff)[entity], operator, operand),
                                    new TemporalQuery.FilterScan(ENTITIES.get(entity),
                                            new ValidTimeInterval((long) LOW, (long) HIGH), predicate));
                        }
                    }
                }
            }
            checkRequestIdentity(before, after, compacted, "seed=" + seed);
        }
    }

    private static void compare(long seed, int cutoff, List<JournalRecord> records, ReferenceModel model,
            InMemoryEngine.ReadView before, InMemoryEngine.ReadView after, TemporalResult expected, TemporalQuery query) {
        String label = "seed=" + seed + " cutoff=" + cutoff + " query=" + query;
        assertEquals(expected, model.query(new CommitPosition(model.head().databaseId(), cutoff), query),
                () -> "reference/" + label + " records=" + records);
        assertEquals(expected, before.query(query), () -> "before/" + label + " records=" + records);
        assertEquals(expected, after.query(query), () -> "after/" + label + " records=" + records);
    }

    private static List<JournalRecord> assertPlan(Source source, List<JournalRecord> original,
            HistoryCompactor.Plan plan, String label) throws IOException {
        assertEquals(source.manifest(), plan.sourceManifest(), label);
        assertEquals(ManifestCodec.decode(ManifestCodec.encode(source.manifest())).id(), plan.sourceManifestId(), label);
        assertEquals(source.manifest().generation() + 1, plan.replacementManifest().generation(), label);
        assertEquals(source.manifest().databaseId(), plan.replacementManifest().databaseId(), label);
        assertEquals(source.manifest().journalPrefix(), plan.replacementManifest().journalPrefix(), label);
        var compacted = plan.segments().stream().flatMap(segment -> segment.records().stream()).toList();
        assertEquals(original, compacted, label + "/exact original requests, order and sequence");
        long inputBytes = source.images().values().stream().mapToLong(image -> image.length).sum();
        long outputBytes = 0;
        var output = new LinkedHashMap<SegmentId, byte[]>();
        for (var segment : plan.segments()) {
            assertEquals(source.manifest().databaseId(), segment.databaseId());
            byte[] image = SegmentCodec.encode(segment);
            outputBytes += image.length;
            output.put(SegmentCodec.decode(image).id(), image);
        }
        assertEquals(inputBytes, plan.inputBytes());
        assertEquals(outputBytes, plan.outputBytes());
        if (original.size() > 1) { assertTrue(outputBytes < inputBytes, label + "/repacking saves duplicated segment envelopes"); }
        CheckpointVerifier.verify(plan.replacementManifest(), output::get);
        assertArrayEquals(journalImage(source.manifest().databaseId(), original),
                journalImage(source.manifest().databaseId(), compacted), label + "/exact prefix bytes");
        return compacted;
    }

    private static void checkRequestIdentity(InMemoryEngine before, InMemoryEngine after,
            List<JournalRecord> compacted, String label) {
        for (var record : compacted) {
            assertEquals(Optional.of(position(before, record.sequence())), before.lookupRequest(record.transaction().requestId()), label);
            assertEquals(Optional.of(position(after, record.sequence())), after.lookupRequest(record.transaction().requestId()), label);
            var oldRetry = before.transact(record.transaction());
            var newRetry = after.transact(record.transaction());
            assertTrue(oldRetry.replayed());
            assertTrue(newRetry.replayed());
            assertEquals(record.sequence(), oldRetry.position().sequence());
            assertEquals(record.sequence(), newRetry.position().sequence());
        }
        if (!compacted.isEmpty()) {
            var original = compacted.getFirst().transaction();
            var operations = new ArrayList<>(original.operations());
            operations.add(new WriteOperation.Retract(original.operations().getFirst().entity(), new ValidTimeInterval(null, null)));
            var conflict = new Transaction(original.requestId(), operations);
            assertEquals(EngineException.Code.REQUEST_ID_REUSED, assertThrows(EngineException.class,
                    () -> before.transact(conflict)).code());
            assertEquals(EngineException.Code.REQUEST_ID_REUSED, assertThrows(EngineException.class,
                    () -> after.transact(conflict)).code());
        }
        assertEquals(compacted.size(), before.head().sequence(), label + "/retry leaves original head unchanged");
        assertEquals(compacted.size(), after.head().sequence(), label + "/retry leaves compacted head unchanged");
    }

    private static Source source(DatabaseId lineage, List<JournalRecord> records) {
        var images = new LinkedHashMap<SegmentId, byte[]>();
        var references = new ArrayList<CheckpointManifest.SegmentReference>();
        for (var record : records) {
            byte[] image = SegmentCodec.encode(new ImmutableSegment(lineage, List.of(record)));
            var id = SegmentCodec.decode(image).id();
            images.put(id, image);
            references.add(new CheckpointManifest.SegmentReference(id, image.length, record.sequence(), record.sequence()));
        }
        byte[] prefix = journalImage(lineage, records);
        var hash = java.util.HexFormat.of().formatHex(sha256(prefix));
        var manifest = new CheckpointManifest(lineage, 7,
                new CheckpointManifest.JournalPrefix(records.size(), prefix.length, hash), references);
        return new Source(manifest, images);
    }

    private record Source(CheckpointManifest manifest, Map<SegmentId, byte[]> images) { }

    private static byte[] journalImage(DatabaseId lineage, List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(lineage));
        for (var record : records) { bytes.writeBytes(JournalCodec.encodeRecord(record)); }
        return bytes.toByteArray();
    }

    private static byte[] sha256(byte[] bytes) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static CommitPosition position(InMemoryEngine engine, long sequence) {
        return new CommitPosition(engine.head().databaseId(), sequence);
    }

    private static Long[][] copy(Long[][] input) {
        var result = new Long[input.length][];
        for (int entity = 0; entity < input.length; entity++) { result[entity] = input[entity].clone(); }
        return result;
    }

    private static void applyDense(Long[][] state, Transaction request) {
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

    private static TemporalResult.Rows denseRows(Long[][] cells, int instant, Operator operator, long operand) {
        var rows = new ArrayList<EntityValue>();
        for (int entity = 0; entity < ENTITIES.size(); entity++) {
            Long value = cells[entity][instant - LOW];
            if (value != null && matches(operator, value, operand)) { rows.add(new EntityValue(ENTITIES.get(entity), value)); }
        }
        return new TemporalResult.Rows(rows);
    }

    private static TemporalResult.Segments denseSegments(Long[] cells, Operator operator, long operand) {
        var result = new ArrayList<TemporalSegment>();
        for (int start = 0; start < cells.length;) {
            Long value = cells[start];
            if (value == null || !matches(operator, value, operand)) { start++; continue; }
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

    private static final class CollectingDatabase implements ContractFixtures.Database, AutoCloseable {
        final InMemoryEngine engine = new InMemoryEngine();
        final ContractFixtures.Database delegate = ContractFixtures.engine(engine);
        final List<JournalRecord> records = new ArrayList<>();
        @Override public CommitResult transact(Transaction request) {
            var result = delegate.transact(request);
            if (!result.replayed()) { records.add(new JournalRecord(result.position().sequence(), request)); }
            return result;
        }
        @Override public CommitPosition openView(ReadCutoff cutoff) { return delegate.openView(cutoff); }
        @Override public TemporalResult query(CommitPosition position, TemporalQuery query) { return delegate.query(position, query); }
        @Override public Optional<CommitPosition> lookupRequest(RequestId id) { return delegate.lookupRequest(id); }
        @Override public void close() { engine.close(); }
    }

    private static final class OutputDatabase implements ContractFixtures.Database, AutoCloseable {
        final InMemoryEngine engine = new InMemoryEngine();
        private final ContractFixtures.Database delegate = ContractFixtures.engine(engine);
        private final List<JournalRecord> compacted;
        int consumed;
        OutputDatabase(List<JournalRecord> compacted) { this.compacted = compacted; }
        @Override public CommitResult transact(Transaction request) {
            if (engine.lookupRequest(request.requestId()).isPresent()) { return delegate.transact(request); }
            assertTrue(consumed < compacted.size(), "Fixture requested a commit absent from compactor output");
            var record = compacted.get(consumed);
            assertEquals(request, record.transaction(), "The rebuilt state must consume actual compacted requests");
            assertEquals(engine.head().sequence() + 1, record.sequence());
            var result = delegate.transact(record.transaction());
            assertFalse(result.replayed());
            assertEquals(record.sequence(), result.position().sequence());
            consumed++;
            return result;
        }
        @Override public CommitPosition openView(ReadCutoff cutoff) { return delegate.openView(cutoff); }
        @Override public TemporalResult query(CommitPosition position, TemporalQuery query) { return delegate.query(position, query); }
        @Override public Optional<CommitPosition> lookupRequest(RequestId id) { return delegate.lookupRequest(id); }
        @Override public void close() { engine.close(); }
    }
}
