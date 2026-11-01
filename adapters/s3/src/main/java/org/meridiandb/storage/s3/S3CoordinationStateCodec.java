// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3CoordinationException.Code;
import org.meridiandb.storage.s3.S3CoordinationState.Attempt;
import org.meridiandb.storage.s3.S3CoordinationState.Completion;
import org.meridiandb.storage.s3.S3CoordinationState.Entry;
import org.meridiandb.storage.s3.S3CoordinationState.Mutation;
import org.meridiandb.storage.s3.S3CoordinationState.Operation;

/** Canonical bounded v1 declarations only; neither decoding nor a matching digest proves durable admission. */
public final class S3CoordinationStateCodec {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final long MAGIC = 0x4d52444e53435354L;
    private static final int FIXED = 128;
    private static final int FOOTER = 32;
    private static final int MIN_BYTES = FIXED + 12 + FOOTER;

    public record DecodedState(ObjectReference reference, S3CoordinationState state) {
        public DecodedState { Objects.requireNonNull(reference, "reference"); Objects.requireNonNull(state, "state"); }
    }
    private S3CoordinationStateCodec() { }

    public static byte[] encode(S3CoordinationState state) {
        Objects.requireNonNull(state, "state");
        int length = MIN_BYTES + state.target().endpoint().toString().length() + state.target().regionId().length() + state.target().bucket().length();
        for (var entry : state.roots()) { length += 264 + entry.root().manifestImage().length; }
        if (state.lastCompletion().isPresent()) { length += 8 + attemptSize(state.lastCompletion().orElseThrow().attempt()); }
        if (state.pending().isPresent()) { length += attemptSize(state.pending().orElseThrow()); }
        if (length > MAX_BYTES) { throw limit("Coordination image exceeds cap"); }
        var output = ByteBuffer.allocate(length);
        int flags = (state.pending().isPresent() ? 1 : 0) | (state.lastCompletion().isPresent() ? 2 : 0);
        output.putLong(MAGIC).putInt(1).putInt(length).putLong(state.generation());
        uuid(output, state.placementId()); uuid(output, state.binding().namespaceId()); uuid(output, state.binding().publisherId());
        output.put(HexFormat.of().parseHex(state.binding().lineageId().value())).putLong(state.completedOrdinal());
        output.putInt(flags).putInt(state.roots().size()).putLong(0);
        text(output, state.target().endpoint().toString()); text(output, state.target().regionId()); text(output, state.target().bucket());
        for (var entry : state.roots()) {
            output.putInt((entry.retained() ? 1 : 0) | (entry.retired() ? 2 : 0)).put(entry.root().headImage());
            byte[] manifest = entry.root().manifestImage();
            output.putInt(manifest.length).put(manifest);
        }
        state.lastCompletion().ifPresent(completion -> {
            attempt(output, completion.attempt()); output.putInt(completion.status()).putInt(0);
        });
        state.pending().ifPresent(value -> attempt(output, value));
        output.put(hash(output.array(), length - FOOTER));
        return output.array();
    }

