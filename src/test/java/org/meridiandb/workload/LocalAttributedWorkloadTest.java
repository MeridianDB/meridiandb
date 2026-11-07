// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.internal.LocalIo;
import org.meridiandb.storage.internal.LocalIoObservation;
import org.meridiandb.storage.internal.LocalIoObservation.Metric;
import org.meridiandb.storage.internal.LocalIoObservation.Snapshot;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.workload.LocalWorkloadIo.Category;
import org.meridiandb.workload.LocalWorkloadIo.Coverage;
import org.meridiandb.workload.LocalWorkloadIo.Entry;
import org.meridiandb.workload.WorkloadMeasurements.Kind;
import org.meridiandb.workload.WorkloadMeasurements.Phase;

/** Native lifecycle attribution checked against the existing literal four-commit corpus. */
@Timeout(120)
class LocalAttributedWorkloadTest {
    private static final LocalWorkloadRunner.Config ONCE = new LocalWorkloadRunner.Config(0, 1);
    private static final String MODE = "CALLER_THREAD_LOCAL_IO_INSTRUMENTED";
    private static final String SCHEDULE = "39bd170644930733aef2da90da5ed3d9c1d42bf48c37a42052d19f57e52f2af0";
    private static final String HISTORY = "cdbb2a26ffde6b13937bf196fa3fe77630c6b65922696e67073d37050be7a211";

    @TempDir
    Path parent;

    @Test
    void literalCorpusBindsDenominatorsAndOneOrderedRawScopeToEveryOperationSample() throws IOException {
        var result = supported(tiny(), ONCE);
        assertEquals(1, result.schemaVersion());
        assertEquals(MODE, result.mode());
        assertEquals(SCHEDULE, result.workload().fingerprint());
        assertEquals(HISTORY, result.workload().historyFingerprint());
        assertEquals(354, result.workload().encodedHistoryBytes());
        var denominators = result.denominators();
        // workload-v1 has an independent 42-byte lineage header and four 78-byte frames.
        assertEquals(42, denominators.headerBytes());
        assertEquals(78, denominators.seedFrameBytes());
        assertEquals(234, denominators.mixedFrameBytes());
        assertEquals(312, denominators.newFrameBytes());
        assertEquals(354, denominators.encodedHistoryBytes());
        assertEquals(4, denominators.newCommits());
        assertEquals(3, denominators.exactRetries());
        assertEquals(5, denominators.queries());
        assertFalse(result.gaps().isEmpty());
        assertTrace(result);

        var operations = operations(result, 0);
        assertEquals(20, operations.size());
        assertEquals(1, count(operations, Phase.LOAD, Kind.CREATE));
        assertEquals(1, count(operations, Phase.LOAD, Kind.COMMIT));
        assertEquals(3, count(operations, Phase.MIXED, Kind.COMMIT));
        assertEquals(3, count(operations, Phase.MIXED, Kind.RETRY));
        for (var kind : List.of(Kind.GET, Kind.SCAN, Kind.SELECT, Kind.FILTER_SCAN, Kind.SUM)) {
            assertEquals(1, count(operations, Phase.MIXED, kind));
        }
        assertEquals(2, count(operations, Phase.MAINTENANCE, Kind.SEGMENT_WRITE));
    }

