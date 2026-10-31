// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadException;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Bounded conditional bytes for one configured head, separate from immutable object access.
 * No graph/actual-journal verification, durable intent, reconciliation, writer lease or deletion.
 * Every call serializes with close; each instance owns its SDK/transport, not caller credentials.
 */
public final class S3CheckpointHeads implements AutoCloseable {
    public record Binding(UUID namespaceId, UUID publisherId, ObjectId lineageId) {
        public Binding {
            Objects.requireNonNull(namespaceId, "namespaceId");
            Objects.requireNonNull(publisherId, "publisherId");
            Objects.requireNonNull(lineageId, "lineageId");
            if (namespaceId.equals(new UUID(0, 0)) || publisherId.equals(new UUID(0, 0))) {
                throw new IllegalArgumentException("Namespace and publisher UUIDs must not be nil");
            }
        }
    }

    private final S3ClientOwner owner;
    private final Binding binding;
    private final ObjectKey key;
    private boolean closed;

    public S3CheckpointHeads(URI endpoint, Region region, String bucket, AwsCredentialsProvider credentials, Binding binding) {
        this(endpoint, region, bucket, credentials, binding, S3ObjectStore.DEFAULT_TIMEOUTS);
    }

    public S3CheckpointHeads(URI endpoint, Region region, String bucket, AwsCredentialsProvider credentials,
                             Binding binding, S3ObjectStore.Timeouts timeouts) {
        this.binding = Objects.requireNonNull(binding, "binding");
        key = headKey(binding.namespaceId());
        owner = new S3ClientOwner(endpoint, region, bucket, credentials, timeouts);
    }

    public static ObjectKey headKey(UUID namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (namespace.equals(new UUID(0, 0))) { throw new IllegalArgumentException("Namespace UUID must not be nil"); }
        return new ObjectKey("ns-" + namespace.toString().replace("-", "") + "-current.head");
    }

    /** Empty means precisely NoSuchKey, not an initialized namespace's permission to create genesis. */
    public synchronized Optional<S3HeadObservation> read() throws IOException {
        usable();
        final Payload payload;
        try {
            payload = owner.client.getObject(GetObjectRequest.builder().bucket(owner.bucket).key(key.value()).build(),
                    S3CheckpointHeads::completeHead);
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404 && "NoSuchKey".equals(errorCode(failure))) { return Optional.empty(); }
            throw new IOException("S3 head read failed", failure);
        } catch (SdkException failure) {
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
                if (cause instanceof ReadFailure rejected) { throw rejected.failure; }
            }
            throw new IOException("S3 head read failed", failure);
        }
        try {
            var result = S3HeadObservation.restore(owner.endpoint, owner.bucket, payload.image(), payload.token());
            checkBinding(result.head());
            return Optional.of(result);
        } catch (ObjectCheckpointHeadException invalid) { throw new IOException("Invalid S3 head metadata", invalid); }
    }

    /** Validate a revision-one intent, then issue one conditional PUT. No implicit read or retry. */
    public synchronized ObjectReference createIfAbsent(byte[] intentImage) throws IOException {
        usable();
        var intent = intent(intentImage);
        if (intent.decoded().head().revision() != 1) { throw new IllegalArgumentException("Initial head revision must be one"); }
        return write(intent, null);
    }

    /** Use the original observed token and exact candidate bytes; never refresh/rebase the precondition. */
    public synchronized ObjectReference replace(S3HeadObservation previous, byte[] intentImage) throws IOException {
        usable();
        Objects.requireNonNull(previous, "previous");
        if (!owner.endpoint.equals(previous.endpoint()) || !owner.bucket.equals(previous.bucket()) || !key.equals(previous.key())) {
            throw new IllegalArgumentException("Head observation target scope differs");
        }
        checkBinding(previous.head());
        var intent = intent(intentImage);
        ObjectCheckpointHeadVerifier.requireSuccessor(previous.head(), intent.decoded().head());
        return write(intent, previous.token());
    }

    private record Intent(byte[] image, ObjectCheckpointHeadCodec.DecodedHead decoded) { }

    private Intent intent(byte[] image) {
        byte[] snapshot = S3HeadObservation.snapshot(image);
        var decoded = ObjectCheckpointHeadCodec.decode(snapshot);
        checkBinding(decoded.head());
        return new Intent(snapshot, decoded);
    }

    private ObjectReference write(Intent intent, S3HeadToken previous) throws IOException {
        byte[] image = intent.image();
        var request = PutObjectRequest.builder().bucket(owner.bucket).key(key.value()).contentLength((long) image.length)
                .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(intent.decoded().reference().id().value())));
        if (previous == null) { request.ifNoneMatch("*"); }
        else { request.ifMatch(previous.value()); }
        try {
            var response = owner.client.putObject(request.build(), RequestBody.fromBytes(image));
            if (response.sdkHttpResponse().statusCode() != 200) {
                throw new S3HeadWriteException(S3HeadWriteException.Code.UNCERTAIN,
                        response.sdkHttpResponse().statusCode(), "Unexpected S3 head PUT response", null);
            }
            return intent.decoded().reference();
        } catch (S3Exception failure) {
            var code = S3HeadWriteException.Code.SERVICE_FAILURE;
            if (failure.statusCode() == 412 && "PreconditionFailed".equals(errorCode(failure))) {
                code = S3HeadWriteException.Code.PRECONDITION_FAILED;
            } else if (failure.statusCode() == 409 && "ConditionalRequestConflict".equals(errorCode(failure))) {
                code = S3HeadWriteException.Code.CONCURRENT_OPERATION;
            } else if (previous != null && failure.statusCode() == 404 && "NoSuchKey".equals(errorCode(failure))) {
                code = S3HeadWriteException.Code.MISSING_KEY;
            }
            throw new S3HeadWriteException(code, failure.statusCode(), "S3 conditional head request failed", failure);
        } catch (SdkException failure) {
            throw new S3HeadWriteException(S3HeadWriteException.Code.UNCERTAIN, 0,
                    "S3 head write outcome may be uncertain", failure);
        }
    }

    record Payload(byte[] image, S3HeadToken token) { }

    static Payload completeHead(GetObjectResponse response, AbortableInputStream input) throws IOException {
        try {
            byte[] image = S3ResponseBody.complete(response, input, ObjectCheckpointHeadCodec.ENCODED_BYTES);
            var headers = response.sdkHttpResponse();
            var lengths = headers.matchingHeaders("Content-Length");
            var tokens = headers.matchingHeaders("ETag");
            if (lengths.size() != 1 || !"256".equals(lengths.getFirst()) || tokens.size() != 1) {
                throw new IOException("Expected one canonical length and one SDK-visible ETag");
            }
            try { return new Payload(image, new S3HeadToken(tokens.getFirst())); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid S3 head ETag", invalid); }
        } catch (IOException failure) { throw new ReadFailure(failure); }
    }

    private static final class ReadFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final IOException failure;
        ReadFailure(IOException failure) { super(failure); this.failure = failure; }
    }

    private void checkBinding(ObjectCheckpointHead head) {
        ObjectCheckpointHeadVerifier.requireBinding(head, binding.namespaceId(), binding.publisherId(), binding.lineageId());
    }

    private static String errorCode(S3Exception failure) {
        return failure.awsErrorDetails() == null ? null : failure.awsErrorDetails().errorCode();
    }

    @Override public synchronized void close() {
        if (closed) { return; }
        closed = true;
        owner.close();
    }

    private void usable() { if (closed) { throw new IllegalStateException("S3 head adapter is closed"); } }
}
