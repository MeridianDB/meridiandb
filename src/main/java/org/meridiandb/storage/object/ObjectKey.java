// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import java.util.Objects;

/** A portable flat name inside a caller-selected namespace, never a path or URL. */
public record ObjectKey(String value) {
    public ObjectKey {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?")
                || value.matches("(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")) {
            throw new IllegalArgumentException("Object key must be a portable flat name of 1..128 characters");
        }
    }
}
