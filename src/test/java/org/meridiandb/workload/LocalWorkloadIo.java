// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.meridiandb.storage.internal.LocalIoObservation;
import org.meridiandb.storage.internal.LocalIoObservation.Snapshot;
import org.meridiandb.workload.WorkloadMeasurements.Call;
import org.meridiandb.workload.WorkloadMeasurements.Kind;
import org.meridiandb.workload.WorkloadMeasurements.Phase;

/** Test-only ordered attribution; observations are outside operation service clocks. */
public final class LocalWorkloadIo {
    public enum Coverage { ADAPTERS_ONLY, EXCLUDED_EXTERNAL_IO }
    public enum Category {
        PREPARATION, NAMESPACE, OPERATION, OWNER_ACQUISITION, PREFIX_VERIFICATION,
        READER_ACQUISITION, VALIDATION, EXTERNAL_HASH, FILE_INVENTORY, TEARDOWN;

        public Coverage coverage() {
            return this == EXTERNAL_HASH || this == FILE_INVENTORY
                    ? Coverage.EXCLUDED_EXTERNAL_IO : Coverage.ADAPTERS_ONLY;
        }
    }

    /** A null snapshot explicitly records observation failure, never an invented zero. */
    public record Entry(int ordinal, int iteration, Category category, Phase phase, Kind kind,
                        boolean callReturned, Snapshot counters, String operationFailureType,
                        String observationFailureType, boolean cleanupOutsideCapture) {
        public Entry {
            Objects.requireNonNull(category, "category");
            if (ordinal < 0 || iteration < -1 || (category == Category.OPERATION) != (phase != null && kind != null)
                    || (category != Category.OPERATION && (phase != null || kind != null))
                    || (callReturned && operationFailureType != null)
                    || (counters == null && observationFailureType == null)) {
                throw new IllegalArgumentException("Invalid file-work scope identity/outcome");
            }
        }
        public boolean accepted() {
            return callReturned && operationFailureType == null && observationFailureType == null
                    && counters != null && counters.complete() && !cleanupOutsideCapture;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final int capacity;
    private final Consumer<Snapshot> afterObservation;
    private final List<Entry> entries = new ArrayList<>();
    private boolean active;
    private int iteration = -1;

    LocalWorkloadIo(int capacity) { this(capacity, ignored -> { }); }

    // This fault seam executes only after capture close/snapshot, never inside native I/O.
    LocalWorkloadIo(int capacity, Consumer<Snapshot> afterObservation) {
        if (capacity < 1 || capacity > LocalAttributedWorkload.MAX_SCOPES) {
            throw new IllegalArgumentException("File-work scope capacity exceeded");
        }
        this.capacity = capacity;
        this.afterObservation = Objects.requireNonNull(afterObservation, "afterObservation");
    }

    void iteration(int value) {
        requireIdle();
        if (value < 0) { throw new IllegalArgumentException("Negative iteration"); }
        iteration = value;
    }

    void admit(int requiredCapacity) {
        requireIdle();
        if (!entries.isEmpty() || iteration != -1) { throw new IllegalStateException("Attributed trace must be fresh"); }
        if (capacity < requiredCapacity) { throw new IllegalArgumentException("Insufficient complete-run trace capacity"); }
    }

    <T> T call(Category category, Phase phase, Kind kind, Call<T> call) throws IOException {
        requireIdle();
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(call, "call");
        if ((category == Category.OPERATION) != (phase != null && kind != null)
                || (category != Category.OPERATION && (phase != null || kind != null))) {
            throw new IllegalArgumentException("Operation phase/kind required only for operation scopes");
        }
        // Reserve evidence capacity before acquisition, writes or any user call.
        if (entries.size() == capacity) { throw new IllegalStateException("File-work scope capacity exhausted before call"); }
        active = true;
        LocalIoObservation capture = null;
        T result = null;
        boolean returned = false;
        Throwable failure = null;
        Throwable observationFailure = null;
        Snapshot snapshot = null;
        String operationFailureType = null;
        try {
            try {
                capture = LocalIoObservation.open();
            } catch (RuntimeException | Error problem) {
                failure = problem;
                observationFailure = problem;
            }
            if (capture != null) {
                try {
                    result = call.invoke();
                    returned = true;
                } catch (IOException | RuntimeException | Error problem) {
                    failure = problem;
                    operationFailureType = problem.getClass().getName();
                }
                try {
                    capture.close();
                    snapshot = capture.snapshot();
                    afterObservation.accept(snapshot);
                    if (!snapshot.complete()) { throw new IOException("Incomplete file-work observation"); }
                } catch (IOException | RuntimeException | Error problem) {
                    observationFailure = problem;
                    failure = combine(failure, problem);
                }
            }
            boolean cleanupOutsideCapture = failure != null && returned && result instanceof AutoCloseable;
            if (cleanupOutsideCapture) {
                // The caller has not received this owner and cannot yet enter try-with-resources.
                // A failed observation grants no complete accounting claim for this cleanup.
                try { ((AutoCloseable) result).close(); }
                catch (Throwable cleanup) { failure = combine(failure, cleanup); }
            }
            try {
                entries.add(new Entry(entries.size(), iteration, category, phase, kind, returned, snapshot,
                        operationFailureType, observationFailure == null ? null : observationFailure.getClass().getName(),
                        cleanupOutsideCapture));
            } catch (RuntimeException | Error recordingFailure) {
                if (failure == null && returned && result instanceof AutoCloseable resource) {
                    try { resource.close(); } catch (Throwable cleanup) { combine(recordingFailure, cleanup); }
                }
                failure = combine(failure, recordingFailure);
            }
            if (failure != null) { rethrow(failure); }
            return result;
        } finally { active = false; }
    }

    List<Entry> entries() { requireIdle(); return List.copyOf(entries); }

    private void requireIdle() {
        if (Thread.currentThread() != owner) { throw new IllegalStateException("File-work trace belongs to another thread"); }
        if (active) { throw new IllegalStateException("Reentrant file-work attribution is unsupported"); }
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) { return next; }
        if (first != next) { first.addSuppressed(next); }
        return first;
    }

    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        throw new IOException(failure);
    }

