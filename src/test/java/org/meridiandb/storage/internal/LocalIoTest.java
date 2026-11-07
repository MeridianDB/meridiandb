// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
@SuppressWarnings("try")
class LocalIoTest {
    @Test
    void literalShortReadsDistinguishReturnedBytesZeroAndEof() throws IOException {
        var capture = LocalIo.start();
        var target = ByteBuffer.allocate(6);
        try (capture; var file = new ScriptedChannel().reads(0, 2, 1, -1)) {
            assertEquals(0, LocalIo.read(LocalIo.Role.JOURNAL, file, target, 0));
            assertEquals(2, LocalIo.read(LocalIo.Role.JOURNAL, file, target, 0));
            assertEquals(1, LocalIo.read(LocalIo.Role.JOURNAL, file, target, 2));
            assertEquals(-1, LocalIo.read(LocalIo.Role.JOURNAL, file, target, 3));
            assertEquals(List.of(0L, 0L, 2L, 3L), file.readOffsets);
            assertEquals(3, target.position());
            assertArrayEquals(new byte[] {10, 11, 12, 0, 0, 0}, target.array());
            assertTrue(file.isOpen(), "Accounting does not own the channel");
        }
        var snapshot = capture.snapshot();
        assertHealthy(snapshot);
        assertRow(snapshot, LocalIo.Role.JOURNAL, 4, 4, 3, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertOtherRolesEmpty(snapshot, LocalIo.Role.JOURNAL);
    }

    @Test
    void positionalWritesCountShortCompletionsWithoutChangingOffsetsOrBytes() throws IOException {
        var capture = LocalIo.start();
        var source = ByteBuffer.wrap(new byte[] {21, 22, 23, 24, 25});
        try (capture; var file = new ScriptedChannel().writes(0, 2, 3)) {
            assertEquals(0, LocalIo.write(LocalIo.Role.SEGMENT, file, source, 10));
            assertEquals(2, LocalIo.write(LocalIo.Role.SEGMENT, file, source, 10));
            assertEquals(3, LocalIo.write(LocalIo.Role.SEGMENT, file, source, 12));
            assertEquals(List.of(10L, 10L, 12L), file.writeOffsets);
            assertEquals(0, file.relativeWrites);
            assertEquals(5, source.position());
            assertArrayEquals(new byte[] {21, 22, 23, 24, 25}, file.output.toByteArray());
        }
        var snapshot = capture.snapshot();
        assertHealthy(snapshot);
        assertRow(snapshot, LocalIo.Role.SEGMENT, 0, 0, 0, 0, 0, 0, 3, 3, 5, 1, 0, 0, 0, 0);
        assertOtherRolesEmpty(snapshot, LocalIo.Role.SEGMENT);
    }

    @Test
    void relativeTailWritesPreserveChannelPositionAndCountOnlyReturnedBytes() throws IOException {
        var capture = LocalIo.start();
        var source = ByteBuffer.wrap(new byte[] {31, 32, 33});
        try (capture; var file = new ScriptedChannel().writes(1, 0, 2)) {
            file.position(7);
            assertEquals(1, LocalIo.write(LocalIo.Role.TAIL_EVIDENCE, file, source));
            assertEquals(0, LocalIo.write(LocalIo.Role.TAIL_EVIDENCE, file, source));
            assertEquals(2, LocalIo.write(LocalIo.Role.TAIL_EVIDENCE, file, source));
            assertEquals(10, file.position());
            assertEquals(3, file.relativeWrites);
            assertEquals(List.of(7L, 8L, 8L), file.writeOffsets);
            assertArrayEquals(new byte[] {31, 32, 33}, file.output.toByteArray());
        }
        var snapshot = capture.snapshot();
        assertHealthy(snapshot);
        assertRow(snapshot, LocalIo.Role.TAIL_EVIDENCE, 0, 0, 0, 0, 0, 0, 3, 3, 3, 1, 0, 0, 0, 0);
    }

    @Test
    void forceAttemptsAndSuccessesRemainSeparateAndMetadataIsForwarded() throws IOException {
        var failure = new IOException("force did not return successfully");
        var capture = LocalIo.start();
        try (capture; var file = new ScriptedChannel().forces(Step.done(0), Step.done(0), Step.failed(failure))) {
            LocalIo.force(LocalIo.Role.DIRECTORY, file, false);
            LocalIo.force(LocalIo.Role.DIRECTORY, file, true);
            assertSame(failure, assertThrows(IOException.class, () -> LocalIo.force(LocalIo.Role.DIRECTORY, file, true)));
            assertEquals(List.of(false, true, true), file.forceMetadata);
        }
        var snapshot = capture.snapshot();
        assertHealthy(snapshot);
        assertRow(snapshot, LocalIo.Role.DIRECTORY, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 2, 1);
    }

    @Test
    void everyWrapperRethrowsTheExactCheckedRuntimeAndErrorObject() throws IOException {
        for (Call call : Call.values()) {
            for (Throwable failure : List.of(new IOException("checked"),
                    new IllegalArgumentException("runtime"), new AssertionError("error"))) {
                var capture = LocalIo.start();
                try (capture; var file = new ScriptedChannel()) {
                    file.reads.add(Step.failed(failure));
                    file.writes.add(Step.failed(failure));
                    file.forces.add(Step.failed(failure));
                    assertSame(failure, assertThrows(failure.getClass(), () -> invoke(call, file)));
                }
                var snapshot = capture.snapshot();
                assertHealthy(snapshot);
                switch (call) {
                    case READ -> assertRow(snapshot, LocalIo.Role.JOURNAL, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0);
                    case POSITIONAL_WRITE, RELATIVE_WRITE -> assertRow(snapshot, LocalIo.Role.JOURNAL,
                            0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0);
                    case FORCE -> assertRow(snapshot, LocalIo.Role.JOURNAL, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1);
                }
            }
        }
    }

    @Test
    void earlierReturnedCompletionsSurviveAfterEffectFailuresWithoutGuessingTheirExtraBytes() throws IOException {
        var readFailure = new IOException("read copied bytes before throwing");
        var writeFailure = new IOException("write copied bytes before throwing");
        var capture = LocalIo.start();
        var target = ByteBuffer.allocate(6);
        var source = ByteBuffer.wrap(new byte[] {41, 42, 43, 44, 45, 46});
        try (capture; var file = new ScriptedChannel()) {
            file.reads.add(Step.done(2));
            file.reads.add(Step.failedAfter(2, readFailure));
            file.writes.add(Step.done(2));
            file.writes.add(Step.failedAfter(2, writeFailure));
            assertEquals(2, LocalIo.read(LocalIo.Role.JOURNAL, file, target, 0));
            assertSame(readFailure, assertThrows(IOException.class, () -> LocalIo.read(LocalIo.Role.JOURNAL, file, target, 2)));
            assertEquals(2, LocalIo.write(LocalIo.Role.SEGMENT, file, source, 0));
            assertSame(writeFailure, assertThrows(IOException.class, () -> LocalIo.write(LocalIo.Role.SEGMENT, file, source, 2)));
            assertEquals(4, target.position());
            assertEquals(4, source.position());
            assertArrayEquals(new byte[] {41, 42, 43, 44}, file.output.toByteArray());
        }
        var snapshot = capture.snapshot();
        assertHealthy(snapshot);
        assertRow(snapshot, LocalIo.Role.JOURNAL, 2, 1, 2, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertRow(snapshot, LocalIo.Role.SEGMENT, 0, 0, 0, 0, 0, 0, 2, 1, 2, 0, 1, 0, 0, 0);
    }

    @Test
    void successfulForceRemainsRecordedWhenSubsequentChannelCloseFails() {
        var closeFailure = new IOException("close failed after a returned force");
        var capture = LocalIo.start();
        var file = new ScriptedChannel().forces(Step.done(0));
        file.closeFailure = closeFailure;
        try (capture) {
            assertSame(closeFailure, assertThrows(IOException.class, () -> {
                try (file) { LocalIo.force(LocalIo.Role.CONTROL, file, true); }
            }));
        }
        assertRow(capture.snapshot(), LocalIo.Role.CONTROL, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0);
    }

    @Test
    void originalFailureAndSuppressedCleanupRemainUnchanged() {
        var writeFailure = new IOException("primary write");
        var closeFailure = new IOException("suppressed close");
        var capture = LocalIo.start();
        var file = new ScriptedChannel();
        file.writes.add(Step.failed(writeFailure));
        file.closeFailure = closeFailure;
        try (capture) {
            var caught = assertThrows(IOException.class, () -> {
                try (file) { LocalIo.write(LocalIo.Role.MANIFEST, file, ByteBuffer.wrap(new byte[] {1}), 0); }
            });
            assertSame(writeFailure, caught);
            assertArrayEquals(new Throwable[] {closeFailure}, caught.getSuppressed());
        }
        assertRow(capture.snapshot(), LocalIo.Role.MANIFEST, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0);
    }

    @Test
    void inactivePathForwardsAllCallsAndDoesNotAccumulateForTheNextCapture() throws IOException {
        var readFailure = new IOException("inactive read");
        try (var file = new ScriptedChannel().reads(2).writes(2, 1).forces(Step.done(0))) {
            var target = ByteBuffer.allocate(3);
            var source = ByteBuffer.wrap(new byte[] {51, 52, 53});
            assertEquals(2, LocalIo.read(null, file, target, 1));
            assertArrayEquals(new byte[] {11, 12, 0}, target.array());
            assertEquals(2, LocalIo.write(null, file, source, 4));
            assertEquals(1, LocalIo.write(null, file, source));
            LocalIo.force(null, file, false);
            file.reads.add(Step.failed(readFailure));
            assertSame(readFailure, assertThrows(IOException.class, () -> LocalIo.read(null, file, target, 3)));
            assertEquals(List.of(4L, 0L), file.writeOffsets);
            assertEquals(List.of(false), file.forceMetadata);
            assertArrayEquals(new byte[] {51, 52, 53}, file.output.toByteArray());
            assertTrue(file.isOpen());
        }
        var capture = LocalIo.start();
        capture.close();
        assertHealthy(capture.snapshot());
        assertAllEmpty(capture.snapshot());
    }

    @Test
    void lifecycleRejectsInvalidLimitsOverlapAndLiveSnapshotsAndOldCloseCannotRemoveNewCapture() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> LocalIo.start(0));
        assertThrows(IllegalArgumentException.class, () -> LocalIo.start(-1));
        var first = LocalIo.start();
        try (first) {
            assertThrows(IllegalStateException.class, first::snapshot);
            assertThrows(IllegalStateException.class, LocalIo::start);
        }
        var second = LocalIo.start();
        try (second; var file = new ScriptedChannel().writes(3)) {
            first.close();
            assertThrows(IllegalStateException.class, LocalIo::start);
            assertEquals(3, LocalIo.write(LocalIo.Role.OBJECT, file, ByteBuffer.wrap(new byte[] {1, 2, 3}), 9));
        }
        first.close();
        assertAllEmpty(first.snapshot());
        assertHealthy(second.snapshot());
        assertRow(second.snapshot(), LocalIo.Role.OBJECT, 0, 0, 0, 0, 0, 0, 1, 1, 3, 0, 0, 0, 0, 0);
    }

