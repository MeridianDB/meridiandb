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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.internal.LocalIo;
import org.meridiandb.storage.internal.LocalIoObservation;
import org.meridiandb.storage.internal.LocalIoObservation.Metric;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.manifest.LocalCheckpointHistory;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;
import org.meridiandb.workload.LocalRecoveryComparison.Access;
import org.meridiandb.workload.LocalRecoveryComparison.IoCoverage;
import org.meridiandb.workload.LocalRecoveryComparison.MeasurementMode;
import org.meridiandb.workload.LocalRecoveryComparison.Operation;
import org.meridiandb.workload.LocalRecoveryComparison.OperationSample;
import org.meridiandb.workload.LocalRecoveryComparison.Shape;
import org.meridiandb.workload.LocalRecoveryComparison.Strategy;
import org.meridiandb.workload.WorkloadMeasurements.Kind;
import org.meridiandb.workload.WorkloadMeasurements.Phase;

/** Equivalent controls and capture ownership; no measured timing-gain assertions. */
@Timeout(120)
class UninstrumentedRecoveryComparisonTest {
    private static final LocalRecoveryComparison.Config ONCE = new LocalRecoveryComparison.Config(0, 1);
    private static final LocalCheckpointHistory.Limits EXACT = new LocalCheckpointHistory.Limits(666, 198, 4);

    @TempDir Path parent;

