// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import java.util.List;
import java.util.Objects;

/** An immutable validated request; its ordered operations are its retry payload. */
public record Transaction(RequestId requestId, List<WriteOperation> operations) {
    public Transaction {
        Objects.requireNonNull(requestId, "requestId");
        operations = List.copyOf(operations);
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("A transaction needs at least one operation");
        }
    }
}
