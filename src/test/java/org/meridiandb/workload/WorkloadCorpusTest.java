// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.ValuePredicate;
import org.meridiandb.temporal.WriteOperation;
import org.meridiandb.workload.WorkloadCorpus.Intent;
import org.meridiandb.workload.WorkloadCorpus.Profile;
import org.meridiandb.workload.WorkloadCorpus.Read;
import org.meridiandb.workload.WorkloadCorpus.Spec;
import org.meridiandb.workload.WorkloadCorpus.Stage;
import org.meridiandb.workload.WorkloadCorpus.Submit;

@Timeout(30)
class WorkloadCorpusTest {
    @ParameterizedTest
    @EnumSource(Profile.class)
    void everyNamedProfileHasRepeatableInterleavedActionsAndExactDeclaredCounts(Profile profile) {
        var corpus = WorkloadCorpus.generate(profile.spec(42));
        var other = WorkloadCorpus.generate(profile.spec(42));
        assertEquals(corpus.actions(), other.actions());
        assertEquals(corpus.fingerprint(), other.fingerprint());
        assertEquals(corpus.historyFingerprint(), other.historyFingerprint());
        var original = new HashMap<RequestId, Submit>();
        int reads = 0;
        long encoded = JournalCodec.encodeHeader(WorkloadCorpus.DATABASE).length;
        boolean readBeforeFinalCommit = false;
        for (var action : corpus.actions()) {
            switch (action) {
                case Submit submit -> {
                    if (submit.retry()) {
                        Submit previous = original.get(submit.request().requestId());
                        assertEquals(previous.sequence(), submit.sequence());
                        assertEquals(previous.request(), submit.request());
                    } else {
                        assertEquals(original.size() + 1L, submit.sequence());
                        assertTrue(original.put(submit.request().requestId(), submit) == null);
                        encoded += JournalCodec.encodedRecordBytes(new JournalRecord(submit.sequence(), submit.request()));
                    }
                }
                case Read read -> {
                    assertTrue(read.cutoff() <= original.size());
                    assertTrue(original.size() > corpus.spec().entities());
                    readBeforeFinalCommit |= original.size() < corpus.spec().commits();
                    reads++;
                }
            }
        }
        assertEquals(128, original.size());
        assertEquals(64, reads);
        assertEquals(encoded, corpus.encodedHistoryBytes());
        assertTrue(readBeforeFinalCommit);
    }

    @Test
    void changingQueryOrRetryChoicesDoesNotPerturbOriginalCommittedHistory() {
        Spec original = Profile.CORRECTIONS.spec(42);
        Spec changed = new Spec(original.seed(), original.entities(), original.commits(), 7,
                original.intervalWidth(), original.correctionPercent(), original.retractionPercent(),
                original.repeatPercent(), 100, original.hotEntityPercent(), 0);
        var a = WorkloadCorpus.generate(original);
        var b = WorkloadCorpus.generate(changed);
        assertEquals(a.historyFingerprint(), b.historyFingerprint());
        assertEquals(a.encodedHistoryBytes(), b.encodedHistoryBytes());
        assertNotEquals(a.fingerprint(), b.fingerprint());
        assertTrue(b.actions().stream().filter(Submit.class::isInstance).map(Submit.class::cast)
                .filter(Submit::retry).count() == changed.commits() - changed.entities());
    }

    @Test
    void randomSeedAliasesAreExplicitAndDoNotPretendToProvideIndependentHistories() {
        var a = Profile.HOT_ENTITY.spec(42);
        var b = Profile.HOT_ENTITY.spec(42 + (1L << 48));
        assertEquals(a.initialRandomState(WorkloadCorpus.WRITE_SALT), b.initialRandomState(WorkloadCorpus.WRITE_SALT));
        assertEquals(a.initialRandomState(WorkloadCorpus.READ_SALT), b.initialRandomState(WorkloadCorpus.READ_SALT));
        var first = WorkloadCorpus.generate(a);
        var second = WorkloadCorpus.generate(b);
        assertEquals(first.actions(), second.actions());
        assertEquals(first.historyFingerprint(), second.historyFingerprint());
        assertNotEquals(first.fingerprint(), second.fingerprint(), "Schedule identity also binds the original specification");
    }

