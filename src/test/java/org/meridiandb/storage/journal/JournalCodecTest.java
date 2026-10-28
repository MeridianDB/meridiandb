// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class JournalCodecTest {
    private static final int MAX_ID_CODE_UNITS = 32_768;
    private static final int MAX_OPERATIONS = 65_536;
    private static final int MAX_PAYLOAD_BYTES = 8_388_608;
    private static final int RECORD_MAGIC = 0x4d54584e;
    private static final byte[] HEADER_MAGIC = HexFormat.of().parseHex("4d52444e4a4e4c00");
    private static final DatabaseId DATABASE = new DatabaseId("db");
    private static final EntityId ENTITY = new EntityId("e");
    private static final JournalRecord FIRST = record(1, "r", new WriteOperation.Put(
            ENTITY, new ValidTimeInterval(null, null), 7));

    // Independently computed using a bitwise Castagnoli implementation with
    // reflected polynomial 0x82f63b78, initial 0xffffffff and final complement.
    private static final byte[] GOLDEN_HEADER = HexFormat.of().parseHex(
            "4d52444e4a4e4c00000000010000000200640062a9cf7c87");
    private static final byte[] GOLDEN_RECORD = HexFormat.of().parseHex(
            "4d54584e0000000100000022182a4007"
            + "00000000000000010000000100720000000101000000010065000000000000000007"
            + "538cd524");

    @Test
    void matchesIndependentGoldenHeaderAndTransactionBytes() {
        assertArrayEquals(GOLDEN_HEADER, JournalCodec.encodeHeader(DATABASE));
        assertArrayEquals(GOLDEN_RECORD, JournalCodec.encodeRecord(FIRST));
        assertEquals(DATABASE, JournalCodec.readHeader(ByteBuffer.wrap(GOLDEN_HEADER)));
        assertEquals(Optional.of(FIRST), JournalCodec.readRecord(ByteBuffer.wrap(GOLDEN_RECORD)));
    }

    @Test
    void recordRequiresPositiveSequenceAndTransaction() {
        assertThrows(IllegalArgumentException.class, () -> new JournalRecord(0, FIRST.transaction()));
        assertThrows(IllegalArgumentException.class, () -> new JournalRecord(-1, FIRST.transaction()));
        assertThrows(NullPointerException.class, () -> new JournalRecord(1, null));
        assertDoesNotThrow(() -> new JournalRecord(Long.MAX_VALUE, FIRST.transaction()));
    }

    @Test
    void preservesOrderedOperationsExactUtf16AndLongExtremes() {
        var exactIdentity = "A\u0000é" + (char) 0xd800 + "e" + (char) 0x0301 + (char) 0xdc00;
        var entity = new EntityId(exactIdentity);
        var operations = List.<WriteOperation>of(
                new WriteOperation.Put(entity, new ValidTimeInterval(null, null), Long.MIN_VALUE),
                new WriteOperation.Put(new EntityId("é"),
                        new ValidTimeInterval(Long.MIN_VALUE, Long.MAX_VALUE), Long.MAX_VALUE),
                new WriteOperation.Retract(new EntityId("e" + (char) 0x0301),
                        new ValidTimeInterval(Long.MAX_VALUE, null)),
                new WriteOperation.Retract(entity, new ValidTimeInterval(null, Long.MAX_VALUE)),
                new WriteOperation.Put(entity, new ValidTimeInterval(Long.MIN_VALUE, null), 0));
        for (long sequence : new long[] {1, Long.MAX_VALUE}) {
            var expected = new JournalRecord(sequence, new Transaction(new RequestId(exactIdentity), operations));
            var bytes = JournalCodec.encodeRecord(expected);
            assertEquals(Optional.of(expected), JournalCodec.readRecord(ByteBuffer.wrap(bytes)));
        }
        var database = new DatabaseId(exactIdentity);
        assertEquals(database, JournalCodec.readHeader(ByteBuffer.wrap(JournalCodec.encodeHeader(database))));
    }

    @Test
    void finiteMinimumEndpointRemainsDifferentFromUnboundedRetryPayload() {
        var unbounded = record(1, "retry", new WriteOperation.Put(
                ENTITY, new ValidTimeInterval(null, null), 1));
        var finite = record(1, "retry", new WriteOperation.Put(
                ENTITY, new ValidTimeInterval(Long.MIN_VALUE, null), 1));
        var left = JournalCodec.readRecord(ByteBuffer.wrap(JournalCodec.encodeRecord(unbounded))).orElseThrow();
        var right = JournalCodec.readRecord(ByteBuffer.wrap(JournalCodec.encodeRecord(finite))).orElseThrow();
        assertNotEquals(left.transaction(), right.transaction());
        assertFalse(Arrays.equals(JournalCodec.encodeRecord(unbounded), JournalCodec.encodeRecord(finite)));
    }

    @Test
    void emptyRecordBufferIsCleanEof() {
        var input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        input.position(2).limit(2);
        assertEquals(Optional.empty(), JournalCodec.readRecord(input));
        assertEquals(2, input.position());
        assertEquals(2, input.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, input.order());
    }

    @Test
    void everyHeaderTruncationFailsWithoutAdvancing() {
        for (int length = 0; length < GOLDEN_HEADER.length; length++) {
            var input = withOffset(Arrays.copyOf(GOLDEN_HEADER, length));
            assertHeaderFailure(JournalFormatException.Code.INCOMPLETE_HEADER, input);
        }
    }

    @Test
    void everyNonemptyRecordTruncationFailsWithoutAdvancing() {
        for (int length = 1; length < GOLDEN_RECORD.length; length++) {
            var input = withOffset(Arrays.copyOf(GOLDEN_RECORD, length));
            assertRecordFailure(JournalFormatException.Code.INCOMPLETE_TAIL, input);
        }
    }

    @Test
    void supportsOffsetDirectReadOnlyAndLittleEndianBuffers() {
        var content = concat(GOLDEN_HEADER, GOLDEN_RECORD);
        var heap = withOffset(content);
        var direct = ByteBuffer.allocateDirect(content.length + 9).order(ByteOrder.LITTLE_ENDIAN);
        direct.position(5).put(content).limit(5 + content.length).position(5);
        var readOnly = withOffset(content).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        for (var input : List.of(heap, direct, readOnly)) {
            int initial = input.position();
            int limit = input.limit();
            assertEquals(DATABASE, JournalCodec.readHeader(input));
            assertEquals(initial + GOLDEN_HEADER.length, input.position());
            assertEquals(Optional.of(FIRST), JournalCodec.readRecord(input));
            assertEquals(initial + content.length, input.position());
            assertEquals(limit, input.limit());
            assertEquals(ByteOrder.LITTLE_ENDIAN, input.order());
        }
    }

    @Test
    void directAndReadOnlyBufferFailuresPreservePositionOrderAndLimit() {
        var broken = GOLDEN_RECORD.clone();
        broken[broken.length - 1] ^= 1;
        var direct = ByteBuffer.allocateDirect(broken.length + 7).order(ByteOrder.LITTLE_ENDIAN);
        direct.position(3).put(broken).limit(3 + broken.length).position(3);
        assertRecordFailure(JournalFormatException.Code.CORRUPT, direct);
        assertRecordFailure(JournalFormatException.Code.CORRUPT,
                withOffset(broken).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN));
    }

    @Test
    void decoderConsumesExactlyOneFrameRatherThanFollowingBytes() {
        var second = record(2, "second", new WriteOperation.Retract(ENTITY, new ValidTimeInterval(null, null)));
        var secondBytes = JournalCodec.encodeRecord(second);
        var input = ByteBuffer.wrap(concat(GOLDEN_RECORD, secondBytes)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(Optional.of(FIRST), JournalCodec.readRecord(input));
        assertEquals(GOLDEN_RECORD.length, input.position());
        assertEquals(Optional.of(second), JournalCodec.readRecord(input));
        assertEquals(GOLDEN_RECORD.length + secondBytes.length, input.position());
    }

    @Test
    void detectsEverySingleByteMutationInGoldenHeaderAndFrame() {
        for (int offset = 0; offset < GOLDEN_HEADER.length; offset++) {
            var bytes = GOLDEN_HEADER.clone();
            bytes[offset] ^= 1;
            assertThrows(JournalFormatException.class, () -> JournalCodec.readHeader(ByteBuffer.wrap(bytes)),
                    "header byte " + offset);
        }
        for (int offset = 0; offset < GOLDEN_RECORD.length; offset++) {
            var bytes = GOLDEN_RECORD.clone();
            bytes[offset] ^= 1;
            assertThrows(JournalFormatException.class, () -> JournalCodec.readRecord(ByteBuffer.wrap(bytes)),
                    "record byte " + offset);
        }
    }

    @Test
    void rejectsCorruptLengthPrefixBeforeTrustingOrAllocatingItsPayloadLength() {
        var bytes = GOLDEN_RECORD.clone();
        ByteBuffer.wrap(bytes).putInt(8, Integer.MAX_VALUE);
        assertRecordFailure(JournalFormatException.Code.CORRUPT, withOffset(bytes));
    }

    @Test
    void rejectsUnsupportedVersionsEvenWhenChecksumsAreValid() {
        var header = GOLDEN_HEADER.clone();
        ByteBuffer.wrap(header).putInt(8, 2);
        replaceChecksum(header, 0, header.length - Integer.BYTES);
        assertHeaderFailure(JournalFormatException.Code.UNSUPPORTED_VERSION, withOffset(header));
        assertRecordFailure(JournalFormatException.Code.UNSUPPORTED_VERSION,
                withOffset(frame(payloadOf(GOLDEN_RECORD), 2, RECORD_MAGIC)));
    }

    @Test
    void rejectsUnknownMagicEvenWhenChecksumsAreValid() {
        var header = GOLDEN_HEADER.clone();
        header[0] ^= 1;
        replaceChecksum(header, 0, header.length - Integer.BYTES);
        assertHeaderFailure(JournalFormatException.Code.CORRUPT, withOffset(header));
        assertRecordFailure(JournalFormatException.Code.CORRUPT,
                withOffset(frame(payloadOf(GOLDEN_RECORD), 1, 0x4e4f5045)));
    }

    @Test
    void rejectsNegativeAndOverLimitPayloadLengthsBeforeReadingTheBody() {
        assertRecordFailure(JournalFormatException.Code.CORRUPT, withOffset(prefix(-1, 1, RECORD_MAGIC)));
        assertRecordFailure(JournalFormatException.Code.CORRUPT, withOffset(prefix(0, 1, RECORD_MAGIC)));
        assertRecordFailure(JournalFormatException.Code.LIMIT_EXCEEDED,
                withOffset(prefix(MAX_PAYLOAD_BYTES + 1, 1, RECORD_MAGIC)));
        assertRecordFailure(JournalFormatException.Code.LIMIT_EXCEEDED,
                withOffset(prefix(Integer.MAX_VALUE, 1, RECORD_MAGIC)));
    }

    @Test
    void rejectsMalformedIdentityLengthsWithoutNormalizingOrCoercingThem() {
        for (int length : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertHeaderFailure(JournalFormatException.Code.CORRUPT,
                    withOffset(headerWithIdentityLength(length)));
            assertRecordFailure(JournalFormatException.Code.CORRUPT,
                    withOffset(frame(paddedPayload(bytes(out -> { out.writeLong(1); out.writeInt(length); })))));
            assertRecordFailure(JournalFormatException.Code.CORRUPT,
                    withOffset(frame(paddedPayload(transactionPayload(1, "r", 1, out -> {
                        out.writeByte(1);
                        out.writeInt(length);
                    })))));
        }
        assertHeaderFailure(JournalFormatException.Code.LIMIT_EXCEEDED,
                withOffset(headerWithIdentityLength(MAX_ID_CODE_UNITS + 1)));
        assertRecordFailure(JournalFormatException.Code.LIMIT_EXCEEDED,
                withOffset(frame(paddedPayload(bytes(out -> { out.writeLong(1); out.writeInt(MAX_ID_CODE_UNITS + 1); })))));
        assertRecordFailure(JournalFormatException.Code.LIMIT_EXCEEDED,
                withOffset(frame(paddedPayload(transactionPayload(1, "r", 1, out -> {
                    out.writeByte(1);
                    out.writeInt(MAX_ID_CODE_UNITS + 1);
                })))));
    }

    @Test
    void rejectsMissingEncodedIdentityOrOperationBytesAsCorruptionOfACompleteFrame() {
        assertRecordFailure(JournalFormatException.Code.CORRUPT,
                withOffset(frame(bytes(out -> { out.writeLong(1); out.writeInt(1); }))));
        assertRecordFailure(JournalFormatException.Code.CORRUPT,
                withOffset(frame(transactionPayload(1, "r", 1, out -> out.writeByte(1)))));
        assertRecordFailure(JournalFormatException.Code.CORRUPT,
                withOffset(frame(transactionPayload(1, "r", 1, out -> {
                    out.writeByte(1);
                    writeString(out, "e");
                    out.writeByte(0);
                }))));
    }

    @Test
    void rejectsInvalidOperationCounts() {
        for (int count : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertRecordFailure(JournalFormatException.Code.CORRUPT,
                    withOffset(frame(paddedPayload(transactionPayload(1, "r", count, out -> {})))));
        }
        assertRecordFailure(JournalFormatException.Code.LIMIT_EXCEEDED,
                withOffset(frame(paddedPayload(transactionPayload(1, "r", MAX_OPERATIONS + 1, out -> {})))));
    }

    @Test
    void rejectsUnknownOperationTagsAndBoundFlagBits() {
        for (int kind : new int[] {0, 3, 255}) {
            assertRecordFailure(JournalFormatException.Code.CORRUPT,
                    withOffset(frame(transactionPayload(1, "r", 1,
                            out -> writeOperation(out, kind, "e", 0, null, null, 7)))));
        }
        for (int flags : new int[] {4, 8, 128, 255}) {
            assertRecordFailure(JournalFormatException.Code.CORRUPT,
                    withOffset(frame(transactionPayload(1, "r", 1,
                            out -> writeOperation(out, 1, "e", flags, null, null, 7)))));
        }
    }

    @Test
    void rejectsInvalidSequenceAndEmptyOrReversedIntervals() {
        for (long sequence : new long[] {0, -1, Long.MIN_VALUE}) {
            assertRecordFailure(JournalFormatException.Code.CORRUPT,
                    withOffset(frame(transactionPayload(sequence, "r", 1,
                            out -> writeOperation(out, 1, "e", 0, null, null, 7)))));
        }
        for (long[] interval : new long[][] {{0, 0}, {1, 0}, {Long.MIN_VALUE, Long.MIN_VALUE}}) {
            assertRecordFailure(JournalFormatException.Code.CORRUPT,
                    withOffset(frame(transactionPayload(1, "r", 1,
                            out -> writeOperation(out, 1, "e", 3, interval[0], interval[1], 7)))));
        }
        assertRecordFailure(JournalFormatException.Code.CORRUPT,
                withOffset(frame(transactionPayload(1, "r", 1,
                        out -> writeOperation(out, 2, "e", 2, null, Long.MIN_VALUE, 0)))));
    }

    @Test
    void rejectsPayloadTrailingBytesAndBothChecksumFailures() {
        assertRecordFailure(JournalFormatException.Code.CORRUPT,
                withOffset(frame(concat(payloadOf(GOLDEN_RECORD), new byte[] {0}))));
        var prefixFailure = GOLDEN_RECORD.clone();
        prefixFailure[12] ^= 1;
        assertRecordFailure(JournalFormatException.Code.CORRUPT, withOffset(prefixFailure));
        var payloadFailure = GOLDEN_RECORD.clone();
        payloadFailure[16] ^= 1;
        assertRecordFailure(JournalFormatException.Code.CORRUPT, withOffset(payloadFailure));
    }

    @Test
    void acceptsMaximumIdentityAndOperationCountButRejectsLargerEncodings() {
        var maximumId = "x".repeat(MAX_ID_CODE_UNITS);
        var database = new DatabaseId(maximumId);
        assertEquals(database, JournalCodec.readHeader(ByteBuffer.wrap(JournalCodec.encodeHeader(database))));
        var operation = new WriteOperation.Put(new EntityId(maximumId), new ValidTimeInterval(null, null), 0);
        var maximumIdentity = record(1, maximumId, operation);
        assertEquals(Optional.of(maximumIdentity),
                JournalCodec.readRecord(ByteBuffer.wrap(JournalCodec.encodeRecord(maximumIdentity))));
        var repeated = new JournalRecord(1, new Transaction(new RequestId("many"),
                Collections.nCopies(MAX_OPERATIONS, FIRST.transaction().operations().getFirst())));
        assertEquals(Optional.of(repeated), JournalCodec.readRecord(ByteBuffer.wrap(JournalCodec.encodeRecord(repeated))));
        assertCode(JournalFormatException.Code.LIMIT_EXCEEDED,
                () -> JournalCodec.encodeHeader(new DatabaseId(maximumId + "x")));
        assertCode(JournalFormatException.Code.LIMIT_EXCEEDED,
                () -> JournalCodec.encodeRecord(record(1, maximumId + "x", FIRST.transaction().operations().getFirst())));
        assertCode(JournalFormatException.Code.LIMIT_EXCEEDED,
                () -> JournalCodec.encodeRecord(record(1, "r", new WriteOperation.Retract(
                        new EntityId(maximumId + "x"), new ValidTimeInterval(null, null)))));
        assertCode(JournalFormatException.Code.LIMIT_EXCEEDED,
                () -> JournalCodec.encodeRecord(new JournalRecord(1, new Transaction(new RequestId("many"),
                        Collections.nCopies(MAX_OPERATIONS + 1, FIRST.transaction().operations().getFirst())))));
        assertCode(JournalFormatException.Code.LIMIT_EXCEEDED,
                () -> JournalCodec.encodeRecord(new JournalRecord(1, new Transaction(new RequestId("large"),
                        Collections.nCopies(128, operation)))));
    }

    @Test
    void acceptsExactlyMaximumPayloadAndRejectsTheNextCodeUnit() {
        var maximumId = "x".repeat(MAX_ID_CODE_UNITS);
        var interval = new ValidTimeInterval(null, null);
        var operations = new ArrayList<WriteOperation>(Collections.nCopies(127,
                new WriteOperation.Put(new EntityId(maximumId), interval, 0)));
        // 18 fixed payload bytes + 128 * (14 + 2 * 32768) - 2 * 905 = 8 MiB.
        var finalIdentity = maximumId.substring(905);
        operations.add(new WriteOperation.Put(new EntityId(finalIdentity), interval, 0));
        var maximum = new JournalRecord(1, new Transaction(new RequestId("r"), operations));
        var bytes = JournalCodec.encodeRecord(maximum);
        assertEquals(MAX_PAYLOAD_BYTES, ByteBuffer.wrap(bytes).getInt(8));
        assertEquals(Optional.of(maximum), JournalCodec.readRecord(ByteBuffer.wrap(bytes)));
        operations.set(127, new WriteOperation.Put(new EntityId(finalIdentity + "x"), interval, 0));
        var overLimit = new JournalRecord(1, new Transaction(new RequestId("r"), operations));
        assertCode(JournalFormatException.Code.LIMIT_EXCEEDED, () -> JournalCodec.encodeRecord(overLimit));
    }

    @Test
    void scanReturnsAnImmutableCompleteHistoryWithExactByteOffset() {
        var second = record(2, "r2", new WriteOperation.Retract(ENTITY, new ValidTimeInterval(0L, 10L)));
        var journal = concat(GOLDEN_HEADER, GOLDEN_RECORD, JournalCodec.encodeRecord(second));
        var scan = JournalCodec.scan(journal);
        assertEquals(DATABASE, scan.databaseId());
        assertEquals(List.of(FIRST, second), scan.records());
        assertEquals(journal.length, scan.validBytes());
        assertFalse(scan.incompleteTail());
        assertThrows(UnsupportedOperationException.class, () -> scan.records().clear());
        Arrays.fill(journal, (byte) 0);
        assertEquals(List.of(FIRST, second), scan.records());
    }

    @Test
    void headerOnlyJournalIsValidAndHasNoIncompleteTail() {
        var scan = JournalCodec.scan(GOLDEN_HEADER.clone());
        assertEquals(DATABASE, scan.databaseId());
        assertEquals(List.of(), scan.records());
        assertEquals(GOLDEN_HEADER.length, scan.validBytes());
        assertFalse(scan.incompleteTail());
    }

    @Test
    void scanPreservesOnlyCompletePrefixForEveryTailTruncation() {
        var second = JournalCodec.encodeRecord(record(2, "next", new WriteOperation.Put(
                ENTITY, new ValidTimeInterval(Long.MIN_VALUE, null), Long.MAX_VALUE)));
        var prefix = concat(GOLDEN_HEADER, GOLDEN_RECORD);
        for (int length = 0; length < second.length; length++) {
            var scan = JournalCodec.scan(concat(prefix, Arrays.copyOf(second, length)));
            assertEquals(List.of(FIRST), scan.records(), "tail bytes " + length);
            assertEquals(prefix.length, scan.validBytes(), "tail bytes " + length);
            assertEquals(length != 0, scan.incompleteTail(), "tail bytes " + length);
        }
    }

    @Test
    void scanRequiresCompleteHeaderAndNeverResynchronizesPastDamage() {
        for (int length = 0; length < GOLDEN_HEADER.length; length++) {
            var journal = Arrays.copyOf(GOLDEN_HEADER, length);
            assertCode(JournalFormatException.Code.INCOMPLETE_HEADER, () -> JournalCodec.scan(journal));
        }
        var second = JournalCodec.encodeRecord(record(2, "r2", FIRST.transaction().operations().getFirst()));
        var broken = GOLDEN_RECORD.clone();
        broken[broken.length - 1] ^= 1;
        assertCode(JournalFormatException.Code.CORRUPT,
                () -> JournalCodec.scan(concat(GOLDEN_HEADER, broken, second)));
        assertCode(JournalFormatException.Code.CORRUPT,
                () -> JournalCodec.scan(concat(GOLDEN_HEADER, GOLDEN_RECORD, new byte[] {0}, second)));
        assertCode(JournalFormatException.Code.UNSUPPORTED_VERSION,
                () -> JournalCodec.scan(concat(GOLDEN_HEADER, frame(payloadOf(GOLDEN_RECORD), 2, RECORD_MAGIC))));
    }

    @Test
    void scanRejectsInitialSequenceGapsReorderingAndRepeatedRequestIds() {
        var sameOperation = FIRST.transaction().operations().getFirst();
        for (long sequence : new long[] {2, Long.MAX_VALUE}) {
            assertCode(JournalFormatException.Code.INVALID_HISTORY,
                    () -> JournalCodec.scan(concat(GOLDEN_HEADER,
                            JournalCodec.encodeRecord(record(sequence, "initial", sameOperation)))));
        }
        for (long sequence : new long[] {1, 3, Long.MAX_VALUE}) {
            assertCode(JournalFormatException.Code.INVALID_HISTORY,
                    () -> JournalCodec.scan(concat(GOLDEN_HEADER, GOLDEN_RECORD,
                            JournalCodec.encodeRecord(record(sequence, "next", sameOperation)))));
        }
        assertCode(JournalFormatException.Code.INVALID_HISTORY,
                () -> JournalCodec.scan(concat(GOLDEN_HEADER, GOLDEN_RECORD,
                        JournalCodec.encodeRecord(record(2, "r", sameOperation)))));
        assertCode(JournalFormatException.Code.INVALID_HISTORY,
                () -> JournalCodec.scan(concat(GOLDEN_HEADER, GOLDEN_RECORD,
                        JournalCodec.encodeRecord(record(2, "r", new WriteOperation.Retract(
                                ENTITY, new ValidTimeInterval(null, null)))))));
    }

    @Test
    void sequenceCorrectTailWithBadChecksumIsCorruptionRatherThanRecoverableTruncation() {
        var second = JournalCodec.encodeRecord(record(2, "next", FIRST.transaction().operations().getFirst()));
        second[second.length - 1] ^= 1;
        assertCode(JournalFormatException.Code.CORRUPT,
                () -> JournalCodec.scan(concat(GOLDEN_HEADER, GOLDEN_RECORD, second)));
    }

    private static JournalRecord record(long sequence, String request, WriteOperation... operations) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(operations)));
    }

    private static ByteBuffer withOffset(byte[] bytes) {
        var result = ByteBuffer.allocate(bytes.length + 9).order(ByteOrder.LITTLE_ENDIAN);
        result.position(5).put(bytes).limit(5 + bytes.length).position(5);
        return result;
    }

    private static void assertHeaderFailure(JournalFormatException.Code code, ByteBuffer input) {
        int position = input.position();
        int limit = input.limit();
        var order = input.order();
        assertCode(code, () -> JournalCodec.readHeader(input));
        assertEquals(position, input.position());
        assertEquals(limit, input.limit());
        assertEquals(order, input.order());
    }

    private static void assertRecordFailure(JournalFormatException.Code code, ByteBuffer input) {
        int position = input.position();
        int limit = input.limit();
        var order = input.order();
        assertCode(code, () -> JournalCodec.readRecord(input));
        assertEquals(position, input.position());
        assertEquals(limit, input.limit());
        assertEquals(order, input.order());
    }

    private static void assertCode(JournalFormatException.Code code, Runnable operation) {
        var failure = assertThrows(JournalFormatException.class, operation::run);
        assertEquals(code, failure.code(), failure.getMessage());
    }

    private static byte[] headerWithIdentityLength(int length) {
        var prefix = bytes(out -> {
            out.write(HEADER_MAGIC);
            out.writeInt(1);
            out.writeInt(length);
        });
        return concat(prefix, intBytes(checksum(prefix, 0, prefix.length)));
    }

    private static byte[] transactionPayload(long sequence, String request, int count, Writer operations) {
        return bytes(out -> {
            out.writeLong(sequence);
            writeString(out, request);
            out.writeInt(count);
            operations.write(out);
        });
    }

    private static void writeOperation(DataOutputStream out, int kind, String entity, int flags,
            Long from, Long to, long value) throws IOException {
        out.writeByte(kind);
        writeString(out, entity);
        out.writeByte(flags);
        if (from != null) {
            out.writeLong(from);
        }
        if (to != null) {
            out.writeLong(to);
        }
        if (kind == 1) {
            out.writeLong(value);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) {
            out.writeChar(value.charAt(index));
        }
    }

    private static byte[] prefix(int length, int version, int magic) {
        var prefix = bytes(out -> {
            out.writeInt(magic);
            out.writeInt(version);
            out.writeInt(length);
        });
        return concat(prefix, intBytes(checksum(prefix, 0, prefix.length)));
    }

    private static byte[] paddedPayload(byte[] payload) {
        return Arrays.copyOf(payload, Math.max(26, payload.length));
    }

    private static byte[] frame(byte[] payload) {
        return frame(payload, 1, RECORD_MAGIC);
    }

    private static byte[] frame(byte[] payload, int version, int magic) {
        return concat(prefix(payload.length, version, magic), payload,
                intBytes(checksum(payload, 0, payload.length)));
    }

    private static byte[] payloadOf(byte[] frame) {
        return Arrays.copyOfRange(frame, 16, frame.length - Integer.BYTES);
    }

    private static void replaceChecksum(byte[] bytes, int offset, int length) {
        ByteBuffer.wrap(bytes).putInt(offset + length, checksum(bytes, offset, length));
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        var crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static byte[] bytes(Writer writer) {
        var result = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(result)) {
            writer.write(out);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return result.toByteArray();
    }

    private static byte[] concat(byte[]... fragments) {
        var result = new ByteArrayOutputStream();
        for (var fragment : fragments) {
            result.writeBytes(fragment);
        }
        return result.toByteArray();
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
