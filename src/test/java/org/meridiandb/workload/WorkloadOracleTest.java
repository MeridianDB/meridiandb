// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.EntityValue;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.ValuePredicate;
import org.meridiandb.temporal.WriteOperation;

class WorkloadOracleTest {
    private static final EntityId ACCOUNT = new EntityId("\uE000");
    private static final EntityId OTHER = new EntityId("\uD800\uDC00");

    @Test
    void literalCorrectionsHistoricalReadsRetriesAndNewNoOpsHaveExactAnswers() {
        var corpus = literalCorpus();
        var prepared = WorkloadOracle.prepare(corpus);
        assertEquals(corpus.actions().size(), prepared.outcomes().size());
        for (int index = 0; index < prepared.outcomes().size(); index++) {
            var expected = prepared.outcomes().get(index);
            var action = switch (expected) {
                case WorkloadOracle.ExpectedSubmit submit -> submit.action();
                case WorkloadOracle.ExpectedRead read -> read.action();
            };
            assertSame(corpus.actions().get(index), action);
        }
        assertEquals(List.of(commit(1, false), commit(2, false), commit(3, false), commit(1, true),
                commit(4, false), commit(5, false), commit(6, false)), prepared.outcomes().stream()
                .filter(WorkloadOracle.ExpectedSubmit.class::isInstance)
                .map(WorkloadOracle.ExpectedSubmit.class::cast).map(WorkloadOracle.ExpectedSubmit::result).toList());
        assertEquals(List.of(
                new TemporalResult.Point(OptionalLong.of(10)),
                new TemporalResult.Segments(List.of(segment(0, 4, 10))),
                new TemporalResult.Segments(List.of(segment(0, 1, 10), segment(1, 3, 20), segment(3, 4, 10))),
                new TemporalResult.Point(OptionalLong.empty()),
                new TemporalResult.Point(OptionalLong.of(20)),
                new TemporalResult.Rows(List.of(new EntityValue(OTHER, 0))),
                new TemporalResult.Segments(List.of()),
                new TemporalResult.Aggregate(1, BigInteger.ZERO),
                new TemporalResult.Aggregate(0, BigInteger.ZERO)), prepared.outcomes().stream()
                .filter(WorkloadOracle.ExpectedRead.class::isInstance)
                .map(WorkloadOracle.ExpectedRead.class::cast).map(WorkloadOracle.ExpectedRead::result).toList());
    }

    @Test
    void literalMetadataCountsOriginalCommittedEffectsSeparatelyFromRetryAndIntentLabels() {
        var summary = WorkloadOracle.prepare(literalCorpus()).summary();
        assertEquals(7, summary.attempts());
        assertEquals(6, summary.commits());
        assertEquals(1, summary.retries());
        assertEquals(6, summary.writeOperations());
        assertEquals(9, summary.reads());
        assertEquals(4, summary.putOperations());
        assertEquals(2, summary.retractOperations());
        assertEquals(2, summary.noOpCommits());
        assertEquals(1, summary.noOpPuts());
        assertEquals(1, summary.noOpRetractions());
        assertEquals(2, summary.putsReplacingPresent(), "The repeated present put also overlaps this count");
        assertEquals(1, summary.retractionsRemovingPresent());
        assertEquals(4, summary.geometricOverlaps());
        assertEquals(Map.of(ACCOUNT.value(), 5L, OTHER.value(), 1L), summary.entityTouches());
        assertEquals(List.of(OTHER.value(), ACCOUNT.value()), new ArrayList<>(summary.entityTouches().keySet()),
                "UTF-16 natural order puts the supplementary entity before the BMP private-use entity");
        assertEquals(Map.of(WorkloadCorpus.Intent.SEED, 2L, WorkloadCorpus.Intent.APPEND, 0L,
                WorkloadCorpus.Intent.CORRECTION, 1L, WorkloadCorpus.Intent.RETRACTION, 1L,
                WorkloadCorpus.Intent.REPEAT, 2L, WorkloadCorpus.Intent.RETRY, 1L), summary.scheduledIntents());
        assertEquals(Map.of(WorkloadOracle.ReadOperator.GET, 3L, WorkloadOracle.ReadOperator.SCAN, 2L,
                WorkloadOracle.ReadOperator.SELECT, 1L, WorkloadOracle.ReadOperator.FILTER_SCAN, 1L,
                WorkloadOracle.ReadOperator.SUM, 2L), summary.readOperators());
        assertEquals(3, summary.historicalReads());
        assertEquals(6, summary.headReads());
        assertEquals(2, summary.pointHits());
        assertEquals(1, summary.pointMisses());
        assertEquals(3, summary.emptyResults());
        assertEquals(6, summary.nonemptyResults(), "Count-one sum-zero is a nonempty result");
    }