    public static DecodedState decode(byte[] image) {
        Objects.requireNonNull(image, "image");
        if (image.length > MAX_BYTES) { throw limit("Coordination image exceeds cap"); }
        need(image.length >= 16);
        byte[] snapshot = image.clone();
        var input = ByteBuffer.wrap(snapshot);
        if (input.getLong() != MAGIC) { throw corrupt("Coordination magic differs"); }
        if (input.getInt() != 1) { throw failure(Code.UNSUPPORTED_VERSION, "Unsupported coordination version", null); }
        int length = input.getInt();
        if (length > MAX_BYTES) { throw limit("Declared coordination image exceeds cap"); }
        if (length < MIN_BYTES) { throw corrupt("Invalid coordination length"); }
        need(snapshot.length >= length);
        if (snapshot.length != length) { throw corrupt("Trailing coordination bytes"); }
        int end = length - FOOTER;
        if (!MessageDigest.isEqual(hash(snapshot, end), Arrays.copyOfRange(snapshot, end, length))) {
            throw corrupt("Coordination checksum differs");
        }
        input.limit(end);
        try {
            long generation = input.getLong();
            UUID placement = uuid(input), namespace = uuid(input), publisher = uuid(input);
            var lineage = new ObjectId(HexFormat.of().formatHex(bytes(input, 32)));
            long completed = input.getLong();
            int flags = input.getInt(), count = input.getInt();
            if ((flags & ~3) != 0 || input.getLong() != 0) { throw corrupt("Invalid coordination flags/reserved field"); }
            if (count < 0) { throw corrupt("Negative root count"); }
            if (count > S3CoordinationState.MAX_ROOTS) { throw limit("Root count exceeds cap"); }
            var target = new S3PublicationState.Target(URI.create(text(input, S3PublicationState.MAX_ENDPOINT_BYTES, false)),
                    text(input, S3PublicationState.MAX_REGION_BYTES, false), text(input, 63, false));
            var binding = new S3CheckpointHeads.Binding(namespace, publisher, lineage);
            var roots = new ArrayList<Entry>(count);
            int references = 0;
            for (int i = 0; i < count; i++) {
                need(input.remaining() >= 4);
                int rootFlags = input.getInt();
                if ((rootFlags & ~3) != 0) { throw corrupt("Unknown root flags"); }
                byte[] head = bytes(input, 256);
                need(input.remaining() >= 4);
                int manifestBytes = input.getInt();
                if (manifestBytes < 0) { throw corrupt("Negative manifest length"); }
                if (manifestBytes > S3CoordinationState.MAX_ROOT_MANIFEST_BYTES) { throw limit("Root manifest exceeds cap"); }
                need(input.remaining() >= manifestBytes);
                // Charge untrusted nested reference declarations before nested decoding/allocation.
                int nestedCount = manifestCount(input, manifestBytes);
                if (nestedCount > S3CoordinationState.MAX_SEGMENT_REFERENCES - references) { throw limit("Aggregate reference work exceeded"); }
                references += nestedCount;
                var root = new S3CoordinationState.Root(head, bytes(input, manifestBytes));
                roots.add(new Entry(root, (rootFlags & 1) != 0, (rootFlags & 2) != 0));
            }
            Completion completion = null;
            if ((flags & 2) != 0) {
                var attempt = attempt(input);
                need(input.remaining() >= 8);
                completion = new Completion(attempt, input.getInt());
                if (input.getInt() != 0) { throw corrupt("Completion reserved field differs"); }
            }
            Attempt pending = (flags & 1) == 0 ? null : attempt(input);
            if (input.hasRemaining()) { throw corrupt("Noncanonical trailing coordination fields"); }
            var state = S3CoordinationState.restore(generation, target, binding, placement, roots, completed, completion, pending);
            return new DecodedState(ObjectReference.forBytes(snapshot), state);
        } catch (S3CoordinationException failure) { throw failure; }
        catch (IllegalArgumentException failure) { throw failure(Code.INVALID_STATE, "Invalid nested coordination metadata", failure); }
    }

    public static DecodedState decode(byte[] image, ObjectReference expected) {
        Objects.requireNonNull(image, "image"); Objects.requireNonNull(expected, "expected");
        if (image.length != expected.byteLength()) { throw failure(Code.REFERENCE_MISMATCH, "Coordination length differs from reference", null); }
        var decoded = decode(image);
        if (!decoded.reference().equals(expected)) { throw failure(Code.REFERENCE_MISMATCH, "Coordination identity differs", null); }
        return decoded;
    }
    public static ObjectReference reference(S3CoordinationState state) { return ObjectReference.forBytes(encode(state)); }

