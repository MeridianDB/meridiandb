// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class LocalJournalPrefixTest {
    private static final DatabaseId DATABASE = new DatabaseId("prefix-db");
    private static final byte[] HEADER = JournalCodec.encodeHeader(DATABASE);
    private static final JournalRecord FIRST = record(1, "first!", 100);
    private static final JournalRecord SECOND = record(2, "second", 120);

    @TempDir
    Path directory;

    @Test
    void realOwnedFileReturnsExactPrefixBytesWithoutChangingForceEvidence() throws IOException {
        var path = directory.resolve("journal");
        List<LocalJournal.Prefix> prefixes;
        long firstEnd;
        try (var journal = LocalJournal.create(directory.resolve(".").resolve("journal"), DATABASE)) {
            assertEquals(Optional.of(directory.toRealPath().resolve("journal")), journal.path());
            journal.append(FIRST);
            journal.force();
            firstEnd = journal.appendedBytes();
            journal.append(SECOND);
            long fullEnd = journal.appendedBytes();
            prefixes = List.of(journal.prefix(0), journal.prefix(1), journal.prefix(2));
            assertEquals(2, journal.appendedSequence());
            assertEquals(fullEnd, journal.appendedBytes());
            assertEquals(1, journal.forcedSequence());
            assertEquals(firstEnd, journal.forcedBytes());
            assertThrows(IOException.class, () -> LocalJournal.open(path));
        }
        // A second file handle is safe only after the locked owner has closed.
        byte[] actual = Files.readAllBytes(path);
        assertPrefix(prefixes.get(0), 0, actual, HEADER.length);
        assertPrefix(prefixes.get(1), 1, actual, Math.toIntExact(firstEnd));
        assertPrefix(prefixes.get(2), 2, actual, actual.length);
        try (var reopened = LocalJournal.open(path)) {
            assertEquals(prefixes.get(2), reopened.prefix(2));
            assertEquals(0, reopened.forcedSequence());
            assertEquals(0, reopened.forcedBytes());
        }
    }

    @Test
    void shortAndTransientZeroReadsHashTheExactOwnerBytesWithoutWritingOrForcing() throws IOException {
        var file = new MemoryFile(image(FIRST, SECOND));
        try (var journal = open(file)) {
            assertEquals(Optional.empty(), journal.path());
            int writes = file.writes;
            int forces = file.forces;
            file.maxRead = 3;
            file.zeroReads = 15;
            assertPrefix(journal.prefix(2), 2, file.data, file.data.length);
            assertTrue(file.reads.size() > 20);
            assertEquals(writes, file.writes);
            assertEquals(forces, file.forces);
            assertEquals(0, journal.forcedSequence());
            assertEquals(0, journal.forcedBytes());
            assertEquals(file.data.length, journal.appendedBytes());
        }
    }

    @Test
    void zeroAndEarlierPrefixesDoNotReadLaterCorruption() throws IOException {
        var file = new MemoryFile(image(FIRST, SECOND));
        try (var journal = open(file)) {
            file.data[file.data.length - 1] ^= 1;
            file.reads.clear();
            file.readLimit = HEADER.length;
            assertPrefix(journal.prefix(0), 0, file.data, HEADER.length);
            assertTrue(file.reads.stream().allMatch(read -> read.end() <= HEADER.length));
            int firstEnd = image(FIRST).length;
            file.reads.clear();
            file.readLimit = firstEnd;
            assertPrefix(journal.prefix(1), 1, file.data, firstEnd);
            assertTrue(file.reads.stream().allMatch(read -> read.end() <= firstEnd));
            file.readLimit = Long.MAX_VALUE;
            var failure = assertThrows(JournalFormatException.class, () -> journal.prefix(2));
            assertEquals(JournalFormatException.Code.CORRUPT, failure.code());
            assertPoisoned(journal, file);
        }
    }

    @Test
    void invalidCutoffsRejectBeforeIoWithoutPoisoningTheOwner() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            int reads = file.reads.size();
            int sizes = file.sizes;
            int writes = file.writes;
            int forces = file.forces;
            assertThrows(IllegalArgumentException.class, () -> journal.prefix(-1));
            assertThrows(IllegalArgumentException.class, () -> journal.prefix(2));
            assertThrows(IllegalArgumentException.class, () -> journal.prefix(Long.MAX_VALUE));
            assertEquals(reads, file.reads.size());
            assertEquals(sizes, file.sizes);
            assertEquals(writes, file.writes);
            assertEquals(forces, file.forces);
            journal.append(SECOND);
            assertEquals(2, journal.force());
            assertPrefix(journal.prefix(2), 2, file.data, file.data.length);
        }
    }

    @Test
    void closedOwnerRejectsPrefixWithoutIo() throws IOException {
        var file = new MemoryFile(image(FIRST));
        var journal = open(file);
        journal.close();
        int reads = file.reads.size();
        int sizes = file.sizes;
        assertThrows(IOException.class, () -> journal.prefix(0));
        assertEquals(reads, file.reads.size());
        assertEquals(sizes, file.sizes);
        assertTrue(file.closed);
    }

    @Test
    void prefixInsideReplayCallbackIsRejectedWithoutPoisoningTheOwner() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            journal.replay(record -> assertThrows(IOException.class, () -> journal.prefix(record.sequence())));
            assertPrefix(journal.prefix(1), 1, file.data, file.data.length);
            journal.append(SECOND);
            assertEquals(2, journal.force());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "zero", "eof", "size"})
    void failedOwnerReadsAndSizeChecksPoisonTheJournal(String injection) throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            switch (injection) {
                case "read" -> file.failRead = true;
                case "zero" -> file.zeroReads = 16;
                case "eof" -> file.eofAt = HEADER.length + 16;
                case "size" -> file.failSize = true;
                default -> throw new AssertionError(injection);
            }
            assertThrows(IOException.class, () -> journal.prefix(1));
            assertEquals(1, journal.appendedSequence());
            assertEquals(image(FIRST).length, journal.appendedBytes());
            assertPoisoned(journal, file);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "frame"})
    void checksumCorruptionPoisonsTheJournal(String damaged) throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            file.data[damaged.equals("header") ? HEADER.length - 1 : file.data.length - 1] ^= 1;
            var failure = assertThrows(JournalFormatException.class, () -> journal.prefix(1));
            assertEquals(JournalFormatException.Code.CORRUPT, failure.code());
            assertPoisoned(journal, file);
        }
    }

    @Test
    void aValidReplacementHeaderCannotChangeTheOwnedLineage() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            byte[] foreign = JournalCodec.encodeHeader(new DatabaseId("other-db!"));
            assertEquals(HEADER.length, foreign.length);
            System.arraycopy(foreign, 0, file.data, 0, foreign.length);
            var failure = assertThrows(JournalFormatException.class, () -> journal.prefix(0));
            assertEquals(JournalFormatException.Code.INVALID_HISTORY, failure.code());
            assertPoisoned(journal, file);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sequence", "request"})
    void validChecksumsDoNotHideInvalidPrefixHistory(String damaged) throws IOException {
        var file = new MemoryFile(image(FIRST, SECOND));
        try (var journal = open(file)) {
            var replacement = damaged.equals("sequence") ? record(3, "second", 120) : record(2, "first!", 120);
            byte[] altered = image(FIRST, replacement);
            assertEquals(file.data.length, altered.length);
            file.data = altered;
            var failure = assertThrows(JournalFormatException.class, () -> journal.prefix(2));
            assertEquals(JournalFormatException.Code.INVALID_HISTORY, failure.code());
            assertPoisoned(journal, file);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void externalSizeChangesBeforeOrDuringHashingPoisonTheJournal(boolean during) throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            file.reads.clear();
            if (during) {
                file.growReportedSizeAfterRead = true;
            } else {
                file.reportedExtra = 1;
            }
            assertThrows(IOException.class, () -> journal.prefix(1));
            assertEquals(during, !file.reads.isEmpty());
            assertPoisoned(journal, file);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checkedFrameLengthRejectsIncompleteOrOversizedPrefixBeforeReadingABody(boolean oversized) throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            byte[] fixed = recordPrefix(JournalCodec.MAX_PAYLOAD_BYTES + (oversized ? 1 : 0));
            System.arraycopy(fixed, 0, file.data, HEADER.length, fixed.length);
            file.readLimit = HEADER.length + 16;
            file.reads.clear();
            var failure = assertThrows(JournalFormatException.class, () -> journal.prefix(1));
            assertEquals(oversized ? JournalFormatException.Code.LIMIT_EXCEEDED
                    : JournalFormatException.Code.INCOMPLETE_TAIL, failure.code());
            assertTrue(file.reads.stream().allMatch(read -> read.end() <= HEADER.length + 16));
            assertPoisoned(journal, file);
        }
    }

    private static void assertPrefix(LocalJournal.Prefix prefix, long sequence, byte[] image, int length) {
        assertEquals(sequence, prefix.sequence());
        assertEquals(length, prefix.byteLength());
        assertEquals(hash(Arrays.copyOf(image, length)), prefix.sha256());
    }

    private static void assertPoisoned(LocalJournal journal, MemoryFile file) {
        int reads = file.reads.size();
        int writes = file.writes;
        int forces = file.forces;
        int sizes = file.sizes;
        assertThrows(IOException.class, () -> journal.prefix(0));
        assertThrows(IOException.class, () -> journal.append(record(3, "third!", 1)));
        assertThrows(IOException.class, journal::force);
        assertThrows(IOException.class, () -> journal.replay(ignored -> {}));
        assertEquals(reads, file.reads.size());
        assertEquals(writes, file.writes);
        assertEquals(forces, file.forces);
        assertEquals(sizes, file.sizes);
        assertFalse(file.closed);
    }

    private static LocalJournal open(MemoryFile file) throws IOException {
        return LocalJournal.open(file, LocalJournal.TailPolicy.REJECT,
                (source, start, size) -> { throw new AssertionError("Unexpected repair"); });
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(null, null), value))));
    }

    private static byte[] image(JournalRecord... records) {
        int length = HEADER.length;
        var frames = new ArrayList<byte[]>();
        for (var record : records) {
            byte[] frame = JournalCodec.encodeRecord(record);
            frames.add(frame);
            length += frame.length;
        }
        var image = Arrays.copyOf(HEADER, length);
        int offset = HEADER.length;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 0, image, offset, frame.length);
            offset += frame.length;
        }
        return image;
    }

    private static byte[] recordPrefix(int payloadBytes) {
        var prefix = ByteBuffer.allocate(16);
        prefix.putInt(0x4d54584e).putInt(1).putInt(payloadBytes);
        var crc = new CRC32C();
        crc.update(prefix.array(), 0, 12);
        prefix.putInt((int) crc.getValue());
        return prefix.array();
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    private record Read(long offset, int requested) {
        long end() { return offset + requested; }
    }

    private static final class MemoryFile implements LocalJournal.FileAccess {
        byte[] data;
        int maxRead = Integer.MAX_VALUE;
        int zeroReads;
        int writes;
        int forces;
        int sizes;
        long eofAt = Long.MAX_VALUE;
        long readLimit = Long.MAX_VALUE;
        int reportedExtra;
        boolean failRead;
        boolean failSize;
        boolean growReportedSizeAfterRead;
        boolean closed;
        final List<Read> reads = new ArrayList<>();

        MemoryFile(byte[] data) { this.data = data.clone(); }

        @Override
        public int read(ByteBuffer target, long offset) throws IOException {
            ensureOpen();
            reads.add(new Read(offset, target.remaining()));
            if (offset + target.remaining() > readLimit) {
                throw new AssertionError("Prefix read past permitted cutoff");
            }
            if (failRead) { throw new IOException("Injected read failure"); }
            if (zeroReads > 0) {
                zeroReads--;
                return 0;
            }
            if (offset >= data.length || offset >= eofAt) { return -1; }
            int n = (int) Math.min(Math.min(maxRead, target.remaining()), Math.min(data.length - offset, eofAt - offset));
            target.put(data, (int) offset, n);
            if (growReportedSizeAfterRead) { reportedExtra = 1; }
            return n;
        }

        @Override
        public int write(ByteBuffer source, long offset) throws IOException {
            ensureOpen();
            writes++;
            int length = source.remaining();
            int end = Math.toIntExact(offset + length);
            if (data.length < end) { data = Arrays.copyOf(data, end); }
            source.get(data, (int) offset, length);
            return length;
        }

        @Override
        public long size() throws IOException {
            ensureOpen();
            sizes++;
            if (failSize) { throw new IOException("Injected size failure"); }
            return data.length + reportedExtra;
        }

        @Override
        public void force() throws IOException {
            ensureOpen();
            forces++;
        }

        @Override
        public void truncate(long size) throws IOException {
            throw new AssertionError("Prefix must not truncate");
        }

        @Override
        public void close() { closed = true; }

        private void ensureOpen() throws IOException {
            if (closed) { throw new IOException("Closed fake file"); }
        }
    }
}
