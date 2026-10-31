// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.net.URI;
import java.util.Objects;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;

/**
 * Immutable head bytes/token plus their declared endpoint/bucket/key scope.
 * A restored value is a saved precondition, not proof of a GET, current selection or verified graph.
 */
public final class S3HeadObservation {
    private final URI endpoint;
    private final String bucket;
    private final byte[] image;
    private final ObjectCheckpointHeadCodec.DecodedHead decoded;
    private final S3HeadToken token;

    private S3HeadObservation(URI endpoint, String bucket, byte[] image,
                              ObjectCheckpointHeadCodec.DecodedHead decoded, S3HeadToken token) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.image = image;
        this.decoded = decoded;
        this.token = token;
    }

    /** Caller supplies the exact previously saved same-response image/token and original target scope. */
    public static S3HeadObservation restore(URI endpoint, String bucket, byte[] image, S3HeadToken token) {
        S3ClientOwner.validateTarget(endpoint, bucket);
        Objects.requireNonNull(token, "token");
        byte[] snapshot = snapshot(image);
        return new S3HeadObservation(endpoint, bucket, snapshot, ObjectCheckpointHeadCodec.decode(snapshot), token);
    }

    static byte[] snapshot(byte[] image) {
        Objects.requireNonNull(image, "image");
        if (image.length != ObjectCheckpointHeadCodec.ENCODED_BYTES) {
            throw new IllegalArgumentException("A complete v1 head must have exactly 256 bytes");
        }
        return image.clone();
    }

    public URI endpoint() { return endpoint; }
    public String bucket() { return bucket; }
    public ObjectKey key() { return S3CheckpointHeads.headKey(head().namespaceId()); }
    public byte[] image() { return image.clone(); }
    public ObjectCheckpointHead head() { return decoded.head(); }
    public ObjectReference reference() { return decoded.reference(); }
    public S3HeadToken token() { return token; }
}
