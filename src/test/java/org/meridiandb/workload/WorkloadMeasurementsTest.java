// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.meridiandb.workload.WorkloadMeasurements.Kind;
import org.meridiandb.workload.WorkloadMeasurements.Phase;
import org.meridiandb.workload.WorkloadMeasurements.Probe;
import org.meridiandb.workload.WorkloadMeasurements.Recorder;
import org.meridiandb.workload.WorkloadMeasurements.ResourceSnapshot;
import org.meridiandb.workload.WorkloadMeasurements.Sample;

@Timeout(30)
class WorkloadMeasurementsTest {
    @Test
    void literalSamplesHaveExactNearestRankPercentilesCountersAndServiceRate() throws IOException {
        var times = new ArrayList<Long>();
        var snapshots = new ArrayList<ResourceSnapshot>();
        long clock = 100;
        for (int index = 100; index >= 1; index--) {
            times.add(clock);
            times.add(clock + index);
            snapshots.add(new ResourceSnapshot(100L, 200L, 500));
            snapshots.add(new ResourceSnapshot(103L, 207L, 400));
            clock += index + 17;
        }
        var recorder = new Recorder(new ScriptedProbe(times, snapshots), 100);
        for (int index = 0; index < 100; index++) {
            int actual = recorder.measure(Phase.MIXED, Kind.GET, () -> recorder.samples().size());
            assertEquals(index, actual);
        }
        var summary = recorder.summaries().getFirst();
        assertEquals(100, summary.count());
        assertEquals(5050, summary.totalNanos());
        assertEquals(1, summary.minNanos());
        assertEquals(50, summary.p50Nanos());
        assertEquals(95, summary.p95Nanos());
        assertEquals(99, summary.p99Nanos());
        assertEquals(100, summary.maxNanos());
        assertEquals(100d * 1_000_000_000 / 5050, summary.serviceOperationsPerSecond());
        assertEquals(300L, summary.threadCpuNanos());
        assertEquals(700L, summary.threadAllocatedBytes());
        assertEquals(100, summary.threadCpuSamples());
        assertEquals(100, summary.threadAllocatedSamples());
        assertEquals(500, summary.maxSampledHeapUsedBytes());
        assertEquals(5050, recorder.serviceNanos(), "Inter-operation gaps are excluded");
    }

    @Test
    void groupingUsesPhaseAndKindAndStableEnumOrder() throws IOException {
        var recorder = new Recorder(script(8, 3, 5), 3);
        recorder.measure(Phase.MAINTENANCE, Kind.CHECKPOINT_REOPEN, () -> null);
        recorder.measure(Phase.MIXED, Kind.COMMIT, () -> null);
        recorder.measure(Phase.LOAD, Kind.COMMIT, () -> null);
        assertEquals(List.of(Phase.LOAD, Phase.MIXED, Phase.MAINTENANCE),
                recorder.summaries().stream().map(WorkloadMeasurements.Summary::phase).toList());
        assertEquals(List.of(5L, 3L, 8L),
                recorder.summaries().stream().map(WorkloadMeasurements.Summary::totalNanos).toList());
    }

    @Test
    void snapshotReadsStayOutsideTheDirectCallClockBrackets() throws IOException {
        var order = new ArrayList<String>();
        var probe = new Probe() {
            private int clock;
            @Override public long nanoTime() { order.add("clock"); return ++clock; }
            @Override public ResourceSnapshot snapshot() {
                order.add("resources");
                return new ResourceSnapshot(null, null, 1);
            }
        };
        var recorder = new Recorder(probe, 1);
        Object value = new Object();
        assertSame(value, recorder.measure(Phase.MIXED, Kind.GET, () -> { order.add("call"); return value; }));
        assertEquals(List.of("resources", "clock", "call", "clock", "resources"), order);
    }

    @Test
    void zeroClockResolutionHasNoInventedInfiniteRate() throws IOException {
        var recorder = new Recorder(script(0, 0), 2);
        recorder.measure(Phase.MIXED, Kind.GET, () -> null);
        recorder.measure(Phase.MIXED, Kind.GET, () -> null);
        var summary = recorder.summaries().getFirst();
        assertEquals(0, summary.totalNanos());
        assertEquals(0, summary.p99Nanos());
        assertNull(summary.serviceOperationsPerSecond());
    }