    @Test
    void literalCreateAndCommitsCountAllNativeWorkWhileRetriesQueriesAndPurePlanRemainZero() throws IOException {
        var result = supported(tiny(), ONCE);
        var create = operation(result, 0, Kind.CREATE);
        assertWork(create.counters(), LocalIo.Role.JOURNAL, 168, 42, 3);
        assertWork(create.counters(), LocalIo.Role.MANIFEST, 154, 154, 1);
        assertWork(create.counters(), LocalIo.Role.CONTROL, 0, 0, 1);
        assertWork(create.counters(), LocalIo.Role.DIRECTORY, 0, 0, 5);
        assertOnlyRoles(create.counters(), LocalIo.Role.JOURNAL, LocalIo.Role.MANIFEST,
                LocalIo.Role.CONTROL, LocalIo.Role.DIRECTORY);

        var commits = operations(result, 0).stream().filter(entry -> entry.kind() == Kind.COMMIT).toList();
        assertEquals(4, commits.size());
        for (var commit : commits) {
            assertWork(commit.counters(), LocalIo.Role.JOURNAL, 0, 78, 1);
            assertOnlyRoles(commit.counters(), LocalIo.Role.JOURNAL);
        }
        for (var entry : operations(result, 0)) {
            if (Set.of(Kind.RETRY, Kind.GET, Kind.SCAN, Kind.SELECT, Kind.FILTER_SCAN, Kind.SUM,
                    Kind.COMPACTION_PLAN).contains(entry.kind())) {
                assertZero(entry.counters());
            }
        }
        assertEquals(312, sum(commits, Metric.WRITE_BYTES));
    }

    @Test
    void recoveryCountsRepeatedJournalAndIntegrityPassesRatherThanUniqueHistoryBytes() throws IOException {
        var result = supported(tiny(), ONCE);
        var journal = operation(result, 0, Kind.JOURNAL_REOPEN).counters();
        // Complete journal open + genesis prefix validation + complete replay: 354+42+354.
        assertWork(journal, LocalIo.Role.JOURNAL, 750, 0, 1);
        assertWork(journal, LocalIo.Role.MANIFEST, 154, 0, 0);
        assertWork(journal, LocalIo.Role.CONTROL, 0, 0, 1);
        assertWork(journal, LocalIo.Role.DIRECTORY, 0, 0, 3);
        assertOnlyRoles(journal, LocalIo.Role.JOURNAL, LocalIo.Role.MANIFEST,
                LocalIo.Role.CONTROL, LocalIo.Role.DIRECTORY);

        var checkpoint = operation(result, 0, Kind.CHECKPOINT_REOPEN).counters();
        assertWork(checkpoint, LocalIo.Role.JOURNAL, 1062, 0, 1);
        assertWork(checkpoint, LocalIo.Role.SEGMENT, 868, 0, 0);
        assertWork(checkpoint, LocalIo.Role.MANIFEST, 210, 0, 0);
        assertWork(checkpoint, LocalIo.Role.CONTROL, 0, 0, 1);
        assertWork(checkpoint, LocalIo.Role.DIRECTORY, 0, 0, 3);
        assertOnlyRoles(checkpoint, LocalIo.Role.JOURNAL, LocalIo.Role.SEGMENT,
                LocalIo.Role.MANIFEST, LocalIo.Role.CONTROL, LocalIo.Role.DIRECTORY);
    }

    @Test
    void checkpointAndCompactionKeepOriginalAndReplacementImageWorkInTheirOwnOperations() throws IOException {
        var result = supported(tiny(), ONCE);
        var sources = operations(result, 0).stream().filter(entry -> entry.kind() == Kind.SEGMENT_WRITE).toList();
        assertEquals(2, sources.size());
        for (var source : sources) {
            assertWork(source.counters(), LocalIo.Role.SEGMENT, 0, 278, 1);
            assertOnlyRoles(source.counters(), LocalIo.Role.SEGMENT);
        }

        var checkpoint = operation(result, 0, Kind.CHECKPOINT_PUBLISH).counters();
        assertWork(checkpoint, LocalIo.Role.JOURNAL, 396, 0, 1);
        assertWork(checkpoint, LocalIo.Role.SEGMENT, 556, 0, 2);
        assertWork(checkpoint, LocalIo.Role.MANIFEST, 154, 266, 1);
        assertWork(checkpoint, LocalIo.Role.DIRECTORY, 0, 0, 2);
        assertOnlyRoles(checkpoint, LocalIo.Role.JOURNAL, LocalIo.Role.SEGMENT,
                LocalIo.Role.MANIFEST, LocalIo.Role.DIRECTORY);

        var compacted = operation(result, 0, Kind.COMPACTION_PUBLISH).counters();
        assertWork(compacted, LocalIo.Role.JOURNAL, 708, 0, 1);
        assertWork(compacted, LocalIo.Role.SEGMENT, 990, 434, 2);
        assertWork(compacted, LocalIo.Role.MANIFEST, 266, 210, 1);
        assertWork(compacted, LocalIo.Role.DIRECTORY, 0, 0, 2);
        assertOnlyRoles(compacted, LocalIo.Role.JOURNAL, LocalIo.Role.SEGMENT,
                LocalIo.Role.MANIFEST, LocalIo.Role.DIRECTORY);
    }

