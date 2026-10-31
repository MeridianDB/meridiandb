// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;

/** Bounded v1 snapshot representation only; encoded/decoded values supply no local force or selection proof. */
public final class S3PublicationStateCodec {
    public static final int MAX_BYTES = 4096;
    private static final int FIXED_BYTES = 96;
    private static final int FOOTER_BYTES = 32;
    private static final int MIN_BYTES = FIXED_BYTES + 12 + FOOTER_BYTES;
    private static final long MAGIC = 0x4d52444e53505354L;

    public record DecodedState(ObjectReference reference, S3PublicationState state) {
        public DecodedState {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(state, "state");
        }
    }

    private S3PublicationStateCodec() { }

    public static byte[] encode(S3PublicationState state) {
        Objects.requireNonNull(state, "state");
        var target = state.target();
        var expected = state.expected();
        var pending = state.pending();
        int length = MIN_BYTES + target.endpoint().toString().length() + target.regionId().length() + target.bucket().length();
        if (expected.isPresent()) { length += ObjectCheckpointHeadCodec.ENCODED_BYTES + 4 + expected.orElseThrow().token().value().length(); }
        if (pending.isPresent()) { length += 4 + ObjectCheckpointHeadCodec.ENCODED_BYTES; }
        if (length > MAX_BYTES) { throw failure(S3PublicationStateException.Code.LIMIT_EXCEEDED, "Publication state exceeds byte cap", null); }
        var output = ByteBuffer.allocate(length);
        int flags = (expected.isPresent() ? 1 : 0) | (pending.isPresent() ? 2 : 0);
        output.putLong(MAGIC).putInt(1).putInt(length).putLong(state.generation()).putInt(flags).putInt(0);
        putUuid(output, state.binding().namespaceId());
        putUuid(output, state.binding().publisherId());
        output.put(HexFormat.of().parseHex(state.binding().lineageId().value()));
        putText(output, target.endpoint().toString());
        putText(output, target.regionId());
        putText(output, target.bucket());
        if (expected.isPresent()) {
            output.put(expected.orElseThrow().image());
            putText(output, expected.orElseThrow().token().value());
        }
        if (pending.isPresent()) {
            output.putInt(pending.orElseThrow().operation() == S3PublicationState.Operation.CREATE_IF_ABSENT ? 1 : 2);
            output.put(pending.orElseThrow().image());
        }
        output.put(hash(output.array(), length - FOOTER_BYTES));
        return output.array();
    }

