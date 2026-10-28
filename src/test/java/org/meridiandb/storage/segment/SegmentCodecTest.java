// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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

class SegmentCodecTest {
    private static final int MAX_RECORDS = 65_536;
    private static final int MAX_SEGMENT_BYTES = 32 * 1024 * 1024;
    private static final int BODY_START = 72;
    private static final DatabaseId DATABASE = new DatabaseId("db");
    private static final EntityId ENTITY = new EntityId("e");
    private static final JournalRecord FIRST = record(1, "r", new WriteOperation.Put(
            ENTITY, new ValidTimeInterval(null, null), 7));
    private static final ImmutableSegment SINGLE = new ImmutableSegment(DATABASE, List.of(FIRST));

    // Constructed independently from scalar fields. CRC32C used the reflected
    // Castagnoli polynomial 0x82f63b78; SHA-256 was computed independently.
    private static final byte[] GOLDEN = HexFormat.of().parseHex(
            "4d52444e534547000000000100000018"
            + "4d52444e4a4e4c00000000010000000200640062a9cf7c87"
            + "0000000000000001000000000000000100000001000000000000003621f423b2"
            + "4d54584e0000000100000022182a4007"
            + "00000000000000010000000100720000000101000000010065000000000000000007"
            + "538cd524"
            + "af1a2406823229a1e45d59ada79103d9b9f834e1c0171ef6a3e83478ba0e28fa");
    private static final String GOLDEN_ID = "d313d28a39ce9900e61fdead724735a02e09ab194861553910fe941d64b26ace";
    private static final String GOLDEN_FOOTER = "af1a2406823229a1e45d59ada79103d9b9f834e1c0171ef6a3e83478ba0e28fa";

    @Test
    void matchesIndependentGoldenImageAndWholeObjectIdentifier() {
        assertArrayEquals(GOLDEN, SegmentCodec.encode(SINGLE));
        var decoded = SegmentCodec.decode(GOLDEN);
        assertEquals(SINGLE, decoded.segment());
        assertEquals(new SegmentId(GOLDEN_ID), decoded.id());
        assertEquals(decoded, SegmentCodec.decode(GOLDEN, new SegmentId(GOLDEN_ID)));
        assertEquals(GOLDEN_FOOTER, HexFormat.of().formatHex(Arrays.copyOfRange(GOLDEN, 126, 158)));
        assertNotEquals(GOLDEN_FOOTER, decoded.id().value());
        assertEquals(GOLDEN_ID, HexFormat.of().formatHex(digest(GOLDEN)));
    }

    @Test
    void segmentIdRequiresCanonicalLowercaseSha256Text() {
        assertDoesNotThrow(() -> new SegmentId("0".repeat(64)));
        assertDoesNotThrow(() -> new SegmentId("abcdef0123456789".repeat(4)));
        for (String invalid : List.of("", "0".repeat(63), "0".repeat(65), "A".repeat(64),
                "g".repeat(64), " " + "0".repeat(63), "0".repeat(63) + "\n")) {
            assertThrows(IllegalArgumentException.class, () -> new SegmentId(invalid));
        }
        assertThrows(NullPointerException.class, () -> new SegmentId(null));
    }

    @Test
    void segmentOwnsAnImmutableCopyOfItsRecordList() {
        var records = new ArrayList<>(List.of(FIRST));
        var segment = new ImmutableSegment(DATABASE, records);
        records.clear();
        assertEquals(List.of(FIRST), segment.records());
        assertThrows(UnsupportedOperationException.class, () -> segment.records().clear());
        assertEquals(1, segment.firstSequence());
        assertEquals(1, segment.lastSequence());
        assertThrows(NullPointerException.class, () -> new ImmutableSegment(null, List.of(FIRST)));
        assertThrows(NullPointerException.class, () -> new ImmutableSegment(DATABASE, null));
        assertThrows(NullPointerException.class,
                () -> new ImmutableSegment(DATABASE, Arrays.asList(FIRST, null)));
    }

