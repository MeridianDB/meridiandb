// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import java.util.Objects;

/** An experimental request to capture the current head or an explicit knowledge prefix. */
public sealed interface ReadCutoff {
    enum Head implements ReadCutoff { INSTANCE }

    record At(CommitPosition position) implements ReadCutoff {
        public At {
            Objects.requireNonNull(position, "position");
        }
    }
}