    @Test
    void allSixteenUninstrumentedScenariosKeepHistoryAndForcedContinuationWithoutInventingZeroCounters()
            throws IOException, NoSuchAlgorithmException {
        var corpus = tiny();
        var result = supported(() -> LocalRecoveryComparison.run(parent, corpus, ONCE,
                MeasurementMode.UNINSTRUMENTED, EXACT));
        assertEquals(1, result.schemaVersion());
        assertEquals("RECOVERY_UNINSTRUMENTED", result.mode());
        assertEquals(LocalRecoveryComparison.UNINSTRUMENTED_MODE, result.mode());
        assertEquals(354, result.encodedHistoryBytes());
        assertEquals(EXACT, result.limits());
        assertEquals(16, result.observations().size());
        var seen = new HashSet<String>();
        String continuedHash = null;
        for (var observation : result.observations()) {
            assertTrue(seen.add(observation.shape() + "/" + observation.access() + "/" + observation.strategy()));
            var expectedOperations = observation.access() == Access.WRITABLE
                    ? List.of(Operation.OPEN, Operation.ORIGINAL_RETRY, Operation.CONTINUATION, Operation.CONTINUATION_RETRY)
                    : List.of(Operation.OPEN);
            assertEquals(expectedOperations, observation.operations().stream().map(OperationSample::operation).toList());
            assertEquals(observation.shape() == Shape.JOURNAL_ONLY ? Kind.JOURNAL_REOPEN : Kind.CHECKPOINT_REOPEN,
                    observation.operations().getFirst().measurement().kind());
            for (var operation : observation.operations()) {
                assertEquals(Phase.MAINTENANCE, operation.measurement().phase());
                assertEquals(IoCoverage.NOT_CAPTURED, operation.coverage());
                assertNull(operation.counters(), "Unavailable file-work evidence must not become a zero snapshot");
            }
            boolean writable = observation.access() == Access.WRITABLE;
            assertEquals(writable ? 510 : 354, observation.closedJournalBytes());
            assertEquals(corpus.historyFingerprint(), observation.fixture().initialFileSha256().get("transactions.journal"));
            if (writable) {
                assertNotEquals(corpus.historyFingerprint(), observation.closedJournalSha256());
                if (continuedHash == null) { continuedHash = observation.closedJournalSha256(); }
                assertEquals(continuedHash, observation.closedJournalSha256());
            } else {
                assertEquals(corpus.historyFingerprint(), observation.closedJournalSha256());
            }
            // Raw identity is inspected only after the comparison has closed every native owner.
            byte[] bytes = Files.readAllBytes(observation.directory().resolve("transactions.journal"));
            assertEquals(observation.closedJournalBytes(), bytes.length);
            assertEquals(observation.closedJournalSha256(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            var decoded = JournalCodec.scan(bytes);
            assertFalse(decoded.incompleteTail());
            assertEquals(writable ? 5 : 4, decoded.records().size());
            if (writable) {
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
                assertEquals(writable ? 5 : 4, database.head().sequence());
                assertEquals(Optional.of(position(1)), database.lookupRequest(new RequestId("r-1")));
                assertEquals(writable ? Optional.of(position(5)) : Optional.empty(),
                        database.lookupRequest(new RequestId("recovery-continuation-0")));
                var entity = new EntityId("recovery-continuation-entity-0");
                try (var old = database.openView(new ReadCutoff.At(position(4)));
                     var head = database.openView(ReadCutoff.Head.INSTANCE);
                     var seed = database.openView(new ReadCutoff.At(position(1)))) {
                    assertEquals(new TemporalResult.Point(OptionalLong.empty()), old.query(new TemporalQuery.Get(entity, 0)));
                    assertEquals(new TemporalResult.Point(writable ? OptionalLong.of(17) : OptionalLong.empty()),
                            head.query(new TemporalQuery.Get(entity, 0)));
                    assertEquals(new TemporalResult.Point(OptionalLong.of(Long.MIN_VALUE)),
                            seed.query(new TemporalQuery.Get(new EntityId("e-0"), 0)));
                    assertEquals(new TemporalResult.Point(OptionalLong.empty()),
                            seed.query(new TemporalQuery.Get(new EntityId("e-0"), 1)));
                }
            }
        }
        assertEquals(16, seen.size());
        LocalIoObservation.requireNoCurrentCapture();
    }

    @Test
    void defaultOverloadRetainsLiteralAdapterCountsAndBothModesStartAndFinishWithIdenticalImages() throws IOException {
        var corpus = tiny();
        var instrumented = supported(() -> LocalRecoveryComparison.run(parent, corpus, ONCE, EXACT));
        var control = supported(() -> LocalRecoveryComparison.run(parent, corpus, ONCE,
                MeasurementMode.UNINSTRUMENTED, EXACT, new ClockProbe()));
        assertEquals("CALLER_THREAD_RECOVERY_IO_INSTRUMENTED", instrumented.mode());
        assertEquals(instrumented.fingerprint(), control.fingerprint());
        assertEquals(instrumented.historyFingerprint(), control.historyFingerprint());
        assertEquals(instrumented.workload(), control.workload());
        assertNotEquals(instrumented.runRoot(), control.runRoot());
        assertEquals(16, instrumented.observations().size());
        assertEquals(16, control.observations().size());
        for (int index = 0; index < 16; index++) {
            var captured = instrumented.observations().get(index);
            var uncaptured = control.observations().get(index);
            assertEquals(captured.shape(), uncaptured.shape());
            assertEquals(captured.access(), uncaptured.access());
            assertEquals(captured.strategy(), uncaptured.strategy());
            assertEquals(captured.strategyOrdinal(), uncaptured.strategyOrdinal());
            assertEquals(captured.fixture(), uncaptured.fixture());
            assertEquals(captured.closedJournalBytes(), uncaptured.closedJournalBytes());
            assertEquals(captured.closedJournalSha256(), uncaptured.closedJournalSha256());
            var counters = captured.operations().getFirst().counters();
            assertTrue(counters.complete());
            long journal = switch (captured.shape()) {
                case JOURNAL_ONLY -> 708;
                case GENESIS -> 750;
                case CHECKPOINT_TAIL, COMPACTED_CHECKPOINT_TAIL -> 906;
            };
            long manifest = switch (captured.shape()) {
                case JOURNAL_ONLY -> 0;
                case GENESIS -> 154;
                case CHECKPOINT_TAIL -> 266;
                case COMPACTED_CHECKPOINT_TAIL -> 210;
            };
            long segment = switch (captured.shape()) {
                case JOURNAL_ONLY, GENESIS -> 0;
                case CHECKPOINT_TAIL -> captured.strategy() == Strategy.ORDINARY ? 800 : 400;
                case COMPACTED_CHECKPOINT_TAIL -> captured.strategy() == Strategy.ORDINARY ? 556 : 278;
            };
            assertEquals(journal, counters.value(LocalIo.Role.JOURNAL, Metric.READ_BYTES));
            assertEquals(manifest, counters.value(LocalIo.Role.MANIFEST, Metric.READ_BYTES));
            assertEquals(segment, counters.value(LocalIo.Role.SEGMENT, Metric.READ_BYTES));
            boolean admitsReader = captured.strategy() == Strategy.ORDINARY && captured.shape() != Shape.JOURNAL_ONLY;
            assertEquals((captured.access() == Access.WRITABLE ? 3 : 1) + (admitsReader ? 2 : 0),
                    counters.sum(Metric.FORCE_SUCCESSES));
            assertEquals(0, counters.sum(Metric.WRITE_BYTES));
            for (int operation = 0; operation < captured.operations().size(); operation++) {
                var a = captured.operations().get(operation);
                var b = uncaptured.operations().get(operation);
                assertEquals(a.operation(), b.operation());
                assertEquals(a.measurement().kind(), b.measurement().kind());
                assertEquals(IoCoverage.ADAPTERS_ONLY, a.coverage());
                assertEquals(IoCoverage.NOT_CAPTURED, b.coverage());
            }
            if (captured.access() == Access.WRITABLE) {
                assertZero(captured.operations().get(1).counters());
                assertZero(captured.operations().get(3).counters());
                assertEquals(156, captured.operations().get(2).counters().sum(Metric.WRITE_BYTES));
                assertEquals(1, captured.operations().get(2).counters().sum(Metric.FORCE_SUCCESSES));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(MeasurementMode.class)
    void syntheticCallClockExcludesLargeSnapshotCostAndUnavailableTelemetryStaysNull(MeasurementMode mode)
            throws IOException {
        var probe = new ClockProbe();
        // The old nullable-probe overload remains an instrumented call at its original argument order.
        var result = supported(() -> mode == MeasurementMode.INSTRUMENTED
                ? LocalRecoveryComparison.run(parent, tiny(), ONCE, EXACT, probe)
                : LocalRecoveryComparison.run(parent, tiny(), ONCE, mode, EXACT, probe));
        long service = 0;
        int samples = 0;
        for (var observation : result.observations()) {
            assertTrue(observation.validationNanos() >= 0);
            assertTrue(observation.teardownNanos() >= 0);
            for (var operation : observation.operations()) {
                var sample = operation.measurement();
                assertEquals(17, sample.elapsedNanos());
                assertNull(sample.threadCpuNanos());
                assertNull(sample.threadAllocatedBytes());
                assertEquals(128, sample.heapUsedBeforeBytes());
                assertEquals(128, sample.heapUsedAfterBytes());
                assertEquals(mode == MeasurementMode.INSTRUMENTED ? IoCoverage.ADAPTERS_ONLY : IoCoverage.NOT_CAPTURED,
                        operation.coverage());
                service += sample.elapsedNanos();
                samples++;
            }
        }
        assertEquals(40, samples);
        assertEquals(80, probe.snapshots);
        assertEquals(680, service);
        assertTrue(probe.ticks >= 80_000_000, "Synthetic resource snapshots carry separately excluded overhead");
    }

    @Test
    void nullModeAndBothNullableProbeSignaturesRejectBeforeNamespaceEffects() throws IOException {
        var corpus = tiny();
        // Keep these uncast nulls: adding a competing five-argument overload must not make them ambiguous.
        assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(parent, corpus, ONCE, EXACT, null));
        assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(parent, corpus, ONCE, null));
        assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(parent, corpus, ONCE, null, EXACT));
        assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(parent, corpus, ONCE,
                null, EXACT, new ClockProbe()));
        for (var mode : MeasurementMode.values()) {
            assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(parent, corpus, ONCE, mode, EXACT, null));
            assertThrows(NullPointerException.class, () -> LocalRecoveryComparison.run(parent, corpus, ONCE, mode, null));
        }
        try (var entries = Files.list(parent)) { assertEquals(0, entries.count()); }
        LocalIoObservation.requireNoCurrentCapture();
    }

