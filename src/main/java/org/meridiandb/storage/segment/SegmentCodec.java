// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.CRC32C;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalFormatException;
import org.meridiandb.storage.journal.JournalRecord;

/**
 * Pure experimental v1 history segment codec. Whole objects are validated before returning records.
 * No file I/O, publication, checkpoint or durability guarantee. Do not mutate input during decoding.
 */
public final class SegmentCodec {
    public static final int MAX_RECORDS = 65_536;
    public static final int MAX_SEGMENT_BYTES = 32 * 1024 * 1024;
    private static final long MAGIC = 0x4d52444e53454700L;
    private static final int VERSION = 1;
    private static final int HEADER_OVERHEAD = 48;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_LINEAGE_BYTES = 20 + 2 * JournalCodec.MAX_ID_CODE_UNITS;

    public record DecodedSegment(SegmentId id, ImmutableSegment segment) {
        public DecodedSegment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(segment, "segment");
        }
    }

    private SegmentCodec() { }

    /** Deterministic bytes owned by the caller; the segment retains no reference to them. */
    public static byte[] encode(ImmutableSegment segment) {
        Objects.requireNonNull(segment, "segment");
        int count = segment.records().size();
        countLimit(count);
        final byte[] lineage;
        try {
            lineage = JournalCodec.encodeHeader(segment.databaseId());
        } catch (JournalFormatException failure) {
            throw nested(failure);
        }
        int headerBytes = HEADER_OVERHEAD + lineage.length;
        long bodyBytes = 0;
        try {
            for (var record : segment.records()) {
                bodyBytes += JournalCodec.encodedRecordBytes(record);
                sizeLimit(headerBytes, bodyBytes);
            }
        } catch (JournalFormatException failure) {
            throw nested(failure);
        }
        int totalBytes = headerBytes + (int) bodyBytes + DIGEST_BYTES;
        var output = ByteBuffer.allocate(totalBytes);
        output.putLong(MAGIC).putInt(VERSION).putInt(lineage.length).put(lineage);
        output.putLong(segment.firstSequence()).putLong(segment.lastSequence());
        output.putInt(count).putLong(bodyBytes);
        output.putInt(crc(output.array(), output.position()));
        for (var record : segment.records()) {
            output.put(JournalCodec.encodeRecord(record));
        }
        output.put(sha256(output.array(), output.position()));
        return output.array();
    }

    /** Decode exactly one complete object, retaining neither its input array nor a partial prefix. */
    public static DecodedSegment decode(byte[] image) {
        Objects.requireNonNull(image, "image");
        if (image.length > MAX_SEGMENT_BYTES) {
            throw failure(SegmentFormatException.Code.LIMIT_EXCEEDED, "Segment exceeds v1 byte limit");
        }
        need(image.length >= 16);
        var input = ByteBuffer.wrap(image);
        if (input.getLong() != MAGIC) {
            throw corrupt("Segment magic does not match");
        }
        if (input.getInt() != VERSION) {
            throw failure(SegmentFormatException.Code.UNSUPPORTED_VERSION, "Unsupported segment version");
        }
        int lineageBytes = input.getInt();
        if (lineageBytes > MAX_LINEAGE_BYTES) {
            throw failure(SegmentFormatException.Code.LIMIT_EXCEEDED, "Segment lineage exceeds v1 limit");
        }
        if (lineageBytes < 22 || (lineageBytes & 1) != 0) {
            throw corrupt("Invalid nested lineage header size");
        }
        int headerBytes = HEADER_OVERHEAD + lineageBytes;
        need(image.length >= headerBytes);
        var lineage = input.slice(16, lineageBytes);
        input.position(16 + lineageBytes);
        long first = input.getLong();
        long last = input.getLong();
        int count = input.getInt();
        long bodyBytes = input.getLong();
        int crcOffset = input.position();
        if (input.getInt() != crc(image, crcOffset)) {
            throw corrupt("Segment header checksum mismatch");
        }
        countLimit(count);
        if (first <= 0 || last < first || last - first + 1 != count) {
            throw history("Segment range does not match record count");
        }
        sizeLimit(headerBytes, bodyBytes);
        int bodyEnd = headerBytes + (int) bodyBytes;
        int totalBytes = bodyEnd + DIGEST_BYTES;
        need(image.length >= totalBytes);
        if (image.length != totalBytes) {
            throw corrupt("Trailing bytes after immutable segment");
        }
        if (!MessageDigest.isEqual(sha256(image, bodyEnd), Arrays.copyOfRange(image, bodyEnd, totalBytes))) {
            throw corrupt("Segment integrity footer mismatch");
        }
        var id = new SegmentId(HexFormat.of().formatHex(sha256(image, image.length)));
        var records = new ArrayList<JournalRecord>(count);
        try {
            var database = JournalCodec.readHeader(lineage);
            if (lineage.hasRemaining()) {
                throw corrupt("Trailing bytes inside nested lineage header");
            }
            input.limit(bodyEnd);
            for (int i = 0; i < count; i++) {
                records.add(JournalCodec.readRecord(input).orElseThrow(() -> corrupt("Missing declared transaction")));
            }
            if (input.hasRemaining()) {
                throw corrupt("Transaction body exceeds declared record count");
            }
            var segment = new ImmutableSegment(database, records);
            if (segment.firstSequence() != first || segment.lastSequence() != last) {
                throw history("Decoded transactions do not match segment range");
            }
            return new DecodedSegment(id, segment);
        } catch (JournalFormatException failure) {
            throw nested(failure);
        } catch (IllegalArgumentException failure) {
            throw history(failure.getMessage());
        }
    }

    /** Bind complete validated bytes to an independently supplied expected content identifier. */
    public static DecodedSegment decode(byte[] image, SegmentId expected) {
        Objects.requireNonNull(expected, "expected");
        var decoded = decode(image);
        if (!decoded.id().equals(expected)) {
            throw failure(SegmentFormatException.Code.IDENTIFIER_MISMATCH, "Segment content identifier does not match");
        }
        return decoded;
    }

    private static void countLimit(int count) {
        if (count > MAX_RECORDS) {
            throw failure(SegmentFormatException.Code.LIMIT_EXCEEDED, "Segment record count exceeds v1 limit");
        }
        if (count <= 0) {
            throw history("A segment requires at least one transaction");
        }
    }

    private static void sizeLimit(int headerBytes, long bodyBytes) {
        if (bodyBytes < 0) {
            throw corrupt("Negative segment body size");
        }
        if (bodyBytes > MAX_SEGMENT_BYTES - headerBytes - DIGEST_BYTES) {
            throw failure(SegmentFormatException.Code.LIMIT_EXCEEDED, "Segment exceeds v1 byte limit");
        }
    }

    private static int crc(byte[] image, int length) {
        var crc = new CRC32C();
        crc.update(image, 0, length);
        return (int) crc.getValue();
    }

    private static byte[] sha256(byte[] image, int length) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(image, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError("Java must provide SHA-256", unavailable);
        }
    }

    private static void need(boolean complete) {
        if (!complete) {
            throw failure(SegmentFormatException.Code.INCOMPLETE, "Incomplete immutable segment");
        }
    }

    private static SegmentFormatException nested(JournalFormatException failure) {
        var code = switch (failure.code()) {
            case UNSUPPORTED_VERSION -> SegmentFormatException.Code.UNSUPPORTED_VERSION;
            case LIMIT_EXCEEDED -> SegmentFormatException.Code.LIMIT_EXCEEDED;
            case INVALID_HISTORY -> SegmentFormatException.Code.INVALID_HISTORY;
            case INCOMPLETE_HEADER, INCOMPLETE_TAIL, CORRUPT -> SegmentFormatException.Code.CORRUPT;
        };
        return failure(code, "Invalid nested journal bytes: " + failure.getMessage());
    }

    private static SegmentFormatException history(String message) {
        return failure(SegmentFormatException.Code.INVALID_HISTORY, message);
    }

    private static SegmentFormatException corrupt(String message) {
        return failure(SegmentFormatException.Code.CORRUPT, message);
    }

    private static SegmentFormatException failure(SegmentFormatException.Code code, String message) {
        return new SegmentFormatException(code, message);
    }
}