    @Test
    void unavailableAndResetCountersPreserveAvailabilityWithoutFakeZeroTotals() throws IOException {
        var probe = new ScriptedProbe(List.of(0L, 1L, 2L, 3L, 4L, 5L), List.of(
                new ResourceSnapshot(null, 20L, 900), new ResourceSnapshot(null, 25L, 700),
                new ResourceSnapshot(10L, null, 600), new ResourceSnapshot(12L, null, 500),
                new ResourceSnapshot(40L, 40L, 400), new ResourceSnapshot(1L, 1L, 300)));
        var recorder = new Recorder(probe, 3);
        for (int index = 0; index < 3; index++) { recorder.measure(Phase.MIXED, Kind.GET, () -> null); }
        var summary = recorder.summaries().getFirst();
        assertNull(summary.threadCpuNanos());
        assertNull(summary.threadAllocatedBytes());
        assertEquals(1, summary.threadCpuSamples());
        assertEquals(1, summary.threadAllocatedSamples());
        assertNull(recorder.samples().getLast().threadCpuNanos());
        assertNull(recorder.samples().getLast().threadAllocatedBytes());
        assertEquals(900, summary.maxSampledHeapUsedBytes());
        assertEquals(700, recorder.samples().getFirst().heapUsedAfterBytes());
    }

    @Test
    void samplesAndSummariesAreImmutableSnapshots() throws IOException {
        var recorder = new Recorder(script(1, 2), 2);
        recorder.measure(Phase.LOAD, Kind.COMMIT, () -> null);
        var samples = recorder.samples();
        var summaries = recorder.summaries();
        recorder.measure(Phase.LOAD, Kind.COMMIT, () -> null);
        assertEquals(1, samples.size());
        assertEquals(1, summaries.getFirst().count());
        assertThrows(UnsupportedOperationException.class, samples::clear);
        assertThrows(UnsupportedOperationException.class, summaries::clear);
        assertEquals(2, recorder.summaries().getFirst().count());
    }

