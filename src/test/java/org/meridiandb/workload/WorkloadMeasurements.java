// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/** Bounded developer measurements; these helpers are not a production API or an arrival-rate harness. */
public final class WorkloadMeasurements {
    public static final int MAX_SAMPLES = 100_000;

    public enum Phase { LOAD, MIXED, MAINTENANCE }
    public enum Kind {
        CREATE, COMMIT, RETRY, GET, SCAN, SELECT, FILTER_SCAN, SUM, JOURNAL_REOPEN,
        SEGMENT_WRITE, CHECKPOINT_PUBLISH, COMPACTION_PLAN, COMPACTION_PUBLISH, CHECKPOINT_REOPEN
    }

    public record ResourceSnapshot(Long threadCpuNanos, Long threadAllocatedBytes, long heapUsedBytes) {
        public ResourceSnapshot {
            optionalNonnegative(threadCpuNanos, "threadCpuNanos");
            optionalNonnegative(threadAllocatedBytes, "threadAllocatedBytes");
            nonnegative(heapUsedBytes, "heapUsedBytes");
        }
    }

    public interface Probe {
        long nanoTime();
        ResourceSnapshot snapshot();
    }

    @FunctionalInterface
    public interface Call<T> {
        T invoke() throws IOException;
    }

    /**
     * Wall time brackets only the call. CPU/allocation snapshots bracket a slightly wider
     * instrumented interval and describe the executing platform thread, not GC or all process
     * threads. Heap values are raw sampled process gauges and can fall after collection.
     */
    public record Sample(Phase phase, Kind kind, long elapsedNanos, Long threadCpuNanos,
                         Long threadAllocatedBytes, long heapUsedBeforeBytes, long heapUsedAfterBytes) {
        public Sample {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(kind, "kind");
            nonnegative(elapsedNanos, "elapsedNanos");
            optionalNonnegative(threadCpuNanos, "threadCpuNanos");
            optionalNonnegative(threadAllocatedBytes, "threadAllocatedBytes");
            nonnegative(heapUsedBeforeBytes, "heapUsedBeforeBytes");
            nonnegative(heapUsedAfterBytes, "heapUsedAfterBytes");
        }
    }

    /** Counter totals are present only when every sample has that counter. Percentiles use nearest rank. */
    public record Summary(Phase phase, Kind kind, int count, long totalNanos, long minNanos,
                          long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos,
                          Double serviceOperationsPerSecond, Long threadCpuNanos, int threadCpuSamples,
                          Long threadAllocatedBytes, int threadAllocatedSamples, long maxSampledHeapUsedBytes) { }

    private WorkloadMeasurements() { }

    /** Initialize before execution. This probe does not enable counters, request GC or reset heap peaks. */
    public static Probe systemProbe() {
        var threads = ManagementFactory.getThreadMXBean();
        var heap = ManagementFactory.getMemoryMXBean();
        var allocations = threads instanceof com.sun.management.ThreadMXBean extended ? extended : null;
        return new Probe() {
            @Override public long nanoTime() { return System.nanoTime(); }

            @Override public ResourceSnapshot snapshot() {
                Long cpu = null;
                Long allocated = null;
                if (!Thread.currentThread().isVirtual() && threads.isCurrentThreadCpuTimeSupported()
                        && threads.isThreadCpuTimeEnabled()) {
                    cpu = available(threads.getCurrentThreadCpuTime());
                }
                if (!Thread.currentThread().isVirtual() && allocations != null
                        && allocations.isThreadAllocatedMemorySupported()
                        && allocations.isThreadAllocatedMemoryEnabled()) {
                    allocated = available(allocations.getCurrentThreadAllocatedBytes());
                }
                return new ResourceSnapshot(cpu, allocated, heap.getHeapMemoryUsage().getUsed());
            }
        };
    }

    /** Single-threaded recorder owned by one run; capacity is admitted before invoking the operation. */
    public static final class Recorder {
        private final Probe probe;
        private final int maxSamples;
        private final List<Sample> samples;
        private long serviceNanos;
        private boolean measuring;

        public Recorder(Probe probe, int maxSamples) {
            this.probe = Objects.requireNonNull(probe, "probe");
            if (maxSamples < 1 || maxSamples > MAX_SAMPLES) {
                throw new IllegalArgumentException("Measurement capacity must be in [1," + MAX_SAMPLES + "]");
            }
            this.maxSamples = maxSamples;
            samples = new ArrayList<>(Math.min(maxSamples, 1024));
        }