    @Test
    void appendProfileEmitsAdjacentFreshWindowsRatherThanHiddenCorrections() {
        var corpus = WorkloadCorpus.generate(Profile.APPEND.spec(17));
        Map<EntityId, Long> frontier = new HashMap<>();
        for (var action : corpus.actions()) {
            if (action instanceof Submit submit && !submit.retry()) {
                var put = (WriteOperation.Put) submit.request().operations().getFirst();
                assertEquals(frontier.getOrDefault(put.entity(), 0L), put.interval().fromInclusiveMicros());
                frontier.put(put.entity(), put.interval().toExclusiveMicros());
            }
        }
        assertEquals(corpus.spec().entities(), frontier.size());
    }

    @Test
    void emittedPredicatesIncludeAllOperatorsAndExtremeOperands() {
        var corpus = WorkloadCorpus.generate(Profile.CORRECTIONS.spec(42));
        var selections = new HashSet<Long>();
        var filters = new HashSet<Long>();
        var operators = new HashSet<ValuePredicate.Operator>();
        for (var action : corpus.actions()) {
            if (action instanceof Read read) {
                if (read.query() instanceof TemporalQuery.Select select) {
                    selections.add(select.predicate().operand());
                    operators.add(select.predicate().operator());
                } else if (read.query() instanceof TemporalQuery.FilterScan filter) {
                    filters.add(filter.predicate().operand());
                    operators.add(filter.predicate().operator());
                }
            }
        }
        assertTrue(selections.containsAll(List.of(Long.MIN_VALUE, Long.MAX_VALUE)));
        assertTrue(filters.containsAll(List.of(Long.MIN_VALUE, Long.MAX_VALUE)));
        assertEquals(ValuePredicate.Operator.values().length, operators.size());
    }

