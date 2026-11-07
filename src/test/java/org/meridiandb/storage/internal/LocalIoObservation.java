// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Test-only activation and immutable export of caller-thread adapter counters. */
public final class LocalIoObservation implements AutoCloseable {
    public enum Metric {
        READ_ATTEMPTS, READ_RETURNS, READ_BYTES, READ_ZERO, READ_EOF, READ_FAILURES,
        WRITE_ATTEMPTS, WRITE_RETURNS, WRITE_BYTES, WRITE_ZERO, WRITE_FAILURES,
        FORCE_ATTEMPTS, FORCE_SUCCESSES, FORCE_FAILURES
    }

    private final LocalIo.Capture capture;

    private LocalIoObservation(LocalIo.Capture capture) { this.capture = capture; }

    public static LocalIoObservation open() { return open(Long.MAX_VALUE); }

    static LocalIoObservation open(long limit) { return new LocalIoObservation(LocalIo.start(limit)); }

    @Override public void close() { capture.close(); }

    /** Allocate and validate the export after closing the capture, outside native calls. */
    public Snapshot snapshot() {
        var raw = capture.snapshot();
        var rows = new ArrayList<List<Long>>(8);
        for (var role : LocalIo.Role.values()) {
            var row = new ArrayList<Long>(14);
            for (var metric : Metric.values()) {
                row.add(raw.value(role, LocalIo.Counter.valueOf(metric.name())));
            }
            rows.add(row);
        }
        return new Snapshot(raw.schemaVersion(), raw.overflow(), raw.invalidResult(), rows);
    }

    /**
     * Fixed v1 counters, including incomplete evidence. Complete coverage does not establish device
     * traffic or operation success. Summing different roles can still exceed the signed-long bound.
     */
    public record Snapshot(int schemaVersion, boolean overflow, boolean invalidResult, List<List<Long>> counts) {
        public Snapshot {
            require(schemaVersion == 1, "Unsupported local I/O counter schema");
            require(counts != null && counts.size() == 8, "Require eight local I/O roles");
            var rows = new ArrayList<List<Long>>(8);
            for (var supplied : counts) {
                require(supplied != null && supplied.size() == 14, "Require fourteen counters per role");
                for (var value : supplied) { require(value != null && value >= 0, "Require nonnegative local I/O counters"); }
                var row = List.copyOf(supplied);
                if (!overflow && !invalidResult) { coherent(row); }
                rows.add(row);
            }
            counts = List.copyOf(rows);
        }

        public long value(LocalIo.Role role, Metric metric) {
            return counts.get(Objects.requireNonNull(role, "role").ordinal())
                    .get(Objects.requireNonNull(metric, "metric").ordinal());
        }

        /** Exact aggregation only; an overflowing aggregate is unavailable, not a saturated sum. */
        public long sum(Metric metric) {
            Objects.requireNonNull(metric, "metric");
            long total = 0;
            for (var row : counts) { total = Math.addExact(total, row.get(metric.ordinal())); }
            return total;
        }

        public boolean complete() {
            if (overflow || invalidResult) { return false; }
            for (var metric : Metric.values()) { if (value(LocalIo.Role.UNKNOWN, metric) != 0) { return false; } }
            for (var role : LocalIo.Role.values()) {
                if (value(role, Metric.READ_FAILURES) != 0 || value(role, Metric.WRITE_FAILURES) != 0
                        || value(role, Metric.FORCE_FAILURES) != 0) { return false; }
            }
            return true;
        }

        private static void coherent(List<Long> row) {
            long readReturns = get(row, Metric.READ_RETURNS), writeReturns = get(row, Metric.WRITE_RETURNS);
            require(get(row, Metric.READ_ATTEMPTS) == Math.addExact(readReturns, get(row, Metric.READ_FAILURES)),
                    "Read attempts differ from returns and failures");
            require(get(row, Metric.WRITE_ATTEMPTS) == Math.addExact(writeReturns, get(row, Metric.WRITE_FAILURES)),
                    "Write attempts differ from returns and failures");
            require(get(row, Metric.FORCE_ATTEMPTS) == Math.addExact(get(row, Metric.FORCE_SUCCESSES), get(row, Metric.FORCE_FAILURES)),
                    "Force attempts differ from successes and failures");
            long readEmpty = Math.addExact(get(row, Metric.READ_ZERO), get(row, Metric.READ_EOF));
            require(readEmpty <= readReturns, "Read zero and EOF exceed returned calls");
            require(get(row, Metric.WRITE_ZERO) <= writeReturns, "Write zero exceeds returned calls");
            positiveBytes(get(row, Metric.READ_BYTES), readReturns - readEmpty, "Read");
            positiveBytes(get(row, Metric.WRITE_BYTES), writeReturns - get(row, Metric.WRITE_ZERO), "Write");
        }

        private static long get(List<Long> row, Metric metric) { return row.get(metric.ordinal()); }

        private static void positiveBytes(long bytes, long positiveReturns, String label) {
            require(positiveReturns == 0 ? bytes == 0 : bytes >= positiveReturns,
                    label + " bytes differ from positive returned calls");
        }

        private static void require(boolean condition, String message) {
            if (!condition) { throw new IllegalArgumentException(message); }
        }
    }
}
