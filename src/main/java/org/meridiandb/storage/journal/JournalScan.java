// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import java.util.List;
import java.util.Objects;
import org.meridiandb.temporal.DatabaseId;

/** Validated byte prefix, not evidence that any record was forced or acknowledged. */
public record JournalScan(DatabaseId databaseId, List<JournalRecord> records, int validBytes, boolean incompleteTail) {
    public JournalScan {
        Objects.requireNonNull(databaseId, "databaseId");
        records = List.copyOf(records);
        if (validBytes < 0) {
            throw new IllegalArgumentException("Prefix length must be nonnegative");
        }
    }
}