    @Test
    void dimensionsPercentagesAndAggregateReferenceWorkRejectBeforeGeneration() {
        assertThrows(IllegalArgumentException.class, () -> spec(0, 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> spec(1025, 2048, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> spec(1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> spec(1, 4097, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> spec(1, 2, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> spec(1, 2, 1025, 1));
        assertThrows(IllegalArgumentException.class, () -> spec(1, 2, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> spec(1, 2, 1, 17));
        assertThrows(IllegalArgumentException.class, () -> spec(16, 4096, 1024, 16));
        assertThrows(IllegalArgumentException.class, () -> new Spec(1, 1, 2, 0, 1, -1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Spec(1, 1, 2, 0, 1, 100, 1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Spec(1, 1, 2, 0, 1, 0, 0, 0, 101, 0, 0));
    }

    @Test
    void zeroReadIngestionAndRepeatedNewCommitsHaveDifferentRequestIdentities() {
        var corpus = WorkloadCorpus.generate(new Spec(1, 1, 4, 0, 1, 0, 0, 100, 0, 0, 0));
        var submissions = corpus.actions().stream().map(Submit.class::cast).toList();
        for (int i = 1; i < submissions.size(); i++) {
            assertEquals(submissions.getFirst().request().operations(), submissions.get(i).request().operations());
            assertNotEquals(submissions.getFirst().request().requestId(), submissions.get(i).request().requestId());
            assertEquals(Intent.REPEAT, submissions.get(i).intent());
        }
        assertEquals(4, new HashSet<>(submissions.stream().map(s -> s.request().requestId()).toList()).size());
    }

    @Test
    void constructorCopiesActionsAndRejectsAggregateCountAndWindowOrFanoutViolations() {
        Spec spec = spec(1, 2, 0, 1);
        var mutable = new ArrayList<>(WorkloadCorpus.generate(spec).actions());
        var corpus = new WorkloadCorpus(spec, mutable);
        mutable.clear();
        assertEquals(2, corpus.actions().size());
        assertThrows(UnsupportedOperationException.class, () -> corpus.actions().clear());
        assertThrows(IllegalArgumentException.class, () -> new WorkloadCorpus(spec, List.of()));
        var tooMany = new ArrayList<>(corpus.actions());
        tooMany.addAll(corpus.actions());
        tooMany.add(corpus.actions().getFirst());
        assertThrows(IllegalArgumentException.class, () -> new WorkloadCorpus(spec, tooMany));
        assertThrows(IllegalArgumentException.class, () -> manual("a", new ValidTimeInterval(null, null), 0));
        assertThrows(IllegalArgumentException.class, () -> manual("a", new ValidTimeInterval(Long.MIN_VALUE, Long.MAX_VALUE), 0));
        var fanout = List.of(new EntityId("left"), new EntityId("right"));
        var actions = new ArrayList<WorkloadCorpus.Action>(WorkloadCorpus.generate(spec(1, 2, 1, 1)).actions());
        actions.set(actions.size() - 1, new Read(2, new TemporalQuery.Sum(fanout, 0)));
        assertEquals(3, new WorkloadCorpus(spec(1, 2, 1, 1), actions).actions().size());
        actions.set(actions.size() - 1, new Read(2, new TemporalQuery.Sum(
                List.of(new EntityId("left"), new EntityId("right"), new EntityId("one-too-many")), 0)));
        assertThrows(IllegalArgumentException.class, () -> new WorkloadCorpus(spec(1, 2, 1, 1), actions));
    }

    @Test
    void seedBoundaryRejectsRelabeledMixedWorkAndEarlyRetriesBeforePreparation() {
        var spec = spec(2, 3, 0, 1);
        var generated = WorkloadCorpus.generate(spec);
        var actions = generated.actions();
        var seed = (Submit) actions.getFirst();
        var mixed = (Submit) actions.getLast();
        for (var replacement : List.of(
                new Submit(seed.sequence(), seed.request(), Stage.MIXED, Intent.SEED),
                new Submit(seed.sequence(), seed.request(), Stage.SEED, Intent.CORRECTION),
                new Submit(seed.sequence(), seed.request(), Stage.MIXED, Intent.CORRECTION))) {
            var changed = new ArrayList<>(actions);
            changed.set(0, replacement);
            assertThrows(IllegalArgumentException.class, () -> new WorkloadCorpus(spec, changed));
        }
        for (var replacement : List.of(
                new Submit(mixed.sequence(), mixed.request(), Stage.SEED, mixed.intent()),
                new Submit(mixed.sequence(), mixed.request(), Stage.SEED, Intent.SEED),
                new Submit(mixed.sequence(), mixed.request(), Stage.MIXED, Intent.SEED))) {
            var changed = new ArrayList<>(actions);
            changed.set(changed.size() - 1, replacement);
            assertThrows(IllegalArgumentException.class, () -> new WorkloadCorpus(spec, changed));
        }
        var earlyRetry = new ArrayList<>(actions);
        earlyRetry.add(1, new Submit(seed.sequence(), seed.request(), Stage.MIXED, Intent.RETRY));
        assertThrows(IllegalArgumentException.class, () -> new WorkloadCorpus(spec, earlyRetry));
        var seedRetry = new ArrayList<>(actions);
        seedRetry.add(new Submit(seed.sequence(), seed.request(), Stage.SEED, Intent.RETRY));
        assertThrows(IllegalArgumentException.class, () -> new WorkloadCorpus(spec, seedRetry));
    }

    @Test
    void fingerprintsPreserveExactUtf16AndFiniteExtremeCoordinateWindows() {
        var surrogate = manual(String.valueOf((char) 0xd800), new ValidTimeInterval(Long.MIN_VALUE, Long.MIN_VALUE + 1), Long.MIN_VALUE);
        var replacement = manual("\ufffd", new ValidTimeInterval(Long.MIN_VALUE, Long.MIN_VALUE + 1), Long.MIN_VALUE);
        assertNotEquals(surrogate.fingerprint(), replacement.fingerprint());
        assertNotEquals(surrogate.historyFingerprint(), replacement.historyFingerprint());
        assertNotEquals(manual("a", new ValidTimeInterval(Long.MAX_VALUE - 1, Long.MAX_VALUE), Long.MAX_VALUE).fingerprint(),
                manual("a", new ValidTimeInterval(Long.MAX_VALUE - 2, Long.MAX_VALUE), Long.MAX_VALUE).fingerprint());
    }

    @Test
    void literalV1GoldenPinsDrawOrderCorrectionsRetriesQueriesAndCanonicalBytes() {
        var corpus = WorkloadCorpus.generate(new Spec(7, 1, 4, 5, 2, 100, 0, 0, 100, 100, 100));
        assertEquals("39bd170644930733aef2da90da5ed3d9c1d42bf48c37a42052d19f57e52f2af0", corpus.fingerprint());
        assertEquals("cdbb2a26ffde6b13937bf196fa3fe77630c6b65922696e67073d37050be7a211", corpus.historyFingerprint());
        assertEquals(354, corpus.encodedHistoryBytes());
        var submissions = corpus.actions().stream().filter(Submit.class::isInstance).map(Submit.class::cast).toList();
        assertEquals(List.of(1L, 2L, 2L, 3L, 2L, 4L, 3L), submissions.stream().map(Submit::sequence).toList());
        var originals = submissions.stream().filter(s -> !s.retry()).toList();
        assertEquals(List.of(Long.MIN_VALUE, -2131460139842329618L, 8463413780889623691L, -8675119527219200843L),
                originals.stream().map(s -> ((WriteOperation.Put) s.request().operations().getFirst()).value()).toList());
        assertEquals(List.of(new ValidTimeInterval(0L, 2L), new ValidTimeInterval(1L, 2L),
                        new ValidTimeInterval(0L, 1L), new ValidTimeInterval(1L, 2L)),
                originals.stream().map(s -> s.request().operations().getFirst().interval()).toList());
        var reads = corpus.actions().stream().filter(Read.class::isInstance).map(Read.class::cast).toList();
        assertEquals(List.of(1L, 0L, 1L, 2L, 2L), reads.stream().map(Read::cutoff).toList());
        assertEquals(List.of(new TemporalResult.Point(OptionalLong.empty()), new TemporalResult.Segments(List.of()),
                        new TemporalResult.Rows(List.of()), new TemporalResult.Segments(List.of()),
                        new TemporalResult.Aggregate(1, BigInteger.valueOf(-2131460139842329618L))),
                WorkloadOracle.prepare(corpus).outcomes().stream().filter(WorkloadOracle.ExpectedRead.class::isInstance)
                        .map(WorkloadOracle.ExpectedRead.class::cast).map(WorkloadOracle.ExpectedRead::result).toList());
    }

    private static Spec spec(int entities, int commits, int queries, int width) {
        return new Spec(42, entities, commits, queries, width, 0, 0, 0, 0, 0, 50);
    }

    private static WorkloadCorpus manual(String identity, ValidTimeInterval interval, long at) {
        var entity = new EntityId(identity);
        var put = new WriteOperation.Put(entity, interval, Long.MAX_VALUE);
        int width = interval.fromInclusiveMicros() == null || interval.toExclusiveMicros() == null
                ? 1 : (int) Math.max(1, Math.min(16,
                        interval.toExclusiveMicros() - interval.fromInclusiveMicros()));
        return new WorkloadCorpus(spec(1, 2, 1, width), List.of(
                new Submit(1, new Transaction(new RequestId("a"), List.of(put)), Stage.SEED, Intent.SEED),
                new Submit(2, new Transaction(new RequestId("b"), List.of(put)), Stage.MIXED, Intent.REPEAT),
                new Read(2, new TemporalQuery.Get(entity, at))));
    }
}