    @Test
    void wrongThreadCannotCloseOrSnapshotTheOwnersCapture() throws Exception {
        var capture = LocalIo.start();
        try (capture; var file = new ScriptedChannel().reads(1)) {
            var task = new FutureTask<Void>(() -> {
                assertThrows(IllegalStateException.class, capture::close);
                assertThrows(IllegalStateException.class, capture::snapshot);
                return null;
            });
            Thread.ofPlatform().start(task);
            task.get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, LocalIo::start);
            assertEquals(1, LocalIo.read(LocalIo.Role.JOURNAL, file, ByteBuffer.allocate(1), 0));
        }
        assertRow(capture.snapshot(), LocalIo.Role.JOURNAL, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void childDoesNotInheritCaptureAndSimultaneousThreadsHaveIndependentCounts() throws Exception {
        var parent = LocalIo.start();
        LocalIo.Snapshot child;
        try (parent; var file = new ScriptedChannel().reads(1)) {
            var task = new FutureTask<LocalIo.Snapshot>(() -> {
                // A parent capture is active globally, but these bytes belong to no child capture.
                try (var outside = new ScriptedChannel().writes(5)) {
                    assertEquals(5, LocalIo.write(LocalIo.Role.JOURNAL, outside, ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5}), 0));
                }
                var own = LocalIo.start();
                try (own; var inside = new ScriptedChannel().reads(2).forces(Step.done(0))) {
                    assertEquals(2, LocalIo.read(LocalIo.Role.MANIFEST, inside, ByteBuffer.allocate(2), 0));
                    LocalIo.force(LocalIo.Role.MANIFEST, inside, true);
                }
                return own.snapshot();
            });
            Thread.ofPlatform().start(task);
            assertEquals(1, LocalIo.read(LocalIo.Role.JOURNAL, file, ByteBuffer.allocate(1), 0));
            child = task.get(5, TimeUnit.SECONDS);
        }
        assertHealthy(parent.snapshot());
        assertHealthy(child);
        assertRow(parent.snapshot(), LocalIo.Role.JOURNAL, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertOtherRolesEmpty(parent.snapshot(), LocalIo.Role.JOURNAL);
        assertRow(child, LocalIo.Role.MANIFEST, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0);
        assertOtherRolesEmpty(child, LocalIo.Role.MANIFEST);
    }

