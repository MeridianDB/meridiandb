// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import java.util.Objects;

/** A newly committed request or an identical retry's original position. */
public record CommitResult(CommitPosition position, boolean replayed) {
    public CommitResult {
        Objects.requireNonNull(position, "position");
        if (position.sequence() == 0) {
            throw new IllegalArgumentException("Genesis is not a committed request");
        }
    }
}