    @Test
    void ambientCallerCaptureIsRejectedWithoutFilesAndRemainsOwnedActiveAndEmpty() throws IOException {
        var caller = LocalIoObservation.open();
        try (caller) {
            assertThrows(IllegalStateException.class, () -> LocalRecoveryComparison.run(parent, tiny(), ONCE,
                    MeasurementMode.UNINSTRUMENTED, EXACT, new ClockProbe()));
            try (var entries = Files.list(parent)) { assertEquals(0, entries.count()); }
            assertThrows(IllegalStateException.class, caller::snapshot);
            assertThrows(IllegalStateException.class, LocalIoObservation::requireNoCurrentCapture);
            assertThrows(IllegalStateException.class, LocalIoObservation::open);
        }
        assertTrue(caller.snapshot().complete());
        assertZero(caller.snapshot());
        LocalIoObservation.requireNoCurrentCapture();
        var next = LocalIoObservation.open();
        next.close();
        assertZero(next.snapshot());
    }

    @Test
    void probeActivatingCaptureBeforeOpenIsRejectedBeforeFactoryIoAndItsCaptureIsPreserved() throws IOException {
        requireNativeCapability();
        var probe = new ActivatingProbe(1);
        Path failedRoot;
        try (probe) {
            var failure = assertThrows(LocalRecoveryComparison.RunFailedException.class,
                    () -> LocalRecoveryComparison.run(parent, tiny(), ONCE, MeasurementMode.UNINSTRUMENTED, EXACT, probe));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            failedRoot = failure.runRoot();
            assertEquals(1, probe.snapshots);
            assertCaptureStillOwned(probe.capture);
        }
        assertTrue(probe.capture.snapshot().complete());
        assertZero(probe.capture.snapshot());
        assertFailedHomesReleased(failedRoot, 1);
    }