    private static int manifestCount(ByteBuffer input, int length) {
        if (length < 16) { throw failure(Code.INVALID_STATE, "Incomplete root manifest metadata", null); }
        var manifest = input.slice(input.position(), length);
        int lineageBytes = manifest.getInt(12);
        if (lineageBytes < 22 || (lineageBytes & 1) != 0 || (long) lineageBytes + 80 > length) {
            throw failure(Code.INVALID_STATE, "Invalid root manifest header bound", null);
        }
        int count = manifest.getInt(72 + lineageBytes);
        if (count < 0) { throw failure(Code.INVALID_STATE, "Negative manifest reference count", null); }
        return count;
    }
    private static int attemptSize(Attempt attempt) {
        var mutation = attempt.mutation();
        return 92 + mutation.key().value().length() + (mutation.condition() == null ? 0 : mutation.condition().value().length())
                + (mutation.originalHead() == null ? 0 : 36);
    }
    private static void attempt(ByteBuffer output, Attempt attempt) {
        uuid(output, attempt.placementId()); output.putLong(attempt.ordinal()); uuid(output, attempt.nonce());
        var mutation = attempt.mutation();
        output.putInt(mutation.operation().ordinal() + 1).putInt(0); reference(output, mutation.content());
        if (mutation.originalHead() != null) { reference(output, mutation.originalHead()); }
        text(output, mutation.key().value()); text(output, mutation.condition() == null ? "" : mutation.condition().value());
    }
    private static Attempt attempt(ByteBuffer input) {
        UUID placement = uuid(input); need(input.remaining() >= 8); long ordinal = input.getLong(); UUID nonce = uuid(input);
        need(input.remaining() >= 8);
        int code = input.getInt();
        if (code < 1 || code > Operation.values().length || input.getInt() != 0) { throw corrupt("Invalid attempt operation/reserved field"); }
        var operation = Operation.values()[code - 1];
        var content = reference(input);
        var original = operation == Operation.REPLACE_HEAD ? reference(input) : null;
        var key = new ObjectKey(text(input, 128, false));
        String token = text(input, S3HeadToken.MAX_BYTES, true);
        return new Attempt(placement, ordinal, nonce, new Mutation(operation, key, content, original, token.isEmpty() ? null : new S3HeadToken(token)));
    }
    private static void reference(ByteBuffer output, ObjectReference reference) {
        output.putInt(reference.byteLength()).put(HexFormat.of().parseHex(reference.id().value()));
    }
    private static ObjectReference reference(ByteBuffer input) {
        need(input.remaining() >= 36);
        int length = input.getInt();
        return new ObjectReference(new ObjectId(HexFormat.of().formatHex(bytes(input, 32))), length);
    }
    private static void text(ByteBuffer output, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII); output.putInt(bytes.length).put(bytes);
    }
    private static String text(ByteBuffer input, int cap, boolean empty) {
        need(input.remaining() >= 4); int length = input.getInt();
        if (length < 0 || (!empty && length == 0)) { throw corrupt("Invalid coordination field length"); }
        if (length > cap) { throw limit("Coordination field exceeds cap"); }
        byte[] image = bytes(input, length);
        for (byte value : image) { if (value < 0x21 || value > 0x7e) { throw failure(Code.INVALID_STATE, "Coordination text is not printable nonspace ASCII", null); } }
        return new String(image, StandardCharsets.US_ASCII);
    }
    private static byte[] bytes(ByteBuffer input, int length) {
        need(length >= 0 && input.remaining() >= length); byte[] result = new byte[length]; input.get(result); return result;
    }
    private static void uuid(ByteBuffer output, UUID id) { output.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static UUID uuid(ByteBuffer input) { need(input.remaining() >= 16); return new UUID(input.getLong(), input.getLong()); }
    private static byte[] hash(byte[] image, int length) {
        try { var digest = MessageDigest.getInstance("SHA-256"); digest.update(image, 0, length); return digest.digest(); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError("Required SHA-256 unavailable", unavailable); }
    }
    private static void need(boolean condition) { if (!condition) { throw failure(Code.INCOMPLETE, "Incomplete coordination state", null); } }
    private static S3CoordinationException corrupt(String message) { return failure(Code.CORRUPT, message, null); }
    private static S3CoordinationException limit(String message) { return failure(Code.LIMIT_EXCEEDED, message, null); }
    private static S3CoordinationException failure(Code code, String message, Throwable cause) { return S3CoordinationState.failure(code, message, cause); }
}