    @Test
    void aScheduledRetractionThatActuallyPutsDoesNotInventRetractionOrGeometricEffects() {
        var first = put("seed", ACCOUNT, 0, 2, 3);
        var fallback = put("restore", ACCOUNT, 2, 4, 4);
        var corpus = new WorkloadCorpus(spec(1, 2, 0), List.of(
                submit(1, first, WorkloadCorpus.Intent.SEED), submit(2, fallback, WorkloadCorpus.Intent.RETRACTION)));
        var summary = WorkloadOracle.prepare(corpus).summary();
        assertEquals(1, summary.scheduledIntents().get(WorkloadCorpus.Intent.RETRACTION));
        assertEquals(2, summary.putOperations());
        assertEquals(0, summary.retractOperations());
        assertEquals(0, summary.retractionsRemovingPresent());
        assertEquals(0, summary.putsReplacingPresent());
        assertEquals(0, summary.geometricOverlaps(), "Adjacent half-open intervals do not geometrically overlap");
        assertEquals(0, summary.noOpCommits());
    }

    @Test
    void geometricOverlapIncludesPriorAbsentRetractionsWithoutCallingThemPresentState() {
        var corpus = new WorkloadCorpus(spec(1, 3, 0), List.of(
                submit(1, put("seed", ACCOUNT, 0, 1, 3), WorkloadCorpus.Intent.SEED),
                submit(2, retract("absent", ACCOUNT, 2, 4), WorkloadCorpus.Intent.RETRACTION),
                submit(3, put("put-after-retract", ACCOUNT, 3, 4, 4), WorkloadCorpus.Intent.CORRECTION)));
        var summary = WorkloadOracle.prepare(corpus).summary();
        assertEquals(1, summary.geometricOverlaps());
        assertEquals(0, summary.putsReplacingPresent());
        assertEquals(0, summary.retractionsRemovingPresent());
        assertEquals(1, summary.noOpCommits());
        assertEquals(1, summary.noOpRetractions());
        assertEquals(Map.of(ACCOUNT.value(), 3L), summary.entityTouches());
    }