    @Test
    void probeActivatingCaptureAfterOpenCannotReturnMislabeledEvidenceAndAcquiredOwnersAreClosed() throws IOException {
        requireNativeCapability();
        var probe = new ActivatingProbe(2);
        Path failedRoot;
        try (probe) {
            var failure = assertThrows(LocalRecoveryComparison.RunFailedException.class,
                    () -> LocalRecoveryComparison.run(parent, tiny(), ONCE, MeasurementMode.UNINSTRUMENTED, EXACT, probe));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            failedRoot = failure.runRoot();
            assertEquals(2, probe.snapshots);
            assertCaptureStillOwned(probe.capture);
        }
        assertTrue(probe.capture.snapshot().complete());
        assertZero(probe.capture.snapshot());
        assertFailedHomesReleased(failedRoot, 1);
    }

    @ParameterizedTest
    @MethodSource("modeAndFailure")
    void postOpenRuntimeAndErrorFailuresReleaseEveryOwnerInBothModes(MeasurementMode mode, boolean error)
            throws IOException {
        requireNativeCapability();
        Throwable problem = error ? new AssertionError("post-open error") : new IllegalStateException("post-open runtime");
        var probe = new FailingProbe(error ? 4 : 2, problem);
        Path root;
        if (error) {
            assertSame(problem, assertThrows(AssertionError.class,
                    () -> LocalRecoveryComparison.run(parent, tiny(), ONCE, mode, EXACT, probe)));
            var roots = recoveryRoots();
            assertEquals(1, roots.size());
            root = roots.getFirst();
        } else {
            var failure = assertThrows(LocalRecoveryComparison.RunFailedException.class,
                    () -> LocalRecoveryComparison.run(parent, tiny(), ONCE, mode, EXACT, probe));
            assertSame(problem, failure.getCause());
            root = failure.runRoot();
        }
        assertFailedHomesReleased(root, error ? 2 : 1);
        LocalIoObservation.requireNoCurrentCapture();
        var fresh = LocalIoObservation.open();
        fresh.close();
        assertZero(fresh.snapshot());
    }

