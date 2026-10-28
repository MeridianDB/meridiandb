// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import java.util.Objects;

/** A database-local knowledge prefix; sequence zero is the empty genesis view. */
public record CommitPosition(DatabaseId databaseId, long sequence) {
    public CommitPosition {
        Objects.requireNonNull(databaseId, "databaseId");
        if (sequence < 0) {
            throw new IllegalArgumentException("Commit sequence must be nonnegative");
        }
    }
}
