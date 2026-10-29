// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Logical results shared by test support and the embedded engine; no public syntax is fixed. */
public sealed interface TemporalResult {
    record Point(OptionalLong value) implements TemporalResult {
        public Point {
            Objects.requireNonNull(value, "value");
        }
    }

    record Segments(List<TemporalSegment> values) implements TemporalResult {
        public Segments {
            values = List.copyOf(values);
        }
    }

    record Rows(List<EntityValue> values) implements TemporalResult {
        public Rows {
            values = List.copyOf(values);
        }
    }

    record Aggregate(long count, BigInteger sum) implements TemporalResult {
        public Aggregate {
            Objects.requireNonNull(sum, "sum");
            if (count < 0 || (count == 0 && sum.signum() != 0)) {
                throw new IllegalArgumentException("Invalid aggregate count/sum");
            }
        }
    }
}
