// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class LocalSegmentFileTest {
    private static final DatabaseId DATABASE = new DatabaseId("local-segment");
    private static final EntityId ENTITY = new EntityId("salary");
    private static final ImmutableSegment SEGMENT = new ImmutableSegment(DATABASE, List.of(
            record(41, "original", 100), record(42, "correction", 120)));
    private static final byte[] IMAGE = SegmentCodec.encode(SEGMENT);
    private static final SegmentId ID = contentId(IMAGE);

    @TempDir
    Path directory;

    @Test
    void realFileRoundTripPreservesExactLineageHistoryOperationsAndWholeFileIdentity() throws IOException {
        var identity = "A\u0000é" + (char) 0xd800 + "e" + (char) 0x0301 + (char) 0xdc00;
        var entity = new EntityId(identity);
        var first = new JournalRecord(9, new Transaction(new RequestId(identity), List.of(
                new WriteOperation.Put(entity, new ValidTimeInterval(null, null), Long.MIN_VALUE),
                new WriteOperation.Retract(entity, new ValidTimeInterval(Long.MIN_VALUE, Long.MAX_VALUE)),
                new WriteOperation.Put(entity, new ValidTimeInterval(Long.MAX_VALUE, null), Long.MAX_VALUE))));
        var segment = new ImmutableSegment(new DatabaseId(identity), List.of(
                first, new JournalRecord(10, new Transaction(new RequestId("no-op"), List.of(
                        new WriteOperation.Retract(new EntityId("absent"), new ValidTimeInterval(null, null)))))));
        var path = directory.resolve("history.segment");
        var written = LocalSegmentFile.writeNew(path, segment);
        byte[] raw = Files.readAllBytes(path);
        assertArrayEquals(SegmentCodec.encode(segment), raw);
        assertEquals(contentId(raw), written);
        assertNotEquals(HexFormat.of().formatHex(Arrays.copyOfRange(raw, raw.length - 32, raw.length)),
                written.value(), "The footer is not the whole-file identifier");
        try (var reader = LocalSegmentFile.open(path, written)) {
            assertEquals(written, reader.id());
            assertEquals(segment, reader.segment());
            assertEquals(first.transaction().operations(), reader.segment().records().getFirst().transaction().operations());
            assertEquals(9, reader.segment().firstSequence());
            assertEquals(10, reader.segment().lastSequence());
        }
        assertArrayEquals(raw, Files.readAllBytes(path));
    }

    @Test
    void createNewPreservesExistingCompleteEmptyAndFailedCandidateFiles() throws IOException {
        for (byte[] original : List.of(IMAGE, new byte[0], new byte[] {4, 2, 9})) {
            var path = directory.resolve("candidate-" + original.length);
            Files.write(path, original);
            assertThrows(IOException.class, () -> LocalSegmentFile.writeNew(path, SEGMENT));
            assertArrayEquals(original, Files.readAllBytes(path));
        }
    }

    @Test
    void encodeFailureOccursBeforeCreatingAnyFileOrParentDirectory() {
        var tooLarge = new ImmutableSegment(new DatabaseId("x".repeat(32_769)), SEGMENT.records());
        var path = directory.resolve("invalid.segment");
        var failure = assertThrows(SegmentFormatException.class, () -> LocalSegmentFile.writeNew(path, tooLarge));
        assertEquals(SegmentFormatException.Code.LIMIT_EXCEEDED, failure.code());
        assertFalse(Files.exists(path));
        var missingParent = directory.resolve("missing").resolve("invalid.segment");
        assertThrows(SegmentFormatException.class, () -> LocalSegmentFile.writeNew(missingParent, tooLarge));
        assertFalse(Files.exists(missingParent.getParent()));
    }

    @Test
    void missingFilesAndDirectoriesAreRejectedWithoutCreatingOrModifyingThem() throws IOException {
        var missing = directory.resolve("missing.segment");
        assertThrows(IOException.class, () -> LocalSegmentFile.open(missing, ID));
        assertFalse(Files.exists(missing));
        assertThrows(IOException.class, () -> LocalSegmentFile.open(directory, ID));
        assertThrows(IOException.class, () -> LocalSegmentFile.writeNew(directory, SEGMENT));
        assertTrue(Files.isDirectory(directory));
        var missingParent = directory.resolve("missing-parent").resolve("segment");
        assertThrows(IOException.class, () -> LocalSegmentFile.writeNew(missingParent, SEGMENT));
        assertFalse(Files.exists(missingParent.getParent()));
    }

    @Test
    void symbolicLinksAreRejectedForReadAndCreateWithoutChangingTheirTarget() throws IOException {
        var target = directory.resolve("target.segment");
        Files.write(target, IMAGE);
        var link = directory.resolve("alias.segment");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | FileSystemException unavailable) {
            assumeTrue(false, "Symbolic links unavailable: " + unavailable);
        }
        assertThrows(IOException.class, () -> LocalSegmentFile.open(link, ID));
        assertThrows(IOException.class, () -> LocalSegmentFile.writeNew(link, SEGMENT));
        assertTrue(Files.isSymbolicLink(link));
        assertArrayEquals(IMAGE, Files.readAllBytes(target));
    }

    @Test
    void invalidObjectsAreRejectedAndRetainedWithoutRepair() throws IOException {
        var corrupt = IMAGE.clone();
        corrupt[corrupt.length - 1] ^= 1;
        var suffixed = Arrays.copyOf(IMAGE, IMAGE.length + 1);
        var candidates = List.of(new byte[0], Arrays.copyOf(IMAGE, 7),
                Arrays.copyOf(IMAGE, IMAGE.length - 1), corrupt, suffixed);
        int index = 0;
        for (var original : candidates) {
            var path = directory.resolve("invalid-" + index++);
            Files.write(path, original);
            assertThrows(SegmentFormatException.class, () -> LocalSegmentFile.open(path, ID));
            assertArrayEquals(original, Files.readAllBytes(path));
        }
    }

    @Test
    void independentlyExpectedIdentifierIsRequiredAndMismatchRetainsFile() throws IOException {
        var path = directory.resolve("segment");
        Files.write(path, IMAGE);
        var mismatch = new SegmentId("0".repeat(64));
        var failure = assertThrows(SegmentFormatException.class, () -> LocalSegmentFile.open(path, mismatch));
        assertEquals(SegmentFormatException.Code.IDENTIFIER_MISMATCH, failure.code());
        assertArrayEquals(IMAGE, Files.readAllBytes(path));
        try (var reader = LocalSegmentFile.open(path, ID)) {
            assertEquals(SEGMENT, reader.segment());
        }
    }

    @Test
    void oversizedSparseFileIsRejectedBeforeReadingItsContents() throws IOException {
        var path = directory.resolve("oversized.segment");
        try (var file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.wrap(new byte[] {1}), SegmentCodec.MAX_SEGMENT_BYTES);
        }
        var failure = assertThrows(SegmentFormatException.class, () -> LocalSegmentFile.open(path, ID));
        assertEquals(SegmentFormatException.Code.LIMIT_EXCEEDED, failure.code());
        assertEquals(SegmentCodec.MAX_SEGMENT_BYTES + 1L, Files.size(path));
    }

    @Test
    void openReaderIsAHeapSnapshotAndLaterDiskMutationFailsNewValidation() throws IOException {
        var path = directory.resolve("segment");
        LocalSegmentFile.writeNew(path, SEGMENT);
        try (var reader = LocalSegmentFile.open(path, ID)) {
            var changed = IMAGE.clone();
            changed[changed.length - 1] ^= 1;
            Files.write(path, changed, StandardOpenOption.TRUNCATE_EXISTING);
            assertEquals(ID, reader.id());
            assertEquals(SEGMENT, reader.segment());
            assertThrows(SegmentFormatException.class, () -> LocalSegmentFile.open(path, ID));
        }
    }

    @Test
    void channelIsClosedBeforeReturningReaderAndReadersHaveIndependentLifetimes() throws IOException {
        var path = directory.resolve("segment");
        LocalSegmentFile.writeNew(path, SEGMENT);
        var first = LocalSegmentFile.open(path, ID);
        try (var second = LocalSegmentFile.open(path, ID)) {
            // Heap readers retain no file descriptor, mapped region or file lock.
            Files.delete(path);
            first.close();
            assertEquals(SEGMENT, second.segment());
            assertEquals(ID, second.id());
            assertThrows(IOException.class, () -> LocalSegmentFile.open(path, ID));
        } finally {
            first.close();
        }
    }

    @Test
    void closeIsIdempotentRejectsAccessAndPreservesPreviouslyReturnedImmutableValues() throws IOException {
        var path = directory.resolve("segment");
        LocalSegmentFile.writeNew(path, SEGMENT);
        var reader = LocalSegmentFile.open(path, ID);
        var id = reader.id();
        var segment = reader.segment();
        var records = segment.records();
        reader.close();
        reader.close();
        assertThrows(IllegalStateException.class, reader::id);
        assertThrows(IllegalStateException.class, reader::segment);
        assertEquals(ID, id);
        assertEquals(SEGMENT, segment);
        assertEquals(SEGMENT.records(), records);
        assertThrows(UnsupportedOperationException.class, records::clear);
    }

    @Test
    void publicNullArgumentsFailWithoutCreatingAPath() {
        var path = directory.resolve("segment");
        assertThrows(NullPointerException.class, () -> LocalSegmentFile.writeNew(null, SEGMENT));
        assertThrows(NullPointerException.class, () -> LocalSegmentFile.writeNew(path, null));
        assertThrows(NullPointerException.class, () -> LocalSegmentFile.open(null, ID));
        assertThrows(NullPointerException.class, () -> LocalSegmentFile.open(path, null));
        assertFalse(Files.exists(path));
    }

    @Test
    void shortWritesProduceExactBytesThenForceAndCloseBeforeReturningIdentifier() throws IOException {
        var file = new MemoryFile(new byte[0]);
        file.maxWrite = 3;
        file.zeroWrites = 2;
        assertEquals(ID, LocalSegmentFile.write(file, IMAGE));
        assertArrayEquals(IMAGE, file.bytes());
        assertEquals(1, file.forces);
        assertEquals(1, file.closes);
        assertTrue(file.closed);
        assertEquals(0, file.reads);
        assertTrue(file.events.indexOf("force") > file.events.lastIndexOf("write"));
        assertEquals("close", file.events.getLast());
        assertEquals(IMAGE.length, file.writtenOffsets.getLast() + file.writtenCounts.getLast());
    }

    @Test
    void successfulWriteOrdersSizeValidationBeforeForceAndClose() throws IOException {
        var file = new MemoryFile(new byte[0]);
        assertEquals(ID, LocalSegmentFile.write(file, IMAGE));
        assertEquals(List.of("size", "write", "size", "force", "close"), file.events);
        assertArrayEquals(IMAGE, file.bytes());
    }

    @Test
    void shortReadsAndTransientZeroReadsIncludeASeparateEofProbeAndClose() throws IOException {
        var file = new MemoryFile(IMAGE);
        file.maxRead = 3;
        file.zeroReads = 2;
        file.zeroEofReads = 2;
        try (var reader = LocalSegmentFile.read(file, ID)) {
            assertEquals(ID, reader.id());
            assertEquals(SEGMENT, reader.segment());
            assertTrue(file.closed, "No channel lifetime is transferred to the heap reader");
            assertEquals(1, file.closes);
            assertEquals(0, file.writes);
            assertEquals(0, file.forces);
            assertEquals(new ReadCall(IMAGE.length, 1), file.readCalls.getLast());
        }
        assertArrayEquals(IMAGE, file.bytes());
        assertEquals(1, file.closes, "Closing a heap reader performs no further file I/O");
    }

    @Test
    void successfulReadClosesAfterSeparateEofAndStableSizeChecks() throws IOException {
        var file = new MemoryFile(IMAGE);
        try (var reader = LocalSegmentFile.read(file, ID)) {
            assertEquals(List.of("size", "read", "read", "size", "close"), file.events);
            assertEquals(List.of(new ReadCall(0, IMAGE.length), new ReadCall(IMAGE.length, 1)), file.readCalls);
            assertEquals(SEGMENT, reader.segment());
        }
    }

    @Test
    void sustainedZeroWriteProgressFailsBoundedlyClosesAndNeverForces() {
        var file = new MemoryFile(new byte[0]);
        file.zeroWrites = 1000;
        assertThrows(IOException.class, () -> LocalSegmentFile.write(file, IMAGE));
        assertTrue(file.zeroWrites > 0, "Stop before exhausting arbitrary zero progress");
        assertEquals(0, file.bytes().length);
        assertClosedWithoutForce(file);
    }

    @Test
    void sustainedZeroBodyReadProgressFailsBoundedlyAndClosesWithoutReader() {
        var file = new MemoryFile(IMAGE);
        file.zeroReads = 1000;
        assertThrows(IOException.class, () -> LocalSegmentFile.read(file, ID));
        assertTrue(file.zeroReads > 0);
        assertClosedWithoutForce(file);
        assertArrayEquals(IMAGE, file.bytes());
    }

    @Test
    void sustainedZeroEofProbeProgressFailsBoundedlyAndClosesWithoutReader() {
        var file = new MemoryFile(IMAGE);
        file.zeroEofReads = 1000;
        assertThrows(IOException.class, () -> LocalSegmentFile.read(file, ID));
        assertTrue(file.zeroEofReads > 0);
        assertClosedWithoutForce(file);
        assertEquals(new ReadCall(IMAGE.length, 1), file.readCalls.getLast());
    }

    @Test
    void partialWriteFailureLeavesCandidatePrefixAndClosesWithoutForce() {
        var file = new MemoryFile(new byte[0]);
        file.maxWrite = 3;
        file.failWriteAt = 7;
        var failure = assertThrows(IOException.class, () -> LocalSegmentFile.write(file, IMAGE));
        assertSame(file.writeFailure, failure);
        assertArrayEquals(Arrays.copyOf(IMAGE, 7), file.bytes());
        assertClosedWithoutForce(file);
    }

    @Test
    void partialReadFailureClosesAndDoesNotMutateCandidate() {
        var file = new MemoryFile(IMAGE);
        file.maxRead = 3;
        file.failReadAt = 7;
        var failure = assertThrows(IOException.class, () -> LocalSegmentFile.read(file, ID));
        assertSame(file.readFailure, failure);
        assertArrayEquals(IMAGE, file.bytes());
        assertClosedWithoutForce(file);
    }

    @Test
    void earlyEofRejectsAFileThatShrankInsteadOfReturningAPrefixReader() {
        var file = new MemoryFile(IMAGE);
        file.sizeReports = new long[] {IMAGE.length + 1L};
        assertThrows(EOFException.class, () -> LocalSegmentFile.read(file, ID));
        assertClosedWithoutForce(file);
    }

    @Test
    void eofProbeRejectsAnAppendedSuffixEvenWhenInitialSizeStillLooksValid() {
        var file = new MemoryFile(Arrays.copyOf(IMAGE, IMAGE.length + 1));
        file.sizeReports = new long[] {IMAGE.length};
        assertThrows(IOException.class, () -> LocalSegmentFile.read(file, ID));
        assertClosedWithoutForce(file);
        assertEquals(new ReadCall(IMAGE.length, 1), file.readCalls.getLast());
    }

    @Test
    void secondSizeCheckRejectsChangesEvenAfterEofProbe() {
        var file = new MemoryFile(IMAGE);
        file.sizeReports = new long[] {IMAGE.length, IMAGE.length + 1L};
        assertThrows(IOException.class, () -> LocalSegmentFile.read(file, ID));
        assertClosedWithoutForce(file);
    }

    @Test
    void negativeAndOversizedInitialReadSizesCloseWithoutReadingOrAllocatingBody() {
        var negative = new MemoryFile(IMAGE);
        negative.sizeReports = new long[] {-1};
        assertThrows(IOException.class, () -> LocalSegmentFile.read(negative, ID));
        assertEquals(0, negative.reads);
        assertClosedWithoutForce(negative);
        var oversized = new MemoryFile(IMAGE);
        oversized.sizeReports = new long[] {SegmentCodec.MAX_SEGMENT_BYTES + 1L};
        var failure = assertThrows(SegmentFormatException.class, () -> LocalSegmentFile.read(oversized, ID));
        assertEquals(SegmentFormatException.Code.LIMIT_EXCEEDED, failure.code());
        assertEquals(0, oversized.reads);
        assertClosedWithoutForce(oversized);
    }

    @Test
    void nonemptyWriteTargetClosesWithoutOverwritingOrForcing() {
        var original = new byte[] {1, 4, 9};
        var file = new MemoryFile(original);
        assertThrows(IOException.class, () -> LocalSegmentFile.write(file, IMAGE));
        assertArrayEquals(original, file.bytes());
        assertEquals(0, file.writes);
        assertClosedWithoutForce(file);
    }

    @Test
    void writeSizeChangeRejectsCompleteCandidateBeforeForce() {
        var file = new MemoryFile(new byte[0]);
        file.sizeReports = new long[] {0, IMAGE.length + 1L};
        assertThrows(IOException.class, () -> LocalSegmentFile.write(file, IMAGE));
        assertArrayEquals(IMAGE, file.bytes());
        assertClosedWithoutForce(file);
    }

    @Test
    void negativeWriteResultFailsAndClosesWithoutForce() {
        var file = new MemoryFile(new byte[0]);
        file.negativeWrite = true;
        assertThrows(IOException.class, () -> LocalSegmentFile.write(file, IMAGE));
        assertEquals(0, file.bytes().length);
        assertClosedWithoutForce(file);
    }

    @Test
    void forceFailureLeavesCompleteCandidateClosesAndReturnsNoIdentifier() {
        var file = new MemoryFile(new byte[0]);
        file.failForce = true;
        var failure = assertThrows(IOException.class, () -> LocalSegmentFile.write(file, IMAGE));
        assertSame(file.forceFailure, failure);
        assertArrayEquals(IMAGE, file.bytes());
        assertEquals(1, file.forces);
        assertEquals(1, file.closes);
        assertTrue(file.closed);
    }

    @Test
    void closeFailureAfterWritePreventsSuccessfulCreationResult() {
        var file = new MemoryFile(new byte[0]);
        file.failClose = true;
        var failure = assertThrows(IOException.class, () -> LocalSegmentFile.write(file, IMAGE));
        assertSame(file.closeFailure, failure);
        assertArrayEquals(IMAGE, file.bytes());
        assertEquals(1, file.forces);
        assertEquals(1, file.closes);
    }

    @Test
    void closeFailureAfterReadPreventsAnyReaderFromEscaping() {
        var file = new MemoryFile(IMAGE);
        file.failClose = true;
        var failure = assertThrows(IOException.class, () -> LocalSegmentFile.read(file, ID));
        assertSame(file.closeFailure, failure);
        assertEquals(1, file.closes);
        assertEquals(0, file.forces);
        assertArrayEquals(IMAGE, file.bytes());
    }

    @Test
    void failedWritePreservesPrimaryExceptionAndSuppressesCloseFailure() {
        var file = new MemoryFile(new byte[0]);
        file.failWriteAt = 0;
        file.failClose = true;
        var failure = assertThrows(IOException.class, () -> LocalSegmentFile.write(file, IMAGE));
        assertSame(file.writeFailure, failure);
        assertArrayEquals(new Throwable[] {file.closeFailure}, failure.getSuppressed());
        assertEquals(1, file.closes);
        assertEquals(0, file.forces);
    }

    @Test
    void failedReadPreservesPrimaryExceptionAndSuppressesCloseFailure() {
        var file = new MemoryFile(IMAGE);
        file.failReadAt = 0;
        file.failClose = true;
        var failure = assertThrows(IOException.class, () -> LocalSegmentFile.read(file, ID));
        assertSame(file.readFailure, failure);
        assertArrayEquals(new Throwable[] {file.closeFailure}, failure.getSuppressed());
        assertEquals(1, file.closes);
    }

    @Test
    void formatFailurePreservesItsCodeAndSuppressesCloseFailure() {
        var file = new MemoryFile(IMAGE);
        file.failClose = true;
        var failure = assertThrows(SegmentFormatException.class,
                () -> LocalSegmentFile.read(file, new SegmentId("0".repeat(64))));
        assertEquals(SegmentFormatException.Code.IDENTIFIER_MISMATCH, failure.code());
        assertArrayEquals(new Throwable[] {file.closeFailure}, failure.getSuppressed());
        assertEquals(1, file.closes);
        assertEquals(0, file.writes);
    }

    @Test
    void initialSizeIoFailureClosesBothReadAndWriteInputs() {
        var readFile = new MemoryFile(IMAGE);
        readFile.failSize = true;
        assertSame(readFile.sizeFailure,
                assertThrows(IOException.class, () -> LocalSegmentFile.read(readFile, ID)));
        assertClosedWithoutForce(readFile);
        var writeFile = new MemoryFile(new byte[0]);
        writeFile.failSize = true;
        assertSame(writeFile.sizeFailure,
                assertThrows(IOException.class, () -> LocalSegmentFile.write(writeFile, IMAGE)));
        assertClosedWithoutForce(writeFile);
        assertEquals(0, readFile.reads);
        assertEquals(0, writeFile.writes);
    }

    @Test
    void nullExpectedIdentifierStillClosesAnAlreadyOwnedSeamInput() {
        var file = new MemoryFile(IMAGE);
        assertThrows(NullPointerException.class, () -> LocalSegmentFile.read(file, null));
        assertClosedWithoutForce(file);
        assertEquals(0, file.reads);
    }

    private static void assertClosedWithoutForce(MemoryFile file) {
        assertTrue(file.closed);
        assertEquals(1, file.closes);
        assertEquals(0, file.forces);
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), value))));
    }

    private static SegmentId contentId(byte[] bytes) {
        try {
            return new SegmentId(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record ReadCall(long offset, int remaining) { }

    /** Positional file double: injected failures leave already-written bytes visible. */
    private static final class MemoryFile implements LocalSegmentFile.FileAccess {
        private byte[] data;
        int maxRead = Integer.MAX_VALUE;
        int maxWrite = Integer.MAX_VALUE;
        int zeroReads;
        int zeroEofReads;
        int zeroWrites;
        long failReadAt = Long.MAX_VALUE;
        long failWriteAt = Long.MAX_VALUE;
        long[] sizeReports;
        boolean failSize;
        boolean failForce;
        boolean failClose;
        boolean negativeWrite;
        boolean closed;
        int reads;
        int writes;
        int forces;
        int closes;
        private int sizes;
        final IOException readFailure = new IOException("injected read failure");
        final IOException writeFailure = new IOException("injected write failure");
        final IOException sizeFailure = new IOException("injected size failure");
        final IOException forceFailure = new IOException("injected force failure");
        final IOException closeFailure = new IOException("injected close failure");
        final List<String> events = new ArrayList<>();
        final List<ReadCall> readCalls = new ArrayList<>();
        final List<Long> writtenOffsets = new ArrayList<>();
        final List<Integer> writtenCounts = new ArrayList<>();

        MemoryFile(byte[] initial) {
            data = initial.clone();
        }

        byte[] bytes() {
            return data.clone();
        }

        @Override
        public int read(ByteBuffer target, long offset) throws IOException {
            ensureOpen();
            events.add("read");
            reads++;
            readCalls.add(new ReadCall(offset, target.remaining()));
            if (offset >= failReadAt) {
                throw readFailure;
            }
            if (offset >= data.length) {
                if (zeroEofReads > 0) {
                    zeroEofReads--;
                    return 0;
                }
                return -1;
            }
            if (zeroReads > 0) {
                zeroReads--;
                return 0;
            }
            int count = (int) Math.min(Math.min(maxRead, target.remaining()),
                    Math.min(data.length - offset, failReadAt - offset));
            target.put(data, (int) offset, count);
            return count;
        }

        @Override
        public int write(ByteBuffer source, long offset) throws IOException {
            ensureOpen();
            events.add("write");
            writes++;
            if (offset >= failWriteAt) {
                throw writeFailure;
            }
            if (negativeWrite) {
                return -1;
            }
            if (zeroWrites > 0) {
                zeroWrites--;
                return 0;
            }
            int count = (int) Math.min(Math.min(maxWrite, source.remaining()), failWriteAt - offset);
            int end = Math.toIntExact(offset + count);
            if (end > data.length) {
                data = Arrays.copyOf(data, end);
            }
            source.get(data, (int) offset, count);
            writtenOffsets.add(offset);
            writtenCounts.add(count);
            return count;
        }

        @Override
        public long size() throws IOException {
            ensureOpen();
            events.add("size");
            if (failSize) {
                throw sizeFailure;
            }
            int call = sizes++;
            return sizeReports != null && call < sizeReports.length ? sizeReports[call] : data.length;
        }

        @Override
        public void force() throws IOException {
            ensureOpen();
            events.add("force");
            forces++;
            if (failForce) {
                throw forceFailure;
            }
        }

        @Override
        public void close() throws IOException {
            events.add("close");
            closes++;
            closed = true;
            if (failClose) {
                throw closeFailure;
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("file already closed");
            }
        }
    }
}
