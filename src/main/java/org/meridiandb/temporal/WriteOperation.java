// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import java.util.Objects;

/** Whole-value range replacement or absence, applied in transaction declaration order. */
public sealed interface WriteOperation {
    EntityId entity();
    ValidTimeInterval interval();

    record Put(EntityId entity, ValidTimeInterval interval, long value) implements WriteOperation {
        public Put {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(interval, "interval");
        }
    }

    record Retract(EntityId entity, ValidTimeInterval interval) implements WriteOperation {
        public Retract {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(interval, "interval");
        }
    }
}
