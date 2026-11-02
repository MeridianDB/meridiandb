// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meridiandb.engine.InMemoryEngine;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.EntityValue;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.ValuePredicate;
import org.meridiandb.temporal.WriteOperation;
import org.meridiandb.workload.WorkloadCorpus.Profile;

@Timeout(90)
class WorkloadConformanceTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(Profile.class)
    void memoryAndPreparedReferenceAnswersAgreeWithIndependentForwardCells(Profile profile) {
        var corpus = WorkloadCorpus.generate(profile.spec(42));
        var prepared = WorkloadOracle.prepare(corpus);
        var snapshots = denseSnapshots(corpus);
        try (var engine = new InMemoryEngine()) {
            var lineage = engine.head().databaseId();
            for (var expected : prepared.outcomes()) {
                switch (expected) {
                    case WorkloadOracle.ExpectedSubmit submit -> {
                        var actual = engine.transact(submit.action().request());
                        assertEquals(lineage, actual.position().databaseId());
                        assertEquals(submit.result().position().sequence(), actual.position().sequence());
                        assertEquals(submit.result().replayed(), actual.replayed());
                    }
                    case WorkloadOracle.ExpectedRead read -> {
                        assertEquals(denseQuery(snapshots.get(Math.toIntExact(read.action().cutoff())), read.action().query()),
                                read.result(), profile + " dense/reference at " + read.action().cutoff());
                        try (var view = engine.openView(new ReadCutoff.At(new CommitPosition(lineage, read.action().cutoff())))) {
                            assertEquals(read.result(), view.query(read.action().query()));
                        }
                    }
                }
            }
            assertEquals(corpus.spec().commits(), engine.head().sequence());
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void actualLocalReopenPreservesEveryScheduledAnswerRetryAndExactJournalImage(Profile profile)
            throws IOException, NoSuchAlgorithmException {
        var corpus = WorkloadCorpus.generate(profile.spec(42));
        var prepared = WorkloadOracle.prepare(corpus);
        Path home = Files.createDirectory(directory.resolve(profile.name()));
        try {
            LocalDatabase database = LocalDatabase.create(home, WorkloadCorpus.DATABASE);
            try {
                for (var expected : prepared.outcomes()) {
                    switch (expected) {
                        case WorkloadOracle.ExpectedSubmit submit -> {
                            assertEquals(submit.result(), database.transact(submit.action().request()));
                            assertEquals(java.util.Optional.of(submit.result().position()),
                                    database.lookupRequest(submit.action().request().requestId()));
                            if (!submit.result().replayed() && submit.result().position().sequence() % 16 == 0) {
                                database.close();
                                database = LocalDatabase.openWritable(home);
                            }
                        }
                        case WorkloadOracle.ExpectedRead read -> {
                            try (var view = database.openView(cutoff(read.action().cutoff()))) {
                                assertEquals(read.result(), view.query(read.action().query()));
                            }
                        }
                    }
                }
                assertEquals(corpus.spec().commits(), database.head().sequence());
            } finally {
                database.close();
            }
            // Inspect raw bytes only after every channel owner has closed.
            byte[] journal = Files.readAllBytes(home.resolve("transactions.journal"));
            assertEquals(corpus.encodedHistoryBytes(), journal.length);
            assertEquals(corpus.historyFingerprint(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(journal)));
            try (var reopened = LocalDatabase.openWritable(home)) {
                for (var expected : prepared.outcomes()) {
                    if (expected instanceof WorkloadOracle.ExpectedRead read) {
                        try (var view = reopened.openView(cutoff(read.action().cutoff()))) {
                            assertEquals(read.result(), view.query(read.action().query()), "Final historical read sweep");
                        }
                    }
                }
                assertEquals(corpus.spec().commits(), reopened.head().sequence());
            }
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory synchronization is unavailable: " + unsupported);
        }
    }

    @Test
    void realizedProfilesExposeEffectsAndDenominatorsInsteadOfAssumingConfiguredPercentages() {
        for (Profile profile : Profile.values()) {
            var corpus = WorkloadCorpus.generate(profile.spec(42));
            var summary = WorkloadOracle.prepare(corpus).summary();
            assertEquals(128, summary.commits());
            assertEquals(64, summary.reads());
            assertEquals(summary.attempts(), summary.commits() + summary.retries());
            assertEquals(summary.commits(), summary.writeOperations());
            assertEquals(summary.writeOperations(), summary.putOperations() + summary.retractOperations());
            assertEquals(summary.noOpCommits(), summary.noOpPuts() + summary.noOpRetractions());
            assertEquals(summary.writeOperations(), summary.entityTouches().values().stream().mapToLong(Long::longValue).sum());
            assertEquals(summary.attempts(), summary.scheduledIntents().values().stream().mapToLong(Long::longValue).sum());
            assertEquals(summary.reads(), summary.readOperators().values().stream().mapToLong(Long::longValue).sum());
            assertEquals(summary.reads(), summary.headReads() + summary.historicalReads());
            assertEquals(summary.reads(), summary.emptyResults() + summary.nonemptyResults());
            assertEquals(summary.readOperators().get(WorkloadOracle.ReadOperator.GET).longValue(),
                    summary.pointHits() + summary.pointMisses());
            assertTrue(summary.readOperators().values().stream().allMatch(count -> count > 0));
            assertTrue(summary.historicalReads() > 0 && summary.headReads() > 0);
            assertTrue(summary.pointMisses() > 0);
            if (profile == Profile.APPEND) {
                assertEquals(0, summary.geometricOverlaps());
                assertEquals(0, summary.noOpCommits());
                assertEquals(0, summary.retractOperations());
            } else {
                assertTrue(summary.putsReplacingPresent() > summary.noOpPuts());
                assertTrue(summary.retractionsRemovingPresent() > 0);
                assertTrue(summary.noOpCommits() > 0);
            }
            if (profile == Profile.HOT_ENTITY) {
                assertTrue(summary.entityTouches().get("e-0") * 100 >= summary.writeOperations() * 60);
            }
        }
    }

    private static ReadCutoff cutoff(long sequence) {
        return new ReadCutoff.At(new CommitPosition(WorkloadCorpus.DATABASE, sequence));
    }

    /** Deliberately finite forward cell assignment; no interval splitting or backward search. */
    private static List<Long[][]> denseSnapshots(WorkloadCorpus corpus) {
        int width = (corpus.spec().commits() + 3) * corpus.spec().intervalWidth();
        var snapshots = new ArrayList<Long[][]>();
        snapshots.add(new Long[corpus.spec().entities()][width]);
        for (var action : corpus.actions()) {
            if (action instanceof WorkloadCorpus.Submit submit && !submit.retry()) {
                Long[][] prior = snapshots.getLast();
                var next = new Long[prior.length][];
                for (int entity = 0; entity < prior.length; entity++) { next[entity] = prior[entity].clone(); }
                var operation = submit.request().operations().getFirst();
                int entity = Integer.parseInt(operation.entity().value().substring(2));
                long from = operation.interval().fromInclusiveMicros();
                long to = operation.interval().toExclusiveMicros();
                Long value = operation instanceof WriteOperation.Put put ? put.value() : null;
                for (long instant = from; instant < to; instant++) { next[entity][Math.toIntExact(instant)] = value; }
                snapshots.add(next);
            }
        }
        return snapshots;
    }

    private static Long value(Long[][] snapshot, EntityId entity, long point) {
        if (!entity.value().startsWith("e-")) { return null; }
        return snapshot[Integer.parseInt(entity.value().substring(2))][Math.toIntExact(point)];
    }

    private static boolean matches(ValuePredicate predicate, long value) {
        return switch (predicate.operator()) {
            case EQ -> value == predicate.operand();
            case NE -> value != predicate.operand();
            case LT -> value < predicate.operand();
            case LE -> value <= predicate.operand();
            case GT -> value > predicate.operand();
            case GE -> value >= predicate.operand();
        };
    }

    private static List<TemporalSegment> segments(Long[][] snapshot, EntityId entity, ValidTimeInterval window) {
        var result = new ArrayList<TemporalSegment>();
        long start = window.fromInclusiveMicros();
        long to = window.toExclusiveMicros();
        while (start < to) {
            Long current = value(snapshot, entity, start);
            long end = start + 1;
            while (end < to && java.util.Objects.equals(current, value(snapshot, entity, end))) { end++; }
            if (current != null) { result.add(new TemporalSegment(new ValidTimeInterval(start, end), current)); }
            start = end;
        }
        return result;
    }

    private static TemporalResult denseQuery(Long[][] snapshot, TemporalQuery query) {
        return switch (query) {
            case TemporalQuery.Get get -> {
                Long value = value(snapshot, get.entity(), get.validAtMicros());
                yield new TemporalResult.Point(value == null ? OptionalLong.empty() : OptionalLong.of(value));
            }
            case TemporalQuery.Scan scan -> new TemporalResult.Segments(segments(snapshot, scan.entity(), scan.window()));
            case TemporalQuery.FilterScan scan -> new TemporalResult.Segments(segments(snapshot, scan.entity(), scan.window())
                    .stream().filter(segment -> matches(scan.predicate(), segment.value())).toList());
            case TemporalQuery.Select select -> {
                var rows = new ArrayList<EntityValue>();
                for (var entity : select.entities()) {
                    Long value = value(snapshot, entity, select.validAtMicros());
                    if (value != null && matches(select.predicate(), value)) { rows.add(new EntityValue(entity, value)); }
                }
                rows.sort(java.util.Comparator.comparing(row -> row.entity().value()));
                yield new TemporalResult.Rows(rows);
            }
            case TemporalQuery.Sum sum -> {
                long count = 0;
                BigInteger total = BigInteger.ZERO;
                for (var entity : sum.entities()) {
                    Long value = value(snapshot, entity, sum.validAtMicros());
                    if (value != null) { count++; total = total.add(BigInteger.valueOf(value)); }
                }
                yield new TemporalResult.Aggregate(count, total);
            }
        };
    }
}
