// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

import java.util.Objects;

/**
 * An immutable heap snapshot of one fully verified segment, with no open file or mapped memory.
 * Closing releases this reader's reference; previously returned immutable values remain valid.
 * Each accessor is serialized with close. No deletion/reclamation permission is implied.
 */
public final class LocalSegmentReader implements AutoCloseable {
    private SegmentCodec.DecodedSegment decoded;

    LocalSegmentReader(SegmentCodec.DecodedSegment decoded) {
        this.decoded = Objects.requireNonNull(decoded, "decoded");
    }

    public synchronized SegmentId id() {
        return usable().id();
    }

    /** Original ordered records provide the sequential scan baseline. */
    public synchronized ImmutableSegment segment() {
        return usable().segment();
    }

    /** Idempotent; no I/O or implicit publication occurs. */
    @Override
    public synchronized void close() {
        decoded = null;
    }

    private SegmentCodec.DecodedSegment usable() {
        if (decoded == null) {
            throw new IllegalStateException("Segment reader is closed");
        }
        return decoded;
    }
}