    @Test
    void auxiliaryCoverageSeparatesSetupReaderAdmissionAndLazyValidationFromExternalIo() throws IOException {
        var result = supported(tiny(), ONCE);
        assertTrace(result);
        var preparation = category(result, Category.PREPARATION);
        assertEquals(2, preparation.size());
        assertTrue(preparation.stream().allMatch(entry -> entry.iteration() == -1));
        for (var entry : preparation) { assertZero(entry.counters()); }

        var namespace = category(result, Category.NAMESPACE);
        assertEquals(4, sumRole(namespace, LocalIo.Role.DIRECTORY, Metric.FORCE_SUCCESSES));
        assertEquals(0, sum(namespace, Metric.READ_BYTES));
        assertEquals(0, sum(namespace, Metric.WRITE_BYTES));
        assertEquals(354, sumRole(category(result, Category.OWNER_ACQUISITION), LocalIo.Role.JOURNAL, Metric.READ_BYTES));
        assertEquals(354, sumRole(category(result, Category.PREFIX_VERIFICATION), LocalIo.Role.JOURNAL, Metric.READ_BYTES));

        var readers = category(result, Category.READER_ACQUISITION);
        assertEquals(708, sumRole(readers, LocalIo.Role.JOURNAL, Metric.READ_BYTES));
        assertEquals(990, sumRole(readers, LocalIo.Role.SEGMENT, Metric.READ_BYTES));
        assertEquals(476, sumRole(readers, LocalIo.Role.MANIFEST, Metric.READ_BYTES));
        assertEquals(1, sumRole(readers, LocalIo.Role.CONTROL, Metric.FORCE_SUCCESSES));
        assertEquals(2, sumRole(readers, LocalIo.Role.DIRECTORY, Metric.FORCE_SUCCESSES));

        var validation = category(result, Category.VALIDATION);
        // Lazy validation rereads both 278-byte old images and the 434-byte replacement.
        assertEquals(990, sumRole(validation, LocalIo.Role.SEGMENT, Metric.READ_BYTES));
        assertEquals(990, sum(validation, Metric.READ_BYTES));
        assertEquals(0, sum(validation, Metric.WRITE_BYTES));
        for (var entry : category(result, Category.TEARDOWN)) { assertZero(entry.counters()); }
        assertFalse(category(result, Category.TEARDOWN).isEmpty());

        for (var kind : List.of(Category.EXTERNAL_HASH, Category.FILE_INVENTORY)) {
            var excluded = category(result, kind);
            assertEquals(1, excluded.size());
            assertEquals(Coverage.EXCLUDED_EXTERNAL_IO, kind.coverage());
            for (var entry : excluded) { assertZero(entry.counters()); }
        }
        var operationScopes = operations(result, 0);
        assertEquals(6436, sum(operationScopes, Metric.READ_BYTES));
        assertEquals(1974, sum(operationScopes, Metric.WRITE_BYTES));
        assertEquals(10308, sum(result.scopes(), Metric.READ_BYTES));
        assertEquals(1974, sum(result.scopes(), Metric.WRITE_BYTES));
    }