    @Test
    void operationSamplesRequireIdentityMeasurementAndCompleteCapturedEvidence() {
        var measurement = sample();
        assertThrows(NullPointerException.class, () -> new OperationSample(null, measurement, null));
        assertThrows(NullPointerException.class, () -> new OperationSample(Operation.OPEN, null, null));
        var absent = new OperationSample(Operation.OPEN, measurement, null);
        assertNull(absent.counters());
        assertEquals(IoCoverage.NOT_CAPTURED, absent.coverage());
        var complete = zeroSnapshot();
        var captured = new OperationSample(Operation.OPEN, measurement, complete);
        assertSame(complete, captured.counters());
        assertEquals(IoCoverage.ADAPTERS_ONLY, captured.coverage());
        for (var incomplete : List.of(
                new LocalIoObservation.Snapshot(1, true, false, emptyRows()),
                new LocalIoObservation.Snapshot(1, false, true, emptyRows()))) {
            assertThrows(IllegalArgumentException.class, () -> new OperationSample(Operation.OPEN, measurement, incomplete));
        }
        var unknown = emptyRows();
        unknown.get(LocalIo.Role.UNKNOWN.ordinal()).set(Metric.READ_ATTEMPTS.ordinal(), 1L);
        unknown.get(LocalIo.Role.UNKNOWN.ordinal()).set(Metric.READ_RETURNS.ordinal(), 1L);
        unknown.get(LocalIo.Role.UNKNOWN.ordinal()).set(Metric.READ_BYTES.ordinal(), 1L);
        assertThrows(IllegalArgumentException.class, () -> new OperationSample(Operation.OPEN, measurement,
                new LocalIoObservation.Snapshot(1, false, false, unknown)));
    }

