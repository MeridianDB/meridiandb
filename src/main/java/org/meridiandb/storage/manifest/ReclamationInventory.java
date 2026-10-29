// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable bounded inspection evidence. It grants no authority to delete any current or future file. */
public final class ReclamationInventory {
    public static final long MAX_VERIFIED_BYTES = 64L * 1024 * 1024;
    public static final long MAX_VERIFIED_RECORDS = 262_144;
    public static final long MAX_ALIAS_COMPARISONS = 1_000_000;

    public enum Status { VERIFIED, DEFERRED_READERS }

    /** Repeated graph work counts again for each published root; these are not exact heap limits. */
    public record Limits(long maxBytes, long maxRecords) {
        public static final Limits DEFAULT = new Limits(MAX_VERIFIED_BYTES, MAX_VERIFIED_RECORDS);
        public Limits {
            if (maxBytes < 0 || maxBytes > MAX_VERIFIED_BYTES || maxRecords < 0 || maxRecords > MAX_VERIFIED_RECORDS) {
                throw new IllegalArgumentException("Invalid reclamation inspection limits");
            }
        }
    }

    /** Exact encoded object length, not allocated blocks or a promise of reclaimed disk space. */
    public record ObjectFile(String name, long byteLength) {
        public ObjectFile {
            Objects.requireNonNull(name, "name");
            if (byteLength < 0) { throw new IllegalArgumentException("Negative object byte length"); }
        }
    }

    private final Status status;
    private final Optional<ManifestId> selected;
    private final Set<ManifestId> retained;
    private final List<ObjectFile> manifests;
    private final List<ObjectFile> segments;
    private final List<String> preserved;
    private final long verifiedBytes;
    private final long verifiedRecords;

    ReclamationInventory(Status status, Optional<ManifestId> selected, Set<ManifestId> retained,
                         List<ObjectFile> manifests, List<ObjectFile> segments, List<String> preserved,
                         long verifiedBytes, long verifiedRecords) {
        this.status = Objects.requireNonNull(status, "status");
        this.selected = Objects.requireNonNull(selected, "selected");
        this.retained = Set.copyOf(retained);
        this.manifests = List.copyOf(manifests);
        this.segments = List.copyOf(segments);
        this.preserved = List.copyOf(preserved);
        this.verifiedBytes = verifiedBytes;
        this.verifiedRecords = verifiedRecords;
    }

    static ReclamationInventory deferred() {
        return new ReclamationInventory(Status.DEFERRED_READERS, Optional.empty(), Set.of(), List.of(), List.of(), List.of(), 0, 0);
    }

    public Status status() { return status; }
    public Optional<ManifestId> selectedManifestId() { return selected; }
    public Set<ManifestId> retainedManifestIds() { return retained; }
    public List<ObjectFile> obsoleteManifests() { return manifests; }
    public List<ObjectFile> obsoleteSegments() { return segments; }
    public List<String> preservedNames() { return preserved; }
    public long verifiedBytes() { return verifiedBytes; }
    public long verifiedRecords() { return verifiedRecords; }
    public long candidateBytes() {
        return manifests.stream().mapToLong(ObjectFile::byteLength).sum() + segments.stream().mapToLong(ObjectFile::byteLength).sum();
    }
}
