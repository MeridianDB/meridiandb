// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

/** Experimental opaque database lineage identity, compared exactly without normalization. */
public record DatabaseId(String value) {
    public DatabaseId {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Database lineage identity must be nonempty");
        }
    }
}
