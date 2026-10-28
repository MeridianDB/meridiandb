// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;

/** Immutable declarations for a complete history prefix; generation is not a temporal commit. */
public record CheckpointManifest(DatabaseId databaseId, long generation, JournalPrefix journalPrefix,
                                 List<SegmentReference> segments) {
    public record JournalPrefix(long sequence, long byteLength, String sha256) {
        public JournalPrefix {
            if (sequence < 0 || byteLength < 22) {
                throw new IllegalArgumentException("Invalid journal prefix sequence or byte length");
            }
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Prefix SHA-256 requires 64 lowercase hex digits");
            }
        }
    }

    public record SegmentReference(SegmentId id, long byteLength, long firstSequence, long lastSequence) {
        public SegmentReference {
            Objects.requireNonNull(id, "id");
            if (byteLength <= 0 || firstSequence <= 0 || lastSequence < firstSequence) {
                throw new IllegalArgumentException("Invalid segment length or sequence range");
            }
        }
    }

    public CheckpointManifest {
        Objects.requireNonNull(databaseId, "databaseId");
        Objects.requireNonNull(journalPrefix, "journalPrefix");
        if (generation <= 0) {
            throw new IllegalArgumentException("Manifest generation must be positive");
        }
        segments = List.copyOf(segments);
        var identities = new HashSet<SegmentId>();
        long previous = 0;
        for (var reference : segments) {
            if (previous == Long.MAX_VALUE || reference.firstSequence() != previous + 1) {
                throw new IllegalArgumentException("Manifest ranges must cover a contiguous prefix starting at one");
            }
            if (!identities.add(reference.id())) {
                throw new IllegalArgumentException("Repeated segment identifier");
            }
            previous = reference.lastSequence();
        }
        if (previous != journalPrefix.sequence()) {
            throw new IllegalArgumentException("Segment coverage differs from journal prefix sequence");
        }
    }
}
