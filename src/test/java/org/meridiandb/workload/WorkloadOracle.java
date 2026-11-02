// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.meridiandb.reference.ReferenceModel;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Independent expected answers and realized coverage, prepared without production execution or clocks. */
public final class WorkloadOracle {
    public enum ReadOperator { GET, SCAN, SELECT, FILTER_SCAN, SUM }

    public sealed interface Expected permits ExpectedSubmit, ExpectedRead { }

    public record ExpectedSubmit(WorkloadCorpus.Submit action, CommitResult result) implements Expected {
        public ExpectedSubmit {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(result, "result");
        }
    }

    public record ExpectedRead(WorkloadCorpus.Read action, TemporalResult result) implements Expected {
        public ExpectedRead {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(result, "result");
        }
    }

    public record Prepared(List<Expected> outcomes, Summary summary) {
        public Prepared {
            outcomes = List.copyOf(outcomes);
            Objects.requireNonNull(summary, "summary");
        }
    }

    /**
     * Write counters describe original committed operations; retries only add attempts and their
     * scheduled RETRY label. Intent labels are configured scheduling, not evidence of an operation's
     * effects. Presence, no-op and geometric-overlap counts overlap: replacing a present value can
     * be a no-op, and an absent retraction can overlap earlier history. Entity keys use UTF-16 order.
     */
    public record Summary(long attempts, long commits, long retries, long writeOperations, long reads,
                          long noOpCommits, long putOperations, long retractOperations,
                          long noOpPuts, long noOpRetractions, long putsReplacingPresent,
                          long retractionsRemovingPresent, long geometricOverlaps,
                          Map<String, Long> entityTouches, Map<WorkloadCorpus.Intent, Long> scheduledIntents,
                          Map<ReadOperator, Long> readOperators, long historicalReads, long headReads,
                          long pointHits, long pointMisses, long emptyResults, long nonemptyResults) {
        public Summary {
            entityTouches = Collections.unmodifiableMap(new TreeMap<>(entityTouches));
            var intents = new EnumMap<WorkloadCorpus.Intent, Long>(WorkloadCorpus.Intent.class);
            intents.putAll(scheduledIntents);
            scheduledIntents = Collections.unmodifiableMap(intents);
            var operators = new EnumMap<ReadOperator, Long>(ReadOperator.class);
            operators.putAll(readOperators);
            readOperators = Collections.unmodifiableMap(operators);
        }
    }

    private WorkloadOracle() { }

    /** Refuse inconsistent declarations before exposing any prepared answers or metadata. */
    public static Prepared prepare(WorkloadCorpus corpus) {
        Objects.requireNonNull(corpus, "corpus");
        var model = new ReferenceModel(WorkloadCorpus.DATABASE);
        var outcomes = new ArrayList<Expected>(corpus.actions().size());
        var counts = new Counts();
        var priorIntervals = new HashMap<String, List<ValidTimeInterval>>();
        for (var action : corpus.actions()) {
            switch (action) {
                case WorkloadCorpus.Submit submit -> {
                    var operation = submit.request().operations().getFirst();
                    boolean knownIdentity = model.lookupRequest(submit.request().requestId()).isPresent();
                    TemporalResult.Segments before = knownIdentity ? null : scan(model, model.head(), operation);
                    final CommitResult result;
                    try {
                        result = model.transact(submit.request());
                    } catch (ReferenceModel.Rejected rejected) {
                        throw new IllegalArgumentException("Workload request is invalid: " + rejected.code(), rejected);
                    }
                    if (result.position().sequence() != submit.sequence() || result.replayed() != submit.retry()) {
                        throw new IllegalArgumentException("Submission sequence or retry declaration differs from reference result");
                    }
                    counts.attempts++;
                    counts.intents.merge(submit.intent(), 1L, Long::sum);
                    if (result.replayed()) {
                        counts.retries++;
                    } else {
                        counts.commits++;
                        counts.writeOperations++;
                        var after = scan(model, model.head(), operation);
                        boolean noOp = before.equals(after);
                        if (noOp) { counts.noOpCommits++; }
                        boolean present = !before.values().isEmpty();
                        switch (operation) {
                            case WriteOperation.Put ignored -> {
                                counts.putOperations++;
                                if (noOp) { counts.noOpPuts++; }
                                if (present) { counts.putsReplacingPresent++; }
                            }
                            case WriteOperation.Retract ignored -> {
                                counts.retractOperations++;
                                if (noOp) { counts.noOpRetractions++; }
                                if (present) { counts.retractionsRemovingPresent++; }
                            }
                        }
                        String entity = operation.entity().value();
                        counts.touches.merge(entity, 1L, Long::sum);
                        var prior = priorIntervals.computeIfAbsent(entity, ignored -> new ArrayList<>());
                        if (prior.stream().anyMatch(interval -> overlaps(interval, operation.interval()))) {
                            counts.geometricOverlaps++;
                        }
                        prior.add(operation.interval());
                    }
                    outcomes.add(new ExpectedSubmit(submit, result));
                }
                case WorkloadCorpus.Read read -> {
                    long head = model.head().sequence();
                    if (read.cutoff() > head) {
                        throw new IllegalArgumentException("Read cutoff is unavailable at its scheduled action");
                    }
                    var position = new CommitPosition(WorkloadCorpus.DATABASE, read.cutoff());
                    var result = model.query(position, read.query());
                    counts.reads++;
                    counts.operators.merge(operator(read.query()), 1L, Long::sum);
                    if (read.cutoff() < head) { counts.historicalReads++; }
                    else { counts.headReads++; }
                    if (result instanceof TemporalResult.Point point) {
                        if (point.value().isPresent()) { counts.pointHits++; }
                        else { counts.pointMisses++; }
                    }
                    if (empty(result)) { counts.emptyResults++; }
                    else { counts.nonemptyResults++; }
                    outcomes.add(new ExpectedRead(read, result));
                }
            }
        }
        if (model.head().sequence() != corpus.spec().commits()) {
            throw new IllegalArgumentException("Reference head differs from declared workload commits");
        }
        return new Prepared(outcomes, counts.summary());
    }