    /** Check the complete bound before copying; all returned values are independent of caller arrays. */
    public static DecodedState decode(byte[] image) {
        Objects.requireNonNull(image, "image");
        if (image.length > MAX_BYTES) { throw failure(S3PublicationStateException.Code.LIMIT_EXCEEDED, "Publication state exceeds byte cap", null); }
        need(image.length >= 16);
        byte[] snapshot = image.clone();
        var input = ByteBuffer.wrap(snapshot);
        if (input.getLong() != MAGIC) { throw corrupt("Publication state magic differs"); }
        if (input.getInt() != 1) { throw failure(S3PublicationStateException.Code.UNSUPPORTED_VERSION, "Unsupported publication state version", null); }
        int length = input.getInt();
        if (length > MAX_BYTES) { throw failure(S3PublicationStateException.Code.LIMIT_EXCEEDED, "Declared state exceeds byte cap", null); }
        if (length < MIN_BYTES) { throw corrupt("Invalid complete state length"); }
        need(snapshot.length >= length);
        if (snapshot.length != length) { throw corrupt("Trailing bytes after publication state"); }
        int payloadEnd = length - FOOTER_BYTES;
        if (!MessageDigest.isEqual(hash(snapshot, payloadEnd), Arrays.copyOfRange(snapshot, payloadEnd, length))) {
            throw corrupt("Publication state checksum differs");
        }
        input.limit(payloadEnd);
        long generation = input.getLong();
        int flags = input.getInt();
        if ((flags & ~3) != 0 || input.getInt() != 0) { throw corrupt("Invalid state flags or reserved bytes"); }
        try {
            UUID namespace = uuid(input);
            UUID publisher = uuid(input);
            byte[] lineage = bytes(input, 32);
            var binding = new S3CheckpointHeads.Binding(namespace, publisher, new ObjectId(HexFormat.of().formatHex(lineage)));
            String endpoint = text(input, S3PublicationState.MAX_ENDPOINT_BYTES);
            String region = text(input, S3PublicationState.MAX_REGION_BYTES);
            String bucket = text(input, 63);
            var target = new S3PublicationState.Target(URI.create(endpoint), region, bucket);
            S3HeadObservation expected = null;
            if ((flags & 1) != 0) {
                byte[] head = bytes(input, ObjectCheckpointHeadCodec.ENCODED_BYTES);
                String token = text(input, S3HeadToken.MAX_BYTES);
                expected = S3HeadObservation.restore(target.endpoint(), target.bucket(), head, new S3HeadToken(token));
            }
            byte[] candidate = null;
            if ((flags & 2) != 0) {
                need(input.remaining() >= 4);
                int operation = input.getInt();
                if (operation != (expected == null ? 1 : 2)) {
                    throw failure(S3PublicationStateException.Code.INVALID_STATE, "Conditional operation contradicts expected state", null);
                }
                candidate = bytes(input, ObjectCheckpointHeadCodec.ENCODED_BYTES);
            }
            if (input.hasRemaining()) { throw corrupt("Noncanonical trailing state fields"); }
            var state = S3PublicationState.restore(generation, target, binding, expected, candidate);
            return new DecodedState(ObjectReference.forBytes(snapshot), state);
        } catch (S3PublicationStateException failure) {
            if (failure.code() == S3PublicationStateException.Code.INVALID_BINDING
                    || failure.code() == S3PublicationStateException.Code.INVALID_TRANSITION) {
                throw failure(S3PublicationStateException.Code.INVALID_STATE, "Contradictory nested publication state", failure);
            }
            throw failure;
        } catch (IllegalArgumentException | org.meridiandb.storage.manifest.ObjectCheckpointHeadException invalid) {
            throw failure(S3PublicationStateException.Code.INVALID_STATE, "Invalid nested publication state", invalid);
        }
    }

    public static DecodedState decode(byte[] image, ObjectReference expected) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(expected, "expected");
        if (image.length != expected.byteLength()) { throw failure(S3PublicationStateException.Code.REFERENCE_MISMATCH, "State length differs from expected reference", null); }
        var decoded = decode(image);
        if (!decoded.reference().equals(expected)) { throw failure(S3PublicationStateException.Code.REFERENCE_MISMATCH, "State full identity differs", null); }
        return decoded;
    }

    public static ObjectReference reference(S3PublicationState state) { return ObjectReference.forBytes(encode(state)); }

    private static void putText(ByteBuffer output, String value) {
        byte[] image = value.getBytes(StandardCharsets.US_ASCII);
        output.putInt(image.length).put(image);
    }
    private static String text(ByteBuffer input, int maximum) {
        need(input.remaining() >= 4);
        int length = input.getInt();
        if (length < 0) { throw corrupt("Negative state field length"); }
        if (length > maximum) { throw failure(S3PublicationStateException.Code.LIMIT_EXCEEDED, "State field exceeds byte cap", null); }
        byte[] image = bytes(input, length);
        for (byte value : image) { if (value < 0x21 || value > 0x7e) { throw failure(S3PublicationStateException.Code.INVALID_STATE, "State field requires printable nonspace ASCII", null); } }
        return new String(image, StandardCharsets.US_ASCII);
    }
    private static byte[] bytes(ByteBuffer input, int length) {
        need(input.remaining() >= length);
        byte[] image = new byte[length];
        input.get(image);
        return image;
    }
    private static void putUuid(ByteBuffer output, UUID value) { output.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()); }
    private static UUID uuid(ByteBuffer input) { need(input.remaining() >= 16); return new UUID(input.getLong(), input.getLong()); }
    private static byte[] hash(byte[] image, int length) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(image, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) { throw new AssertionError("Required SHA-256 digest unavailable", unavailable); }
    }
    private static void need(boolean condition) { if (!condition) { throw failure(S3PublicationStateException.Code.INCOMPLETE, "Incomplete publication state", null); } }
    private static S3PublicationStateException corrupt(String message) { return failure(S3PublicationStateException.Code.CORRUPT, message, null); }
    private static S3PublicationStateException failure(S3PublicationStateException.Code code, String message, Throwable cause) {
        return new S3PublicationStateException(code, message, cause);
    }
}
