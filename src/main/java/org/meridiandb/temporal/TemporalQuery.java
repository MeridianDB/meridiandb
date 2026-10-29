// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Experimental logical queries; point coordinates are signed epoch microseconds. */
public sealed interface TemporalQuery {
    record Get(EntityId entity, long validAtMicros) implements TemporalQuery {
        public Get {
            Objects.requireNonNull(entity, "entity");
        }
    }

    record Scan(EntityId entity, ValidTimeInterval window) implements TemporalQuery {
        public Scan {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(window, "window");
        }
    }

    /** Present matching entities at one valid instant, returned in exact String.compareTo order. */
    record Select(List<EntityId> entities, long validAtMicros, ValuePredicate predicate) implements TemporalQuery {
        public Select {
            entities = List.copyOf(entities);
            if (new HashSet<>(entities).size() != entities.size()) {
                throw new IllegalArgumentException("Selection entity identities must be distinct");
            }
            Objects.requireNonNull(predicate, "predicate");
        }
    }

    /** Filter already resolved state intervals; absence and rejected values remain gaps. */
    record FilterScan(EntityId entity, ValidTimeInterval window, ValuePredicate predicate) implements TemporalQuery {
        public FilterScan {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(predicate, "predicate");
        }
    }

    record Sum(List<EntityId> entities, long validAtMicros) implements TemporalQuery {
        public Sum {
            entities = List.copyOf(entities);
            if (new HashSet<>(entities).size() != entities.size()) {
                throw new IllegalArgumentException("Aggregate entity identities must be distinct");
            }
        }
    }
}
