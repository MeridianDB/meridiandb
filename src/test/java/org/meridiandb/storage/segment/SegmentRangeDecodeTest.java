// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class SegmentRangeDecodeTest {
    private static final int MAX_RECORDS = 65_536;
    private static final int BODY_START = 72;
    private static final int FIRST_OFFSET = 40;
    private static final int LAST_OFFSET = 48;
    private static final int COUNT_OFFSET = 56;
    private static final int BODY_LENGTH_OFFSET = 60;
    private static final int HEADER_CRC_OFFSET = 68;
    private static final DatabaseId DATABASE = new DatabaseId("db");
    private static final SegmentId GOLDEN_ID = new SegmentId(
            "d313d28a39ce9900e61fdead724735a02e09ab194861553910fe941d64b26ace");
    private static final SegmentId OTHER_ID = new SegmentId("0".repeat(64));

    // Independent v1 fixture shared by the format specification: lineage db,
    // sequence 1, request r and an unbounded put of entity e to value 7.
    private static final byte[] GOLDEN = HexFormat.of().parseHex(
            "4d52444e534547000000000100000018"
            + "4d52444e4a4e4c00000000010000000200640062a9cf7c87"
            + "0000000000000001000000000000000100000001000000000000003621f423b2"
            + "4d54584e0000000100000022182a4007"
            + "00000000000000010000000100720000000101000000010065000000000000000007"
            + "538cd524"
            + "af1a2406823229a1e45d59ada79103d9b9f834e1c0171ef6a3e83478ba0e28fa");

    @Test
    void matchingLiteralRangeReturnsTheGoldenHistoryAndCompleteIdentifier() {
        var decoded = SegmentCodec.decode(GOLDEN, GOLDEN_ID, 1, 1);
        assertEquals(GOLDEN_ID, decoded.id());
        assertEquals(DATABASE, decoded.segment().databaseId());
        assertEquals(1, decoded.segment().firstSequence());
        assertEquals(1, decoded.segment().lastSequence());
        assertEquals(List.of(new JournalRecord(1, new Transaction(new RequestId("r"), List.of(
                new WriteOperation.Put(new EntityId("e"), new ValidTimeInterval(null, null), 7))))),
                decoded.segment().records());
        assertEquals(SegmentCodec.decode(GOLDEN), decoded);
        assertEquals(SegmentCodec.decode(GOLDEN, GOLDEN_ID), decoded);
    }

    @Test
    void acceptsAnInteriorRangeAndAnExactSingleRecordAtLongMaximum() {
        for (long[] bounds : new long[][] {{7, 8}, {Long.MAX_VALUE, Long.MAX_VALUE},
                {Long.MAX_VALUE - 1, Long.MAX_VALUE}}) {
            var records = bounds[0] == bounds[1]
                    ? List.of(record(bounds[0], "first"))
                    : List.of(record(bounds[0], "first"), record(bounds[1], "last"));
            var original = new ImmutableSegment(DATABASE, records);
            var image = SegmentCodec.encode(original);
            var expected = contentId(image);
            var decoded = SegmentCodec.decode(image, expected, bounds[0], bounds[1]);
            assertEquals(original, decoded.segment());
            assertEquals(expected, decoded.id());
        }
    }

    @Test
    void rejectsWrongFirstWrongLastAndShiftedRanges() {
        var image = SegmentCodec.encode(new ImmutableSegment(DATABASE,
                List.of(record(7, "first"), record(8, "last"))));
        var id = contentId(image);
        for (long[] expected : new long[][] {{6, 8}, {7, 9}, {6, 7}, {8, 9}, {7, 7}, {8, 8}}) {
            assertCode(SegmentFormatException.Code.INVALID_HISTORY,
                    () -> SegmentCodec.decode(image, id, expected[0], expected[1]));
        }
    }

    @Test
    void rangeMismatchPrecedesIntegrityFooterValidationAndIdentifierComparison() {
        var image = SegmentCodec.encode(new ImmutableSegment(DATABASE,
                List.of(record(7, "first"), record(8, "last"))));
        var originalId = contentId(image);
        image[image.length - 1] ^= 1;
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(image, originalId));
        for (long[] expected : new long[][] {{6, 8}, {7, 9}, {7, 7}, {6, 7}}) {
            assertCode(SegmentFormatException.Code.INVALID_HISTORY,
                    () -> SegmentCodec.decode(image, OTHER_ID, expected[0], expected[1]));
        }
    }

    @Test
    void rangeMismatchPrecedesNestedRecordValidation() {
        var image = GOLDEN.clone();
        image[BODY_START + 34] = 3; // Unknown operation, with valid frame and object integrity.
        resignPayloadAndFooter(image);
        var id = contentId(image);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(image, id));
        assertCode(SegmentFormatException.Code.INVALID_HISTORY,
                () -> SegmentCodec.decode(image, id, 2, 2));
    }

    @Test
    void rejectsLargeValidHeaderRangeBeforeRequiringOrMaterializingItsBody() {
        var headerOnly = Arrays.copyOf(GOLDEN, BODY_START);
        setRange(headerOnly, 1, MAX_RECORDS, MAX_RECORDS);
        // A valid bounded whole-object size does not make the missing body available.
        ByteBuffer.wrap(headerOnly).putLong(BODY_LENGTH_OFFSET,
                SegmentCodec.MAX_SEGMENT_BYTES - BODY_START - 32L);
        resignHeader(headerOnly);
        assertCode(SegmentFormatException.Code.INVALID_HISTORY,
                () -> SegmentCodec.decode(headerOnly, OTHER_ID, 1, 1));
        assertCode(SegmentFormatException.Code.INCOMPLETE,
                () -> SegmentCodec.decode(headerOnly, OTHER_ID, 1, MAX_RECORDS));
        assertCode(SegmentFormatException.Code.INCOMPLETE, () -> SegmentCodec.decode(headerOnly));
    }

    @Test
    void largestExpectedSpanNearLongMaximumIsAcceptedWithoutOverflow() {
        var headerOnly = Arrays.copyOf(GOLDEN, BODY_START);
        long first = Long.MAX_VALUE - MAX_RECORDS + 1;
        setRange(headerOnly, first, Long.MAX_VALUE, MAX_RECORDS);
        resignHeader(headerOnly);
        assertCode(SegmentFormatException.Code.INCOMPLETE,
                () -> SegmentCodec.decode(headerOnly, OTHER_ID, first, Long.MAX_VALUE));
        assertCode(SegmentFormatException.Code.INVALID_HISTORY,
                () -> SegmentCodec.decode(headerOnly, OTHER_ID, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void rejectsExpectedRangesOutsideThePositiveOrderedBoundedDomainBeforeImageDecode() {
        for (long[] expected : new long[][] {
                {0, 0}, {0, 1}, {-1, 1}, {Long.MIN_VALUE, Long.MAX_VALUE},
                {1, 0}, {2, 1}, {Long.MAX_VALUE, Long.MAX_VALUE - 1},
                {1, MAX_RECORDS + 1L}, {1, Long.MAX_VALUE},
                {Long.MAX_VALUE - MAX_RECORDS, Long.MAX_VALUE}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SegmentCodec.decode(new byte[0], GOLDEN_ID, expected[0], expected[1]));
        }
    }

    @Test
    void nullImageAndExpectedIdentifierRemainInvalid() {
        assertThrows(NullPointerException.class, () -> SegmentCodec.decode(null, GOLDEN_ID, 1, 1));
        assertThrows(NullPointerException.class, () -> SegmentCodec.decode(GOLDEN, null, 1, 1));
    }

    @Test
    void validHeaderChecksumRemainsRequiredBeforeTrustingItsRange() {
        var image = GOLDEN.clone();
        ByteBuffer.wrap(image).putLong(FIRST_OFFSET, 2).putLong(LAST_OFFSET, 2);
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(image, GOLDEN_ID, 1, 1));
    }

    @Test
    void malformedHeaderRangesAndCountsRemainInvalidHistory() {
        for (long[] declaration : new long[][] {
                {0, 1, 2}, {2, 1, 1}, {1, 1, 2}, {1, 2, 1}, {1, 1, 0}, {1, 1, -1}}) {
            var image = GOLDEN.clone();
            setRange(image, declaration[0], declaration[1], (int) declaration[2]);
            resignHeader(image);
            assertCode(SegmentFormatException.Code.INVALID_HISTORY,
                    () -> SegmentCodec.decode(image, GOLDEN_ID, 1, 1));
        }
    }

    @Test
    void headerVersionAndCountLimitsStillPrecedeExpectedRangeComparison() {
        var unsupported = GOLDEN.clone();
        ByteBuffer.wrap(unsupported).putInt(8, 2);
        resignHeader(unsupported);
        assertCode(SegmentFormatException.Code.UNSUPPORTED_VERSION,
                () -> SegmentCodec.decode(unsupported, GOLDEN_ID, 2, 2));
        var countOverflow = GOLDEN.clone();
        setRange(countOverflow, 1, MAX_RECORDS + 1L, MAX_RECORDS + 1);
        resignHeader(countOverflow);
        assertCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                () -> SegmentCodec.decode(countOverflow, GOLDEN_ID, 2, 2));
    }

    @Test
    void matchingRangeStillRequiresCompleteObjectAndExactFullObjectIdentifier() {
        assertCode(SegmentFormatException.Code.INCOMPLETE,
                () -> SegmentCodec.decode(Arrays.copyOf(GOLDEN, GOLDEN.length - 1), GOLDEN_ID, 1, 1));
        assertCode(SegmentFormatException.Code.IDENTIFIER_MISMATCH,
                () -> SegmentCodec.decode(GOLDEN, OTHER_ID, 1, 1));
        var footerOnly = new SegmentId(HexFormat.of().formatHex(
                Arrays.copyOfRange(GOLDEN, GOLDEN.length - 32, GOLDEN.length)));
        assertCode(SegmentFormatException.Code.IDENTIFIER_MISMATCH,
                () -> SegmentCodec.decode(GOLDEN, footerOnly, 1, 1));
        var footerFailure = GOLDEN.clone();
        footerFailure[footerFailure.length - 1] ^= 1;
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(footerFailure, contentId(footerFailure), 1, 1));
    }

    @Test
    void matchingRangeStillValidatesActualTransactionSequence() {
        var image = GOLDEN.clone();
        ByteBuffer.wrap(image).putLong(BODY_START + 16, 2);
        resignPayloadAndFooter(image);
        assertCode(SegmentFormatException.Code.INVALID_HISTORY,
                () -> SegmentCodec.decode(image, contentId(image), 1, 1));
    }

    @Test
    void matchingRangeStillRequiresExactlyTheDeclaredNumberOfCompleteRecords() {
        var image = GOLDEN.clone();
        setRange(image, 1, 2, 2);
        resignHeaderAndFooter(image);
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(image, contentId(image), 1, 2));
    }

    @Test
    void matchingRangePreservesExistingCallerOwnedInputAndImmutableResults() {
        var image = GOLDEN.clone();
        var before = image.clone();
        var decoded = SegmentCodec.decode(image, GOLDEN_ID, 1, 1);
        assertArrayEquals(before, image);
        Arrays.fill(image, (byte) 0);
        assertEquals(7, ((WriteOperation.Put) decoded.segment().records().getFirst()
                .transaction().operations().getFirst()).value());
        assertEquals(GOLDEN_ID, decoded.id());
        assertThrows(UnsupportedOperationException.class, () -> decoded.segment().records().clear());
    }

    private static JournalRecord record(long sequence, String request) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Retract(new EntityId("e"), new ValidTimeInterval(null, null)))));
    }

    private static void assertCode(SegmentFormatException.Code expected, Runnable operation) {
        assertEquals(expected, assertThrows(SegmentFormatException.class, operation::run).code());
    }

    private static void setRange(byte[] image, long first, long last, int count) {
        ByteBuffer.wrap(image).putLong(FIRST_OFFSET, first).putLong(LAST_OFFSET, last).putInt(COUNT_OFFSET, count);
    }

    private static void resignHeader(byte[] image) {
        var crc = new CRC32C();
        crc.update(image, 0, HEADER_CRC_OFFSET);
        ByteBuffer.wrap(image).putInt(HEADER_CRC_OFFSET, (int) crc.getValue());
    }

    private static void resignHeaderAndFooter(byte[] image) {
        resignHeader(image);
        resignFooter(image);
    }

    private static void resignPayloadAndFooter(byte[] image) {
        int payloadBytes = ByteBuffer.wrap(image).getInt(BODY_START + 8);
        var crc = new CRC32C();
        crc.update(image, BODY_START + 16, payloadBytes);
        ByteBuffer.wrap(image).putInt(BODY_START + 16 + payloadBytes, (int) crc.getValue());
        resignFooter(image);
    }

    private static void resignFooter(byte[] image) {
        int footer = image.length - 32;
        System.arraycopy(digest(Arrays.copyOf(image, footer)), 0, image, footer, 32);
    }

    private static SegmentId contentId(byte[] image) {
        return new SegmentId(HexFormat.of().formatHex(digest(image)));
    }

    private static byte[] digest(byte[] image) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(image);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }
}
