// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

import java.io.IOException;
import java.util.Objects;
import org.meridiandb.storage.object.ImmutableObjectStore;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;

/** Segment-codec consumer of immutable byte access, without manifest selection or deletion. */
public final class SegmentObjects {
    private SegmentObjects() { }

    public static ImmutableObjectStore.CreateResult createOrVerify(
            ImmutableObjectStore store, ObjectKey key, ImmutableSegment segment) throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        return store.createOrVerify(key, SegmentCodec.encode(segment));
    }

    /** Return exact immutable history only after complete object and segment-format validation. */
    public static SegmentCodec.DecodedSegment read(
            ImmutableObjectStore store, ObjectKey key, SegmentId expected, int byteLength) throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expected, "expected");
        var reference = new ObjectReference(new ObjectId(expected.value()), byteLength);
        try (var object = store.read(key, reference)) {
            if (!object.reference().equals(reference)) { throw new IOException("Adapter returned a different object"); }
            return SegmentCodec.decode(object.bytes(), expected);
        }
    }
}