    @Test
    void ordinaryRunnerKeepsItsSchemaCorpusAndLifecycleWithoutStartingAnInnerCapture() throws IOException {
        var corpus = tiny();
        var attributed = supported(corpus, ONCE);
        LocalWorkloadRunner.Result ordinary;
        // A default run must not activate its own overlapping capture even with an external one.
        var outer = LocalIoObservation.open();
        try (outer) {
            ordinary = supportedOrdinary(corpus, ONCE);
        }
        assertTrue(outer.snapshot().complete());
        assertNotEquals(ordinary.runRoot(), attributed.workload().runRoot());
        assertEquals(1, ordinary.schemaVersion());
        assertEquals(ordinary.corpusVersion(), attributed.workload().corpusVersion());
        assertEquals(ordinary.spec(), attributed.workload().spec());
        assertEquals(ordinary.config(), attributed.workload().config());
        assertEquals(ordinary.fingerprint(), attributed.workload().fingerprint());
        assertEquals(ordinary.historyFingerprint(), attributed.workload().historyFingerprint());
        assertEquals(ordinary.workload(), attributed.workload().workload());
        assertEquals(ordinary.iterations().getFirst().namedStorage(), attributed.workload().iterations().getFirst().namedStorage());
        assertEquals(ordinary.iterations().getFirst().samples().stream().map(sample -> List.of(sample.phase(), sample.kind())).toList(),
                attributed.workload().iterations().getFirst().samples().stream().map(sample -> List.of(sample.phase(), sample.kind())).toList());
    }