    @Test
    void aggregateExpectedAnswersPreserveExactSumsBeyondSignedLong() {
        var corpus = new WorkloadCorpus(spec(2, 3, 1), List.of(
                submit(1, put("left", ACCOUNT, 0, 1, Long.MAX_VALUE), WorkloadCorpus.Intent.SEED),
                submit(2, put("right", OTHER, 0, 1, Long.MAX_VALUE), WorkloadCorpus.Intent.SEED),
                submit(3, put("repeat", ACCOUNT, 0, 1, Long.MAX_VALUE), WorkloadCorpus.Intent.REPEAT),
                new WorkloadCorpus.Read(3, new TemporalQuery.Sum(List.of(ACCOUNT, OTHER), 0))));
        var prepared = WorkloadOracle.prepare(corpus);
        var answer = (WorkloadOracle.ExpectedRead) prepared.outcomes().getLast();
        assertEquals(new TemporalResult.Aggregate(2, new BigInteger("18446744073709551614")), answer.result());
        assertEquals(1, prepared.summary().nonemptyResults());
        assertEquals(0, prepared.summary().emptyResults());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sequence", "retry-new-request", "retry-missing-flag", "retry-sequence", "conflicting-request", "future-read"})
    void inconsistentInputDeclarationsAreRejectedBeforeAnyPreparedResultEscapes(String defect) {
        var first = put("first", ACCOUNT, 0, 4, 10);
        var second = put("second", ACCOUNT, 0, 4, 20);
        var actions = new ArrayList<WorkloadCorpus.Action>();
        actions.add(submit(1, first, WorkloadCorpus.Intent.SEED));
        int reads = 0;
        switch (defect) {
            case "sequence" -> actions.add(submit(1, second, WorkloadCorpus.Intent.CORRECTION));
            case "retry-new-request" -> {
                actions.add(submit(2, second, WorkloadCorpus.Intent.RETRY));
                actions.add(submit(2, put("third", ACCOUNT, 0, 4, 30), WorkloadCorpus.Intent.CORRECTION));
            }
            case "retry-missing-flag" -> actions.add(submit(1, first, WorkloadCorpus.Intent.REPEAT));
            case "retry-sequence" -> {
                actions.add(submit(2, first, WorkloadCorpus.Intent.RETRY));
                actions.add(submit(2, second, WorkloadCorpus.Intent.CORRECTION));
            }
            case "conflicting-request" -> actions.add(submit(2, put("first", ACCOUNT, 0, 4, 20), WorkloadCorpus.Intent.CORRECTION));
            case "future-read" -> {
                actions.add(new WorkloadCorpus.Read(2, new TemporalQuery.Get(ACCOUNT, 1)));
                actions.add(submit(2, second, WorkloadCorpus.Intent.CORRECTION));
                reads = 1;
            }
            default -> throw new AssertionError(defect);
        }
        var corpus = new WorkloadCorpus(spec(1, 2, reads), actions);
        assertThrows(IllegalArgumentException.class, () -> WorkloadOracle.prepare(corpus));
    }

    @Test
    void preparedOutcomesAndEveryMetadataMapAreDefensivelyImmutable() {
        var prepared = WorkloadOracle.prepare(literalCorpus());
        var outcomes = new ArrayList<>(prepared.outcomes());
        var copied = new WorkloadOracle.Prepared(outcomes, prepared.summary());
        outcomes.clear();
        assertEquals(16, copied.outcomes().size());
        assertThrows(UnsupportedOperationException.class, () -> copied.outcomes().clear());
        var source = prepared.summary();
        var entities = new HashMap<>(source.entityTouches());
        var intents = new EnumMap<>(source.scheduledIntents());
        var operators = new EnumMap<>(source.readOperators());
        var summary = new WorkloadOracle.Summary(source.attempts(), source.commits(), source.retries(), source.writeOperations(),
                source.reads(), source.noOpCommits(), source.putOperations(), source.retractOperations(), source.noOpPuts(),
                source.noOpRetractions(), source.putsReplacingPresent(), source.retractionsRemovingPresent(), source.geometricOverlaps(),
                entities, intents, operators, source.historicalReads(), source.headReads(), source.pointHits(), source.pointMisses(),
                source.emptyResults(), source.nonemptyResults());
        entities.clear();
        intents.clear();
        operators.clear();
        assertEquals(source.entityTouches(), summary.entityTouches());
        assertEquals(source.scheduledIntents(), summary.scheduledIntents());
        assertEquals(source.readOperators(), summary.readOperators());
        assertThrows(UnsupportedOperationException.class, () -> summary.entityTouches().clear());
        assertThrows(UnsupportedOperationException.class, () -> summary.scheduledIntents().clear());
        assertThrows(UnsupportedOperationException.class, () -> summary.readOperators().clear());
    }

    @Test
    void requiredInputsAndOutcomeValuesRejectNull() {
        assertThrows(NullPointerException.class, () -> WorkloadOracle.prepare(null));
        assertThrows(NullPointerException.class, () -> new WorkloadOracle.Prepared(List.of(), null));
        assertThrows(NullPointerException.class, () -> new WorkloadOracle.ExpectedSubmit(null, commit(1, false)));
        assertThrows(NullPointerException.class, () -> new WorkloadOracle.ExpectedRead(new WorkloadCorpus.Read(0,
                new TemporalQuery.Get(ACCOUNT, 0)), null));
        assertTrue(WorkloadOracle.prepare(literalCorpus()).summary().entityTouches().containsKey(ACCOUNT.value()));
    }

    private static WorkloadCorpus literalCorpus() {
        var seed = put("seed", ACCOUNT, 0, 4, 10);
        return new WorkloadCorpus(spec(2, 6, 9), List.of(
                submit(1, seed, WorkloadCorpus.Intent.SEED),
                new WorkloadCorpus.Read(1, new TemporalQuery.Get(ACCOUNT, 1)),
                submit(2, put("other", OTHER, 0, 4, 0), WorkloadCorpus.Intent.SEED),
                submit(3, put("correction", ACCOUNT, 1, 3, 20), WorkloadCorpus.Intent.CORRECTION),
                new WorkloadCorpus.Read(1, new TemporalQuery.Scan(ACCOUNT, window(0, 4))),
                new WorkloadCorpus.Read(3, new TemporalQuery.Scan(ACCOUNT, window(0, 4))),
                submit(1, seed, WorkloadCorpus.Intent.RETRY),
                submit(4, put("new-repeat", ACCOUNT, 1, 3, 20), WorkloadCorpus.Intent.REPEAT),
                submit(5, retract("remove", ACCOUNT, 0, 4), WorkloadCorpus.Intent.RETRACTION),
                new WorkloadCorpus.Read(5, new TemporalQuery.Get(ACCOUNT, 1)),
                new WorkloadCorpus.Read(3, new TemporalQuery.Get(ACCOUNT, 1)),
                submit(6, retract("new-absent-repeat", ACCOUNT, 0, 4), WorkloadCorpus.Intent.REPEAT),
                new WorkloadCorpus.Read(6, new TemporalQuery.Select(List.of(ACCOUNT, OTHER), 1,
                        new ValuePredicate(ValuePredicate.Operator.EQ, 0))),
                new WorkloadCorpus.Read(6, new TemporalQuery.FilterScan(ACCOUNT, window(0, 4),
                        new ValuePredicate(ValuePredicate.Operator.GT, 0))),
                new WorkloadCorpus.Read(6, new TemporalQuery.Sum(List.of(ACCOUNT, OTHER), 1)),
                new WorkloadCorpus.Read(0, new TemporalQuery.Sum(List.of(ACCOUNT, OTHER), 1))));
    }

    private static WorkloadCorpus.Spec spec(int entities, int commits, int reads) {
        return new WorkloadCorpus.Spec(42, entities, commits, reads, 4, 30, 30, 10, 10, 50, 50);
    }

    private static WorkloadCorpus.Submit submit(long sequence, Transaction request, WorkloadCorpus.Intent intent) {
        return new WorkloadCorpus.Submit(sequence, request,
                intent == WorkloadCorpus.Intent.SEED ? WorkloadCorpus.Stage.SEED : WorkloadCorpus.Stage.MIXED, intent);
    }

    private static Transaction put(String request, EntityId entity, long from, long to, long value) {
        return new Transaction(new RequestId(request), List.of(new WriteOperation.Put(entity, window(from, to), value)));
    }

    private static Transaction retract(String request, EntityId entity, long from, long to) {
        return new Transaction(new RequestId(request), List.of(new WriteOperation.Retract(entity, window(from, to))));
    }

    private static ValidTimeInterval window(long from, long to) { return new ValidTimeInterval(from, to); }
    private static TemporalSegment segment(long from, long to, long value) { return new TemporalSegment(window(from, to), value); }
    private static CommitResult commit(long sequence, boolean replayed) {
        return new CommitResult(new CommitPosition(WorkloadCorpus.DATABASE, sequence), replayed);
    }
}
