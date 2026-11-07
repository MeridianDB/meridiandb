// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.util.List;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;

/**
 * Heap-owned original checkpoint history from one complete local graph and actual-prefix check.
 * It owns no files or reader protection and grants no publication or reclamation permission.
 * Encoded-work limits bound acquisition; they are not exact heap or total engine-recovery limits.
 */
public final class LocalCheckpointHistory {
    public static final long MAX_OBJECT_BYTES = 64L * 1024 * 1024;
    public static final long MAX_JOURNAL_BYTES = 64L * 1024 * 1024;
    public static final long MAX_RECORD_WORK = 262_144;

    /** Metadata plus dependencies, actual prefix bytes, and checkpoint plus prefix record work. */
    public record Limits(long maxObjectBytes, long maxJournalBytes, long maxRecordWork) {
        public static final Limits DEFAULT = new Limits(MAX_OBJECT_BYTES, MAX_JOURNAL_BYTES, MAX_RECORD_WORK);
        public Limits {
            if (maxObjectBytes < 0 || maxObjectBytes > MAX_OBJECT_BYTES
                    || maxJournalBytes < 0 || maxJournalBytes > MAX_JOURNAL_BYTES
                    || maxRecordWork < 0 || maxRecordWork > MAX_RECORD_WORK) {
                throw new IllegalArgumentException("Invalid local checkpoint history limits");
            }
        }
    }

    /** Successful encoded work; includes the selected manifest and each segment exactly once. */
    public record Usage(long objectBytes, long journalBytes, long recordWork, int objectReads) { }

    private final ManifestCodec.DecodedManifest decoded;
    private final LocalJournal.Prefix actualPrefix;
    private final Usage usage;
    private final List<JournalRecord> records;

    // Only the store's private successful verification path constructs this result.
    LocalCheckpointHistory(ManifestCodec.DecodedManifest decoded, LocalJournal.Prefix actualPrefix,
                           Usage usage, List<JournalRecord> records) {
        this.decoded = decoded;
        this.actualPrefix = actualPrefix;
        this.usage = usage;
        this.records = List.copyOf(records);
    }

    public ManifestId manifestId() { return decoded.id(); }
    public CheckpointManifest manifest() { return decoded.manifest(); }
    public LocalJournal.Prefix actualPrefix() { return actualPrefix; }
    public Usage usage() { return usage; }
    public List<JournalRecord> records() { return records; }
}
