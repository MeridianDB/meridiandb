// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;

/** Pure fixed-size v1 encoding; decoding checks declarations, not selection, graph or actual journal. */
public final class ObjectCheckpointHeadCodec {
    public static final int ENCODED_BYTES = 256;
    private static final long MAGIC = 0x4d52444e48454144L;
    private static final int FOOTER_OFFSET = 224;

    public record DecodedHead(ObjectReference reference, ObjectCheckpointHead head) {
        public DecodedHead {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(head, "head");
        }
    }

    private ObjectCheckpointHeadCodec() { }

    /** Return fresh canonical bytes; all model bounds are validated before output allocation. */
    public static byte[] encode(ObjectCheckpointHead head) {
        Objects.requireNonNull(head, "head");
        var output = ByteBuffer.allocate(ENCODED_BYTES);
        output.putLong(MAGIC).putInt(1).putInt(head.predecessor() == null ? 0 : 1);
        putUuid(output, head.namespaceId());
        putUuid(output, head.publisherId());
        putUuid(output, head.publicationId());
        putId(output, head.lineageId());
        output.putLong(head.revision());
        putId(output, head.manifest().id());
        output.putInt(head.manifest().byteLength());
        output.putLong(head.journalPrefix().sequence()).putLong(head.journalPrefix().byteLength());
        output.put(HexFormat.of().parseHex(head.journalPrefix().sha256()));
        if (head.predecessor() == null) { output.put(new byte[36]); }
        else {
            putId(output, head.predecessor().id());
            output.putInt(head.predecessor().byteLength());
        }
        output.put(digest(output.array(), FOOTER_OFFSET));
        return output.array();
    }

    /** Complete-image identity, distinct from the integrity footer and any provider CAS token. */
    public static ObjectReference reference(ObjectCheckpointHead head) {
        return ObjectReference.forBytes(encode(head));
    }

    /** Retains no input or partial value. Keep input unchanged during this call. */
    public static DecodedHead decode(byte[] image) {
        Objects.requireNonNull(image, "image");
        if (image.length > ENCODED_BYTES) {
            throw failure(ObjectCheckpointHeadException.Code.LIMIT_EXCEEDED, "Head exceeds the fixed v1 size");
        }
        if (image.length < ENCODED_BYTES) {
            throw failure(ObjectCheckpointHeadException.Code.INCOMPLETE, "Incomplete head image");
        }
        var input = ByteBuffer.wrap(image);
        if (input.getLong() != MAGIC) { throw failure(ObjectCheckpointHeadException.Code.CORRUPT, "Head magic differs"); }
        if (input.getInt() != 1) {
            throw failure(ObjectCheckpointHeadException.Code.UNSUPPORTED_VERSION, "Unsupported head version");
        }
        int flags = input.getInt();
        if (flags != 0 && flags != 1) {
            throw failure(ObjectCheckpointHeadException.Code.CORRUPT, "Unknown head flags");
        }
        if (!MessageDigest.isEqual(digest(image, FOOTER_OFFSET), Arrays.copyOfRange(image, FOOTER_OFFSET, ENCODED_BYTES))) {
            throw failure(ObjectCheckpointHeadException.Code.CORRUPT, "Head integrity footer differs");
        }
        try {
            UUID namespace = getUuid(input);
            UUID publisher = getUuid(input);
            UUID publication = getUuid(input);
            ObjectId lineage = getId(input);
            long revision = input.getLong();
            var manifest = new ObjectReference(getId(input), input.getInt());
            long sequence = input.getLong();
            long prefixBytes = input.getLong();
            String prefixHash = getId(input).value();
            var predecessorId = getId(input);
            int predecessorBytes = input.getInt();
            ObjectReference predecessor = null;
            if (flags == 0) {
                if (!predecessorId.value().equals("0".repeat(64)) || predecessorBytes != 0) {
                    throw failure(ObjectCheckpointHeadException.Code.CORRUPT, "Absent predecessor fields must be zero");
                }
            } else { predecessor = new ObjectReference(predecessorId, predecessorBytes); }
            var prefix = new CheckpointManifest.JournalPrefix(sequence, prefixBytes, prefixHash);
            var head = new ObjectCheckpointHead(namespace, publisher, publication, lineage,
                    revision, manifest, prefix, predecessor);
            return new DecodedHead(ObjectReference.forBytes(image), head);
        } catch (IllegalArgumentException invalid) {
            throw new ObjectCheckpointHeadException(ObjectCheckpointHeadException.Code.INVALID_HEAD,
                    "Invalid head declarations: " + invalid.getMessage(), invalid);
        }
    }

    public static DecodedHead decode(byte[] image, ObjectReference expected) {
        Objects.requireNonNull(expected, "expected");
        var decoded = decode(image);
        if (!decoded.reference().equals(expected)) {
            throw failure(ObjectCheckpointHeadException.Code.REFERENCE_MISMATCH, "Complete head reference differs");
        }
        return decoded;
    }

    private static void putUuid(ByteBuffer output, UUID value) {
        output.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer input) { return new UUID(input.getLong(), input.getLong()); }
    private static void putId(ByteBuffer output, ObjectId id) { output.put(HexFormat.of().parseHex(id.value())); }
    private static ObjectId getId(ByteBuffer input) {
        byte[] hash = new byte[32];
        input.get(hash);
        return new ObjectId(HexFormat.of().formatHex(hash));
    }

    private static byte[] digest(byte[] image, int length) {
        var hash = ManifestCodec.sha256();
        hash.update(image, 0, length);
        return hash.digest();
    }

    private static ObjectCheckpointHeadException failure(ObjectCheckpointHeadException.Code code, String message) {
        return new ObjectCheckpointHeadException(code, message);
    }
}
