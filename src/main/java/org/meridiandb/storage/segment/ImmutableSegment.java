// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.RequestId;

/** A nonempty contiguous transaction-history range; it does not discard overwritten temporal facts. */
public record ImmutableSegment(DatabaseId databaseId, List<JournalRecord> records) {
    public ImmutableSegment {
        Objects.requireNonNull(databaseId, "databaseId");
        records = List.copyOf(records);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("An immutable segment needs at least one transaction");
        }
        var identities = new HashSet<RequestId>();
        long previous = 0;
        for (int i = 0; i < records.size(); i++) {
            var record = records.get(i);
            if (i != 0 && (previous == Long.MAX_VALUE || record.sequence() != previous + 1)) {
                throw new IllegalArgumentException("Segment sequences must be contiguous");
            }
            if (!identities.add(record.transaction().requestId())) {
                throw new IllegalArgumentException("Segment request identities must be unique");
            }
            previous = record.sequence();
        }
    }

    public long firstSequence() { return records.getFirst().sequence(); }
    public long lastSequence() { return records.getLast().sequence(); }
}
