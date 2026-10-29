// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import java.util.Objects;

/** One present entity/value row; selection results omit absence. */
public record EntityValue(EntityId entity, long value) {
    public EntityValue {
        Objects.requireNonNull(entity, "entity");
    }
}