    private static TemporalResult.Segments scan(ReferenceModel model, CommitPosition position, WriteOperation operation) {
        return (TemporalResult.Segments) model.query(position, new TemporalQuery.Scan(operation.entity(), operation.interval()));
    }

    private static boolean overlaps(ValidTimeInterval left, ValidTimeInterval right) {
        // Corpus v1 validates finite short windows, so no sentinel arithmetic or endpoint widening is needed.
        return left.fromInclusiveMicros() < right.toExclusiveMicros()
                && right.fromInclusiveMicros() < left.toExclusiveMicros();
    }

    private static ReadOperator operator(TemporalQuery query) {
        return switch (query) {
            case TemporalQuery.Get ignored -> ReadOperator.GET;
            case TemporalQuery.Scan ignored -> ReadOperator.SCAN;
            case TemporalQuery.Select ignored -> ReadOperator.SELECT;
            case TemporalQuery.FilterScan ignored -> ReadOperator.FILTER_SCAN;
            case TemporalQuery.Sum ignored -> ReadOperator.SUM;
        };
    }

    private static boolean empty(TemporalResult result) {
        return switch (result) {
            case TemporalResult.Point point -> point.value().isEmpty();
            case TemporalResult.Segments segments -> segments.values().isEmpty();
            case TemporalResult.Rows rows -> rows.values().isEmpty();
            case TemporalResult.Aggregate aggregate -> aggregate.count() == 0;
        };
    }

    private static final class Counts {
        long attempts;
        long commits;
        long retries;
        long writeOperations;
        long reads;
        long noOpCommits;
        long putOperations;
        long retractOperations;
        long noOpPuts;
        long noOpRetractions;
        long putsReplacingPresent;
        long retractionsRemovingPresent;
        long geometricOverlaps;
        long historicalReads;
        long headReads;
        long pointHits;
        long pointMisses;
        long emptyResults;
        long nonemptyResults;
        final Map<String, Long> touches = new TreeMap<>();
        final EnumMap<WorkloadCorpus.Intent, Long> intents = new EnumMap<>(WorkloadCorpus.Intent.class);
        final EnumMap<ReadOperator, Long> operators = new EnumMap<>(ReadOperator.class);
        Counts() {
            for (var intent : WorkloadCorpus.Intent.values()) { intents.put(intent, 0L); }
            for (var operator : ReadOperator.values()) { operators.put(operator, 0L); }
        }
        Summary summary() {
            return new Summary(attempts, commits, retries, writeOperations, reads, noOpCommits,
                    putOperations, retractOperations, noOpPuts, noOpRetractions, putsReplacingPresent,
                    retractionsRemovingPresent, geometricOverlaps, touches, intents, operators,
                    historicalReads, headReads, pointHits, pointMisses, emptyResults, nonemptyResults);
        }
    }
}
