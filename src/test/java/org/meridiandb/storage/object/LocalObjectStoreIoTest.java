// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Fault-boundary evidence for byte I/O; real local ownership is tested separately. */
class LocalObjectStoreIoTest {
    private static final byte[] IMAGE = "abc".getBytes(StandardCharsets.US_ASCII);
    private static final ObjectReference EXPECTED = new ObjectReference(new ObjectId(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), 3);

    @Test
    void shortWritesCompleteExactImageThenForceAndCloseBeforeReturning() throws IOException {
        var file = new MemoryFile(new byte[0]);
        file.chunk = 1;
        assertEquals(EXPECTED, LocalObjectStore.write(file, IMAGE));
        assertArrayEquals(IMAGE, file.data);
        assertEquals(3, file.writes);
        assertEquals(List.of("force", "close"), file.events.subList(file.events.size() - 2, file.events.size()));
        assertEquals(1, file.forces);
        assertEquals(1, file.closes);
    }

    @Test
    void shortReadsCompleteExactImageAndProbeEofBeforeClosing() throws IOException {
        var file = new MemoryFile(IMAGE);
        file.chunk = 1;
        try (var object = LocalObjectStore.read(file, EXPECTED)) {
            assertEquals(1, file.closes);
            assertArrayEquals(IMAGE, object.bytes());
            assertEquals(EXPECTED, object.reference());
        }
        assertEquals(4, file.reads);
        assertEquals(1, file.eofReads);
        assertEquals(0, file.forces);
    }

    @Test
    void intermittentZeroWritesResumeAfterEveryPositiveStep() throws IOException {
        var file = new MemoryFile(new byte[0]) {
            @Override public int write(ByteBuffer source, long offset) throws IOException {
                if (writes % 2 == 0) { writes++; return 0; }
                return super.write(source, offset);
            }
        };
        file.chunk = 1;
        assertEquals(EXPECTED, LocalObjectStore.write(file, IMAGE));
        assertArrayEquals(IMAGE, file.data);
        assertEquals(6, file.writes);
        assertEquals(1, file.forces);
    }

    @Test
    void intermittentZeroReadsResumeAndEofProbeCanMakeTemporaryZeroProgress() throws IOException {
        var file = new MemoryFile(IMAGE) {
            @Override public int read(ByteBuffer target, long offset) throws IOException {
                if (reads % 2 == 0) { reads++; return 0; }
                return super.read(target, offset);
            }
        };
        file.chunk = 1;
        try (var object = LocalObjectStore.read(file, EXPECTED)) {
            assertArrayEquals(IMAGE, object.bytes());
        }
        assertEquals(8, file.reads);
        assertEquals(1, file.eofReads);
    }

    @Test
    void fifteenZeroWritesBetweenPositiveStepsDoNotAccumulateAsOneStall() throws IOException {
        var file = new MemoryFile(new byte[0]) {
            @Override public int write(ByteBuffer source, long offset) throws IOException {
                if (writes % 16 != 15) { writes++; return 0; }
                return super.write(source, offset);
            }
        };
        file.chunk = 1;
        assertEquals(EXPECTED, LocalObjectStore.write(file, IMAGE));
        assertEquals(48, file.writes);
        assertArrayEquals(IMAGE, file.data);
    }

    @Test
    void endlessZeroWritesFailWithinSixteenAttemptsWithoutForce() {
        var file = new MemoryFile(new byte[0]) {
            @Override public int write(ByteBuffer source, long offset) { writes++; return 0; }
        };
        assertThrows(IOException.class, () -> LocalObjectStore.write(file, IMAGE));
        assertEquals(16, file.writes);
        assertEquals(0, file.forces);
        assertEquals(1, file.closes);
        assertArrayEquals(new byte[0], file.data);
    }

    @Test
    void endlessZeroReadsFailWithinSixteenAttempts() {
        var file = new MemoryFile(IMAGE) {
            @Override public int read(ByteBuffer target, long offset) { reads++; return 0; }
        };
        assertThrows(IOException.class, () -> LocalObjectStore.read(file, EXPECTED));
        assertEquals(16, file.reads);
        assertEquals(1, file.closes);
    }