    @Test
    void segmentRejectsEmptyGappedReversedAndReusedRequestHistories() {
        assertThrows(IllegalArgumentException.class, () -> new ImmutableSegment(DATABASE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ImmutableSegment(DATABASE,
                List.of(FIRST, record(3, "r3"))));
        assertThrows(IllegalArgumentException.class, () -> new ImmutableSegment(DATABASE,
                List.of(record(2, "r2"), FIRST)));
        assertThrows(IllegalArgumentException.class, () -> new ImmutableSegment(DATABASE,
                List.of(FIRST, record(2, "r"))));
        assertThrows(IllegalArgumentException.class, () -> new ImmutableSegment(DATABASE,
                List.of(record(Long.MAX_VALUE, "last"), record(1, "wrapped"))));
    }

    @Test
    void contiguousSequenceMayBeginAfterOneAndEndAtLongMaximum() {
        var interior = new ImmutableSegment(DATABASE,
                List.of(record(41, "a"), record(42, "b"), record(43, "c")));
        assertEquals(41, interior.firstSequence());
        assertEquals(43, interior.lastSequence());
        assertEquals(interior, SegmentCodec.decode(SegmentCodec.encode(interior)).segment());
        var maximum = new ImmutableSegment(DATABASE,
                List.of(record(Long.MAX_VALUE - 1, "penultimate"), record(Long.MAX_VALUE, "last")));
        assertEquals(maximum, SegmentCodec.decode(SegmentCodec.encode(maximum)).segment());
    }

    @Test
    void preservesExactUtf16LineageIdentitiesOperationOrderAndNumericExtremes() {
        String exact = "A\u0000é" + (char) 0xd800 + "e" + (char) 0x0301 + (char) 0xdc00;
        var entity = new EntityId(exact);
        var operations = List.<WriteOperation>of(
                new WriteOperation.Put(entity, new ValidTimeInterval(null, null), Long.MIN_VALUE),
                new WriteOperation.Retract(entity, new ValidTimeInterval(Long.MIN_VALUE, Long.MAX_VALUE)),
                new WriteOperation.Put(new EntityId("é"),
                        new ValidTimeInterval(Long.MAX_VALUE, null), Long.MAX_VALUE),
                new WriteOperation.Retract(new EntityId("e" + (char) 0x0301),
                        new ValidTimeInterval(null, Long.MAX_VALUE)),
                new WriteOperation.Put(entity, new ValidTimeInterval(Long.MIN_VALUE, null), 0));
        var segment = new ImmutableSegment(new DatabaseId(exact), List.of(
                new JournalRecord(9, new Transaction(new RequestId(exact), operations)),
                record(10, "é"), record(11, "e" + (char) 0x0301)));
        var decoded = SegmentCodec.decode(SegmentCodec.encode(segment)).segment();
        assertEquals(segment, decoded);
        assertEquals(operations, decoded.records().getFirst().transaction().operations());
        assertNotEquals(decoded.records().get(1).transaction().requestId(),
                decoded.records().get(2).transaction().requestId());
    }

    @Test
    void preservesNoopTransactionsAndRetryPayloadDistinctions() {
        var put = new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), 7);
        var retract = new WriteOperation.Retract(ENTITY, new ValidTimeInterval(null, null));
        var history = new ImmutableSegment(DATABASE, List.of(
                record(3, "first", put), record(4, "equal-value", put),
                record(5, "remove", retract), record(6, "already-absent", retract)));
        assertEquals(history, SegmentCodec.decode(SegmentCodec.encode(history)).segment());
        var finite = new ImmutableSegment(DATABASE, List.of(record(1, "r", new WriteOperation.Put(
                ENTITY, new ValidTimeInterval(Long.MIN_VALUE, null), 7))));
        var unbounded = SegmentCodec.decode(GOLDEN);
        var bounded = SegmentCodec.decode(SegmentCodec.encode(finite));
        assertNotEquals(unbounded.segment().records().getFirst().transaction(),
                bounded.segment().records().getFirst().transaction());
        assertNotEquals(unbounded.id(), bounded.id());
    }

    @Test
    void identifierChangesWithLineageRequestSequenceOrOperationOrder() {
        var a = new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), 1);
        var b = new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), 2);
        var base = new ImmutableSegment(DATABASE, List.of(record(1, "r", a, b)));
        var baseId = SegmentCodec.decode(SegmentCodec.encode(base)).id();
        for (var other : List.of(
                new ImmutableSegment(new DatabaseId("other"), base.records()),
                new ImmutableSegment(DATABASE, List.of(record(1, "other", a, b))),
                new ImmutableSegment(DATABASE, List.of(record(2, "r", a, b))),
                new ImmutableSegment(DATABASE, List.of(record(1, "r", b, a))))) {
            assertNotEquals(baseId, SegmentCodec.decode(SegmentCodec.encode(other)).id());
        }
        assertEquals(baseId, SegmentCodec.decode(SegmentCodec.encode(base)).id());
    }

    @Test
    void decodedValuesRetainNoMutableInputAndEncodingReturnsCallerOwnedBytes() {
        var input = GOLDEN.clone();
        var decoded = SegmentCodec.decode(input);
        Arrays.fill(input, (byte) 0);
        assertEquals(SINGLE, decoded.segment());
        assertEquals(new SegmentId(GOLDEN_ID), decoded.id());
        assertThrows(UnsupportedOperationException.class, () -> decoded.segment().records().clear());
        var firstEncoding = SegmentCodec.encode(SINGLE);
        Arrays.fill(firstEncoding, (byte) 0);
        assertArrayEquals(GOLDEN, SegmentCodec.encode(SINGLE));
    }

    @Test
    void rejectsEveryTruncatedWholeImageWithoutReturningAPrefix() {
        for (int length = 0; length < GOLDEN.length; length++) {
            var truncated = Arrays.copyOf(GOLDEN, length);
            assertCode(SegmentFormatException.Code.INCOMPLETE, () -> SegmentCodec.decode(truncated));
        }
        assertEquals(SINGLE, SegmentCodec.decode(GOLDEN).segment());
    }

    @Test
    void detectsEverySingleByteMutationAcrossEnvelopeFrameAndFooter() {
        for (int offset = 0; offset < GOLDEN.length; offset++) {
            var changed = GOLDEN.clone();
            changed[offset] ^= 1;
            assertThrows(SegmentFormatException.class, () -> SegmentCodec.decode(changed),
                    "segment byte " + offset);
        }
    }

    @Test
    void rejectsTrailingBytesAndConcatenatedObjects() {
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(concat(GOLDEN, new byte[] {0})));
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(concat(GOLDEN, GOLDEN)));
    }

    @Test
    void expectedIdentifierIsCheckedAgainstWholeImageAfterValidation() {
        assertCode(SegmentFormatException.Code.IDENTIFIER_MISMATCH,
                () -> SegmentCodec.decode(GOLDEN, new SegmentId("0".repeat(64))));
        assertCode(SegmentFormatException.Code.IDENTIFIER_MISMATCH,
                () -> SegmentCodec.decode(GOLDEN, new SegmentId(GOLDEN_FOOTER)));
        var other = new ImmutableSegment(new DatabaseId("other"), SINGLE.records());
        var otherId = SegmentCodec.decode(SegmentCodec.encode(other)).id();
        assertCode(SegmentFormatException.Code.IDENTIFIER_MISMATCH,
                () -> SegmentCodec.decode(GOLDEN, otherId));
        var corrupt = GOLDEN.clone();
        corrupt[BODY_START + 20] ^= 1;
        assertThrows(SegmentFormatException.class,
                () -> SegmentCodec.decode(corrupt, new SegmentId(GOLDEN_ID)));
    }

    @Test
    void rejectsUnsupportedOuterNestedAndFrameVersionsWithValidIntegrityFields() {
        var outer = GOLDEN.clone();
        ByteBuffer.wrap(outer).putInt(8, 2);
        resignHeaderAndFooter(outer);
        assertCode(SegmentFormatException.Code.UNSUPPORTED_VERSION, () -> SegmentCodec.decode(outer));
        var nested = GOLDEN.clone();
        ByteBuffer.wrap(nested).putInt(24, 2);
        replaceChecksum(nested, 16, 20);
        resignHeaderAndFooter(nested);
        assertCode(SegmentFormatException.Code.UNSUPPORTED_VERSION, () -> SegmentCodec.decode(nested));
        var frame = GOLDEN.clone();
        ByteBuffer.wrap(frame).putInt(BODY_START + 4, 2);
        replaceChecksum(frame, BODY_START, 12);
        resignFooter(frame);
        assertCode(SegmentFormatException.Code.UNSUPPORTED_VERSION, () -> SegmentCodec.decode(frame));
    }

    @Test
    void rejectsUnknownMagicEvenWhenChecksumsAndFooterAreValid() {
        var outer = GOLDEN.clone();
        outer[0] ^= 1;
        resignHeaderAndFooter(outer);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(outer));
        var nested = GOLDEN.clone();
        nested[16] ^= 1;
        replaceChecksum(nested, 16, 20);
        resignHeaderAndFooter(nested);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(nested));
        var frame = GOLDEN.clone();
        frame[BODY_START] ^= 1;
        replaceChecksum(frame, BODY_START, 12);
        resignFooter(frame);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(frame));
    }

    @Test
    void outerDigestDoesNotReplaceHeaderAndNestedJournalChecksums() {
        var outerHeader = GOLDEN.clone();
        outerHeader[BODY_START - 1] ^= 1;
        resignFooter(outerHeader);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(outerHeader));
        var nestedHeader = GOLDEN.clone();
        nestedHeader[39] ^= 1;
        resignHeaderAndFooter(nestedHeader);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(nestedHeader));
        var prefix = GOLDEN.clone();
        prefix[BODY_START + 12] ^= 1;
        resignFooter(prefix);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(prefix));
        var payload = GOLDEN.clone();
        payload[BODY_START + 20] ^= 1;
        resignFooter(payload);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(payload));
    }

    @Test
    void completeEnvelopeCannotHideAPartialNestedHeaderOrTransactionFrame() {
        byte[] nested = nestedHeader("db");
        byte[] frame = rawRecord(1, "r");
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(image(Arrays.copyOf(nested, nested.length - 1),
                        frame, 1, 1, 1, frame.length)));
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(image(nested,
                        Arrays.copyOf(frame, frame.length - 1), 1, 1, 1, frame.length - 1)));
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(image(concat(nested, new byte[] {0}),
                        frame, 1, 1, 1, frame.length)));
    }

    @Test
    void rejectsMalformedPayloadEvenWhenAllEnvelopeAndFrameChecksumsAreValid() {
        var operationCount = GOLDEN.clone();
        ByteBuffer.wrap(operationCount).putInt(BODY_START + 30, 0);
        resignPayloadAndFooter(operationCount);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(operationCount));
        var operationTag = GOLDEN.clone();
        operationTag[BODY_START + 34] = 3;
        resignPayloadAndFooter(operationTag);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(operationTag));
        var invalidSequence = GOLDEN.clone();
        ByteBuffer.wrap(invalidSequence).putLong(BODY_START + 16, 0);
        resignPayloadAndFooter(invalidSequence);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(invalidSequence));
        var flags = GOLDEN.clone();
        flags[BODY_START + 41] = 4;
        resignPayloadAndFooter(flags);
        assertCode(SegmentFormatException.Code.CORRUPT, () -> SegmentCodec.decode(flags));
    }

    @Test
    void detectsNoncontiguousAndDuplicateRequestHistoriesInsideValidObjects() {
        for (var records : List.of(
                List.of(rawRecord(7, "a"), rawRecord(9, "b")),
                List.of(rawRecord(7, "a"), rawRecord(7, "b")),
                List.of(rawRecord(7, "a"), rawRecord(8, "a")))) {
            byte[] body = concat(records.toArray(byte[][]::new));
            assertCode(SegmentFormatException.Code.INVALID_HISTORY,
                    () -> SegmentCodec.decode(image(nestedHeader("db"), body, 7, 8, 2, body.length)));
        }
    }

    @Test
    void metadataMustAgreeWithDecodedCountAndSequenceRange() {
        byte[] body = concat(rawRecord(7, "a"), rawRecord(8, "b"));
        for (long[] metadata : new long[][] {
                {7, 8, 1}, {7, 8, 3}, {6, 7, 2}, {8, 9, 2}, {7, 9, 2},
                {0, 1, 2}, {8, 7, 2}, {7, 8, 0}, {7, 8, -1}}) {
            assertCode(SegmentFormatException.Code.INVALID_HISTORY,
                    () -> SegmentCodec.decode(image(nestedHeader("db"), body,
                            metadata[0], metadata[1], (int) metadata[2], body.length)));
        }
    }

    @Test
    void rejectsUnrepresentableAndOverLimitMetadataWithoutAllocatingBodies() {
        byte[] body = rawRecord(1, "r");
        assertCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                () -> SegmentCodec.decode(image(nestedHeader("db"), body, 1, 1,
                        MAX_RECORDS + 1, body.length)));
        for (long bodyBytes : new long[] {MAX_SEGMENT_BYTES, Integer.MAX_VALUE, Long.MAX_VALUE}) {
            assertCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                    () -> SegmentCodec.decode(image(nestedHeader("db"), body, 1, 1, 1, bodyBytes)));
        }
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(image(nestedHeader("db"), body, 1, 1, 1, -1)));
        var nestedLength = GOLDEN.clone();
        ByteBuffer.wrap(nestedLength).putInt(12, Integer.MAX_VALUE);
        assertThrows(SegmentFormatException.class, () -> SegmentCodec.decode(nestedLength));
        ByteBuffer.wrap(nestedLength).putInt(12, -1);
        assertThrows(SegmentFormatException.class, () -> SegmentCodec.decode(nestedLength));
    }

    @Test
    void boundedWholeSizeAcceptsTheLargestDeclaredSizeAsIncomplete() {
        byte[] body = rawRecord(1, "r");
        int overhead = 48 + nestedHeader("db").length + 32;
        assertCode(SegmentFormatException.Code.INCOMPLETE,
                () -> SegmentCodec.decode(image(nestedHeader("db"), body,
                        1, 1, 1, MAX_SEGMENT_BYTES - overhead)));
        assertCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                () -> SegmentCodec.decode(image(nestedHeader("db"), body,
                        1, 1, 1, MAX_SEGMENT_BYTES - overhead + 1L)));
    }

    @Test
    void completeBodyMustContainExactlyTheDeclaredFrameBytes() {
        byte[] body = rawRecord(1, "r");
        var extra = concat(body, new byte[] {0});
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(image(nestedHeader("db"), extra, 1, 1, 1, extra.length)));
        assertCode(SegmentFormatException.Code.CORRUPT,
                () -> SegmentCodec.decode(image(nestedHeader("db"), new byte[0], 1, 1, 1, 0)));
    }

    @Test
    void supportsMaximumRecordCountAndRejectsTheNextRecord() {
        assertEquals(MAX_RECORDS, SegmentCodec.MAX_RECORDS);
        assertEquals(MAX_SEGMENT_BYTES, SegmentCodec.MAX_SEGMENT_BYTES);
        var records = new ArrayList<JournalRecord>(MAX_RECORDS + 1);
        for (int i = 1; i <= MAX_RECORDS; i++) {
            records.add(record(i, "r" + i));
        }
        var maximum = new ImmutableSegment(DATABASE, records);
        byte[] encoded = SegmentCodec.encode(maximum);
        assertTrue(encoded.length < MAX_SEGMENT_BYTES);
        assertEquals(maximum, SegmentCodec.decode(encoded).segment());
        records.add(record(MAX_RECORDS + 1L, "overflow"));
        assertCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                () -> SegmentCodec.encode(new ImmutableSegment(DATABASE, records)));
    }

    @Test
    void encodingAppliesNestedJournalIdentityAndOperationLimits() {
        var tooLong = "x".repeat(32_769);
        assertCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                () -> SegmentCodec.encode(new ImmutableSegment(new DatabaseId(tooLong), SINGLE.records())));
        assertCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                () -> SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(record(1, tooLong)))));
        assertCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                () -> SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(record(1, "r",
                        new WriteOperation.Put(new EntityId(tooLong), new ValidTimeInterval(null, null), 0))))));
        var operations = java.util.Collections.nCopies(65_537,
                FIRST.transaction().operations().getFirst());
        assertCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                () -> SegmentCodec.encode(new ImmutableSegment(DATABASE,
                        List.of(new JournalRecord(1, new Transaction(new RequestId("r"), operations))))));
    }

    @Test
    void aggregateEncodeLimitIsCheckedBeforeMaterializingIndividuallyValidFrames() {
        var operation = new WriteOperation.Put(new EntityId("x".repeat(32_768)),
                new ValidTimeInterval(null, null), 0);
        // Each frame contains 127 large identities, remaining below the journal's
        // 8 MiB payload limit. The five frames exceed the segment's 32 MiB limit.
        // Shared strings/operations keep the test input small; encoding must
        // reject the aggregate during preflight without materializing the frames.
        var operations = java.util.Collections.<WriteOperation>nCopies(127, operation);
        var records = new ArrayList<JournalRecord>();
        for (int i = 1; i <= 5; i++) {
            records.add(new JournalRecord(i, new Transaction(new RequestId("r" + i), operations)));
        }
        var oversized = new ImmutableSegment(DATABASE, records);
        assertCode(SegmentFormatException.Code.LIMIT_EXCEEDED, () -> SegmentCodec.encode(oversized));
    }

    @Test
    void rejectsNullInputsAndNullExpectedIdentifier() {
        assertThrows(NullPointerException.class, () -> SegmentCodec.encode(null));
        assertThrows(NullPointerException.class, () -> SegmentCodec.decode(null));
        assertThrows(NullPointerException.class, () -> SegmentCodec.decode(GOLDEN, null));
    }

    private static JournalRecord record(long sequence, String request, WriteOperation... operations) {
        if (operations.length == 0) {
            operations = new WriteOperation[] {
                new WriteOperation.Retract(ENTITY, new ValidTimeInterval(null, null))};
        }
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(operations)));
    }

    private static void assertCode(SegmentFormatException.Code code, Runnable action) {
        assertEquals(code, assertThrows(SegmentFormatException.class, action::run).code());
    }

    // These writers intentionally do not call any production encoding routine.
    private static byte[] rawRecord(long sequence, String request) {
        byte[] payload = bytes(out -> {
            out.writeLong(sequence);
            writeString(out, request);
            out.writeInt(1);
            out.writeByte(2);
            writeString(out, "e");
            out.writeByte(0);
        });
        byte[] prefix = bytes(out -> {
            out.writeInt(0x4d54584e);
            out.writeInt(1);
            out.writeInt(payload.length);
        });
        return concat(prefix, integer(checksum(prefix, 0, prefix.length)), payload,
                integer(checksum(payload, 0, payload.length)));
    }

    private static byte[] nestedHeader(String database) {
        byte[] prefix = bytes(out -> {
            out.writeLong(0x4d52444e4a4e4c00L);
            out.writeInt(1);
            writeString(out, database);
        });
        return concat(prefix, integer(checksum(prefix, 0, prefix.length)));
    }

    private static byte[] image(byte[] nested, byte[] body, long first, long last,
            int count, long bodyBytes) {
        byte[] prefix = bytes(out -> {
            out.writeLong(0x4d52444e53454700L);
            out.writeInt(1);
            out.writeInt(nested.length);
            out.write(nested);
            out.writeLong(first);
            out.writeLong(last);
            out.writeInt(count);
            out.writeLong(bodyBytes);
        });
        byte[] content = concat(prefix, integer(checksum(prefix, 0, prefix.length)), body);
        return concat(content, digest(content));
    }

    private static void resignHeaderAndFooter(byte[] image) {
        int checksumOffset = 44 + ByteBuffer.wrap(image).getInt(12);
        replaceChecksum(image, 0, checksumOffset);
        resignFooter(image);
    }

    private static void resignPayloadAndFooter(byte[] image) {
        int payloadBytes = ByteBuffer.wrap(image).getInt(BODY_START + 8);
        replaceChecksum(image, BODY_START + 16, payloadBytes);
        resignFooter(image);
    }

    private static void resignFooter(byte[] image) {
        byte[] content = Arrays.copyOf(image, image.length - 32);
        System.arraycopy(digest(content), 0, image, content.length, 32);
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        var checksum = new CRC32C();
        checksum.update(bytes, offset, length);
        return (int) checksum.getValue();
    }

    private static void replaceChecksum(byte[] bytes, int offset, int length) {
        ByteBuffer.wrap(bytes).putInt(offset + length, checksum(bytes, offset, length));
    }

    private static byte[] integer(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeInt(value.length());
        for (int i = 0; i < value.length(); i++) {
            out.writeChar(value.charAt(i));
        }
    }

    private static byte[] bytes(Writer writer) {
        var output = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(output)) {
            writer.write(out);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return output.toByteArray();
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
