// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

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
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;

/** Pure bounded v1 manifest codec. Decoding declarations does not verify their dependencies. */
public final class ManifestCodec {
    public static final int MAX_SEGMENTS = 4096;
    public static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_LINEAGE_BYTES = 20 + 2 * JournalCodec.MAX_ID_CODE_UNITS;
    public static final long MAX_PREFIX_BYTES = (long) MAX_SEGMENTS * SegmentCodec.MAX_SEGMENT_BYTES + MAX_LINEAGE_BYTES;
    private static final long MAGIC = 0x4d52444e4d414e00L;
    private static final int HEADER_OVERHEAD = 80;
    private static final int REFERENCE_BYTES = 56;
    private static final int DIGEST_BYTES = 32;

    public record DecodedManifest(ManifestId id, CheckpointManifest manifest) {
        public DecodedManifest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(manifest, "manifest");
        }
    }

    private ManifestCodec() { }

    /** Fresh caller-owned bytes; rejects unsupported resource declarations before output allocation. */
    public static byte[] encode(CheckpointManifest manifest) {
        byte[] lineage = checkedLineage(manifest);
        var prefix = manifest.journalPrefix();
        int length = HEADER_OVERHEAD + lineage.length + manifest.segments().size() * REFERENCE_BYTES + DIGEST_BYTES;
        var output = ByteBuffer.allocate(length);
        output.putLong(MAGIC).putInt(1).putInt(lineage.length).put(lineage);
        output.putLong(manifest.generation()).putLong(prefix.sequence()).putLong(prefix.byteLength());
        output.put(HexFormat.of().parseHex(prefix.sha256())).putInt(manifest.segments().size());
        output.putInt(crc(output.array(), output.position()));
        for (var reference : manifest.segments()) {
            output.put(HexFormat.of().parseHex(reference.id().value()));
            output.putLong(reference.byteLength()).putLong(reference.firstSequence()).putLong(reference.lastSequence());
        }
        output.put(sha256(output.array(), output.position()));
        return output.array();
    }

    /** Decode one complete manifest; retain no input or partial value. Keep input unchanged during decoding. */
    public static DecodedManifest decode(byte[] image) {
        Objects.requireNonNull(image, "image");
        if (image.length > MAX_MANIFEST_BYTES) {
            throw failure(ManifestFormatException.Code.LIMIT_EXCEEDED, "Manifest exceeds v1 byte limit");
        }
        need(image.length >= 16);
        var input = ByteBuffer.wrap(image);
        if (input.getLong() != MAGIC) {
            throw failure(ManifestFormatException.Code.CORRUPT, "Manifest magic does not match");
        }
        if (input.getInt() != 1) {
            throw failure(ManifestFormatException.Code.UNSUPPORTED_VERSION, "Unsupported manifest version");
        }
        int lineageBytes = input.getInt();
        if (lineageBytes > MAX_LINEAGE_BYTES) {
            throw failure(ManifestFormatException.Code.LIMIT_EXCEEDED, "Manifest lineage exceeds v1 limit");
        }
        if (lineageBytes < 22 || (lineageBytes & 1) != 0) {
            throw failure(ManifestFormatException.Code.CORRUPT, "Invalid nested lineage size");
        }
        int headerBytes = HEADER_OVERHEAD + lineageBytes;
        need(image.length >= headerBytes);
        var lineage = input.slice(16, lineageBytes);
        input.position(16 + lineageBytes);
        long generation = input.getLong();
        long sequence = input.getLong();
        long prefixBytes = input.getLong();
        byte[] prefixHash = new byte[DIGEST_BYTES];
        input.get(prefixHash);
        int count = input.getInt();
        int crcOffset = input.position();
        if (input.getInt() != crc(image, crcOffset)) {
            throw failure(ManifestFormatException.Code.CORRUPT, "Manifest header checksum mismatch");
        }
        countLimit(count);
        int footerOffset = headerBytes + count * REFERENCE_BYTES;
        int totalBytes = footerOffset + DIGEST_BYTES;
        need(image.length >= totalBytes);
        if (image.length != totalBytes) {
            throw failure(ManifestFormatException.Code.CORRUPT, "Trailing bytes after manifest");
        }
        if (!MessageDigest.isEqual(sha256(image, footerOffset), Arrays.copyOfRange(image, footerOffset, totalBytes))) {
            throw failure(ManifestFormatException.Code.CORRUPT, "Manifest integrity footer mismatch");
        }
        try {
            var database = JournalCodec.readHeader(lineage);
            if (lineage.hasRemaining()) {
                throw failure(ManifestFormatException.Code.CORRUPT, "Trailing nested lineage bytes");
            }
            var references = new ArrayList<CheckpointManifest.SegmentReference>(count);
            for (int i = 0; i < count; i++) {
                byte[] id = new byte[DIGEST_BYTES];
                input.get(id);
                references.add(new CheckpointManifest.SegmentReference(new SegmentId(HexFormat.of().formatHex(id)),
                        input.getLong(), input.getLong(), input.getLong()));
            }
            var prefix = new CheckpointManifest.JournalPrefix(sequence, prefixBytes, HexFormat.of().formatHex(prefixHash));
            var manifest = new CheckpointManifest(database, generation, prefix, references);
            checkedLineage(manifest);
            return new DecodedManifest(new ManifestId(HexFormat.of().formatHex(sha256(image, image.length))), manifest);
        } catch (JournalFormatException failure) {
            throw nested(failure);
        } catch (IllegalArgumentException failure) {
            throw failure(ManifestFormatException.Code.INVALID_CHECKPOINT, failure.getMessage());
        }
    }

    public static DecodedManifest decode(byte[] image, ManifestId expected) {
        Objects.requireNonNull(expected, "expected");
        var decoded = decode(image);
        if (!decoded.id().equals(expected)) {
            throw failure(ManifestFormatException.Code.IDENTIFIER_MISMATCH, "Manifest content identifier does not match");
        }
        return decoded;
    }

    // Shared v1 resource validation for encoding and whole-set verification; no manifest image needed.
    static byte[] checkedLineage(CheckpointManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        countLimit(manifest.segments().size());
        final byte[] lineage;
        try {
            lineage = JournalCodec.encodeHeader(manifest.databaseId());
        } catch (JournalFormatException failure) {
            throw nested(failure);
        }
        if (manifest.journalPrefix().byteLength() > MAX_PREFIX_BYTES) {
            throw failure(ManifestFormatException.Code.LIMIT_EXCEEDED, "Journal prefix exceeds v1 limit");
        }
        if (manifest.journalPrefix().byteLength() < lineage.length) {
            throw failure(ManifestFormatException.Code.INVALID_CHECKPOINT, "Journal prefix is shorter than its lineage header");
        }
        for (var reference : manifest.segments()) {
            if (reference.byteLength() > SegmentCodec.MAX_SEGMENT_BYTES
                    || reference.lastSequence() - reference.firstSequence() + 1 > SegmentCodec.MAX_RECORDS) {
                throw failure(ManifestFormatException.Code.LIMIT_EXCEEDED, "Referenced segment exceeds v1 limits");
            }
        }
        return lineage;
    }

    private static void countLimit(int count) {
        if (count > MAX_SEGMENTS) {
            throw failure(ManifestFormatException.Code.LIMIT_EXCEEDED, "Manifest reference count exceeds v1 limit");
        }
        if (count < 0) {
            throw failure(ManifestFormatException.Code.CORRUPT, "Negative manifest reference count");
        }
    }

    private static int crc(byte[] image, int length) {
        var crc = new CRC32C();
        crc.update(image, 0, length);
        return (int) crc.getValue();
    }

    private static byte[] sha256(byte[] image, int length) {
        var digest = sha256();
        digest.update(image, 0, length);
        return digest.digest();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError("Java must provide SHA-256", unavailable);
        }
    }

    private static ManifestFormatException nested(JournalFormatException failure) {
        var code = switch (failure.code()) {
            case UNSUPPORTED_VERSION -> ManifestFormatException.Code.UNSUPPORTED_VERSION;
            case LIMIT_EXCEEDED -> ManifestFormatException.Code.LIMIT_EXCEEDED;
            case INCOMPLETE_HEADER, INCOMPLETE_TAIL, CORRUPT, INVALID_HISTORY -> ManifestFormatException.Code.CORRUPT;
        };
        return failure(code, "Invalid nested journal lineage: " + failure.getMessage());
    }

    private static void need(boolean complete) {
        if (!complete) {
            throw failure(ManifestFormatException.Code.INCOMPLETE, "Incomplete manifest");
        }
    }

    private static ManifestFormatException failure(ManifestFormatException.Code code, String message) {
        return new ManifestFormatException(code, message);
    }
}
