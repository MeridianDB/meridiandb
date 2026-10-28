// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

/**
 * A nonempty half-open interval of signed epoch-microsecond coordinates.
 *
 * <p>This is an experimental primitive under the initial temporal contract,
 * not a stable database API. A null bound means unbounded in that direction;
 * both extreme finite long values remain usable as instants.
 *
 * @param fromInclusiveMicros the included lower bound, or null for unbounded
 * @param toExclusiveMicros the excluded upper bound, or null for unbounded
 */
public record ValidTimeInterval(Long fromInclusiveMicros, Long toExclusiveMicros) {

    /**
     * Rejects intervals containing no representable finite instant.
     *
     * @throws IllegalArgumentException if the interval is empty or reversed
     */
    public ValidTimeInterval {
        if (toExclusiveMicros != null
                && (toExclusiveMicros == Long.MIN_VALUE
                    || (fromInclusiveMicros != null && fromInclusiveMicros >= toExclusiveMicros))) {
            throw new IllegalArgumentException("Valid-time interval must contain a finite instant");
        }
    }

    /**
     * Tests membership without endpoint arithmetic, including at long limits.
     *
     * @param validTimeMicros a finite signed epoch-microsecond coordinate
     * @return whether this interval includes the coordinate
     */
    public boolean contains(long validTimeMicros) {
        return (fromInclusiveMicros == null || validTimeMicros >= fromInclusiveMicros)
                && (toExclusiveMicros == null || validTimeMicros < toExclusiveMicros);
    }
}