    @Test
    void independentlyConstructedResultsRejectUnknownMixedAndMismatchedModesAndCopyTheirInputs() {
        var captured = new OperationSample(Operation.OPEN, sample(), zeroSnapshot());
        var uncaptured = new OperationSample(Operation.OPEN, sample(), null);
        var instrumented = observation(List.of(captured));
        var control = observation(List.of(uncaptured));
        assertEquals(LocalRecoveryComparison.MODE, result(LocalRecoveryComparison.MODE, List.of(instrumented), List.of()).mode());
        assertEquals(LocalRecoveryComparison.UNINSTRUMENTED_MODE,
                result(LocalRecoveryComparison.UNINSTRUMENTED_MODE, List.of(control), List.of()).mode());
        assertThrows(NullPointerException.class, () -> result(null, List.of(control), List.of()));
        for (String unknown : List.of("", "INSTRUMENTED", "RECOVERY_UNINSTRUMENTED_UNKNOWN")) {
            assertThrows(IllegalArgumentException.class, () -> result(unknown, List.of(control), List.of()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> result(LocalRecoveryComparison.MODE, List.of(control), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> result(LocalRecoveryComparison.UNINSTRUMENTED_MODE, List.of(instrumented), List.of()));
        for (String mode : List.of(LocalRecoveryComparison.MODE, LocalRecoveryComparison.UNINSTRUMENTED_MODE)) {
            assertThrows(IllegalArgumentException.class,
                    () -> result(mode, List.of(observation(List.of(captured, uncaptured))), List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> result(mode, List.of(instrumented, control), List.of()));
        }
        var operations = new ArrayList<>(List.of(uncaptured));
        var copiedObservation = observation(operations);
        operations.clear();
        var observations = new ArrayList<>(List.of(copiedObservation));
        var gaps = new ArrayList<>(List.of("scope remains unavailable"));
        var copied = result(LocalRecoveryComparison.UNINSTRUMENTED_MODE, observations, gaps);
        observations.clear();
        gaps.clear();
        assertEquals(List.of(copiedObservation), copied.observations());
        assertEquals(List.of(uncaptured), copiedObservation.operations());
        assertEquals(List.of("scope remains unavailable"), copied.gaps());
        assertThrows(UnsupportedOperationException.class, () -> copied.observations().clear());
        assertThrows(UnsupportedOperationException.class, () -> copiedObservation.operations().clear());
        assertThrows(UnsupportedOperationException.class, () -> copied.gaps().clear());
    }

    @Test
    void uninstrumentedWarmupsKeepFreshNamespacesAndDoNotShiftMeasuredAbBaOrder() throws IOException {
        var result = supported(() -> LocalRecoveryComparison.run(parent, tiny(), LocalRecoveryComparison.Config.DEFAULT,
                MeasurementMode.UNINSTRUMENTED, EXACT, new ClockProbe()));
        assertEquals(48, result.observations().size());
        var homes = new HashSet<Path>();
        var pairOrders = new HashMap<String, List<Strategy>>();
        for (var observation : result.observations()) {
            assertTrue(homes.add(observation.directory()));
            assertEquals(result.runRoot(), observation.directory().getParent());
            assertEquals(observation.iteration() == 0, observation.warmup());
            String key = observation.iteration() + "/" + observation.shape() + "/" + observation.access();
            var order = pairOrders.computeIfAbsent(key, ignored -> new ArrayList<>());
            assertEquals(order.size(), observation.strategyOrdinal());
            order.add(observation.strategy());
            for (var operation : observation.operations()) { assertEquals(IoCoverage.NOT_CAPTURED, operation.coverage()); }
        }
        for (var shape : Shape.values()) {
            for (var access : Access.values()) {
                assertEquals(List.of(Strategy.ORDINARY, Strategy.HISTORY_REUSE), pairOrders.get("0/" + shape + "/" + access));
                assertEquals(List.of(Strategy.ORDINARY, Strategy.HISTORY_REUSE), pairOrders.get("1/" + shape + "/" + access));
                assertEquals(List.of(Strategy.HISTORY_REUSE, Strategy.ORDINARY), pairOrders.get("2/" + shape + "/" + access));
            }
        }
    }

    @Test
    void uninstrumentedRunNeedsNoAdmissionSlotWhenAllSixtyFourCapturesBelongToOtherThreads() throws Exception {
        requireNativeCapability();
        var ready = new CountDownLatch(64);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(64, Thread.ofPlatform().factory());
        var holders = new ArrayList<Future<LocalIoObservation.Snapshot>>();
        try {
            for (int index = 0; index < 64; index++) {
                holders.add(executor.submit(() -> {
                    var capture = LocalIoObservation.open();
                    try (capture) {
                        ready.countDown();
                        assertTrue(release.await(90, TimeUnit.SECONDS), "Other-thread capture owner exceeded its bounded hold");
                    }
                    return capture.snapshot();
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS), "All 64 independent capture owners must be active");
            assertThrows(IllegalStateException.class, LocalIoObservation::open);
            LocalIoObservation.requireNoCurrentCapture();
            var result = supported(() -> LocalRecoveryComparison.run(parent, tiny(), ONCE,
                    MeasurementMode.UNINSTRUMENTED, EXACT, new ClockProbe()));
            assertEquals("RECOVERY_UNINSTRUMENTED", result.mode());
            assertEquals(16, result.observations().size());
            for (var observation : result.observations()) {
                for (var operation : observation.operations()) {
                    assertEquals(IoCoverage.NOT_CAPTURED, operation.coverage());
                    assertNull(operation.counters());
                }
            }
            // The run neither admitted its own capture nor closed a capture owned by another thread.
            assertThrows(IllegalStateException.class, LocalIoObservation::open);
        } finally {
            release.countDown();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Capture owners did not settle");
                }
            } finally {
                executor.shutdownNow();
            }
        }
        for (var holder : holders) {
            var snapshot = holder.get(5, TimeUnit.SECONDS);
            assertTrue(snapshot.complete());
            assertZero(snapshot);
        }
        var fresh = LocalIoObservation.open();
        fresh.close();
        assertZero(fresh.snapshot());
    }

    private static Stream<Arguments> modeAndFailure() {
        return Stream.of(
                Arguments.of(MeasurementMode.INSTRUMENTED, false),
                Arguments.of(MeasurementMode.INSTRUMENTED, true),
                Arguments.of(MeasurementMode.UNINSTRUMENTED, false),
                Arguments.of(MeasurementMode.UNINSTRUMENTED, true));
    }

    private LocalRecoveryComparison.Result result(String mode, List<LocalRecoveryComparison.Observation> observations,
                                                  List<String> gaps) {
        var corpus = tiny();
        var runtime = new LocalWorkloadRunner.RuntimeInfo("25", "25", "test", "test", "test", "test", 1, 1, "test");
        return new LocalRecoveryComparison.Result(1, mode, WorkloadCorpus.VERSION, corpus.spec(), ONCE, EXACT,
                corpus.fingerprint(), corpus.historyFingerprint(), corpus.encodedHistoryBytes(),
                WorkloadOracle.prepare(corpus).summary(), runtime, parent.resolve("synthetic"), observations, gaps);
    }

    private LocalRecoveryComparison.Observation observation(List<OperationSample> operations) {
        var fixture = new LocalRecoveryComparison.Fixture(OptionalLong.empty(), 0, null, 0, 0, 0,
                Map.of("transactions.journal", "a"));
        return new LocalRecoveryComparison.Observation(0, false, 0, Shape.JOURNAL_ONLY, Access.READ_ONLY,
                Strategy.ORDINARY, parent.resolve("synthetic-home"), fixture, operations, 0, 0, 354, "a");
    }

    private static WorkloadMeasurements.Sample sample() {
        return new WorkloadMeasurements.Sample(Phase.MAINTENANCE, Kind.JOURNAL_REOPEN, 17, null, null, 128, 128);
    }

    private static List<List<Long>> emptyRows() {
        var rows = new ArrayList<List<Long>>();
        for (int role = 0; role < 8; role++) {
            var row = new ArrayList<Long>();
            for (int metric = 0; metric < 14; metric++) { row.add(0L); }
            rows.add(row);
        }
        return rows;
    }

    private static LocalIoObservation.Snapshot zeroSnapshot() {
        return new LocalIoObservation.Snapshot(1, false, false, emptyRows());
    }

    private static void assertCaptureStillOwned(LocalIoObservation capture) {
        assertThrows(IllegalStateException.class, capture::snapshot);
        assertThrows(IllegalStateException.class, LocalIoObservation::requireNoCurrentCapture);
        assertThrows(IllegalStateException.class, LocalIoObservation::open);
    }

    private void assertFailedHomesReleased(Path root, int expected) throws IOException {
        List<Path> homes;
        try (var entries = Files.list(root)) { homes = entries.filter(Files::isDirectory).toList(); }
        assertEquals(expected, homes.size());
        for (var home : homes) {
            try (var database = LocalDatabase.openWritable(home)) { assertEquals(4, database.head().sequence()); }
        }
        LocalIoObservation.requireNoCurrentCapture();
    }

    private List<Path> recoveryRoots() throws IOException {
        try (var entries = Files.list(parent)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("mdb-recovery-")).toList();
        }
    }

    private LocalRecoveryComparison.Result supported(NativeRun run) throws IOException {
        try { return run.invoke(); }
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

    private static void assertZero(LocalIoObservation.Snapshot counters) {
        for (var metric : Metric.values()) { assertEquals(0, counters.sum(metric), metric.toString()); }
    }

    private static CommitPosition position(long sequence) { return new CommitPosition(WorkloadCorpus.DATABASE, sequence); }

    private static WorkloadCorpus tiny() { return WorkloadCorpus.generate(WorkloadCorpus.Profile.APPEND.spec(43, 1, 4, 3, 1)); }

    @FunctionalInterface
    private interface NativeRun { LocalRecoveryComparison.Result invoke() throws IOException; }

    private static class ClockProbe implements WorkloadMeasurements.Probe {
        long ticks;
        int snapshots;
        @Override public long nanoTime() { return ticks += 17; }
        @Override public WorkloadMeasurements.ResourceSnapshot snapshot() {
            snapshots++;
            ticks += 1_000_000;
            return new WorkloadMeasurements.ResourceSnapshot(null, snapshots % 2 == 1 ? 100L : 99L, 128);
        }
    }

    private static final class ActivatingProbe extends ClockProbe implements AutoCloseable {
        private final int activateAt;
        LocalIoObservation capture;
        ActivatingProbe(int activateAt) { this.activateAt = activateAt; }
        @Override public WorkloadMeasurements.ResourceSnapshot snapshot() {
            var snapshot = super.snapshot();
            if (snapshots == activateAt) { capture = LocalIoObservation.open(); }
            return snapshot;
        }
        @Override public void close() { if (capture != null) { capture.close(); } }
    }

    private static final class FailingProbe extends ClockProbe {
        private final int failAt;
        private final Throwable failure;
        FailingProbe(int failAt, Throwable failure) { this.failAt = failAt; this.failure = failure; }
        @Override public WorkloadMeasurements.ResourceSnapshot snapshot() {
            var snapshot = super.snapshot();
            if (snapshots == failAt) {
                if (failure instanceof RuntimeException runtime) { throw runtime; }
                throw (Error) failure;
            }
            return snapshot;
        }
    }
}
