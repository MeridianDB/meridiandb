// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import java.io.IOException;
import java.util.Objects;

/**
 * Bounded immutable byte access, separate from writer ownership and checkpoint publication.
 * Adapters declare their persistence/namespace guarantees. No deletion, mutable head or journal API.
 */
public interface ImmutableObjectStore {
    /** Create only; failed or uncertain output is retained. Never replace or repair an existing key. */
    ObjectReference createNew(ObjectKey key, byte[] image) throws IOException;

    /** Verify the complete expected image and close native resources before returning a snapshot. */
    VerifiedObject read(ObjectKey key, ObjectReference expected) throws IOException;

    record CreateResult(ObjectReference reference, boolean created) {
        public CreateResult { Objects.requireNonNull(reference, "reference"); }
    }

    /**
     * Explicit identical-content reuse. Reuse proves verified bytes, not an earlier force,
     * acknowledgement, checkpoint publication or permission to reclaim anything.
     */
    default CreateResult createOrVerify(ObjectKey key, byte[] image) throws IOException {
        Objects.requireNonNull(key, "key");
        byte[] snapshot = ObjectReference.snapshot(image);
        ObjectReference expected = ObjectReference.forBytes(snapshot);
        try {
            ObjectReference created = createNew(key, snapshot);
            if (!created.equals(expected)) { throw new IOException("Adapter returned a different object identity"); }
            return new CreateResult(created, true);
        } catch (ObjectAlreadyExistsException exists) {
            try (var reader = read(key, expected)) {
                if (!reader.reference().equals(expected)) { throw new IOException("Adapter returned a different object"); }
                return new CreateResult(expected, false);
            }
        }
    }
}
