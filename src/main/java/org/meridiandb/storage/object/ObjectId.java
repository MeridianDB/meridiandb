// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import java.util.Objects;

/** SHA-256 of the complete encoded byte image, independent of provider metadata. */
public record ObjectId(String value) {
    public ObjectId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Object ID must be 64 lowercase hexadecimal characters");
        }
    }
}
