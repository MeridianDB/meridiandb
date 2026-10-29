// SPDX-License-Identifier: Apache-2.0
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.CheckpointVerifier;
import org.meridiandb.storage.manifest.HistoryCompactor;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.*;

/** Pure repacking and encoded-byte accounting; creates, publishes and deletes no files. */
class CompactionExample {
    public static void main(String[] args) throws Exception {
        var database = new DatabaseId("repack-example");
        var entity = new EntityId("salary");
        var records = List.of(
                new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(null, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(10L, 20L), 120)))));
        var images = new HashMap<SegmentId, byte[]>();
        var references = new ArrayList<CheckpointManifest.SegmentReference>();
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] header = JournalCodec.encodeHeader(database);
        digest.update(header);
        long prefixBytes = header.length;
        for (var record : records) {
            byte[] image = SegmentCodec.encode(new ImmutableSegment(database, List.of(record)));
            var id = SegmentCodec.decode(image).id();
            images.put(id, image);
            references.add(new CheckpointManifest.SegmentReference(id, image.length, record.sequence(), record.sequence()));
            byte[] frame = JournalCodec.encodeRecord(record);
            prefixBytes += frame.length;
            digest.update(frame);
        }
        var prefix = new CheckpointManifest.JournalPrefix(2, prefixBytes, HexFormat.of().formatHex(digest.digest()));
        var source = new CheckpointManifest(database, 7, prefix, references);
        var plan = HistoryCompactor.compact(source, images::get, HistoryCompactor.Limits.DEFAULT);
        if (plan.segments().size() != 1 || !records.equals(plan.segments().getFirst().records())
                || !prefix.equals(plan.replacementManifest().journalPrefix())
                || plan.replacementManifest().generation() != 8 || plan.outputBytes() >= plan.inputBytes()) {
            throw new AssertionError("Repacking must preserve history and reduce repeated headers in this example");
        }
        var output = new HashMap<SegmentId, byte[]>();
        for (int i = 0; i < plan.segments().size(); i++) {
            output.put(plan.replacementManifest().segments().get(i).id(), SegmentCodec.encode(plan.segments().get(i)));
        }
        CheckpointVerifier.verify(plan.replacementManifest(), output::get);
        System.out.println("Source/repacked segments: " + source.segments().size() + " / " + plan.segments().size());
        System.out.println("Encoded segment bytes: " + plan.inputBytes() + " / " + plan.outputBytes());
        System.out.println("Proposed generation: 8; unchanged temporal cutoff: 2");
    }
}
