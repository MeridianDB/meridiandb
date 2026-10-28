// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

/** Experimental opaque request identity, compared exactly without normalization. */
public record RequestId(String value) {
    public RequestId {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Request identity must be nonempty");
        }
    }
}
