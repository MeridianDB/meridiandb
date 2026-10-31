// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import java.util.Arrays;
import java.util.Objects;

/**
 * Fully verified heap snapshot with no native resources. Ranges copy from this complete image;
 * they are not partial-object integrity proofs or physical range I/O. Returned copies survive close.
 */
public final class VerifiedObject implements AutoCloseable {
    private final ObjectReference reference;
    private byte[] image;

    private VerifiedObject(byte[] image, ObjectReference reference) {
        this.image = image;
        this.reference = reference;
    }

    /** Validate bounded length before cloning, then verify the owned complete copy. */
    public static VerifiedObject verify(byte[] image, ObjectReference expected) throws ObjectIntegrityException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(expected, "expected");
        if (image.length != expected.byteLength()) {
            throw new ObjectIntegrityException(ObjectIntegrityException.Code.LENGTH_MISMATCH, "Object length differs");
        }
        byte[] snapshot = image.clone();
        if (!ObjectReference.forBytes(snapshot).equals(expected)) {
            throw new ObjectIntegrityException(ObjectIntegrityException.Code.ID_MISMATCH, "Object content ID differs");
        }
        return new VerifiedObject(snapshot, expected);
    }

    public synchronized ObjectReference reference() { usable(); return reference; }
    public synchronized byte[] bytes() { return usable().clone(); }

    /** Exact [offset, offset+length) copy; a zero-length range at EOF is valid. */
    public synchronized byte[] readRange(long offset, int length) {
        byte[] source = usable();
        if (offset < 0 || offset > source.length || length < 0 || length > source.length - offset) {
            throw new IllegalArgumentException("Range is outside the complete object");
        }
        return Arrays.copyOfRange(source, (int) offset, (int) offset + length);
    }

    @Override public synchronized void close() { image = null; }

    private byte[] usable() {
        if (image == null) { throw new IllegalStateException("Object reader is closed"); }
        return image;
    }
}
