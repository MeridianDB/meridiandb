// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;

/** Pure bounded repacking of complete checkpoint history; no publication, deletion or force receipt. */
public final class HistoryCompactor {
    public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    public static final long MAX_TOTAL_RECORDS = 262_144;

    /** Hard serialized-byte and record budgets; these are not exact JVM heap-byte limits. */
    public record Limits(int targetSegmentBytes, long maxInputBytes, long maxOutputBytes, long maxRecords) {
        public static final Limits DEFAULT = new Limits(SegmentCodec.MAX_SEGMENT_BYTES,
                MAX_TOTAL_BYTES, MAX_TOTAL_BYTES, MAX_TOTAL_RECORDS);
        public Limits {
            if (targetSegmentBytes <= 0 || targetSegmentBytes > SegmentCodec.MAX_SEGMENT_BYTES
                    || maxInputBytes < 0 || maxInputBytes > MAX_TOTAL_BYTES
                    || maxOutputBytes < 0 || maxOutputBytes > MAX_TOTAL_BYTES
                    || maxRecords < 0 || maxRecords > MAX_TOTAL_RECORDS) {
                throw new IllegalArgumentException("Invalid compaction limits");
            }
        }
    }

    /** Immutable proposal, constructed only after complete validation; not authority to publish or reclaim. */
    public static final class Plan {
        private final CheckpointManifest source;
        private final ManifestId sourceId;
        private final CheckpointManifest replacement;
        private final List<ImmutableSegment> segments;
        private final long inputBytes;
        private final long outputBytes;
        private Plan(CheckpointManifest source, ManifestId sourceId, CheckpointManifest replacement,
                     List<ImmutableSegment> segments, long inputBytes, long outputBytes) {
            this.source = source;
            this.sourceId = sourceId;
            this.replacement = replacement;
            this.segments = List.copyOf(segments);
            this.inputBytes = inputBytes;
            this.outputBytes = outputBytes;
        }
        public CheckpointManifest sourceManifest() { return source; }
        public ManifestId sourceManifestId() { return sourceId; }
        public CheckpointManifest replacementManifest() { return replacement; }
        public List<ImmutableSegment> segments() { return segments; }
        public long inputBytes() { return inputBytes; }
        public long outputBytes() { return outputBytes; }
    }

    private HistoryCompactor() { }

    /** Read each referenced image once. Keep supplied bytes stable during validation, as for the verifier. */
    public static Plan compact(CheckpointManifest manifest, CheckpointVerifier.SegmentSource source,
                               Limits limits) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        var sourceId = ManifestCodec.decode(ManifestCodec.encode(manifest)).id();
        if (manifest.generation() == Long.MAX_VALUE) { throw limit("Physical generation exhausted"); }
        if (manifest.journalPrefix().sequence() > limits.maxRecords()) { throw limit("Input record budget exceeded"); }
        long inputBytes = 0;
        for (var reference : manifest.segments()) {
            if (reference.byteLength() > limits.maxInputBytes() - inputBytes) {
                throw limit("Input segment-byte budget exceeded");
            }
            inputBytes += reference.byteLength();
        }
        var builder = new Builder(manifest, limits);
        CheckpointVerifier.verify(manifest, source, builder::accept);
        builder.finish();
        var replacement = new CheckpointManifest(manifest.databaseId(), manifest.generation() + 1,
                manifest.journalPrefix(), builder.references);
        ManifestCodec.encode(replacement); // Validate replacement metadata before a proposal can escape.
        return new Plan(manifest, sourceId, replacement, builder.segments, inputBytes, builder.outputBytes);
    }

    private static final class Builder {
        private final CheckpointManifest source;
        private final Limits limits;
        private final List<ImmutableSegment> segments = new ArrayList<>();
        private final List<CheckpointManifest.SegmentReference> references = new ArrayList<>();
        private final List<JournalRecord> current = new ArrayList<>();
        private int overhead;
        private long currentBytes;
        private long outputBytes;
        Builder(CheckpointManifest source, Limits limits) { this.source = source; this.limits = limits; }

        void accept(JournalRecord record) {
            int frameBytes = JournalCodec.encodedRecordBytes(record);
            if (overhead == 0) {
                overhead = SegmentCodec.encodedBytes(new ImmutableSegment(source.databaseId(), List.of(record))) - frameBytes;
                currentBytes = overhead;
            }
            if ((long) overhead + frameBytes > limits.targetSegmentBytes()) {
                throw limit("A whole transaction exceeds the target segment byte limit");
            }
            if (current.size() == SegmentCodec.MAX_RECORDS || currentBytes + frameBytes > limits.targetSegmentBytes()) {
                finish();
            }
            current.add(record);
            currentBytes += frameBytes;
        }

        void finish() {
            if (current.isEmpty()) { return; }
            if (references.size() == ManifestCodec.MAX_SEGMENTS) { throw limit("Output segment-count limit exceeded"); }
            if (currentBytes > limits.maxOutputBytes() - outputBytes) { throw limit("Output segment-byte budget exceeded"); }
            var segment = new ImmutableSegment(source.databaseId(), current);
            byte[] image = SegmentCodec.encode(segment);
            if (image.length != currentBytes) { throw new IllegalStateException("Compaction size accounting differs from codec"); }
            var id = new SegmentId(HexFormat.of().formatHex(ManifestCodec.sha256().digest(image)));
            segments.add(segment);
            references.add(new CheckpointManifest.SegmentReference(id, image.length,
                    segment.firstSequence(), segment.lastSequence()));
            outputBytes += image.length;
            current.clear();
            currentBytes = overhead;
        }
    }

    private static ManifestFormatException limit(String message) {
        return new ManifestFormatException(ManifestFormatException.Code.LIMIT_EXCEEDED, message);
    }
}
