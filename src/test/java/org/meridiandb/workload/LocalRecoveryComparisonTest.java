// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.internal.LocalIo;
import org.meridiandb.storage.internal.LocalIoObservation;
import org.meridiandb.storage.internal.LocalIoObservation.Metric;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.manifest.LocalCheckpointHistory;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;
import org.meridiandb.workload.LocalRecoveryComparison.Access;
import org.meridiandb.workload.LocalRecoveryComparison.Operation;
import org.meridiandb.workload.LocalRecoveryComparison.Shape;
import org.meridiandb.workload.LocalRecoveryComparison.Strategy;
import org.meridiandb.workload.WorkloadMeasurements.Kind;
import org.meridiandb.workload.WorkloadMeasurements.Phase;

/** Paired native fixtures and raw operation evidence, without timing-gain assertions. */
@Timeout(120)
class LocalRecoveryComparisonTest {
    private static final LocalRecoveryComparison.Config ONCE = new LocalRecoveryComparison.Config(0, 1);
    private static final LocalCheckpointHistory.Limits EXACT = new LocalCheckpointHistory.Limits(666, 198, 4);

    @TempDir
    Path parent;

    @Test
    void allSixteenOpensPreserveLiteralReadAndStartupForceDifferences() throws IOException {
        var result = supported(tiny(), ONCE);
        assertEquals(1, result.schemaVersion());
        assertEquals("CALLER_THREAD_RECOVERY_IO_INSTRUMENTED", result.mode());
        assertEquals(1, result.corpusVersion());
        assertEquals(354, result.encodedHistoryBytes());
        assertEquals(EXACT, result.limits());
        assertEquals(16, result.observations().size());
        var seen = new HashSet<String>();
        for (var observation : result.observations()) {
            assertTrue(seen.add(observation.shape() + "/" + observation.access() + "/" + observation.strategy()));
            var opened = observation.operations().getFirst();
            assertEquals(Operation.OPEN, opened.operation());
            assertEquals(Phase.MAINTENANCE, opened.measurement().phase());
            assertEquals(observation.shape() == Shape.JOURNAL_ONLY ? Kind.JOURNAL_REOPEN : Kind.CHECKPOINT_REOPEN,
                    opened.measurement().kind());
            var counters = opened.counters();
            assertTrue(counters.complete());
            long journal = switch (observation.shape()) {
                case JOURNAL_ONLY -> 708;
                case GENESIS -> 750;
                case CHECKPOINT_TAIL, COMPACTED_CHECKPOINT_TAIL -> 906;
            };
            long manifest = switch (observation.shape()) {
                case JOURNAL_ONLY -> 0;
                case GENESIS -> 154;
                case CHECKPOINT_TAIL -> 266;
                case COMPACTED_CHECKPOINT_TAIL -> 210;
            };
            long segment = switch (observation.shape()) {
                case JOURNAL_ONLY, GENESIS -> 0;
                case CHECKPOINT_TAIL -> observation.strategy() == Strategy.ORDINARY ? 800 : 400;
                case COMPACTED_CHECKPOINT_TAIL -> observation.strategy() == Strategy.ORDINARY ? 556 : 278;
            };
            assertEquals(journal, counters.value(LocalIo.Role.JOURNAL, Metric.READ_BYTES));
            assertEquals(manifest, counters.value(LocalIo.Role.MANIFEST, Metric.READ_BYTES));
            assertEquals(segment, counters.value(LocalIo.Role.SEGMENT, Metric.READ_BYTES));
            assertEquals(journal + manifest + segment, counters.sum(Metric.READ_BYTES));
            boolean admitsReader = observation.strategy() == Strategy.ORDINARY && observation.shape() != Shape.JOURNAL_ONLY;
            long forces = (observation.access() == Access.WRITABLE ? 3 : 1) + (admitsReader ? 2 : 0);
            assertEquals(forces, counters.sum(Metric.FORCE_SUCCESSES));
            assertEquals(admitsReader ? 1 : 0, counters.value(LocalIo.Role.CONTROL, Metric.FORCE_SUCCESSES));
            assertEquals(observation.access() == Access.WRITABLE ? 1 : 0,
                    counters.value(LocalIo.Role.JOURNAL, Metric.FORCE_SUCCESSES));
            assertEquals(0, counters.sum(Metric.WRITE_ATTEMPTS));
            assertEquals(0, counters.sum(Metric.WRITE_BYTES));
        }
        assertEquals(16, seen.size());
    }

