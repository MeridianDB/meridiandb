// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3CoordinationState.Mutation;
import org.meridiandb.storage.s3.S3CoordinationState.Operation;
import org.meridiandb.storage.s3.S3CoordinationState.Root;

final class CoordinationFixtures {
    static final UUID PLACEMENT = new UUID(0, 3), NONCE = new UUID(0, 4);
    static final S3PublicationState.Target TARGET = new S3PublicationState.Target(URI.create("http://localhost:9000"), "us-east-1", "bucket");
    static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(new UUID(0, 1), new UUID(0, 2),
            new ObjectId("2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c"));
    static final Root ONE = new Root(vector("head-one"), vector("manifest-one"));
    static final Root TWO = new Root(vector("head-two"), vector("manifest-two"));
    static final S3HeadToken TOKEN = new S3HeadToken("\"orig,opaque\\tag\"");
    private CoordinationFixtures() { }
    static byte[] vector(String name) {
        try (var input = CoordinationFixtures.class.getResourceAsStream("/coordination-v1/" + name + ".hex")) {
            if (input == null) { throw new IllegalStateException("Missing independent coordination vector " + name); }
            return HexFormat.of().parseHex(new String(input.readAllBytes(), StandardCharsets.US_ASCII).replace("\r", "").replace("\n", ""));
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    static S3CoordinationState fresh() { return S3CoordinationState.fresh(TARGET, BINDING, PLACEMENT); }
    static Mutation create(Root root) {
        return new Mutation(Operation.CREATE_IMMUTABLE, S3PublicationPreparer.intentKey(BINDING.namespaceId(), root.reference().id()), root.reference(), null, null);
    }
    static Mutation replace() {
        return new Mutation(Operation.REPLACE_HEAD, S3CheckpointHeads.headKey(BINDING.namespaceId()), TWO.reference(), ONE.reference(), TOKEN);
    }
    static S3CoordinationState registered() { return fresh().register(ONE); }
    static S3CoordinationState pending() { return registered().begin(NONCE, create(ONE)); }
    static S3CoordinationState completed() { var state = pending(); return state.settle(state.pending().orElseThrow(), 200); }
    static S3CoordinationState replacement() { return completed().register(TWO).begin(new UUID(0, 5), replace()); }
    static Root root(int identity, List<CheckpointManifest.SegmentReference> segments) {
        var prefix = segments.isEmpty() ? ONE.manifest().journalPrefix()
                : new CheckpointManifest.JournalPrefix(segments.getLast().lastSequence(), 24 + segments.size() * 160L, "7".repeat(64));
        var manifest = new CheckpointManifest(ONE.manifest().databaseId(), identity, prefix, segments);
        byte[] manifestImage = ManifestCodec.encode(manifest);
        var head = new ObjectCheckpointHead(BINDING.namespaceId(), BINDING.publisherId(), new UUID(1, identity), BINDING.lineageId(),
                1, ObjectReference.forBytes(manifestImage), prefix, null);
        return new Root(ObjectCheckpointHeadCodec.encode(head), manifestImage);
    }
}
