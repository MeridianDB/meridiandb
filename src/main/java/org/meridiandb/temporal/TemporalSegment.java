// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import java.util.Objects;

/** One present state interval, as opposed to an event or an absence marker. */
public record TemporalSegment(ValidTimeInterval interval, long value) {
    public TemporalSegment {
        Objects.requireNonNull(interval, "interval");
    }
}
