// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.util.Objects;

/** SHA-256 of the complete encoded manifest, including its separate integrity footer. */
public record ManifestId(String value) {
    public ManifestId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Manifest identifier requires 64 lowercase hex digits");
        }
    }
}