    @Test
    void everyWarmupAndMeasuredIterationUsesFreshFilesAndStartsTheSameLiteralHistory() throws IOException, NoSuchAlgorithmException {
        var result = supported(tiny(), new LocalWorkloadRunner.Config(1, 2));
        assertTrace(result);
        assertEquals(List.of(true, false, false), result.workload().iterations().stream()
                .map(LocalWorkloadRunner.Iteration::warmup).toList());
        var homes = new HashSet<Path>();
        for (var iteration : result.workload().iterations()) {
            assertTrue(homes.add(iteration.directory()));
            assertEquals(result.workload().runRoot(), iteration.directory().getParent());
            assertEquals(20, operations(result, iteration.index()).size());
            assertWork(operation(result, iteration.index(), Kind.CREATE).counters(), LocalIo.Role.JOURNAL, 168, 42, 3);
            assertEquals(1974, sum(operations(result, iteration.index()), Metric.WRITE_BYTES));
            // The runner has closed every owner before returning; raw verification opens no competing handle.
            byte[] journal = Files.readAllBytes(iteration.directory().resolve("transactions.journal"));
            assertEquals(354, journal.length);
            assertEquals(HISTORY, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(journal)));
            try (var reopened = LocalDatabase.openWritable(iteration.directory())) {
                assertEquals(4, reopened.head().sequence());
            }
        }
        var next = supported(tiny(), ONCE);
        assertNotEquals(result.workload().runRoot(), next.workload().runRoot());
    }

    @Test
    void conservativeScopeAdmissionAcceptsItsExactBoundAndRefusesMoreBeforeFilesystemMutation() throws IOException {
        // C100 => seven source segments; 100 originals +99 retries +785 reads =984 actions.
        // 2 +(2*984 +2*7 +64)*1 =2048 scopes conservatively reserved.
        var rejected = denseReads(786);
        assertEquals(985, rejected.actions().size());
        assertThrows(IllegalArgumentException.class, () -> LocalAttributedWorkload.run(parent, rejected, ONCE));
        try (var paths = Files.list(parent)) { assertEquals(0, paths.count()); }
        assertEquals(2048, LocalAttributedWorkload.MAX_SCOPES);
        var boundary = denseReads(785);
        assertEquals(984, boundary.actions().size());
        var result = supported(boundary, ONCE);
        assertTrace(result);
        assertTrue(result.scopes().size() <= 2048);
        assertEquals(100, result.denominators().newCommits());
        assertEquals(99, result.denominators().exactRetries());
        assertEquals(785, result.denominators().queries());
    }

    @ParameterizedTest
    @EnumSource(WorkloadCorpus.Profile.class)
    void independentlyScaledProfilesWithZeroQueriesRetainExactCoverageAndOriginalFrameDenominators(WorkloadCorpus.Profile profile)
            throws IOException {
        var corpus = WorkloadCorpus.generate(profile.spec(42, 3, 9, 0, 2));
        var result = supported(corpus, ONCE);
        assertTrace(result);
        assertEquals(corpus.spec(), result.workload().spec());
        assertEquals(WorkloadOracle.prepare(corpus).summary(), result.workload().workload());
        assertEquals(9, result.denominators().newCommits());
        assertEquals(0, result.denominators().queries());
        assertEquals(corpus.encodedHistoryBytes(), Math.addExact(result.denominators().headerBytes(),
                result.denominators().newFrameBytes()));
        var operations = operations(result, 0);
        assertEquals(9, operations.stream().filter(entry -> entry.kind() == Kind.COMMIT).count());
        assertEquals(result.workload().workload().retries(), operations.stream().filter(entry -> entry.kind() == Kind.RETRY).count());
        assertTrue(operations.stream().noneMatch(entry -> Set.of(Kind.GET, Kind.SCAN, Kind.SELECT, Kind.FILTER_SCAN, Kind.SUM)
                .contains(entry.kind())));
    }

    @Test
    void returnedScopesCounterRowsAndGapsAreImmutableIndependentSnapshots() throws IOException {
        var result = supported(tiny(), ONCE);
        var first = result.scopes().getFirst();
        assertThrows(UnsupportedOperationException.class, () -> result.scopes().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.gaps().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.counters().counts().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.counters().counts().getFirst().set(0, 1L));
        for (var invalid : List.of(
                new LocalAttributedWorkload.Denominators(42, 78, 234, 354, 3, 3, 5),
                new LocalAttributedWorkload.Denominators(42, 78, 234, 354, 4, 2, 5),
                new LocalAttributedWorkload.Denominators(42, 78, 234, 354, 4, 3, 4),
                new LocalAttributedWorkload.Denominators(43, 77, 234, 354, 4, 3, 5))) {
            assertThrows(IllegalArgumentException.class, () -> new LocalAttributedWorkload.Result(1, MODE,
                    result.workload(), result.scopes(), invalid, result.gaps()));
        }
        var missingScope = new java.util.ArrayList<>(result.scopes());
        missingScope.removeLast();
        assertThrows(IllegalArgumentException.class, () -> new LocalAttributedWorkload.Result(1, MODE,
                result.workload(), missingScope, result.denominators(), result.gaps()));
        var create = operation(result, 0, Kind.CREATE).counters();
        supported(tiny(), ONCE);
        assertWork(create, LocalIo.Role.JOURNAL, 168, 42, 3);
    }

    private LocalAttributedWorkload.Result supported(WorkloadCorpus corpus, LocalWorkloadRunner.Config config) throws IOException {
        try { return LocalAttributedWorkload.run(parent, corpus, config); }
        catch (LocalAttributedWorkload.RunFailedException failure) {
            skipUnsupportedWindows(failure);
            throw failure;
        }
    }

    private LocalWorkloadRunner.Result supportedOrdinary(WorkloadCorpus corpus, LocalWorkloadRunner.Config config) throws IOException {
        try { return LocalWorkloadRunner.run(parent, corpus, config); }
        catch (LocalWorkloadRunner.RunFailedException failure) {
            skipUnsupportedWindows(failure);
            throw failure;
        }
    }

    private static void skipUnsupportedWindows(Throwable failure) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof LocalCheckpointStore.DirectorySyncUnsupportedException) {
                    assumeTrue(false, "Windows directory force unavailable: " + cause);
                }
            }
        }
    }

    private static WorkloadCorpus tiny() {
        return WorkloadCorpus.generate(new WorkloadCorpus.Spec(7, 1, 4, 5, 2, 100, 0, 0, 100, 100, 100));
    }

    private static WorkloadCorpus denseReads(int queries) {
        return WorkloadCorpus.generate(new WorkloadCorpus.Spec(7, 1, 100, queries, 1, 0, 0, 0, 100, 0, 50));
    }

    private static void assertTrace(LocalAttributedWorkload.Result result) {
        assertTrue(result.scopes().size() <= LocalAttributedWorkload.MAX_SCOPES);
        for (int ordinal = 0; ordinal < result.scopes().size(); ordinal++) {
            var entry = result.scopes().get(ordinal);
            assertEquals(ordinal, entry.ordinal());
            assertTrue(entry.callReturned());
            assertTrue(entry.accepted());
            assertNull(entry.operationFailureType());
            assertNull(entry.observationFailureType());
            assertFalse(entry.cleanupOutsideCapture());
            assertNotNull(entry.counters());
            assertTrue(entry.counters().complete());
            if (entry.category() == Category.PREPARATION) { assertEquals(-1, entry.iteration()); }
            else { assertTrue(entry.iteration() >= 0 && entry.iteration() < result.workload().iterations().size()); }
            if (entry.category() == Category.OPERATION) {
                assertNotNull(entry.phase());
                assertNotNull(entry.kind());
                assertEquals(Coverage.ADAPTERS_ONLY, entry.category().coverage());
            } else {
                assertNull(entry.phase());
                assertNull(entry.kind());
            }
        }
        for (var iteration : result.workload().iterations()) {
            var scopes = operations(result, iteration.index());
            assertEquals(iteration.samples().size(), scopes.size());
            for (int index = 0; index < scopes.size(); index++) {
                assertEquals(iteration.samples().get(index).phase(), scopes.get(index).phase());
                assertEquals(iteration.samples().get(index).kind(), scopes.get(index).kind());
            }
        }
    }

    private static List<Entry> operations(LocalAttributedWorkload.Result result, int iteration) {
        return result.scopes().stream().filter(entry -> entry.category() == Category.OPERATION && entry.iteration() == iteration).toList();
    }

    private static Entry operation(LocalAttributedWorkload.Result result, int iteration, Kind kind) {
        var matching = operations(result, iteration).stream().filter(entry -> entry.kind() == kind).toList();
        assertEquals(1, matching.size(), kind.toString());
        return matching.getFirst();
    }

    private static List<Entry> category(LocalAttributedWorkload.Result result, Category category) {
        return result.scopes().stream().filter(entry -> entry.category() == category).toList();
    }

    private static long count(List<Entry> scopes, Phase phase, Kind kind) {
        return scopes.stream().filter(entry -> entry.phase() == phase && entry.kind() == kind).count();
    }

    private static long sum(List<Entry> scopes, Metric metric) {
        long total = 0;
        for (var entry : scopes) { total = Math.addExact(total, entry.counters().sum(metric)); }
        return total;
    }

    private static long sumRole(List<Entry> scopes, LocalIo.Role role, Metric metric) {
        long total = 0;
        for (var entry : scopes) { total = Math.addExact(total, entry.counters().value(role, metric)); }
        return total;
    }

    private static void assertWork(Snapshot observed, LocalIo.Role role, long reads, long writes, long forces) {
        assertEquals(reads, observed.value(role, Metric.READ_BYTES), role + " read bytes");
        assertEquals(writes, observed.value(role, Metric.WRITE_BYTES), role + " written bytes");
        assertEquals(forces, observed.value(role, Metric.FORCE_SUCCESSES), role + " force successes");
    }

    private static void assertOnlyRoles(Snapshot observed, LocalIo.Role... active) {
        var expected = List.of(active);
        for (var role : LocalIo.Role.values()) {
            if (!expected.contains(role)) {
                for (var metric : Metric.values()) { assertEquals(0, observed.value(role, metric), role + " " + metric); }
            }
        }
    }

    private static void assertZero(Snapshot observed) { assertOnlyRoles(observed); }
}
