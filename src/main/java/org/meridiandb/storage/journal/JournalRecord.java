// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import java.util.Objects;
import org.meridiandb.temporal.Transaction;

/** One complete transaction in a journal whose header supplies the database lineage. */
public record JournalRecord(long sequence, Transaction transaction) {
    public JournalRecord {
        if (sequence <= 0) {
            throw new IllegalArgumentException("Journal sequences must be positive");
        }
        Objects.requireNonNull(transaction, "transaction");
    }
}