    @Test
    void pairedInitialImagesAreIdenticalIncludingRetainedOldObjectsAndShapeMetadata() throws IOException {
        var result = supported(tiny(), ONCE);
        var perShape = new HashMap<Shape, LocalRecoveryComparison.Fixture>();
        for (int pair = 0; pair < result.observations().size(); pair += 2) {
            var first = result.observations().get(pair);
            var second = result.observations().get(pair + 1);
            assertEquals(first.shape(), second.shape());
            assertEquals(first.access(), second.access());
            assertNotEquals(first.strategy(), second.strategy());
            assertNotEquals(first.directory(), second.directory());
            assertEquals(first.fixture(), second.fixture());
            var fixture = first.fixture();
            var previous = perShape.putIfAbsent(first.shape(), fixture);
            if (previous != null) { assertEquals(previous, fixture); }
            assertEquals(result.historyFingerprint(), fixture.initialFileSha256().get("transactions.journal"));
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    fixture.initialFileSha256().get(".meridiandb-checkpoints.lock"));
            int files = switch (first.shape()) {
                case JOURNAL_ONLY -> 2;
                case GENESIS -> 3;
                case CHECKPOINT_TAIL -> 5;
                case COMPACTED_CHECKPOINT_TAIL -> 7;
            };
            assertEquals(files, fixture.initialFileSha256().size());
            assertFalse(fixture.initialFileSha256().containsKey(".meridiandb-readers.lock"));
            if (first.shape() == Shape.JOURNAL_ONLY) {
                assertEquals(OptionalLong.empty(), fixture.checkpointSequence());
                assertEquals(0, fixture.prefixBytes());
                assertNull(fixture.prefixSha256());
                assertEquals(0, fixture.selectedManifestBytes());
            } else {
                assertEquals(OptionalLong.of(first.shape() == Shape.GENESIS ? 0 : 2), fixture.checkpointSequence());
                assertEquals(first.shape() == Shape.GENESIS ? 42 : 198, fixture.prefixBytes());
                assertTrue(fixture.prefixSha256().matches("[0-9a-f]{64}"));
                assertEquals(first.shape() == Shape.GENESIS ? 154 : first.shape() == Shape.CHECKPOINT_TAIL ? 266 : 210,
                        fixture.selectedManifestBytes());
            }
            assertEquals(first.shape() == Shape.CHECKPOINT_TAIL ? 400 : first.shape() == Shape.COMPACTED_CHECKPOINT_TAIL ? 278 : 0,
                    fixture.selectedSegmentBytes());
            assertEquals(first.shape() == Shape.CHECKPOINT_TAIL ? 2 : first.shape() == Shape.COMPACTED_CHECKPOINT_TAIL ? 1 : 0,
                    fixture.selectedSegments());
            if (first.shape() == Shape.COMPACTED_CHECKPOINT_TAIL) {
                assertEquals(3, fixture.initialFileSha256().keySet().stream().filter(name -> name.endsWith(".segment")).count());
                assertEquals(2, fixture.initialFileSha256().keySet().stream().filter(name -> name.endsWith(".manifest")).count());
            }
        }
    }

    @Test
    void writableContinuationAppendsOne156ByteStateChangingFrameAndBothRetryCallsDoZeroFileWork() throws IOException, NoSuchAlgorithmException {
        var corpus = tiny();
        var result = supported(corpus, ONCE);
        String continuedHash = null;
        for (var observation : result.observations()) {
            var operations = observation.operations();
            assertEquals(observation.access() == Access.WRITABLE ? 4 : 1, operations.size());
            if (observation.access() == Access.WRITABLE) {
                assertEquals(List.of(Operation.OPEN, Operation.ORIGINAL_RETRY, Operation.CONTINUATION, Operation.CONTINUATION_RETRY),
                        operations.stream().map(LocalRecoveryComparison.OperationSample::operation).toList());
                assertZero(operations.get(1).counters());
                assertZero(operations.get(3).counters());
                var commit = operations.get(2);
                assertEquals(Kind.COMMIT, commit.measurement().kind());
                // Request 23 and fresh entity 30 units replace two 3-unit IDs; unbounded removes 16 bytes.
                // Original finite frame 78 + 2*(23-3) + 2*(30-3) - 16 = 156.
                assertEquals(156, commit.counters().value(LocalIo.Role.JOURNAL, Metric.WRITE_BYTES));
                assertEquals(156, commit.counters().sum(Metric.WRITE_BYTES));
                assertEquals(1, commit.counters().sum(Metric.FORCE_SUCCESSES));
                assertEquals(1, commit.counters().value(LocalIo.Role.JOURNAL, Metric.FORCE_SUCCESSES));
                assertEquals(0, commit.counters().sum(Metric.READ_BYTES));
                assertEquals(510, observation.closedJournalBytes());
                assertNotEquals(corpus.historyFingerprint(), observation.closedJournalSha256());
                if (continuedHash == null) { continuedHash = observation.closedJournalSha256(); }
                assertEquals(continuedHash, observation.closedJournalSha256());
            } else {
                assertEquals(354, observation.closedJournalBytes());
                assertEquals(corpus.historyFingerprint(), observation.closedJournalSha256());
            }
            // Every comparison owner is already closed before raw reads or a fresh independent reopen.
            byte[] journal = Files.readAllBytes(observation.directory().resolve("transactions.journal"));
            assertEquals(observation.closedJournalBytes(), journal.length);
            assertEquals(observation.closedJournalSha256(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(journal)));
            var decoded = JournalCodec.scan(journal);
            assertFalse(decoded.incompleteTail());
            assertEquals(observation.access() == Access.WRITABLE ? 5 : 4, decoded.records().size());
            if (observation.access() == Access.WRITABLE) {
                var last = decoded.records().getLast();
                assertEquals(5, last.sequence());
                assertEquals(new RequestId("recovery-continuation-0"), last.transaction().requestId());
                assertEquals(1, last.transaction().operations().size());
                var put = assertInstanceOf(WriteOperation.Put.class, last.transaction().operations().getFirst());
                assertEquals(new EntityId("recovery-continuation-entity-0"), put.entity());
                assertEquals(new ValidTimeInterval(null, null), put.interval());
                assertEquals(17, put.value());
            }
            try (var database = LocalDatabase.open(observation.directory())) {
                assertEquals(observation.access() == Access.WRITABLE ? 5 : 4, database.head().sequence());
                assertEquals(Optional.of(position(1)), database.lookupRequest(new RequestId("r-1")));
                assertEquals(observation.access() == Access.WRITABLE ? Optional.of(position(5)) : Optional.empty(),
                        database.lookupRequest(new RequestId("recovery-continuation-0")));
                var fresh = new EntityId("recovery-continuation-entity-0");
                try (var previous = database.openView(new ReadCutoff.At(position(4))); var head = database.openView(ReadCutoff.Head.INSTANCE)) {
                    assertEquals(new TemporalResult.Point(OptionalLong.empty()), previous.query(new TemporalQuery.Get(fresh, 0)));
                    assertEquals(new TemporalResult.Point(observation.access() == Access.WRITABLE ? OptionalLong.of(17) : OptionalLong.empty()),
                            head.query(new TemporalQuery.Get(fresh, 0)));
                }
                try (var seed = database.openView(new ReadCutoff.At(position(1)))) {
                    assertEquals(new TemporalResult.Point(OptionalLong.of(Long.MIN_VALUE)), seed.query(new TemporalQuery.Get(new EntityId("e-0"), 0)));
                    assertEquals(new TemporalResult.Point(OptionalLong.empty()), seed.query(new TemporalQuery.Get(new EntityId("e-0"), 1)));
                }
            }
        }
    }

    @Test
    void warmupDoesNotShiftMeasuredCounterbalancingAndEveryObservationHasFreshPlacement() throws IOException {
        var result = supported(tiny(), LocalRecoveryComparison.Config.DEFAULT);
        assertEquals(48, result.observations().size());
        var homes = new HashSet<Path>();
        var pairOrder = new HashMap<String, List<Strategy>>();
        for (var observation : result.observations()) {
            assertTrue(homes.add(observation.directory()));
            assertEquals(result.runRoot(), observation.directory().getParent());
            assertEquals(observation.iteration() == 0, observation.warmup());
            assertEquals(observation.iteration() == 2 ? Strategy.HISTORY_REUSE : Strategy.ORDINARY,
                    observation.strategyOrdinal() == 0 ? observation.strategy() : opposite(observation.strategy()));
            String pair = observation.iteration() + "/" + observation.shape() + "/" + observation.access();
            pairOrder.computeIfAbsent(pair, ignored -> new java.util.ArrayList<>()).add(observation.strategy());
        }
        for (var shape : Shape.values()) {
            for (var access : Access.values()) {
                assertEquals(List.of(Strategy.ORDINARY, Strategy.HISTORY_REUSE), pairOrder.get("0/" + shape + "/" + access));
                assertEquals(List.of(Strategy.ORDINARY, Strategy.HISTORY_REUSE), pairOrder.get("1/" + shape + "/" + access));
                assertEquals(List.of(Strategy.HISTORY_REUSE, Strategy.ORDINARY), pairOrder.get("2/" + shape + "/" + access));
            }
        }
        assertTrue(result.gaps().stream().anyMatch(gap -> gap.contains("even measured repetitions")));
    }

    @Test
    void rawCollectionsAndFixtureMapsAreImmutableCopies() throws IOException {
        var result = supported(tiny(), ONCE);
        assertThrows(UnsupportedOperationException.class, () -> result.observations().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.gaps().clear());
        var observation = result.observations().getFirst();
        assertThrows(UnsupportedOperationException.class, () -> observation.operations().clear());
        assertThrows(UnsupportedOperationException.class, () -> observation.fixture().initialFileSha256().clear());
        var snapshot = observation.operations().getFirst().counters();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.counts().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.counts().getFirst().set(0, 1L));
        var mutable = new HashMap<>(Map.of("original", "a"));
        var fixture = new LocalRecoveryComparison.Fixture(OptionalLong.empty(), 0, null, 0, 0, 0, mutable);
        mutable.clear();
        assertEquals(Map.of("original", "a"), fixture.initialFileSha256());
    }

    @Test
    void invalidArgumentsAggregateAdmissionAndEveryOneBelowCheckpointBudgetPrecedeNamespaceEffects() throws IOException {
        var corpus = tiny();
        assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(null, corpus, ONCE, EXACT));
        assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(parent, null, ONCE, EXACT));
        assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(parent, corpus, null, EXACT));
        assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(parent, corpus, ONCE, null));
        assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(parent, corpus, ONCE, EXACT, null));
        for (int[] config : List.of(new int[] {-1, 1}, new int[] {3, 1}, new int[] {0, 0}, new int[] {0, 6})) {
            assertThrows(IllegalArgumentException.class, () -> new LocalRecoveryComparison.Config(config[0], config[1]));
        }
        for (var limit : List.of(new LocalCheckpointHistory.Limits(665, 198, 4),
                new LocalCheckpointHistory.Limits(666, 197, 4), new LocalCheckpointHistory.Limits(666, 198, 3))) {
            assertThrows(IllegalArgumentException.class, () -> LocalRecoveryComparison.run(parent, corpus, ONCE, limit));
        }
        var manyReads = WorkloadCorpus.generate(WorkloadCorpus.Profile.APPEND.spec(43, 1, 4, 512, 1));
        assertThrows(IllegalArgumentException.class, () -> LocalRecoveryComparison.run(parent, manyReads,
                new LocalRecoveryComparison.Config(2, 5), LocalCheckpointHistory.Limits.DEFAULT));
        var recoveryWork = WorkloadCorpus.generate(WorkloadCorpus.Profile.APPEND.spec(43, 1, 1500, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> LocalRecoveryComparison.run(parent, recoveryWork,
                ONCE, LocalCheckpointHistory.Limits.DEFAULT));
        try (var entries = Files.list(parent)) { assertEquals(0, entries.count()); }
    }

    @Test
    void nonDirectoryAndMissingScratchPreserveExistingBytesAndCreateNoReplacement() throws IOException {
        Path marker = Files.writeString(parent.resolve("marker"), "retained");
        assertThrows(IOException.class, () -> LocalRecoveryComparison.run(marker, tiny(), ONCE, EXACT));
        assertEquals("retained", Files.readString(marker));
        Path absent = parent.resolve("absent");
        assertThrows(IOException.class, () -> LocalRecoveryComparison.run(absent, tiny(), ONCE, EXACT));
        assertFalse(Files.exists(absent));
        try (var entries = Files.list(parent)) { assertEquals(List.of(marker), entries.toList()); }
    }

    @Test
    void postOpenRuntimeFailureClosesOwnerAndCaptureAndRetainsItsFailedRun() throws IOException {
        requireNativeCapability();
        var problem = new IllegalStateException("post-open observation");
        var probe = new Probe(2, problem);
        var failed = assertThrows(LocalRecoveryComparison.RunFailedException.class,
                () -> LocalRecoveryComparison.run(parent, tiny(), ONCE, EXACT, probe));
        assertSame(problem, failed.getCause());
        assertTrue(Files.isDirectory(failed.runRoot()));
        assertFailedHomesReleased(failed.runRoot(), 1);
    }

    @Test
    void postOpenErrorInSecondStrategyClosesTheCandidateAndRetainsAllExistingHomes() throws IOException {
        requireNativeCapability();
        var problem = new AssertionError("post-history-open observation");
        var before = recoveryRoots();
        assertSame(problem, assertThrows(AssertionError.class,
                () -> LocalRecoveryComparison.run(parent, tiny(), ONCE, EXACT, new Probe(4, problem))));
        var roots = recoveryRoots();
        roots.removeAll(before);
        assertEquals(1, roots.size());
        assertFailedHomesReleased(roots.getFirst(), 2);
    }

    @Test
    void unavailableCpuAndDecreasingAllocationRemainNullWhileNativeCountersAndRawClockScopesStayUsable() throws IOException {
        var result = supported(tiny(), ONCE, new Probe(0, null));
        assertEquals(16, result.observations().size());
        for (var observation : result.observations()) {
            assertTrue(observation.validationNanos() >= 0);
            assertTrue(observation.teardownNanos() >= 0);
            for (var operation : observation.operations()) {
                assertNull(operation.measurement().threadCpuNanos());
                assertNull(operation.measurement().threadAllocatedBytes());
                assertEquals(128, operation.measurement().heapUsedBeforeBytes());
                assertEquals(128, operation.measurement().heapUsedAfterBytes());
                assertTrue(operation.measurement().elapsedNanos() >= 0);
                assertTrue(operation.counters().complete());
            }
        }
    }

    private LocalRecoveryComparison.Result supported(WorkloadCorpus corpus, LocalRecoveryComparison.Config config) throws IOException {
        return supported(corpus, config, WorkloadMeasurements.systemProbe());
    }

    private LocalRecoveryComparison.Result supported(WorkloadCorpus corpus, LocalRecoveryComparison.Config config,
                                                     WorkloadMeasurements.Probe probe) throws IOException {
        try { return LocalRecoveryComparison.run(parent, corpus, config, EXACT, probe); }
        catch (LocalRecoveryComparison.RunFailedException failure) {
            skipUnsupportedWindows(failure);
            throw failure;
        }
    }

    private void requireNativeCapability() throws IOException {
        Path home = Files.createDirectory(parent.resolve("capability"));
        try (var store = LocalCheckpointStore.open(home)) { assertTrue(store.journalPath().isAbsolute()); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException failure) {
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

    private void assertFailedHomesReleased(Path root, int expected) throws IOException {
        List<Path> homes;
        try (var entries = Files.list(root)) { homes = entries.filter(Files::isDirectory).toList(); }
        assertEquals(expected, homes.size());
        for (var home : homes) {
            assertTrue(Files.isRegularFile(home.resolve("transactions.journal")));
            try (var database = LocalDatabase.openWritable(home)) { assertEquals(4, database.head().sequence()); }
        }
        var capture = LocalIoObservation.open();
        try (capture) { assertTrue(Files.isDirectory(root)); }
        assertTrue(capture.snapshot().complete());
        assertZero(capture.snapshot());
    }

    private List<Path> recoveryRoots() throws IOException {
        try (var entries = Files.list(parent)) {
            return new java.util.ArrayList<>(entries.filter(path -> path.getFileName().toString().startsWith("mdb-recovery-")).toList());
        }
    }

    private static void assertZero(LocalIoObservation.Snapshot counters) {
        for (var metric : Metric.values()) { assertEquals(0, counters.sum(metric), metric.toString()); }
    }

    private static Strategy opposite(Strategy strategy) {
        return strategy == Strategy.ORDINARY ? Strategy.HISTORY_REUSE : Strategy.ORDINARY;
    }

    private static CommitPosition position(long sequence) { return new CommitPosition(WorkloadCorpus.DATABASE, sequence); }

    private static WorkloadCorpus tiny() { return WorkloadCorpus.generate(WorkloadCorpus.Profile.APPEND.spec(43, 1, 4, 3, 1)); }

    private static final class Probe implements WorkloadMeasurements.Probe {
        private final int failAt;
        private final Throwable failure;
        private long ticks;
        private int snapshots;
        Probe(int failAt, Throwable failure) { this.failAt = failAt; this.failure = failure; }
        @Override public long nanoTime() { return ticks += 10; }
        @Override public WorkloadMeasurements.ResourceSnapshot snapshot() {
            if (++snapshots == failAt) {
                if (failure instanceof RuntimeException runtime) { throw runtime; }
                throw (Error) failure;
            }
            return new WorkloadMeasurements.ResourceSnapshot(null, snapshots % 2 == 1 ? 100L : 99L, 128);
        }
    }
}
