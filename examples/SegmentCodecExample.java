// SPDX-License-Identifier: Apache-2.0
import java.util.Arrays;
import java.util.List;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Pure in-memory representation example; no file or durable checkpoint is created. */
class SegmentCodecExample {
    public static void main(String[] args) {
        var entity = new EntityId("salary");
        var records = List.of(
                new JournalRecord(1, new Transaction(new RequestId("original"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(null, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(10L, 20L), 120)))));
        var original = new ImmutableSegment(new DatabaseId("example"), records);
        byte[] image = SegmentCodec.encode(original);
        var decoded = SegmentCodec.decode(image);
        if (!decoded.segment().equals(original)
                || !Arrays.equals(image, SegmentCodec.encode(decoded.segment()))) {
            throw new AssertionError("Segment changed exact transaction history");
        }
        var verified = SegmentCodec.decode(image, decoded.id());
        System.out.println("Segment ID: " + verified.id().value());
        System.out.println("Sequence range: " + verified.segment().firstSequence()
                + ".." + verified.segment().lastSequence());
        System.out.println("Exact transactions: " + verified.segment().records().size());
    }
}
