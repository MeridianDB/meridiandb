// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

/** Experimental opaque entity identity, compared exactly without normalization. */
public record EntityId(String value) {
    public EntityId {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Entity identity must be nonempty");
        }
    }
}
