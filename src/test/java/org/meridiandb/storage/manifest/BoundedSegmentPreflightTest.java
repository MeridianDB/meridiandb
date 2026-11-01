// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class BoundedSegmentPreflightTest {
    private static final DatabaseId DATABASE = new DatabaseId("db");
    private static final int JOURNAL_HEADER_BYTES = 24;
    private static final int SEGMENT_HEADER_BYTES = 72;
    private static final int FRAME_BYTES = 56;
    private static final int FOOTER_BYTES = 32;
    private static final int SEGMENT_BYTES = 160;
    private static final int MAX_RECORDS = 65_536;

    @ParameterizedTest @ValueSource(strings = {"payload", "footer"})
    void oneReservedRecordRejectsAValidMaximumRangeHeaderBeforeItsBodyTrap(String trap) {
        byte[] image = segment(1, MAX_RECORDS, MAX_RECORDS, frame(1, trap.equals("payload")));
        if (trap.equals("footer")) { image[image.length - 1] ^= 1; }
        assertHeader(image, 1, MAX_RECORDS, MAX_RECORDS);
        assertBodyTrap(image, trap);
        var input = bundle(image);
        var calls = new AtomicInteger();
        var limits = limits(SEGMENT_BYTES, SEGMENT_BYTES, 1);
        assertEquals(1, input.manifest().journalPrefix().sequence());
        assertEquals(1, limits.maxRecords());
        var bounded = assertThrows(SegmentFormatException.class, () -> HistoryCompactor.compact(input.manifest(), id -> {
            calls.incrementAndGet();
            return input.images().get(id);
        }, limits));
        assertEquals(SegmentFormatException.Code.INVALID_HISTORY, bounded.code());
        assertTrue(bounded.getMessage().contains("expected sequence range"));
        assertEquals(1, calls.get(), "manifest count is admitted; unreserved header count is refused at the first image");
        assertSegmentCode(trap.equals("payload") ? SegmentFormatException.Code.LIMIT_EXCEEDED : SegmentFormatException.Code.CORRUPT,
                () -> CheckpointVerifier.verify(input.manifest(), input.images()::get));
    }

    @Test void equalCountShiftedValidRangeHasBoundedHeaderFailureAndLegacyManifestFailure() throws IOException {
        byte[] image = segment(2, 2, 1, frame(2, false));
        assertHeader(image, 2, 2, 1);
        assertFooter(image);
        var input = bundle(image);
        assertEquals(List.of(record(2)), SegmentCodec.decode(image, new SegmentId(hash(image))).segment().records());
        assertSegmentCode(SegmentFormatException.Code.INVALID_HISTORY,
                () -> HistoryCompactor.compact(input.manifest(), input.images()::get, limits(SEGMENT_BYTES, SEGMENT_BYTES, 1)));
        assertManifestCode(ManifestFormatException.Code.INVALID_CHECKPOINT,
                () -> CheckpointVerifier.verify(input.manifest(), input.images()::get));
    }

    @Test void equalCountShiftedHeaderAlsoPrecedesNestedPayloadDecoding() {
        byte[] image = segment(2, 2, 1, frame(2, true));
        assertHeader(image, 2, 2, 1);
        assertBodyTrap(image, "payload");
        var input = bundle(image);
        assertSegmentCode(SegmentFormatException.Code.INVALID_HISTORY,
                () -> HistoryCompactor.compact(input.manifest(), input.images()::get, limits(SEGMENT_BYTES, SEGMENT_BYTES, 1)));
        assertSegmentCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                () -> CheckpointVerifier.verify(input.manifest(), input.images()::get));
    }

    @ParameterizedTest @ValueSource(strings = {"payload", "footer"})
    void matchingRangePreservesTheSameNestedAndFooterFailuresAsRawVerification(String trap) {
        byte[] image = segment(1, 1, 1, frame(1, trap.equals("payload")));
        if (trap.equals("footer")) { image[image.length - 1] ^= 1; }
        assertHeader(image, 1, 1, 1);
        assertBodyTrap(image, trap);
        var input = bundle(image);
        var expected = trap.equals("payload") ? SegmentFormatException.Code.LIMIT_EXCEEDED : SegmentFormatException.Code.CORRUPT;
        assertSegmentCode(expected,
                () -> HistoryCompactor.compact(input.manifest(), input.images()::get, limits(SEGMENT_BYTES, SEGMENT_BYTES, 1)));
        assertSegmentCode(expected, () -> CheckpointVerifier.verify(input.manifest(), input.images()::get));
    }

    @Test void matchingValidRangesPreserveExactRecordsPrefixAndByteAccounting() throws IOException {
        var input = bundle(segment(1, 1, 1, frame(1, false)), segment(2, 2, 1, frame(2, false)), segment(3, 3, 1, frame(3, false)));
        byte[] first = input.images().get(input.manifest().segments().getFirst().id());
        // Independently calculated using Python struct/hashlib and a bitwise Castagnoli CRC.
        assertEquals(0xa9cf7c87, ByteBuffer.wrap(first).getInt(36));
        assertEquals(0x8d8c9c95, ByteBuffer.wrap(first).getInt(68));
        assertEquals("4c3b07745ebd8f9aac3554bc2bfe26a8d530805db6a894ae167bab940c9d4c65", hash(first));
        var raw = new ArrayList<JournalRecord>();
        var bounded = new ArrayList<JournalRecord>();
        CheckpointVerifier.verify(input.manifest(), input.images()::get, raw::add);
        CheckpointVerifier.verifyBounded(input.manifest(), input.images()::get, bounded::add);
        assertEquals(List.of(record(1), record(2), record(3)), raw);
        assertEquals(raw, bounded);
        for (var record : raw) { assertArrayEquals(frame(Math.toIntExact(record.sequence()), false), JournalCodec.encodeRecord(record)); }
        var reads = new ArrayList<SegmentId>();
        var plan = HistoryCompactor.compact(input.manifest(), id -> {
            reads.add(id);
            return input.images().get(id);
        }, limits(480, 272, 3));
        assertEquals(input.manifest().segments().stream().map(SegmentReference::id).toList(), reads);
        assertEquals(480, plan.inputBytes());
        assertEquals(272, plan.outputBytes());
        assertEquals(1, plan.segments().size());
        assertEquals(raw, plan.segments().getFirst().records());
        assertEquals(input.manifest().journalPrefix(), plan.replacementManifest().journalPrefix());
        assertEquals(8, plan.replacementManifest().generation());
        assertThrows(UnsupportedOperationException.class, () -> plan.segments().clear());
    }

    @Test void rangeIsTrustedOnlyAfterTheIndependentHeaderChecksumPasses() {
        byte[] image = segment(2, 2, 1, frame(2, true));
        image[SEGMENT_HEADER_BYTES - 1] ^= 1;
        replaceFooter(image);
        assertNotEquals(crc(image, 0, SEGMENT_HEADER_BYTES - Integer.BYTES),
                ByteBuffer.wrap(image).getInt(SEGMENT_HEADER_BYTES - Integer.BYTES));
        var input = bundle(image);
        assertSegmentCode(SegmentFormatException.Code.CORRUPT,
                () -> HistoryCompactor.compact(input.manifest(), input.images()::get, limits(SEGMENT_BYTES, SEGMENT_BYTES, 1)));
        assertSegmentCode(SegmentFormatException.Code.CORRUPT, () -> CheckpointVerifier.verify(input.manifest(), input.images()::get));
    }

    @Test void declaredRecordBudgetStillRejectsBeforeAnyDependencyRead() {
        var input = bundle(segment(1, MAX_RECORDS, MAX_RECORDS, frame(1, true)));
        var reads = new AtomicInteger();
        assertManifestCode(ManifestFormatException.Code.LIMIT_EXCEEDED,
                () -> HistoryCompactor.compact(input.manifest(), ignored -> {
                    reads.incrementAndGet();
                    throw new AssertionError("Manifest record budget must be checked before source access");
                }, limits(SEGMENT_BYTES, SEGMENT_BYTES, 0)));
        assertEquals(0, reads.get());
    }

    @Test void lateRangeTrapStopsAfterEarlierPrivateRecordsWithoutReadingTheNextDependencyOrReturningAPlan() {
        byte[] late = segment(2, MAX_RECORDS, MAX_RECORDS - 1, frame(2, true));
        assertHeader(late, 2, MAX_RECORDS, MAX_RECORDS - 1);
        var input = bundle(segment(1, 1, 1, frame(1, false)), late, segment(3, 3, 1, frame(3, false)));
        var calls = new ArrayList<SegmentId>();
        var escaped = new AtomicReference<HistoryCompactor.Plan>();
        assertSegmentCode(SegmentFormatException.Code.INVALID_HISTORY, () -> escaped.set(HistoryCompactor.compact(input.manifest(), id -> {
            calls.add(id);
            return input.images().get(id);
        }, limits(480, 480, 3))));
        assertEquals(input.manifest().segments().subList(0, 2).stream().map(SegmentReference::id).toList(), calls);
        assertNull(escaped.get());
        assertSegmentCode(SegmentFormatException.Code.LIMIT_EXCEEDED,
                () -> CheckpointVerifier.verify(input.manifest(), input.images()::get));
    }

    @Test void sourceIoFailureStillPropagatesUnchangedAndStopsBeforeLaterReferences() {
        var input = bundle(segment(1, 1, 1, frame(1, false)), segment(2, 2, 1, frame(2, false)), segment(3, 3, 1, frame(3, false)));
        var calls = new ArrayList<SegmentId>();
        var expected = new IOException("late dependency unavailable");
        var failedId = input.manifest().segments().get(1).id();
        assertSame(expected, assertThrows(IOException.class, () -> HistoryCompactor.compact(input.manifest(), id -> {
            calls.add(id);
            if (id.equals(failedId)) { throw expected; }
            return input.images().get(id);
        }, limits(480, 480, 3))));
        assertEquals(input.manifest().segments().subList(0, 2).stream().map(SegmentReference::id).toList(), calls);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "length"})
    void missingAndLengthGuardsStillPrecedeSegmentDecodingForBothPaths(String failure) {
        byte[] image = segment(1, MAX_RECORDS, MAX_RECORDS, frame(1, true));
        var input = bundle(image);
        CheckpointVerifier.SegmentSource source = ignored -> failure.equals("missing") ? null : Arrays.copyOf(image, image.length - 1);
        assertManifestCode(ManifestFormatException.Code.INVALID_CHECKPOINT,
                () -> HistoryCompactor.compact(input.manifest(), source, limits(SEGMENT_BYTES, SEGMENT_BYTES, 1)));
        assertManifestCode(ManifestFormatException.Code.INVALID_CHECKPOINT, () -> CheckpointVerifier.verify(input.manifest(), source));
    }

    @Test void matchingRangeStillChecksWholeObjectIdentityAfterSuccessfulDecoding() {
        byte[] image = segment(1, 1, 1, frame(1, false));
        var input = bundle(image);
        var wrongId = new SegmentId("0".repeat(64));
        assertNotEquals(wrongId.value(), hash(image));
        var manifest = new CheckpointManifest(DATABASE, 7, input.manifest().journalPrefix(),
                List.of(new SegmentReference(wrongId, image.length, 1, 1)));
        assertSegmentCode(SegmentFormatException.Code.IDENTIFIER_MISMATCH,
                () -> HistoryCompactor.compact(manifest, ignored -> image, limits(SEGMENT_BYTES, SEGMENT_BYTES, 1)));
        assertSegmentCode(SegmentFormatException.Code.IDENTIFIER_MISMATCH, () -> CheckpointVerifier.verify(manifest, ignored -> image));
    }

    @Test void genesisNeedsNoSegmentReadOrRecordBudgetAndPreservesTheExactHeaderPrefix() {
        var input = bundle();
        CheckpointVerifier.SegmentSource unused = ignored -> { throw new AssertionError("No genesis dependency"); };
        assertDoesNotThrow(() -> CheckpointVerifier.verifyBounded(input.manifest(), unused,
                ignored -> { throw new AssertionError("No genesis record"); }));
        var plan = assertDoesNotThrow(() -> HistoryCompactor.compact(input.manifest(), unused,
                new HistoryCompactor.Limits(1, 0, 0, 0)));
        assertEquals(8, plan.replacementManifest().generation());
        assertEquals(new JournalPrefix(0, JOURNAL_HEADER_BYTES, hash(lineage())), plan.replacementManifest().journalPrefix());
        assertEquals(List.of(), plan.segments());
        assertEquals(List.of(), plan.replacementManifest().segments());
        assertEquals(0, plan.inputBytes());
        assertEquals(0, plan.outputBytes());
    }

    private static HistoryCompactor.Limits limits(long inputBytes, long outputBytes, long records) {
        return new HistoryCompactor.Limits(SegmentCodec.MAX_SEGMENT_BYTES, inputBytes, outputBytes, records);
    }

    private static Bundle bundle(byte[]... images) {
        var references = new ArrayList<SegmentReference>();
        var objects = new LinkedHashMap<SegmentId, byte[]>();
        var journal = new ByteArrayOutputStream();
        journal.writeBytes(lineage());
        for (int index = 0; index < images.length; index++) {
            byte[] image = images[index];
            var id = new SegmentId(hash(image));
            references.add(new SegmentReference(id, image.length, index + 1, index + 1));
            objects.put(id, image);
            journal.writeBytes(frame(index + 1, false));
        }
        byte[] prefix = journal.toByteArray();
        var manifest = new CheckpointManifest(DATABASE, 7, new JournalPrefix(images.length, prefix.length, hash(prefix)), references);
        return new Bundle(manifest, objects);
    }

    private record Bundle(CheckpointManifest manifest, Map<SegmentId, byte[]> images) { }

    // These v1 fixtures are laid out and signed independently of SegmentCodec and JournalCodec.
    // "db" contributes 24 lineage bytes, a small Put frame contributes 56, and the segment totals 160.
    private static byte[] lineage() {
        var bytes = ByteBuffer.allocate(JOURNAL_HEADER_BYTES);
        bytes.putLong(0x4d52444e4a4e4c00L).putInt(1).putInt(2).putChar('d').putChar('b');
        bytes.putInt(crc(bytes.array(), 0, 20));
        return bytes.array();
    }

    private static byte[] frame(int sequence, boolean payloadTrap) {
        var bytes = ByteBuffer.allocate(FRAME_BYTES);
        bytes.putInt(0x4d54584e).putInt(1).putInt(36);
        bytes.putInt(crc(bytes.array(), 0, 12));
        bytes.putLong(sequence).putInt(2).putChar('r').putChar((char) ('0' + sequence));
        bytes.putInt(payloadTrap ? 65_537 : 1);
        bytes.put((byte) 1).putInt(1).putChar('e').put((byte) 0).putLong(sequence * 10L);
        bytes.putInt(crc(bytes.array(), 16, 36));
        return bytes.array();
    }

    private static byte[] segment(long first, long last, int count, byte[] body) {
        var bytes = ByteBuffer.allocate(SEGMENT_HEADER_BYTES + body.length + FOOTER_BYTES);
        bytes.putLong(0x4d52444e53454700L).putInt(1).putInt(JOURNAL_HEADER_BYTES).put(lineage());
        bytes.putLong(first).putLong(last).putInt(count).putLong(body.length);
        bytes.putInt(crc(bytes.array(), 0, SEGMENT_HEADER_BYTES - Integer.BYTES));
        bytes.put(body);
        bytes.put(digest(bytes.array(), 0, bytes.position()));
        return bytes.array();
    }

    private static void assertHeader(byte[] image, long first, long last, int count) {
        assertEquals(SEGMENT_BYTES, image.length);
        var header = ByteBuffer.wrap(image);
        assertEquals(0x4d52444e53454700L, header.getLong());
        assertEquals(1, header.getInt());
        assertEquals(JOURNAL_HEADER_BYTES, header.getInt());
        assertArrayEquals(lineage(), Arrays.copyOfRange(image, 16, 40));
        assertEquals(0x4d52444e4a4e4c00L, header.getLong(16));
        assertEquals(crc(image, 16, 20), header.getInt(36));
        assertEquals(first, header.getLong(40));
        assertEquals(last, header.getLong(48));
        assertEquals(count, header.getInt(56));
        assertTrue(count > 0 && count <= MAX_RECORDS);
        assertEquals((long) count, last - first + 1);
        assertEquals(FRAME_BYTES, header.getLong(60));
        assertEquals(crc(image, 0, 68), header.getInt(68));
    }

    private static void assertBodyTrap(byte[] image, String trap) {
        var frame = ByteBuffer.wrap(image);
        assertEquals(0x4d54584e, frame.getInt(SEGMENT_HEADER_BYTES));
        assertEquals(1, frame.getInt(SEGMENT_HEADER_BYTES + 4));
        assertEquals(36, frame.getInt(SEGMENT_HEADER_BYTES + 8));
        assertEquals(crc(image, SEGMENT_HEADER_BYTES, 12), frame.getInt(SEGMENT_HEADER_BYTES + 12));
        assertEquals(crc(image, SEGMENT_HEADER_BYTES + 16, 36), frame.getInt(SEGMENT_HEADER_BYTES + 52));
        if (trap.equals("payload")) {
            assertEquals(65_537, frame.getInt(SEGMENT_HEADER_BYTES + 32));
            assertFooter(image);
        } else {
            assertEquals(1, frame.getInt(SEGMENT_HEADER_BYTES + 32));
            assertFalseFooter(image);
        }
    }

    private static void assertFooter(byte[] image) {
        assertArrayEquals(digest(image, 0, image.length - FOOTER_BYTES),
                Arrays.copyOfRange(image, image.length - FOOTER_BYTES, image.length));
    }

    private static void assertFalseFooter(byte[] image) {
        assertFalse(MessageDigest.isEqual(digest(image, 0, image.length - FOOTER_BYTES),
                Arrays.copyOfRange(image, image.length - FOOTER_BYTES, image.length)));
    }

    private static void replaceFooter(byte[] image) {
        System.arraycopy(digest(image, 0, image.length - FOOTER_BYTES), 0, image, image.length - FOOTER_BYTES, FOOTER_BYTES);
    }

    private static int crc(byte[] image, int offset, int length) {
        var crc = new CRC32C();
        crc.update(image, offset, length);
        return (int) crc.getValue();
    }

    private static byte[] digest(byte[] image, int offset, int length) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(image, offset, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static String hash(byte[] image) { return HexFormat.of().formatHex(digest(image, 0, image.length)); }

    private static JournalRecord record(int sequence) {
        return new JournalRecord(sequence, new Transaction(new RequestId("r" + sequence), List.of(
                new WriteOperation.Put(new EntityId("e"), new ValidTimeInterval(null, null), sequence * 10L))));
    }

    @FunctionalInterface private interface Work { void run() throws IOException; }

    private static void assertSegmentCode(SegmentFormatException.Code expected, Work work) {
        assertEquals(expected, assertThrows(SegmentFormatException.class, work::run).code());
    }

    private static void assertManifestCode(ManifestFormatException.Code expected, Work work) {
        assertEquals(expected, assertThrows(ManifestFormatException.class, work::run).code());
    }
}
