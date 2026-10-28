// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

import java.util.Objects;

/** Canonical SHA-256 of the complete encoded segment, including its integrity footer. */
public record SegmentId(String value) {
    public SegmentId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Segment identifier requires 64 lowercase hexadecimal digits");
        }
    }
}
