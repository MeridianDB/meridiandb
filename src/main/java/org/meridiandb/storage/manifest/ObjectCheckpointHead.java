// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.util.Objects;
import java.util.UUID;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.segment.SegmentCodec;

/**
 * Immutable declarations for one remote head image, not a selected checkpoint or writer lease.
 * Revision is remote selection order, separate from manifest generation and temporal cutoff.
 * Lineage ID hashes the exact canonical journal header; predecessor identifies the whole old head.
 */
public record ObjectCheckpointHead(UUID namespaceId, UUID publisherId, UUID publicationId,
                                   ObjectId lineageId, long revision, ObjectReference manifest,
                                   CheckpointManifest.JournalPrefix journalPrefix, ObjectReference predecessor) {
    public static final long MAX_SEQUENCE = (long) ManifestCodec.MAX_SEGMENTS * SegmentCodec.MAX_RECORDS;

    public ObjectCheckpointHead {
        identity(namespaceId, "namespaceId");
        identity(publisherId, "publisherId");
        identity(publicationId, "publicationId");
        Objects.requireNonNull(lineageId, "lineageId");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(journalPrefix, "journalPrefix");
        if (revision <= 0) { throw new IllegalArgumentException("Head revision must be positive"); }
        if (manifest.byteLength() == 0 || manifest.byteLength() > ManifestCodec.MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("Manifest reference exceeds the supported encoded bounds");
        }
        if (journalPrefix.byteLength() > ManifestCodec.MAX_PREFIX_BYTES || journalPrefix.sequence() > MAX_SEQUENCE) {
            throw new IllegalArgumentException("Journal prefix exceeds supported manifest coverage");
        }
        if (journalPrefix.sequence() == 0 && !journalPrefix.sha256().equals(lineageId.value())) {
            throw new IllegalArgumentException("An empty journal prefix hashes exactly its lineage header");
        }
        if ((revision == 1) != (predecessor == null)) {
            throw new IllegalArgumentException("Only revision one has no predecessor");
        }
        if (predecessor != null && predecessor.byteLength() != ObjectCheckpointHeadCodec.ENCODED_BYTES) {
            throw new IllegalArgumentException("Predecessor must identify a complete v1 head");
        }
    }

    private static void identity(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0) {
            throw new IllegalArgumentException(name + " must not be the nil UUID");
        }
    }
}