    @Test
    void endlessZeroEofProbeFailsWithinSixteenProbeAttempts() {
        var file = new MemoryFile(IMAGE) {
            @Override public int read(ByteBuffer target, long offset) throws IOException {
                if (offset == IMAGE.length) { eofReads++; reads++; return 0; }
                return super.read(target, offset);
            }
        };
        assertThrows(IOException.class, () -> LocalObjectStore.read(file, EXPECTED));
        assertEquals(16, file.eofReads);
        assertEquals(1, file.closes);
    }

    @Test
    void declaredLengthMismatchFailsBeforeAnyReadOrAllocationBasedOnStoredSize() {
        var file = new MemoryFile(IMAGE) {
            @Override public long size() { return Long.MAX_VALUE; }
        };
        var failure = assertThrows(ObjectIntegrityException.class, () -> LocalObjectStore.read(file, EXPECTED));
        assertEquals(ObjectIntegrityException.Code.LENGTH_MISMATCH, failure.code());
        assertEquals(0, file.reads);
        assertEquals(1, file.closes);
    }

    @Test
    void negativeStoredLengthIsRejectedBeforeRead() {
        var file = new MemoryFile(IMAGE) {
            @Override public long size() { return -1; }
        };
        assertThrows(ObjectIntegrityException.class, () -> LocalObjectStore.read(file, EXPECTED));
        assertEquals(0, file.reads);
        assertEquals(1, file.closes);
    }

    @Test
    void nullExpectedReferenceStillClosesAnAlreadyAcquiredFile() {
        var file = new MemoryFile(IMAGE);
        assertThrows(NullPointerException.class, () -> LocalObjectStore.read(file, null));
        assertEquals(0, file.reads);
        assertEquals(1, file.closes);
    }

    @Test
    void nonemptyNewObjectIsNotWrittenOrForced() {
        var file = new MemoryFile(new byte[] {9});
        assertThrows(IOException.class, () -> LocalObjectStore.write(file, IMAGE));
        assertEquals(0, file.writes);
        assertEquals(0, file.forces);
        assertEquals(1, file.closes);
        assertArrayEquals(new byte[] {9}, file.data);
    }

    @Test
    void nullWriteImageStillClosesAnAlreadyAcquiredFile() {
        var file = new MemoryFile(new byte[0]);
        assertThrows(NullPointerException.class, () -> LocalObjectStore.write(file, null));
        assertEquals(0, file.writes);
        assertEquals(0, file.forces);
        assertEquals(1, file.closes);
    }

    @Test
    void midReadEofDoesNotReturnAPrefix() {
        var file = new MemoryFile(IMAGE) {
            @Override public int read(ByteBuffer target, long offset) throws IOException {
                if (offset > 0) { reads++; return -1; }
                return super.read(target, offset);
            }
        };
        file.chunk = 1;
        assertThrows(EOFException.class, () -> LocalObjectStore.read(file, EXPECTED));
        assertEquals(2, file.reads);
        assertEquals(1, file.closes);
    }

    @Test
    void anExtraByteFailsEvenWhenSizeStillReportsExpectedLength() {
        var file = new MemoryFile(new byte[] {'a', 'b', 'c', 'd'}) {
            @Override public long size() { return IMAGE.length; }
        };
        assertThrows(IOException.class, () -> LocalObjectStore.read(file, EXPECTED));
        assertEquals(1, file.closes);
    }

    @Test
    void changedFinalSizeFailsEvenWhenEofProbeReportsNoExtraByte() {
        var file = new MemoryFile(IMAGE) {
            private int sizes;
            @Override public long size() { return sizes++ == 0 ? IMAGE.length : IMAGE.length + 1; }
        };
        assertThrows(IOException.class, () -> LocalObjectStore.read(file, EXPECTED));
        assertEquals(1, file.eofReads);
        assertEquals(1, file.closes);
    }

    @Test
    void changedFinalWriteSizeFailsBeforeForce() {
        var file = new MemoryFile(new byte[0]) {
            private int sizes;
            @Override public long size() { return sizes++ == 0 ? 0 : IMAGE.length + 1; }
        };
        assertThrows(IOException.class, () -> LocalObjectStore.write(file, IMAGE));
        assertEquals(0, file.forces);
        assertEquals(1, file.closes);
        assertArrayEquals(IMAGE, file.data);
    }

    @Test
    void negativeReadOtherThanEofIsAnIoFailure() {
        var file = new MemoryFile(IMAGE) {
            @Override public int read(ByteBuffer target, long offset) { reads++; return -2; }
        };
        var failure = assertThrows(IOException.class, () -> LocalObjectStore.read(file, EXPECTED));
        assertFalse(failure instanceof EOFException);
        assertEquals(1, file.closes);
    }