        public <T> T measure(Phase phase, Kind kind, Call<T> call) throws IOException {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(call, "call");
            if (measuring) { throw new IllegalStateException("Reentrant measurement is unsupported"); }
            if (samples.size() >= maxSamples) {
                throw new IllegalStateException("Measurement sample capacity exhausted before operation");
            }
            measuring = true;
            try {
                var before = Objects.requireNonNull(probe.snapshot(), "before snapshot");
                long start = probe.nanoTime();
                T result = call.invoke();
                try {
                    // nanoTime has an arbitrary signed origin; subtraction preserves short
                    // intervals across its signed boundary. Negative spans are invalid/too long.
                    long elapsed = probe.nanoTime() - start;
                    nonnegative(elapsed, "elapsedNanos");
                    var after = Objects.requireNonNull(probe.snapshot(), "after snapshot");
                    var sample = new Sample(phase, kind, elapsed,
                            delta(before.threadCpuNanos(), after.threadCpuNanos()),
                            delta(before.threadAllocatedBytes(), after.threadAllocatedBytes()),
                            before.heapUsedBytes(), after.heapUsedBytes());
                    long updatedServiceNanos = Math.addExact(serviceNanos, elapsed);
                    samples.add(sample);
                    serviceNanos = updatedServiceNanos;
                    return result;
                } catch (RuntimeException | Error failure) {
                    // CREATE/REOPEN may already have acquired native owners before telemetry fails.
                    if (result instanceof AutoCloseable resource) {
                        try {
                            resource.close();
                        } catch (Throwable cleanup) {
                            if (cleanup != failure) { failure.addSuppressed(cleanup); }
                        }
                    }
                    throw failure;
                }
            } finally {
                measuring = false;
            }
        }

        public List<Sample> samples() { return List.copyOf(samples); }

        /** Sum of call-bracket wall durations; excludes validation and recording gaps. */
        public long serviceNanos() { return serviceNanos; }

        /** Stable enum order, independent of first-use order. No histogram or timing data are discarded. */
        public List<Summary> summaries() {
            var groups = new EnumMap<Phase, EnumMap<Kind, List<Sample>>>(Phase.class);
            for (var sample : samples) {
                groups.computeIfAbsent(sample.phase(), ignored -> new EnumMap<>(Kind.class))
                        .computeIfAbsent(sample.kind(), ignored -> new ArrayList<>()).add(sample);
            }
            var summaries = new ArrayList<Summary>();
            for (var phase : groups.entrySet()) {
                for (var kind : phase.getValue().entrySet()) {
                    summaries.add(summarize(phase.getKey(), kind.getKey(), kind.getValue()));
                }
            }
            return List.copyOf(summaries);
        }
    }

    private static Summary summarize(Phase phase, Kind kind, List<Sample> samples) {
        long[] elapsed = new long[samples.size()];
        long total = 0;
        long cpu = 0;
        long allocated = 0;
        long heap = 0;
        int cpuSamples = 0;
        int allocatedSamples = 0;
        for (int index = 0; index < samples.size(); index++) {
            var sample = samples.get(index);
            elapsed[index] = sample.elapsedNanos();
            total = Math.addExact(total, sample.elapsedNanos());
            if (sample.threadCpuNanos() != null) {
                cpu = Math.addExact(cpu, sample.threadCpuNanos());
                cpuSamples++;
            }
            if (sample.threadAllocatedBytes() != null) {
                allocated = Math.addExact(allocated, sample.threadAllocatedBytes());
                allocatedSamples++;
            }
            heap = Math.max(heap, Math.max(sample.heapUsedBeforeBytes(), sample.heapUsedAfterBytes()));
        }
        Arrays.sort(elapsed);
        Double rate = total == 0 ? null : (double) samples.size() * 1_000_000_000 / total;
        if (rate != null && !Double.isFinite(rate)) { rate = null; }
        return new Summary(phase, kind, samples.size(), total, elapsed[0], percentile(elapsed, 50),
                percentile(elapsed, 95), percentile(elapsed, 99), elapsed[elapsed.length - 1], rate,
                cpuSamples == samples.size() ? cpu : null, cpuSamples,
                allocatedSamples == samples.size() ? allocated : null, allocatedSamples, heap);
    }

    private static long percentile(long[] sorted, int percent) {
        return sorted[(int) (((long) percent * sorted.length + 99) / 100 - 1)];
    }

    private static Long delta(Long before, Long after) {
        return before == null || after == null || after < before ? null : after - before;
    }

    private static Long available(long value) { return value < 0 ? null : value; }

    private static void optionalNonnegative(Long value, String name) {
        if (value != null) { nonnegative(value, name); }
    }

    private static void nonnegative(long value, String name) {
        if (value < 0) { throw new IllegalArgumentException(name + " must be nonnegative"); }
    }
}
