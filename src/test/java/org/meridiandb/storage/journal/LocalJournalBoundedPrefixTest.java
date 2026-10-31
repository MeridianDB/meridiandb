// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

class LocalJournalBoundedPrefixTest {
    private static final DatabaseId DATABASE = new DatabaseId("bounded-prefix");
    private static final byte[] HEADER = JournalCodec.encodeHeader(DATABASE);
    private static final JournalRecord FIRST = record(1, "first!", 100);
    private static final JournalRecord SECOND = record(2, "second", 120);
    private static final JournalRecord THIRD = record(3, "third!", 140);

    @TempDir Path directory;

    @Test void exactEncodedCapsMatchIndependentHashAndUnlimitedOverload() throws IOException {
        var file = new MemoryFile(image(FIRST, SECOND));
        try (var journal = open(file)) {
            int writes = file.writes;
            int forces = file.forces;
            for (int cutoff = 0; cutoff <= 2; cutoff++) {
                int length = cutoff == 0 ? HEADER.length : image(cutoff == 1
                        ? new JournalRecord[] {FIRST} : new JournalRecord[] {FIRST, SECOND}).length;
                var bounded = journal.prefix(cutoff, length);
                assertPrefix(bounded, cutoff, file.data, length);
                assertEquals(bounded, journal.prefix(cutoff));
                assertEquals(bounded, journal.prefix(cutoff, Long.MAX_VALUE));
            }
            assertEquals(writes, file.writes);
            assertEquals(forces, file.forces);
        }
    }

