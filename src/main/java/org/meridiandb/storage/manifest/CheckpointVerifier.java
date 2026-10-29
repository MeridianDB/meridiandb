// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.RequestId;

/**
 * Verify all referenced objects against a declared history prefix. No filesystem publication or
 * actual journal source is supplied here. Success is consistency evidence, not a force receipt.
 */
public final class CheckpointVerifier {
    @FunctionalInterface
    public interface SegmentSource {
        /** Return complete bytes for this ID, or null if missing. Keep bytes stable during validation. */
        byte[] read(SegmentId id) throws IOException;
    }

    private CheckpointVerifier() { }

    /** Resolve one segment at a time and return only after complete whole-set consistency checks. */
    public static void verify(CheckpointManifest manifest, SegmentSource source) throws IOException {
        verify(manifest, source, record -> { });
    }

    // Internal collectors must keep all work private until full verification returns successfully.
    // No application visitor API: a malformed late object/hash can invalidate the entire prefix.
    static void verify(CheckpointManifest manifest, SegmentSource source,
                       Consumer<? super JournalRecord> collector) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(collector, "collector");
        byte[] lineage = ManifestCodec.checkedLineage(manifest);
        var digest = ManifestCodec.sha256();
        digest.update(lineage);
        long prefixBytes = lineage.length;
        var identities = new HashSet<RequestId>();
        for (var reference : manifest.segments()) {
            byte[] image = source.read(reference.id());
            if (image == null) {
                throw invalid("Missing referenced segment");
            }
            if (image.length != reference.byteLength()) {
                throw invalid("Referenced segment byte length differs");
            }
            var segment = SegmentCodec.decode(image, reference.id()).segment();
            if (!segment.databaseId().equals(manifest.databaseId())
                    || segment.firstSequence() != reference.firstSequence()
                    || segment.lastSequence() != reference.lastSequence()) {
                throw invalid("Referenced segment lineage or sequence range differs");
            }
            for (var record : segment.records()) {
                if (!identities.add(record.transaction().requestId())) {
                    throw invalid("Request identity reused across segments");
                }
                byte[] frame = JournalCodec.encodeRecord(record);
                prefixBytes += frame.length;
                if (prefixBytes > manifest.journalPrefix().byteLength()) {
                    throw invalid("Reconstructed journal prefix exceeds declared byte length");
                }
                digest.update(frame);
                collector.accept(record);
            }
        }
        if (prefixBytes != manifest.journalPrefix().byteLength()
                || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(manifest.journalPrefix().sha256()))) {
            throw invalid("Reconstructed journal prefix length or SHA-256 differs");
        }
    }

    private static ManifestFormatException invalid(String message) {
        return new ManifestFormatException(ManifestFormatException.Code.INVALID_CHECKPOINT, message);
    }
}
