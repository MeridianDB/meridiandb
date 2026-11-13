// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/** Internal caller-thread accounting. Normal storage has no active capture or observer callback. */
public final class LocalIo {
    public enum Role { JOURNAL, SEGMENT, MANIFEST, CONTROL, DIRECTORY, TAIL_EVIDENCE, OBJECT, UNKNOWN }
    enum Counter {
        READ_ATTEMPTS, READ_RETURNS, READ_BYTES, READ_ZERO, READ_EOF, READ_FAILURES,
        WRITE_ATTEMPTS, WRITE_RETURNS, WRITE_BYTES, WRITE_ZERO, WRITE_FAILURES,
        FORCE_ATTEMPTS, FORCE_SUCCESSES, FORCE_FAILURES
    }

    static final int MAX_ACTIVE_CAPTURES = 64;
    private static final Capture[] EMPTY = new Capture[0];
    private static volatile Capture[] activeCaptures = EMPTY;

    private LocalIo() { }

    // Package-private activation keeps the measurement interface out of the engine's public API.
    static Capture start() { return start(Long.MAX_VALUE); }

    static synchronized Capture start(long counterLimit) {
        if (counterLimit < 1) { throw new IllegalArgumentException("Counter limit must be positive"); }
        if (current() != null) { throw new IllegalStateException("Overlapping I/O captures are unsupported"); }
        if (activeCaptures.length == MAX_ACTIVE_CAPTURES) { throw new IllegalStateException("I/O capture capacity exhausted"); }
        var capture = new Capture(Thread.currentThread(), counterLimit);
        var next = new Capture[activeCaptures.length + 1];
        System.arraycopy(activeCaptures, 0, next, 0, activeCaptures.length);
        next[next.length - 1] = capture;
        activeCaptures = next;
        return capture;
    }

    private static Capture current() {
        Capture[] captures = activeCaptures;
        if (captures.length == 0) { return null; }
        Thread caller = Thread.currentThread();
        // A bounded array lookup also avoids allocating on uncaptured threads during another capture.
        for (var capture : captures) { if (capture.owner == caller) { return capture; } }
        return null;
    }

    public static int read(Role role, FileChannel channel, ByteBuffer target, long offset) throws IOException {
        var capture = current();
        if (capture == null) { return channel.read(target, offset); }
        capture.add(role, Counter.READ_ATTEMPTS, 1);
        try {
            int count = channel.read(target, offset);
            capture.add(role, Counter.READ_RETURNS, 1);
            if (count > 0) { capture.add(role, Counter.READ_BYTES, count); }
            else if (count == 0) { capture.add(role, Counter.READ_ZERO, 1); }
            else if (count == -1) { capture.add(role, Counter.READ_EOF, 1); }
            else { capture.invalidResult = true; }
            return count;
        } catch (IOException | RuntimeException | Error failure) {
            capture.add(role, Counter.READ_FAILURES, 1);
            throw failure;
        }
    }

    public static int write(Role role, FileChannel channel, ByteBuffer source, long offset) throws IOException {
        var capture = current();
        if (capture == null) { return channel.write(source, offset); }
        capture.add(role, Counter.WRITE_ATTEMPTS, 1);
        try {
            int count = channel.write(source, offset);
            capture.written(role, count);
            return count;
        } catch (IOException | RuntimeException | Error failure) {
            capture.add(role, Counter.WRITE_FAILURES, 1);
            throw failure;
        }
    }

    /** Relative output is used only for preserved diagnostic tail images. */
    public static int write(Role role, FileChannel channel, ByteBuffer source) throws IOException {
        var capture = current();
        if (capture == null) { return channel.write(source); }
        capture.add(role, Counter.WRITE_ATTEMPTS, 1);
        try {
            int count = channel.write(source);
            capture.written(role, count);
            return count;
        } catch (IOException | RuntimeException | Error failure) {
            capture.add(role, Counter.WRITE_FAILURES, 1);
            throw failure;
        }
    }

    public static void force(Role role, FileChannel channel, boolean metadata) throws IOException {
        var capture = current();
        if (capture == null) { channel.force(metadata); return; }
        capture.add(role, Counter.FORCE_ATTEMPTS, 1);
        try {
            channel.force(metadata);
            capture.add(role, Counter.FORCE_SUCCESSES, 1);
        } catch (IOException | RuntimeException | Error failure) {
            capture.add(role, Counter.FORCE_FAILURES, 1);
            throw failure;
        }
    }

    static final class Capture implements AutoCloseable {
        private final Thread owner;
        private final long limit;
        private final long[][] counts = new long[Role.values().length][Counter.values().length];
        private boolean overflow;
        private boolean invalidResult;
        private boolean closed;

        private Capture(Thread owner, long limit) { this.owner = owner; this.limit = limit; }

        private void add(Role supplied, Counter counter, long value) {
            Role role = supplied == null ? Role.UNKNOWN : supplied;
            if (role == Role.UNKNOWN) { invalidResult = true; }
            long[] row = counts[role.ordinal()];
            int index = counter.ordinal();
            if (value > limit - row[index]) { row[index] = limit; overflow = true; }
            else { row[index] += value; }
        }

        private void written(Role role, int count) {
            add(role, Counter.WRITE_RETURNS, 1);
            if (count > 0) { add(role, Counter.WRITE_BYTES, count); }
            else if (count == 0) { add(role, Counter.WRITE_ZERO, 1); }
            else { invalidResult = true; }
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("I/O capture belongs to another thread"); }
        }

        @Override public void close() {
            requireOwner();
            if (closed) { return; }
            synchronized (LocalIo.class) {
                Capture[] before = activeCaptures;
                int index = -1;
                for (int i = 0; i < before.length; i++) { if (before[i] == this) { index = i; break; } }
                if (index < 0) { throw new IllegalStateException("I/O capture is not current"); }
                var next = before.length == 1 ? EMPTY : new Capture[before.length - 1];
                System.arraycopy(before, 0, next, 0, index);
                System.arraycopy(before, index + 1, next, index, before.length - index - 1);
                activeCaptures = next;
                closed = true;
            }
        }

        Snapshot snapshot() {
            requireOwner();
            if (!closed) { throw new IllegalStateException("Close I/O capture before taking its snapshot"); }
            var rows = new ArrayList<List<Long>>(counts.length);
            for (long[] row : counts) {
                var values = new ArrayList<Long>(row.length);
                for (long count : row) { values.add(count); }
                rows.add(List.copyOf(values));
            }
            return new Snapshot(1, overflow, invalidResult, List.copyOf(rows));
        }
    }

    /** Completed counters only: throwing calls can have unknown additional native effects. */
    record Snapshot(int schemaVersion, boolean overflow, boolean invalidResult, List<List<Long>> counts) {
        Snapshot {
            counts = counts.stream().map(List::copyOf).toList();
        }
        long value(Role role, Counter counter) { return counts.get(role.ordinal()).get(counter.ordinal()); }
    }
    // Read-only package bridge for test-support admission; never creates or changes a capture.
    static void requireNoCurrentCapture() {
        if (current() != null) { throw new IllegalStateException("Caller-thread I/O capture must be inactive"); }
    }
}
