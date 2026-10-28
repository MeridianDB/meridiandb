// SPDX-License-Identifier: Apache-2.0
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.CheckpointVerifier;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** An in-memory object graph; no file publication, selected generation or durable database. */
class ManifestExample {
    public static void main(String[] args) throws Exception {
        var database = new DatabaseId("example");
        var entity = new EntityId("salary");
        var first = new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                new WriteOperation.Put(entity, new ValidTimeInterval(10L, null), 100))));
        var second = new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                new WriteOperation.Put(entity, new ValidTimeInterval(20L, 30L), 120))));
        var segments = List.of(new ImmutableSegment(database, List.of(first)),
                new ImmutableSegment(database, List.of(second)));
        var references = new ArrayList<CheckpointManifest.SegmentReference>();
        var images = new HashMap<SegmentId, byte[]>();
        var prefix = new ByteArrayOutputStream();
        prefix.writeBytes(JournalCodec.encodeHeader(database));
        for (var segment : segments) {
            byte[] image = SegmentCodec.encode(segment);
            var id = SegmentCodec.decode(image).id();
            images.put(id, image);
            references.add(new CheckpointManifest.SegmentReference(id, image.length,
                    segment.firstSequence(), segment.lastSequence()));
            for (var record : segment.records()) {
                prefix.writeBytes(JournalCodec.encodeRecord(record));
            }
        }
        String prefixHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prefix.toByteArray()));
        var manifest = new CheckpointManifest(database, 7,
                new CheckpointManifest.JournalPrefix(2, prefix.size(), prefixHash), references);
        var decoded = ManifestCodec.decode(ManifestCodec.encode(manifest));
        if (!decoded.manifest().equals(manifest)) {
            throw new AssertionError("Manifest round-trip differs");
        }
        CheckpointVerifier.verify(decoded.manifest(), images::get);
        System.out.println("Verified manifest ID: " + decoded.id().value());
        System.out.println("Physical generation: " + manifest.generation());
        System.out.println("Journal prefix sequence: " + manifest.journalPrefix().sequence());
        System.out.println("Verified referenced segments: " + manifest.segments().size());
    }
}