    @Test
    void capacityIsRejectedBeforeTelemetryOrOperationEffects() throws IOException {
        var probe = script(1);
        var recorder = new Recorder(probe, 1);
        recorder.measure(Phase.MIXED, Kind.COMMIT, () -> null);
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class,
                () -> recorder.measure(Phase.MIXED, Kind.COMMIT, calls::incrementAndGet));
        assertEquals(0, calls.get());
        assertEquals(2, probe.timeIndex);
        assertEquals(2, probe.snapshotIndex);
        assertEquals(1, recorder.samples().size());
    }

    @Test
    void reentrantCallsCannotBypassCapacityOrInvokeNestedOperations() throws IOException {
        var recorder = new Recorder(script(1), 1);
        var nestedCalls = new AtomicInteger();
        recorder.measure(Phase.MIXED, Kind.GET, () -> {
            assertThrows(IllegalStateException.class,
                    () -> recorder.measure(Phase.MIXED, Kind.GET, nestedCalls::incrementAndGet));
            return null;
        });
        assertEquals(0, nestedCalls.get());
        assertEquals(1, recorder.samples().size());
        assertEquals(1, recorder.serviceNanos());
    }

    @Test
    void invalidCapacitiesAndRequiredValuesRejectBeforeOperation() {
        var probe = script(1);
        assertThrows(NullPointerException.class, () -> new Recorder(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new Recorder(probe, 0));
        assertThrows(IllegalArgumentException.class, () -> new Recorder(probe, WorkloadMeasurements.MAX_SAMPLES + 1));
        var recorder = new Recorder(probe, 1);
        assertThrows(NullPointerException.class, () -> recorder.measure(null, Kind.GET, () -> null));
        assertThrows(NullPointerException.class, () -> recorder.measure(Phase.LOAD, null, () -> null));
        assertThrows(NullPointerException.class, () -> recorder.measure(Phase.LOAD, Kind.GET, null));
        assertEquals(0, probe.timeIndex);
        assertEquals(0, probe.snapshotIndex);
        assertTrue(recorder.samples().isEmpty());
        assertTrue(recorder.summaries().isEmpty());
    }

    @Test
    void checkedAndUncheckedOperationFailuresDoNotYieldSuccessSamples() {
        for (Throwable failure : List.of(new IOException("native I/O"), new IllegalStateException("operation"),
                new AssertionError("operation"))) {
            var probe = script(1);
            var recorder = new Recorder(probe, 1);
            Throwable actual = assertThrows(Throwable.class, () -> recorder.measure(Phase.MIXED, Kind.COMMIT, () -> {
                if (failure instanceof IOException io) { throw io; }
                if (failure instanceof RuntimeException runtime) { throw runtime; }
                throw (Error) failure;
            }));
            assertSame(failure, actual);
            assertTrue(recorder.samples().isEmpty());
            assertEquals(0, recorder.serviceNanos());
            assertEquals(1, probe.timeIndex);
            assertEquals(1, probe.snapshotIndex);
        }
    }

    @Test
    void postCallClockFailureClosesTheReturnedResource() {
        var failure = new IllegalStateException("post clock");
        var probe = new Probe() {
            private int calls;
            @Override public long nanoTime() { if (++calls == 2) { throw failure; } return 1; }
            @Override public ResourceSnapshot snapshot() { return new ResourceSnapshot(null, null, 0); }
        };
        var recorder = new Recorder(probe, 1);
        var resource = new TrackedResource(null);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> recorder.measure(Phase.LOAD, Kind.CREATE, () -> resource)));
        assertEquals(1, resource.closes);
        assertTrue(recorder.samples().isEmpty());
    }

    @Test
    void postCallTelemetryFailurePreservesPrimaryAndSuppressesCleanupFailure() {
        var failure = new IllegalStateException("post resources");
        var cleanup = new IOException("close");
        var probe = new Probe() {
            private int calls;
            @Override public long nanoTime() { return 1; }
            @Override public ResourceSnapshot snapshot() {
                if (++calls == 2) { throw failure; }
                return new ResourceSnapshot(null, null, 0);
            }
        };
        var recorder = new Recorder(probe, 1);
        var resource = new TrackedResource(cleanup);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> recorder.measure(Phase.MAINTENANCE, Kind.JOURNAL_REOPEN, () -> resource)));
        assertEquals(List.of(cleanup), List.of(failure.getSuppressed()));
        assertEquals(1, resource.closes);
        assertEquals(0, recorder.serviceNanos());
        assertTrue(recorder.samples().isEmpty());
    }

    @Test
    void modularClockSubtractionAcceptsShortWrapAndRejectsNegativeOrExcessiveSpans() throws IOException {
        var boundaryProbe = new ScriptedProbe(List.of(Long.MAX_VALUE, Long.MIN_VALUE),
                List.of(new ResourceSnapshot(null, null, 0), new ResourceSnapshot(null, null, 0)));
        var boundaryRecorder = new Recorder(boundaryProbe, 1);
        var boundaryResource = new TrackedResource(null);
        assertSame(boundaryResource,
                boundaryRecorder.measure(Phase.LOAD, Kind.CREATE, () -> boundaryResource));
        assertEquals(1, boundaryRecorder.samples().getFirst().elapsedNanos());
        assertEquals(1, boundaryRecorder.serviceNanos());
        assertEquals(0, boundaryResource.closes, "Successful acquisition leaves ownership with the caller");
        boundaryResource.close();
        for (List<Long> times : List.of(List.of(2L, 1L), List.of(0L, Long.MIN_VALUE))) {
            var probe = new ScriptedProbe(times,
                    List.of(new ResourceSnapshot(null, null, 0), new ResourceSnapshot(null, null, 0)));
            var recorder = new Recorder(probe, 1);
            var resource = new TrackedResource(null);
            assertThrows(RuntimeException.class,
                    () -> recorder.measure(Phase.LOAD, Kind.CREATE, () -> resource));
            assertEquals(1, resource.closes);
            assertTrue(recorder.samples().isEmpty());
        }
    }

    @Test
    void aggregateWallOverflowRejectsBeforePublishingANewSampleAndReleasesOwner() throws IOException {
        var probe = new ScriptedProbe(List.of(0L, Long.MAX_VALUE, 0L, 1L), List.of(
                new ResourceSnapshot(null, null, 0), new ResourceSnapshot(null, null, 0),
                new ResourceSnapshot(null, null, 0), new ResourceSnapshot(null, null, 0)));
        var recorder = new Recorder(probe, 2);
        recorder.measure(Phase.MIXED, Kind.GET, () -> null);
        var resource = new TrackedResource(null);
        assertThrows(ArithmeticException.class,
                () -> recorder.measure(Phase.MAINTENANCE, Kind.CHECKPOINT_REOPEN, () -> resource));
        assertEquals(1, resource.closes);
        assertEquals(Long.MAX_VALUE, recorder.serviceNanos());
        assertEquals(1, recorder.samples().size());
    }

    @Test
    void snapshotAndSampleValidationAcceptMissingTelemetryAndRejectNegativeValues() {
        assertEquals(new ResourceSnapshot(null, null, 0), new ResourceSnapshot(null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new ResourceSnapshot(-1L, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new ResourceSnapshot(null, -1L, 0));
        assertThrows(IllegalArgumentException.class, () -> new ResourceSnapshot(null, null, -1));
        assertThrows(NullPointerException.class, () -> new Sample(null, Kind.GET, 0, null, null, 0, 0));
        assertThrows(NullPointerException.class, () -> new Sample(Phase.MIXED, null, 0, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sample(Phase.MIXED, Kind.GET, -1, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sample(Phase.MIXED, Kind.GET, 0, -1L, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sample(Phase.MIXED, Kind.GET, 0, null, -1L, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sample(Phase.MIXED, Kind.GET, 0, null, null, -1, 0));
    }

    @Test
    void virtualThreadsHaveExplicitlyUnavailableThreadCounters() throws InterruptedException {
        var probe = WorkloadMeasurements.systemProbe();
        var snapshot = new AtomicReference<ResourceSnapshot>();
        var worker = Thread.startVirtualThread(() -> snapshot.set(probe.snapshot()));
        worker.join();
        assertNull(snapshot.get().threadCpuNanos());
        assertNull(snapshot.get().threadAllocatedBytes());
        assertTrue(snapshot.get().heapUsedBytes() >= 0);
    }

    @Test
    void systemProbeSamplesWithoutEnablingCpuOrAllocationCounters() {
        var threads = ManagementFactory.getThreadMXBean();
        boolean cpuEnabled = threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled();
        var allocations = threads instanceof com.sun.management.ThreadMXBean extended ? extended : null;
        boolean allocatedEnabled = allocations != null && allocations.isThreadAllocatedMemorySupported()
                && allocations.isThreadAllocatedMemoryEnabled();
        var probe = WorkloadMeasurements.systemProbe();
        var snapshot = probe.snapshot();
        assertTrue(snapshot.heapUsedBytes() >= 0);
        if (snapshot.threadCpuNanos() != null) { assertTrue(snapshot.threadCpuNanos() >= 0); }
        if (snapshot.threadAllocatedBytes() != null) { assertTrue(snapshot.threadAllocatedBytes() >= 0); }
        assertEquals(cpuEnabled, threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled());
        assertEquals(allocatedEnabled, allocations != null && allocations.isThreadAllocatedMemorySupported()
                && allocations.isThreadAllocatedMemoryEnabled());
        assertFalse(Thread.currentThread().isVirtual(), "Jupiter executes this ordinary test on a platform thread");
    }

    private static ScriptedProbe script(long... durations) {
        var times = new ArrayList<Long>();
        var snapshots = new ArrayList<ResourceSnapshot>();
        long clock = 0;
        for (long duration : durations) {
            times.add(clock);
            times.add(clock + duration);
            clock += duration + 10;
            snapshots.add(new ResourceSnapshot(null, null, 1));
            snapshots.add(new ResourceSnapshot(null, null, 1));
        }
        return new ScriptedProbe(times, snapshots);
    }

    private static final class ScriptedProbe implements Probe {
        private final List<Long> times;
        private final List<ResourceSnapshot> snapshots;
        private int timeIndex;
        private int snapshotIndex;

        ScriptedProbe(List<Long> times, List<ResourceSnapshot> snapshots) {
            this.times = List.copyOf(times);
            this.snapshots = List.copyOf(snapshots);
        }
        @Override public long nanoTime() { return times.get(timeIndex++); }
        @Override public ResourceSnapshot snapshot() { return snapshots.get(snapshotIndex++); }
    }

    private static final class TrackedResource implements AutoCloseable {
        private final IOException failure;
        private int closes;

        TrackedResource(IOException failure) { this.failure = failure; }

        @Override public void close() throws IOException {
            closes++;
            if (failure != null) { throw failure; }
        }
    }
}