    @Test
    void negativeWriteIsAnIoFailure() {
        var file = new MemoryFile(new byte[0]) {
            @Override public int write(ByteBuffer source, long offset) { writes++; return -1; }
        };
        assertThrows(IOException.class, () -> LocalObjectStore.write(file, IMAGE));
        assertEquals(0, file.forces);
        assertEquals(1, file.closes);
    }

    @Test
    void aReportedReadWithoutBufferProgressCannotProduceAVerifiedObject() {
        var file = new MemoryFile(IMAGE) {
            @Override public int read(ByteBuffer target, long offset) { reads++; return 1; }
        };
        assertThrows(IOException.class, () -> LocalObjectStore.read(file, EXPECTED));
        assertEquals(1, file.reads);
        assertEquals(1, file.closes);
    }

    @Test
    void aWriteWhichMovesBytesButReportsZeroIsRejected() {
        var file = new MemoryFile(new byte[0]) {
            @Override public int write(ByteBuffer source, long offset) throws IOException {
                super.write(source, offset);
                return 0;
            }
        };
        file.chunk = 1;
        assertThrows(IOException.class, () -> LocalObjectStore.write(file, IMAGE));
        assertArrayEquals(new byte[] {'a'}, file.data);
        assertEquals(0, file.forces);
        assertEquals(1, file.closes);
    }

    @Test
    void corruptCompleteBytesFailIdentityVerificationAfterReadAndBeforeReturn() {
        var file = new MemoryFile(new byte[] {'a', 'b', 'd'});
        var failure = assertThrows(ObjectIntegrityException.class, () -> LocalObjectStore.read(file, EXPECTED));
        assertEquals(ObjectIntegrityException.Code.ID_MISMATCH, failure.code());
        assertEquals(1, file.eofReads);
        assertEquals(1, file.closes);
    }

