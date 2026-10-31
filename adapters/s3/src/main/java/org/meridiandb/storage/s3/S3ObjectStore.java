// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import org.meridiandb.storage.object.ImmutableObjectStore;
import org.meridiandb.storage.object.ObjectAlreadyExistsException;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.object.VerifiedObject;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Optional, owned synchronous SDK client for one existing general-purpose bucket.
 * No bucket creation, listing, deletion, mutable head, journal or distributed ownership.
 * Operations serialize with close; completed readers retain only verified heap bytes.
 */
public final class S3ObjectStore implements ImmutableObjectStore, AutoCloseable {
    public record Timeouts(Duration operation, Duration connect, Duration socket) {
        public Timeouts {
            positive(operation); positive(connect); positive(socket);
        }
        private static void positive(Duration value) {
            Objects.requireNonNull(value, "timeout");
            if (value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("Timeout must be between 1 ms and 10 minutes");
            }
        }
    }

    public static final Timeouts DEFAULT_TIMEOUTS = new Timeouts(
            Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(15));
    private final String bucket;
    private final S3ClientOwner owner;
    private final S3Client client;
    private boolean closed;

    /** Uses an explicit endpoint, signing region and caller-owned credential provider. */
    public S3ObjectStore(URI endpoint, Region region, String bucket, AwsCredentialsProvider credentials) {
        this(endpoint, region, bucket, credentials, DEFAULT_TIMEOUTS);
    }

    public S3ObjectStore(URI endpoint, Region region, String bucket, AwsCredentialsProvider credentials, Timeouts timeouts) {
        owner = new S3ClientOwner(endpoint, region, bucket, credentials, timeouts);
        this.bucket = owner.bucket;
        client = owner.client;
    }

    @Override public synchronized ObjectReference createNew(ObjectKey key, byte[] image) throws IOException {
        usable();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(image, "image");
        if (image.length > ObjectReference.MAX_BYTES) { throw new IllegalArgumentException("Object exceeds 32 MiB"); }
        byte[] snapshot = image.clone();
        var expected = ObjectReference.forBytes(snapshot);
        var request = PutObjectRequest.builder().bucket(bucket).key(key.value()).ifNoneMatch("*")
                .contentLength((long) snapshot.length)
                .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(expected.id().value())))
                .build();
        try {
            var response = client.putObject(request, RequestBody.fromBytes(snapshot));
            if (response.sdkHttpResponse().statusCode() != 200) { throw new IOException("Unexpected S3 PUT status"); }
            return expected;
        } catch (S3Exception failure) {
            if (failure.statusCode() == 412 && "PreconditionFailed".equals(code(failure))) {
                throw new ObjectAlreadyExistsException(key, new IOException("S3 conditional create conflict", failure));
            }
            throw new IOException("S3 conditional creation failed; stored outcome may be uncertain", failure);
        } catch (SdkException failure) {
            throw new IOException("S3 conditional creation failed; stored outcome may be uncertain", failure);
        }
    }

    @Override public synchronized VerifiedObject read(ObjectKey key, ObjectReference expected) throws IOException {
        usable();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expected, "expected");
        byte[] image;
        try {
            image = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key.value()).build(),
                    (response, input) -> completeImage(response, input, expected));
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404 && "NoSuchKey".equals(code(failure))) {
                var absent = new NoSuchFileException(key.value());
                absent.initCause(failure);
                throw absent;
            }
            throw new IOException("S3 object read failed", failure);
        } catch (SdkException failure) {
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
                if (cause instanceof ReadFailure rejected) { throw rejected.failure; }
            }
            throw new IOException("S3 object read failed", failure);
        }
        return VerifiedObject.verify(image, expected);
    }

    @Override public synchronized CreateResult createOrVerify(ObjectKey key, byte[] image) throws IOException {
        usable();
        return ImmutableObjectStore.super.createOrVerify(key, image);
    }

    static byte[] completeImage(GetObjectResponse response, AbortableInputStream input,
                               ObjectReference expected) throws IOException {
        try { return S3ResponseBody.complete(response, input, expected.byteLength()); }
        catch (IOException failure) { throw new ReadFailure(failure); }
    }

    private static String code(S3Exception failure) {
        return failure.awsErrorDetails() == null ? null : failure.awsErrorDetails().errorCode();
    }

    private static final class ReadFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final IOException failure;
        ReadFailure(IOException failure) { super(failure); this.failure = failure; }
    }

    @Override public synchronized void close() {
        if (closed) { return; }
        closed = true;
        owner.close();
    }

    private void usable() { if (closed) { throw new IllegalStateException("S3 object store is closed"); } }
}
