// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Expected whole-object identity and exact byte count; this is not a publication receipt. */
public record ObjectReference(ObjectId id, int byteLength) {
    public static final int MAX_BYTES = 32 * 1024 * 1024;

    public ObjectReference {
        Objects.requireNonNull(id, "id");
        if (byteLength < 0 || byteLength > MAX_BYTES) {
            throw new IllegalArgumentException("Object length must be between zero and 32 MiB");
        }
    }

    /** Computes metadata from the supplied bytes; callers still own their input array. */
    public static ObjectReference forBytes(byte[] image) {
        checkLength(image);
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(image);
            return new ObjectReference(new ObjectId(HexFormat.of().formatHex(hash)), image.length);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError("Java must provide SHA-256", unavailable);
        }
    }

    static byte[] snapshot(byte[] image) { checkLength(image); return image.clone(); }

    private static void checkLength(byte[] image) {
        Objects.requireNonNull(image, "image");
        if (image.length > MAX_BYTES) { throw new IllegalArgumentException("Object exceeds 32 MiB"); }
    }
}