    @Test
    void emptyObjectsStillForceAndCloseOnWriteAndProbeEofOnRead() throws IOException {
        var reference = new ObjectReference(new ObjectId(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), 0);
        var output = new MemoryFile(new byte[0]);
        assertEquals(reference, LocalObjectStore.write(output, new byte[0]));
        assertEquals(0, output.writes);
        assertEquals(List.of("force", "close"), output.events);
        var input = new MemoryFile(new byte[0]);
        try (var object = LocalObjectStore.read(input, reference)) {
            assertArrayEquals(new byte[0], object.bytes());
        }
        assertEquals(1, input.eofReads);
        assertEquals(1, input.closes);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void readFailureKeepsExactPrimaryAndCloses(Throwable failure) {
        var file = new MemoryFile(IMAGE);
        file.readFailure = failure;
        assertSame(failure, assertThrows(Throwable.class, () -> LocalObjectStore.read(file, EXPECTED)));
        assertEquals(1, file.closes);
        assertEquals(0, file.forces);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void writeFailureKeepsExactPrimaryAndPartialCandidate(Throwable failure) {
        var file = new MemoryFile(new byte[0]) {
            @Override public int write(ByteBuffer source, long offset) throws IOException {
                super.write(source, offset);
                raise(failure);
                throw new AssertionError("unreachable");
            }
        };
        file.chunk = 1;
        assertSame(failure, assertThrows(Throwable.class, () -> LocalObjectStore.write(file, IMAGE)));
        assertArrayEquals(new byte[] {'a'}, file.data);
        assertEquals(1, file.closes);
        assertEquals(0, file.forces);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void sizeFailureClosesWithoutReadingOrWriting(Throwable failure) {
        var file = new MemoryFile(IMAGE);
        file.sizeFailure = failure;
        assertSame(failure, assertThrows(Throwable.class, () -> LocalObjectStore.read(file, EXPECTED)));
        assertEquals(0, file.reads);
        assertEquals(1, file.closes);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void forceFailureLeavesCompleteCandidateAndCloses(Throwable failure) {
        var file = new MemoryFile(new byte[0]);
        file.forceFailure = failure;
        assertSame(failure, assertThrows(Throwable.class, () -> LocalObjectStore.write(file, IMAGE)));
        assertArrayEquals(IMAGE, file.data);
        assertEquals(List.of("force", "close"), file.events.subList(file.events.size() - 2, file.events.size()));
        assertEquals(1, file.closes);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void readCloseFailurePreventsAnySnapshotFromEscaping(Throwable failure) {
        var file = new MemoryFile(IMAGE);
        file.closeFailure = failure;
        var returned = new AtomicReference<VerifiedObject>();
        assertSame(failure, assertThrows(Throwable.class, () -> returned.set(LocalObjectStore.read(file, EXPECTED))));
        assertNull(returned.get());
        assertEquals(1, file.closes);
        assertEquals(1, file.eofReads);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void writeCloseFailurePreventsSuccessDespiteCompletedForce(Throwable failure) {
        var file = new MemoryFile(new byte[0]);
        file.closeFailure = failure;
        var returned = new AtomicReference<ObjectReference>();
        assertSame(failure, assertThrows(Throwable.class, () -> returned.set(LocalObjectStore.write(file, IMAGE))));
        assertNull(returned.get());
        assertEquals(1, file.forces);
        assertEquals(1, file.closes);
        assertArrayEquals(IMAGE, file.data);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void primaryReadFailureRetainsDistinctCleanupFailureAsSuppressed(Throwable primary) {
        var file = new MemoryFile(IMAGE);
        file.readFailure = primary;
        var cleanup = new IOException("cleanup");
        file.closeFailure = cleanup;
        assertSame(primary, assertThrows(Throwable.class, () -> LocalObjectStore.read(file, EXPECTED)));
        assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
        assertEquals(1, file.closes);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void primaryWriteFailureRetainsErrorDuringCleanup(Throwable primary) {
        var file = new MemoryFile(new byte[0]);
        file.writeFailure = primary;
        var cleanup = new AssertionError("cleanup");
        file.closeFailure = cleanup;
        assertSame(primary, assertThrows(Throwable.class, () -> LocalObjectStore.write(file, IMAGE)));
        assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
        assertEquals(1, file.closes);
        assertEquals(0, file.forces);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void sameReadAndCloseThrowableIsNotSelfSuppressed(Throwable failure) {
        var file = new MemoryFile(IMAGE);
        file.readFailure = failure;
        file.closeFailure = failure;
        assertSame(failure, assertThrows(Throwable.class, () -> LocalObjectStore.read(file, EXPECTED)));
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(1, file.closes);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void sameWriteAndCloseThrowableIsNotSelfSuppressed(Throwable failure) {
        var file = new MemoryFile(new byte[0]);
        file.writeFailure = failure;
        file.closeFailure = failure;
        assertSame(failure, assertThrows(Throwable.class, () -> LocalObjectStore.write(file, IMAGE)));
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(1, file.closes);
    }

    private static Stream<Throwable> failures() {
        return Stream.of(new IOException("injected I/O"), new IllegalStateException("injected runtime"),
                new AssertionError("injected error"));
    }

    private static void raise(Throwable failure) throws IOException {
        if (failure == null) { return; }
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        throw new AssertionError("Unsupported injection", failure);
    }

    private static class MemoryFile implements LocalObjectStore.FileAccess {
        byte[] data;
        int chunk = Integer.MAX_VALUE;
        int reads;
        int eofReads;
        int writes;
        int forces;
        int closes;
        Throwable readFailure;
        Throwable writeFailure;
        Throwable sizeFailure;
        Throwable forceFailure;
        Throwable closeFailure;
        final List<String> events = new ArrayList<>();

        MemoryFile(byte[] data) { this.data = data.clone(); }

        @Override public int read(ByteBuffer target, long offset) throws IOException {
            reads++;
            events.add("read:" + offset);
            raise(readFailure);
            if (offset >= data.length) { eofReads++; return -1; }
            int count = Math.min(Math.min(target.remaining(), chunk), data.length - Math.toIntExact(offset));
            target.put(data, Math.toIntExact(offset), count);
            return count;
        }

        @Override public int write(ByteBuffer source, long offset) throws IOException {
            writes++;
            events.add("write:" + offset);
            raise(writeFailure);
            int count = Math.min(source.remaining(), chunk);
            int start = Math.toIntExact(offset);
            data = Arrays.copyOf(data, Math.max(data.length, start + count));
            source.get(data, start, count);
            return count;
        }

        @Override public long size() throws IOException { raise(sizeFailure); return data.length; }
        @Override public void force() throws IOException { forces++; events.add("force"); raise(forceFailure); }
        @Override public void close() throws IOException { closes++; events.add("close"); raise(closeFailure); }
    }
}