    static <T> T observe(LocalWorkloadIo trace, Category category, Call<T> call) throws IOException {
        return trace == null ? call.invoke() : trace.call(category, null, null, call);
    }

    static <T> T operation(LocalWorkloadIo trace, Phase phase, Kind kind,
                           WorkloadMeasurements.Recorder recorder, Call<T> call) throws IOException {
        return trace == null ? recorder.measure(phase, kind, call)
                : trace.call(Category.OPERATION, phase, kind, () -> recorder.measure(phase, kind, call));
    }

    static <T extends AutoCloseable> Owner<T> own(LocalWorkloadIo trace, T value) {
        try { return new Owner<>(trace, value); }
        catch (RuntimeException | Error failure) {
            if (value != null) {
                try { value.close(); } catch (Throwable cleanup) { combine(failure, cleanup); }
            }
            throw failure;
        }
    }

    static final class Owner<T extends AutoCloseable> implements AutoCloseable {
        private final LocalWorkloadIo trace;
        private final T value;
        private boolean attempted;
        Owner(LocalWorkloadIo trace, T value) { this.trace = trace; this.value = Objects.requireNonNull(value, "value"); }
        T value() { return value; }
        @Override public void close() throws IOException {
            if (trace != null) { trace.requireIdle(); }
            int before = trace == null ? 0 : trace.entries.size();
            // Exhausted observation capacity cannot prevent releasing an already-owned resource.
            if (trace != null && trace.entries.size() == trace.capacity) {
                var failure = new IllegalStateException("Cleanup observation capacity exhausted");
                try { closeValue(); } catch (Throwable cleanup) { combine(failure, cleanup); }
                throw failure;
            }
            attempted = false;
            try {
                observe(trace, Category.TEARDOWN, () -> { closeValue(); return null; });
            } catch (IOException | RuntimeException | Error failure) {
                if (!attempted) {
                    // A failed capture setup must not strand an owner acquired in an earlier scope.
                    try { closeValue(); } catch (Throwable cleanup) { combine(failure, cleanup); }
                    if (trace != null && trace.entries.size() > before) {
                        int index = trace.entries.size() - 1;
                        var entry = trace.entries.get(index);
                        if (entry.category() == Category.TEARDOWN) {
                            trace.entries.set(index, new Entry(entry.ordinal(), entry.iteration(), entry.category(),
                                    entry.phase(), entry.kind(), entry.callReturned(), entry.counters(),
                                    entry.operationFailureType(), entry.observationFailureType(), true));
                        }
                    }
                }
                throw failure;
            }
        }
        private void closeValue() throws IOException {
            attempted = true;
            try { value.close(); }
            catch (IOException | RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new IOException("Owner cleanup failed", failure); }
        }
    }
}