    @Test void lessThanSixteenBytesForTheHeaderMakesNoFramingRead() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            for (long cap : new long[] {0, 1, 15}) {
                file.reads.clear();
                file.readLimit = 0;
                assertLimit(journal, 0, cap);
                assertTrue(file.reads.isEmpty());
                assertFalse(file.closed);
            }
            file.readLimit = Long.MAX_VALUE;
            assertPrefix(journal.prefix(1, file.data.length), 1, file.data, file.data.length);
        }
    }

    @Test void aHeaderBodyAboveBudgetStopsAfterItsFixedFraming() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            for (long cap : new long[] {16, HEADER.length - 1L}) {
                file.reads.clear();
                file.readLimit = 16;
                assertLimit(journal, 0, cap);
                assertEquals(List.of(new Read(0, 16)), file.reads);
            }
            file.readLimit = Long.MAX_VALUE;
            assertPrefix(journal.prefix(0, HEADER.length), 0, file.data, HEADER.length);
        }
    }

    @Test void maximumLineageUsesTheSamePreallocationHeaderBudget() throws IOException {
        byte[] largestHeader = JournalCodec.encodeHeader(new DatabaseId("x".repeat(JournalCodec.MAX_ID_CODE_UNITS)));
        var file = new MemoryFile(largestHeader);
        try (var journal = open(file)) {
            file.reads.clear();
            file.readLimit = 16;
            assertLimit(journal, 0, largestHeader.length - 1L);
            assertEquals(List.of(new Read(0, 16)), file.reads);
            file.readLimit = Long.MAX_VALUE;
            assertPrefix(journal.prefix(0, largestHeader.length), 0, file.data, largestHeader.length);
        }
    }

    @Test void insufficientRemainingFramingBudgetDoesNotReadTheNextFrame() throws IOException {
        int firstEnd = image(FIRST).length;
        var file = new MemoryFile(image(FIRST, SECOND));
        try (var journal = open(file)) {
            for (long cap : new long[] {firstEnd, firstEnd + 15L}) {
                file.reads.clear();
                file.readLimit = firstEnd;
                assertLimit(journal, 2, cap);
                assertTrue(file.reads.stream().allMatch(read -> read.end() <= firstEnd));
                assertTrue(file.reads.stream().noneMatch(read -> read.offset() >= firstEnd));
            }
            file.readLimit = Long.MAX_VALUE;
            assertPrefix(journal.prefix(2, file.data.length), 2, file.data, file.data.length);
        }
    }

    @Test void aFrameBodyAboveBudgetStopsBeforeAnyBodyRead() throws IOException {
        int firstEnd = image(FIRST).length;
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            for (long cap : new long[] {HEADER.length + 16L, firstEnd - 1L}) {
                file.reads.clear();
                file.readLimit = HEADER.length + 16L;
                assertLimit(journal, 1, cap);
                assertEquals(new Read(HEADER.length, 16), file.reads.getLast());
                assertTrue(file.reads.stream().allMatch(read -> read.end() <= HEADER.length + 16L));
            }
        }
    }

    @Test void aShorterSameLineageHistoryDoesNotBudgetALargerActualRecord() throws IOException {
        byte[] shorter = image(FIRST);
        var larger = new JournalRecord(1, new Transaction(FIRST.transaction().requestId(), List.of(
                new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(null, null), 100),
                new WriteOperation.Put(new EntityId("allowance"), new ValidTimeInterval(null, null), 200))));
        var file = new MemoryFile(image(larger));
        assertTrue(file.data.length > shorter.length);
        try (var journal = open(file)) {
            assertEquals(DATABASE, journal.databaseId());
            assertEquals(FIRST.sequence(), journal.appendedSequence());
            file.reads.clear();
            file.readLimit = HEADER.length + 16L;
            assertLimit(journal, FIRST.sequence(), shorter.length);
            assertEquals(new Read(HEADER.length, 16), file.reads.getLast());
            file.readLimit = Long.MAX_VALUE;
            assertPrefix(journal.prefix(1, file.data.length), 1, file.data, file.data.length);
        }
    }

    @Test void cumulativeOneByteShortBudgetReadsNoSecondBody() throws IOException {
        int firstEnd = image(FIRST).length;
        var file = new MemoryFile(image(FIRST, SECOND));
        try (var journal = open(file)) {
            file.reads.clear();
            file.readLimit = firstEnd + 16L;
            assertLimit(journal, 2, file.data.length - 1L);
            assertEquals(new Read(firstEnd, 16), file.reads.getLast());
            assertTrue(file.reads.stream().allMatch(read -> read.end() <= firstEnd + 16L));
            file.readLimit = Long.MAX_VALUE;
            assertPrefix(journal.prefix(2, file.data.length), 2, file.data, file.data.length);
        }
    }

    @Test void shortAndTransientZeroReadsStayWithinTheExactEncodedCap() throws IOException {
        var file = new MemoryFile(image(FIRST, SECOND));
        try (var journal = open(file)) {
            file.reads.clear();
            file.readLimit = file.data.length;
            file.maxRead = 3;
            file.zeroReads = 15;
            assertPrefix(journal.prefix(2, file.data.length), 2, file.data, file.data.length);
            assertTrue(file.reads.size() > 20);
            assertTrue(file.reads.stream().allMatch(read -> read.end() <= file.data.length));
            assertEquals(0, file.writes);
            assertEquals(0, file.forces);
        }
    }

    @Test void policyLimitPreservesBytesCutoffsIdentityAndSubsequentAppend() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            journal.force();
            long forcedBytes = journal.forcedBytes();
            journal.append(SECOND);
            byte[] before = file.data.clone();
            int writes = file.writes;
            int forces = file.forces;
            assertLimit(journal, 2, file.data.length - 1L);
            assertEquals(2, journal.appendedSequence());
            assertEquals(before.length, journal.appendedBytes());
            assertEquals(1, journal.forcedSequence());
            assertEquals(forcedBytes, journal.forcedBytes());
            assertEquals(writes, file.writes);
            assertEquals(forces, file.forces);
            assertArrayEquals(before, file.data);
            assertFalse(file.closed);
            assertPrefix(journal.prefix(2, before.length), 2, before, before.length);
            assertThrows(IllegalArgumentException.class, () -> journal.append(record(3, "first!", 9)));
            journal.append(THIRD);
            assertEquals(3, journal.force());
            assertPrefix(journal.prefix(3, file.data.length), 3, file.data, file.data.length);
        }
    }

    @Test void realFileLimitRetainsOwnershipAndDoesNotForceTheUnforcedTail() throws IOException {
        Path path = directory.resolve("journal");
        LocalJournal.Prefix complete;
        try (var journal = LocalJournal.create(path, DATABASE)) {
            journal.append(FIRST);
            journal.force();
            long forcedBytes = journal.forcedBytes();
            journal.append(SECOND);
            long appendedBytes = journal.appendedBytes();
            assertLimit(journal, 2, appendedBytes - 1);
            assertThrows(IOException.class, () -> {
                try (var conflicting = LocalJournal.open(path)) { conflicting.prefix(0); }
            });
            assertEquals(1, journal.forcedSequence());
            assertEquals(forcedBytes, journal.forcedBytes());
            assertEquals(2, journal.appendedSequence());
            assertEquals(appendedBytes, journal.appendedBytes());
            complete = journal.prefix(2, appendedBytes);
            assertEquals(1, journal.forcedSequence());
            assertEquals(forcedBytes, journal.forcedBytes());
        }
        // Inspect raw bytes only after the owning locked handle closes.
        byte[] actual = Files.readAllBytes(path);
        assertPrefix(complete, 2, actual, actual.length);
        try (var reopened = LocalJournal.open(path)) {
            assertEquals(complete, reopened.prefix(2, actual.length));
            assertEquals(0, reopened.forcedSequence());
        }
    }

    @Test void aLargeValidFrameDeclarationUsesPolicyLimitBeforeReadingItsBody() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            byte[] original = file.data.clone();
            System.arraycopy(recordFraming(JournalCodec.MAX_PAYLOAD_BYTES), 0, file.data, HEADER.length, 16);
            file.reads.clear();
            file.readLimit = HEADER.length + 16L;
            assertLimit(journal, 1, HEADER.length + 20L);
            assertEquals(new Read(HEADER.length, 16), file.reads.getLast());
            file.data = original;
            file.readLimit = Long.MAX_VALUE;
            assertPrefix(journal.prefix(1, original.length), 1, original, original.length);
        }
    }

    @Test void aValidButUnavailableBodyRemainsCorruptionWithASufficientBudget() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            System.arraycopy(recordFraming(JournalCodec.MAX_PAYLOAD_BYTES), 0, file.data, HEADER.length, 16);
            file.reads.clear();
            file.readLimit = HEADER.length + 16L;
            var failure = assertThrows(JournalFormatException.class, () -> journal.prefix(1, Long.MAX_VALUE));
            assertEquals(JournalFormatException.Code.INCOMPLETE_TAIL, failure.code());
            assertEquals(new Read(HEADER.length, 16), file.reads.getLast());
            assertPoisoned(journal, file);
        }
    }

    @Test void anOversizedFrameDeclarationIsAFormatFailureRatherThanACallerLimit() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            System.arraycopy(recordFraming(JournalCodec.MAX_PAYLOAD_BYTES + 1), 0, file.data, HEADER.length, 16);
            file.reads.clear();
            file.readLimit = HEADER.length + 16L;
            var failure = assertThrows(JournalFormatException.class,
                    () -> journal.prefix(1, HEADER.length + 16L));
            assertEquals(JournalFormatException.Code.LIMIT_EXCEEDED, failure.code());
            assertEquals(new Read(HEADER.length, 16), file.reads.getLast());
            assertPoisoned(journal, file);
        }
    }

    @Test void anObservedBadFramePrefixPoisonsEvenWhenNoBodyFitsTheBudget() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            file.data[HEADER.length + 15] ^= 1;
            var failure = assertThrows(JournalFormatException.class,
                    () -> journal.prefix(1, HEADER.length + 16L));
            assertEquals(JournalFormatException.Code.CORRUPT, failure.code());
            assertPoisoned(journal, file);
        }
    }

    @Test void zeroEarlierAndExhaustedPrefixesDoNotReadAnUnobservedLateFault() throws IOException {
        int firstEnd = image(FIRST).length;
        var file = new MemoryFile(image(FIRST, SECOND));
        try (var journal = open(file)) {
            file.failReadAt = firstEnd;
            file.reads.clear();
            assertPrefix(journal.prefix(0, HEADER.length), 0, file.data, HEADER.length);
            assertPrefix(journal.prefix(1, firstEnd), 1, file.data, firstEnd);
            assertLimit(journal, 2, firstEnd + 15L);
            assertTrue(file.reads.stream().allMatch(read -> read.end() <= firstEnd));
            var failure = assertThrows(IOException.class, () -> journal.prefix(2, file.data.length));
            assertFalse(failure instanceof LocalJournal.PrefixLimitException);
            assertPoisoned(journal, file);
        }
    }

    @Test void invalidBudgetAndCutoffsRejectBeforeAllFileAccess() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            int reads = file.reads.size();
            int sizes = file.sizes;
            file.failSize = true;
            for (long budget : new long[] {-1, Long.MIN_VALUE}) {
                assertThrows(IllegalArgumentException.class, () -> journal.prefix(0, budget));
            }
            for (long cutoff : new long[] {-1, 2, Long.MAX_VALUE}) {
                assertThrows(IllegalArgumentException.class, () -> journal.prefix(cutoff, 0));
            }
            assertEquals(reads, file.reads.size());
            assertEquals(sizes, file.sizes);
            assertEquals(0, file.writes);
            assertEquals(0, file.forces);
            file.failSize = false;
            journal.append(SECOND);
            assertPrefix(journal.prefix(2, Long.MAX_VALUE), 2, file.data, file.data.length);
        }
    }

    @Test void closedOwnerRejectsTheNewOverloadBeforeFileAccess() throws IOException {
        var file = new MemoryFile(image(FIRST));
        var journal = open(file);
        journal.close();
        int reads = file.reads.size();
        int sizes = file.sizes;
        assertThrows(IOException.class, () -> journal.prefix(0, HEADER.length));
        assertEquals(reads, file.reads.size());
        assertEquals(sizes, file.sizes);
        assertTrue(file.closed);
    }

    @Test void replayCallbackRejectsTheNewOverloadWithoutPoisoningTheOwner() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            journal.replay(record -> {
                int reads = file.reads.size();
                int sizes = file.sizes;
                assertThrows(IOException.class, () -> journal.prefix(record.sequence(), Long.MAX_VALUE));
                assertEquals(reads, file.reads.size());
                assertEquals(sizes, file.sizes);
            });
            assertPrefix(journal.prefix(1, file.data.length), 1, file.data, file.data.length);
            journal.append(SECOND);
            assertEquals(2, journal.force());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"read", "zero", "eof", "size"})
    void ordinaryIoFailuresUnderAnAdequateBudgetPoisonTheOwner(String fault) throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            switch (fault) {
                case "read" -> file.failReadAt = 0;
                case "zero" -> file.zeroReads = 16;
                case "eof" -> file.eofAt = HEADER.length + 16;
                case "size" -> file.failSize = true;
                default -> throw new AssertionError(fault);
            }
            var failure = assertThrows(IOException.class, () -> journal.prefix(1, file.data.length));
            assertFalse(failure instanceof LocalJournal.PrefixLimitException);
            assertPoisoned(journal, file);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"header", "body"})
    void checksumCorruptionInsideTheBudgetStillPoisonsTheOwner(String damaged) throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            file.data[damaged.equals("header") ? HEADER.length - 1 : file.data.length - 1] ^= 1;
            var failure = assertThrows(JournalFormatException.class, () -> journal.prefix(1, file.data.length));
            assertEquals(JournalFormatException.Code.CORRUPT, failure.code());
            assertPoisoned(journal, file);
        }
    }

    @Test void observedSizeChangeIsNotHiddenByAZeroBytePolicy() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            file.reportedExtra = 1;
            file.reads.clear();
            var failure = assertThrows(IOException.class, () -> journal.prefix(0, 0));
            assertFalse(failure instanceof LocalJournal.PrefixLimitException);
            assertTrue(file.reads.isEmpty());
            assertPoisoned(journal, file);
        }
    }

    private static void assertLimit(LocalJournal journal, long sequence, long maxBytes) {
        var failure = assertThrows(LocalJournal.PrefixLimitException.class, () -> journal.prefix(sequence, maxBytes));
        assertEquals(maxBytes, failure.maxBytes());
    }

    private static void assertPrefix(LocalJournal.Prefix prefix, long sequence, byte[] image, int length) {
        assertEquals(sequence, prefix.sequence());
        assertEquals(length, prefix.byteLength());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(image, length));
            assertEquals(HexFormat.of().formatHex(digest), prefix.sha256());
        } catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static void assertPoisoned(LocalJournal journal, MemoryFile file) {
        int reads = file.reads.size();
        int sizes = file.sizes;
        int writes = file.writes;
        int forces = file.forces;
        assertThrows(IOException.class, () -> journal.prefix(0, Long.MAX_VALUE));
        assertThrows(IOException.class, () -> journal.prefix(0));
        assertThrows(IOException.class, () -> journal.append(record(3, "unused", 1)));
        assertThrows(IOException.class, journal::force);
        assertThrows(IOException.class, () -> journal.replay(ignored -> { }));
        assertEquals(reads, file.reads.size());
        assertEquals(sizes, file.sizes);
        assertEquals(writes, file.writes);
        assertEquals(forces, file.forces);
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
        var frames = new ArrayList<byte[]>();
        int length = HEADER.length;
        for (var record : records) {
            byte[] frame = JournalCodec.encodeRecord(record);
            frames.add(frame);
            length += frame.length;
        }
        byte[] image = Arrays.copyOf(HEADER, length);
        int offset = HEADER.length;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 0, image, offset, frame.length);
            offset += frame.length;
        }
        return image;
    }

    private static byte[] recordFraming(int payloadBytes) {
        var bytes = ByteBuffer.allocate(16);
        bytes.putInt(0x4d54584e).putInt(1).putInt(payloadBytes);
        var crc = new CRC32C();
        crc.update(bytes.array(), 0, 12);
        bytes.putInt((int) crc.getValue());
        return bytes.array();
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
        int reportedExtra;
        long readLimit = Long.MAX_VALUE;
        long failReadAt = Long.MAX_VALUE;
        long eofAt = Long.MAX_VALUE;
        boolean failSize;
        boolean closed;
        final List<Read> reads = new ArrayList<>();

        MemoryFile(byte[] bytes) { data = bytes.clone(); }

        @Override public int read(ByteBuffer target, long offset) throws IOException {
            ensureOpen();
            reads.add(new Read(offset, target.remaining()));
            if (offset + target.remaining() > readLimit) {
                throw new AssertionError("Read request exceeds the permitted framing/body boundary");
            }
            if (offset >= failReadAt) { throw new IOException("Injected read failure"); }
            if (zeroReads > 0) { zeroReads--; return 0; }
            if (offset >= data.length || offset >= eofAt) { return -1; }
            int count = (int) Math.min(Math.min(target.remaining(), maxRead),
                    Math.min(data.length - offset, eofAt - offset));
            target.put(data, (int) offset, count);
            return count;
        }

        @Override public int write(ByteBuffer source, long offset) throws IOException {
            ensureOpen();
            writes++;
            int length = source.remaining();
            int end = Math.toIntExact(offset + length);
            if (data.length < end) { data = Arrays.copyOf(data, end); }
            source.get(data, (int) offset, length);
            return length;
        }

        @Override public long size() throws IOException {
            ensureOpen();
            sizes++;
            if (failSize) { throw new IOException("Injected size failure"); }
            return data.length + reportedExtra;
        }

        @Override public void force() throws IOException { ensureOpen(); forces++; }
        @Override public void truncate(long size) { throw new AssertionError("Prefix must not truncate"); }
        @Override public void close() { closed = true; }
        private void ensureOpen() throws IOException { if (closed) { throw new IOException("Closed file seam"); } }
    }
}