    @Test
    void sixtyFourConcurrentCapturesBoundAdmissionAndReleasedCapacityCanBeReused() throws Exception {
        assertEquals(64, LocalIo.MAX_ACTIVE_CAPTURES);
        var ready = new CountDownLatch(64);
        var release = new CountDownLatch(1);
        var tasks = new ArrayList<FutureTask<LocalIo.Snapshot>>();
        try {
            for (int i = 0; i < 64; i++) {
                var task = new FutureTask<LocalIo.Snapshot>(() -> {
                    var capture = LocalIo.start();
                    try (capture) {
                        ready.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS), "Parent releases all admitted captures");
                    }
                    return capture.snapshot();
                });
                tasks.add(task);
                Thread.ofVirtual().start(task);
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "All 64 captures enter before testing the 65th");
            assertThrows(IllegalStateException.class, LocalIo::start);
        } finally {
            release.countDown();
            for (var task : tasks) { assertAllEmpty(task.get(5, TimeUnit.SECONDS)); }
        }
        var reused = LocalIo.start();
        reused.close();
        assertHealthy(reused.snapshot());
    }

    @Test
    void overflowSaturatesOnlyTelemetryAndPreservesAllDelegateReturnValues() throws IOException {
        var exact = LocalIo.start(3);
        try (exact; var file = new ScriptedChannel().reads(3)) {
            assertEquals(3, LocalIo.read(LocalIo.Role.JOURNAL, file, ByteBuffer.allocate(3), 0));
        }
        assertHealthy(exact.snapshot());
        assertRow(exact.snapshot(), LocalIo.Role.JOURNAL, 1, 1, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        var capture = LocalIo.start(3);
        try (capture; var file = new ScriptedChannel().reads(2, 2, -1, 0)) {
            var target = ByteBuffer.allocate(6);
            assertEquals(2, LocalIo.read(LocalIo.Role.JOURNAL, file, target, 0));
            assertEquals(2, LocalIo.read(LocalIo.Role.JOURNAL, file, target, 2));
            assertEquals(-1, LocalIo.read(LocalIo.Role.JOURNAL, file, target, 4));
            assertEquals(0, LocalIo.read(LocalIo.Role.JOURNAL, file, target, 4));
            assertArrayEquals(new byte[] {10, 11, 12, 13, 0, 0}, target.array());
        }
        var snapshot = capture.snapshot();
        assertTrue(snapshot.overflow());
        assertFalse(snapshot.invalidResult());
        assertRow(snapshot, LocalIo.Role.JOURNAL, 3, 3, 3, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void allEightRolesAreIndependentAndUnknownRoleMakesCoverageInvalid() throws IOException {
        assertArrayEquals(new String[] {"JOURNAL", "SEGMENT", "MANIFEST", "CONTROL", "DIRECTORY", "TAIL_EVIDENCE", "OBJECT", "UNKNOWN"},
                java.util.Arrays.stream(LocalIo.Role.values()).map(Enum::name).toArray(String[]::new));
        var capture = LocalIo.start();
        try (capture) {
            for (var role : LocalIo.Role.values()) {
                try (var file = new ScriptedChannel().reads(1).writes(1).forces(Step.done(0))) {
                    assertEquals(1, LocalIo.read(role, file, ByteBuffer.allocate(1), 0));
                    assertEquals(1, LocalIo.write(role, file, ByteBuffer.wrap(new byte[] {1}), 0));
                    LocalIo.force(role, file, true);
                }
            }
        }
        var snapshot = capture.snapshot();
        assertEquals(1, snapshot.schemaVersion());
        assertTrue(snapshot.invalidResult());
        assertFalse(snapshot.overflow());
        for (var role : LocalIo.Role.values()) {
            assertRow(snapshot, role, 1, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 0);
        }
    }

    @Test
    void nullRoleAndInvalidNativeResultsAreFlaggedWithoutChangingReturnedValues() throws IOException {
        var unknown = LocalIo.start();
        try (unknown; var file = new ScriptedChannel().reads(1)) {
            assertEquals(1, LocalIo.read(null, file, ByteBuffer.allocate(1), 0));
        }
        assertTrue(unknown.snapshot().invalidResult());
        assertFalse(unknown.snapshot().overflow());
        assertRow(unknown.snapshot(), LocalIo.Role.UNKNOWN, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        for (var call : List.of(Call.READ, Call.POSITIONAL_WRITE, Call.RELATIVE_WRITE)) {
            var capture = LocalIo.start();
            try (capture; var file = new ScriptedChannel().reads(-2).writes(-9)) {
                switch (call) {
                    case READ -> assertEquals(-2, LocalIo.read(LocalIo.Role.JOURNAL, file, ByteBuffer.allocate(1), 0));
                    case POSITIONAL_WRITE -> assertEquals(-9, LocalIo.write(LocalIo.Role.JOURNAL, file, ByteBuffer.wrap(new byte[] {1}), 0));
                    case RELATIVE_WRITE -> assertEquals(-9, LocalIo.write(LocalIo.Role.JOURNAL, file, ByteBuffer.wrap(new byte[] {1})));
                    case FORCE -> throw new AssertionError("Not a returned-byte operation");
                }
            }
            var snapshot = capture.snapshot();
            assertTrue(snapshot.invalidResult(), "Each invalid result independently invalidates telemetry");
            assertFalse(snapshot.overflow());
            if (call == Call.READ) { assertRow(snapshot, LocalIo.Role.JOURNAL, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
            else { assertRow(snapshot, LocalIo.Role.JOURNAL, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0); }
        }
    }

    @Test
    void closedSnapshotsAreDeeplyImmutableAndLaterCapturesCannotChangeThem() throws IOException {
        var first = LocalIo.start();
        try (first; var file = new ScriptedChannel().reads(2)) {
            assertEquals(2, LocalIo.read(LocalIo.Role.JOURNAL, file, ByteBuffer.allocate(2), 0));
        }
        var snapshot = first.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.counts().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.counts().get(0).set(0, 99L));
        var second = LocalIo.start();
        try (second; var file = new ScriptedChannel().writes(4)) {
            assertEquals(4, LocalIo.write(LocalIo.Role.JOURNAL, file, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 0));
        }
        assertEquals(snapshot, first.snapshot());
        assertRow(snapshot, LocalIo.Role.JOURNAL, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertRow(second.snapshot(), LocalIo.Role.JOURNAL, 0, 0, 0, 0, 0, 0, 1, 1, 4, 0, 0, 0, 0, 0);
    }

    private static void assertHealthy(LocalIo.Snapshot snapshot) {
        assertEquals(1, snapshot.schemaVersion());
        assertFalse(snapshot.overflow());
        assertFalse(snapshot.invalidResult());
    }

    // The vector order is the independently named v1 counter contract, not enum iteration.
    private static void assertRow(LocalIo.Snapshot snapshot, LocalIo.Role role,
                                  long readAttempts, long readReturns, long readBytes, long readZero, long readEof, long readFailures,
                                  long writeAttempts, long writeReturns, long writeBytes, long writeZero, long writeFailures,
                                  long forceAttempts, long forceSuccesses, long forceFailures) {
        assertEquals(readAttempts, snapshot.value(role, LocalIo.Counter.READ_ATTEMPTS));
        assertEquals(readReturns, snapshot.value(role, LocalIo.Counter.READ_RETURNS));
        assertEquals(readBytes, snapshot.value(role, LocalIo.Counter.READ_BYTES));
        assertEquals(readZero, snapshot.value(role, LocalIo.Counter.READ_ZERO));
        assertEquals(readEof, snapshot.value(role, LocalIo.Counter.READ_EOF));
        assertEquals(readFailures, snapshot.value(role, LocalIo.Counter.READ_FAILURES));
        assertEquals(writeAttempts, snapshot.value(role, LocalIo.Counter.WRITE_ATTEMPTS));
        assertEquals(writeReturns, snapshot.value(role, LocalIo.Counter.WRITE_RETURNS));
        assertEquals(writeBytes, snapshot.value(role, LocalIo.Counter.WRITE_BYTES));
        assertEquals(writeZero, snapshot.value(role, LocalIo.Counter.WRITE_ZERO));
        assertEquals(writeFailures, snapshot.value(role, LocalIo.Counter.WRITE_FAILURES));
        assertEquals(forceAttempts, snapshot.value(role, LocalIo.Counter.FORCE_ATTEMPTS));
        assertEquals(forceSuccesses, snapshot.value(role, LocalIo.Counter.FORCE_SUCCESSES));
        assertEquals(forceFailures, snapshot.value(role, LocalIo.Counter.FORCE_FAILURES));
    }

    private static void assertAllEmpty(LocalIo.Snapshot snapshot) {
        for (var role : LocalIo.Role.values()) {
            assertRow(snapshot, role, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private static void assertOtherRolesEmpty(LocalIo.Snapshot snapshot, LocalIo.Role except) {
        for (var role : LocalIo.Role.values()) {
            if (role != except) { assertRow(snapshot, role, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
        }
    }

    private enum Call { READ, POSITIONAL_WRITE, RELATIVE_WRITE, FORCE }

    private static void invoke(Call call, ScriptedChannel file) throws IOException {
        switch (call) {
            case READ -> LocalIo.read(LocalIo.Role.JOURNAL, file, ByteBuffer.allocate(1), 0);
            case POSITIONAL_WRITE -> LocalIo.write(LocalIo.Role.JOURNAL, file, ByteBuffer.wrap(new byte[] {1}), 0);
            case RELATIVE_WRITE -> LocalIo.write(LocalIo.Role.JOURNAL, file, ByteBuffer.wrap(new byte[] {1}));
            case FORCE -> LocalIo.force(LocalIo.Role.JOURNAL, file, true);
        }
    }

    private record Step(int result, int bytesBeforeFailure, Throwable failure) {
        static Step done(int result) { return new Step(result, 0, null); }
        static Step failed(Throwable failure) { return failedAfter(0, failure); }
        static Step failedAfter(int bytes, Throwable failure) { return new Step(0, bytes, failure); }
    }

    /** Scripted delegate effects are independent of the observer and may precede a thrown failure. */
    private static final class ScriptedChannel extends FileChannel {
        final Deque<Step> reads = new ArrayDeque<>();
        final Deque<Step> writes = new ArrayDeque<>();
        final Deque<Step> forces = new ArrayDeque<>();
        final List<Long> readOffsets = new ArrayList<>();
        final List<Long> writeOffsets = new ArrayList<>();
        final List<Boolean> forceMetadata = new ArrayList<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final byte[] image = {10, 11, 12, 13, 14, 15, 16, 17};
        private long position;
        int relativeWrites;
        IOException closeFailure;

        ScriptedChannel reads(int... results) { for (int result : results) { reads.add(Step.done(result)); } return this; }
        ScriptedChannel writes(int... results) { for (int result : results) { writes.add(Step.done(result)); } return this; }
        ScriptedChannel forces(Step... steps) { forces.addAll(List.of(steps)); return this; }

        @Override public int read(ByteBuffer target, long offset) throws IOException {
            readOffsets.add(offset);
            var step = reads.removeFirst();
            int effects = step.failure() == null ? Math.max(0, step.result()) : step.bytesBeforeFailure();
            for (int i = 0; i < effects; i++) { target.put(image[Math.toIntExact(offset) + i]); }
            rethrow(step.failure());
            return step.result();
        }

        @Override public int write(ByteBuffer source, long offset) throws IOException {
            writeOffsets.add(offset);
            var step = writes.removeFirst();
            int effects = step.failure() == null ? Math.max(0, step.result()) : step.bytesBeforeFailure();
            for (int i = 0; i < effects; i++) { output.write(source.get()); }
            rethrow(step.failure());
            return step.result();
        }

        @Override public int write(ByteBuffer source) throws IOException {
            relativeWrites++;
            int result = write(source, position);
            if (result > 0) { position += result; }
            return result;
        }

        @Override public void force(boolean metadata) throws IOException {
            forceMetadata.add(metadata);
            rethrow(forces.removeFirst().failure());
        }

        private static void rethrow(Throwable failure) throws IOException {
            if (failure instanceof IOException checked) { throw checked; }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
            if (failure != null) { throw new AssertionError("Unexpected fixture throwable", failure); }
        }

        @Override protected void implCloseChannel() throws IOException { if (closeFailure != null) { throw closeFailure; } }
        @Override public long position() { return position; }
        @Override public FileChannel position(long next) { position = next; return this; }
        @Override public long size() { throw unused(); }
        @Override public FileChannel truncate(long size) { throw unused(); }
        @Override public int read(ByteBuffer target) { throw unused(); }
        @Override public long read(ByteBuffer[] targets, int offset, int length) { throw unused(); }
        @Override public long write(ByteBuffer[] sources, int offset, int length) { throw unused(); }
        @Override public long transferTo(long offset, long count, WritableByteChannel target) { throw unused(); }
        @Override public long transferFrom(ReadableByteChannel source, long offset, long count) { throw unused(); }
        @Override public MappedByteBuffer map(MapMode mode, long offset, long size) { throw unused(); }
        @Override public FileLock lock(long offset, long size, boolean shared) { throw unused(); }
        @Override public FileLock tryLock(long offset, long size, boolean shared) { throw unused(); }
        private static AssertionError unused() { return new AssertionError("Unexpected fixture method"); }
    }
}
