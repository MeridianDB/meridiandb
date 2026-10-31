// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Receipt after complete removal/name synchronization and reader-barrier release; no block-space claim. */
public final class ReclamationResult {
    public enum Status { COMPLETED, DEFERRED_READERS }
    private final Status status;
    private final ReclamationInventory inventory;

    private ReclamationResult(Status status, ReclamationInventory inventory) {
        this.status = status;
        this.inventory = inventory;
    }

    static ReclamationResult completed(ReclamationInventory inventory) {
        if (inventory.status() != ReclamationInventory.Status.VERIFIED) { throw new IllegalArgumentException("Verified inventory required"); }
        return new ReclamationResult(Status.COMPLETED, inventory);
    }

    static ReclamationResult deferred() {
        return new ReclamationResult(Status.DEFERRED_READERS, ReclamationInventory.deferred());
    }

    public Status status() { return status; }
    public Optional<ManifestId> selectedManifestId() { return inventory.selectedManifestId(); }
    public Set<ManifestId> retainedManifestIds() { return inventory.retainedManifestIds(); }
    public List<ReclamationInventory.ObjectFile> removedManifests() { return inventory.obsoleteManifests(); }
    public List<ReclamationInventory.ObjectFile> removedSegments() { return inventory.obsoleteSegments(); }
    public long removedBytes() { return inventory.candidateBytes(); }
}
