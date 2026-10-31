// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meridiandb.storage.object.ObjectReference;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Stream fault checks supplement real-SDK HTTP conformance; they do not model a provider. */
final class S3ResponseResourcesTest {
    private static final byte[] ABC = {97, 98, 99};
    private static final ObjectReference EXPECTED = ObjectReference.forBytes(ABC);

    @Test void completeReadClosesNativeStreamBeforeReturningBytes() throws IOException {
        var nativeBytes = new TrackedInput(ABC, false);
        var aborted = new AtomicInteger();
        byte[] result = S3ObjectStore.completeImage(response(3), AbortableInputStream.create(nativeBytes, aborted::incrementAndGet), EXPECTED);
        assertArrayEquals(ABC, result);
        assertEquals(1, nativeBytes.closes);
        assertEquals(0, aborted.get());
    }

    @Test void closeFailureReturnsNoImageAndAttemptsAbort() {
        var nativeBytes = new TrackedInput(ABC, true);
        var aborted = new AtomicInteger();
        var rejected = assertThrows(IOException.class, () -> S3ObjectStore.completeImage(response(3),
                AbortableInputStream.create(nativeBytes, aborted::incrementAndGet), EXPECTED));
        assertNotNull(rejected.getCause());
        assertSame(nativeBytes.closeFailure, rejected.getCause());
        assertTrue(aborted.get() > 0);
    }

    @Test void primaryShortReadPreservesCloseAndAbortFailures() {
        var nativeBytes = new TrackedInput(new byte[] {97}, true);
        var abortFailure = new IllegalStateException("injected abort failure");
        var rejected = assertThrows(IOException.class, () -> S3ObjectStore.completeImage(response(3),
                AbortableInputStream.create(nativeBytes, () -> { throw abortFailure; }), EXPECTED));
        var primary = rejected.getCause();
        assertTrue(primary.getMessage().contains("Short"));
        assertTrue(java.util.Arrays.asList(primary.getSuppressed()).contains(nativeBytes.closeFailure));
        assertTrue(java.util.Arrays.asList(primary.getSuppressed()).contains(abortFailure));
    }

    @Test void extraBodyByteAndStalledStreamReturnNoImage() {
        var bytes = new TrackedInput(new byte[] {97, 98, 99, 100}, false);
        var aborted = new AtomicInteger();
        assertThrows(IOException.class, () -> S3ObjectStore.completeImage(response(3),
                AbortableInputStream.create(bytes, aborted::incrementAndGet), EXPECTED));
        assertTrue(aborted.get() > 0);
        assertEquals(1, bytes.closes);
        var stalled = new ByteArrayInputStream(ABC) {
            @Override public int read(byte[] target, int offset, int length) { return 0; }
        };
        assertThrows(IOException.class, () -> S3ObjectStore.completeImage(response(3),
                AbortableInputStream.create(stalled), EXPECTED));
    }

    @Test void boundedControlBodyIsDetachedAfterNativeCloseAndForwardsAbort() throws IOException {
        byte[] bytes = new byte[BoundedHttpClient.MAX_CONTROL_BYTES];
        var nativeBytes = new TrackedInput(bytes, false);
        var delegate = new Transport(nativeBytes, 403);
        try (var guard = new BoundedHttpClient(delegate)) {
            var operation = guard.prepareRequest(request(SdkHttpMethod.GET));
            var result = operation.call();
            assertEquals(1, nativeBytes.closes);
            assertSame(delegate.response.httpResponse(), result.httpResponse());
            try (var detached = result.responseBody().orElseThrow()) { assertArrayEquals(bytes, detached.readAllBytes()); }
            operation.abort();
            assertEquals(1, delegate.requestAborts);
        }
        assertEquals(1, delegate.closes);
    }

    @Test void oversizedControlBodyAndItsCloseFailurePreserveFailureAndAbort() {
        var nativeBytes = new TrackedInput(new byte[BoundedHttpClient.MAX_CONTROL_BYTES + 1], true);
        var delegate = new Transport(nativeBytes, 403);
        try (var guard = new BoundedHttpClient(delegate)) {
            var rejected = assertThrows(IOException.class, () -> guard.prepareRequest(request(SdkHttpMethod.GET)).call());
            assertTrue(rejected.getMessage().contains("64 KiB"));
            assertTrue(java.util.Arrays.asList(rejected.getSuppressed()).contains(nativeBytes.closeFailure));
            assertTrue(delegate.bodyAborts.get() > 0);
        }
    }

    @Test void successfulGetIsConsumedByTheWholeObjectTransformer() throws IOException {
        var nativeBytes = new TrackedInput(ABC, false);
        var delegate = new Transport(nativeBytes, 200);
        try (var guard = new BoundedHttpClient(delegate)) {
            var result = guard.prepareRequest(request(SdkHttpMethod.GET)).call();
            assertSame(delegate.response, result);
            assertEquals(0, nativeBytes.closes);
            assertArrayEquals(ABC, S3ObjectStore.completeImage(response(3), result.responseBody().orElseThrow(), EXPECTED));
            assertEquals(1, nativeBytes.closes);
        }
    }

    @Test void putWithAnySuccessfulBodyIsRejectedAfterNativeCleanup() {
        var nativeBytes = new TrackedInput(ABC, false);
        var delegate = new Transport(nativeBytes, 200);
        try (var guard = new BoundedHttpClient(delegate)) {
            assertThrows(IOException.class, () -> guard.prepareRequest(request(SdkHttpMethod.PUT)).call());
            assertEquals(1, nativeBytes.closes);
        }
    }

    private static GetObjectResponse response(long length) {
        return (GetObjectResponse) GetObjectResponse.builder().contentLength(length)
                .sdkHttpResponse(SdkHttpFullResponse.builder().statusCode(200).build()).build();
    }

    private static HttpExecuteRequest request(SdkHttpMethod method) {
        return HttpExecuteRequest.builder().request(SdkHttpFullRequest.builder()
                .uri(URI.create("http://127.0.0.1/bucket/key")).method(method).build()).build();
    }

    private static final class TrackedInput extends ByteArrayInputStream {
        private final boolean failClose;
        private final IOException closeFailure = new IOException("injected close failure");
        private int closes;
        TrackedInput(byte[] bytes, boolean failClose) { super(bytes); this.failClose = failClose; }
        @Override public void close() throws IOException {
            closes++;
            if (failClose) { throw closeFailure; }
            super.close();
        }
    }

    private static final class Transport implements SdkHttpClient {
        private final AtomicInteger bodyAborts = new AtomicInteger();
        private final HttpExecuteResponse response;
        private int requestAborts;
        private int closes;
        Transport(TrackedInput bytes, int status) {
            response = HttpExecuteResponse.builder().response(SdkHttpFullResponse.builder().statusCode(status).build())
                    .responseBody(AbortableInputStream.create(bytes, bodyAborts::incrementAndGet)).build();
        }
        @Override public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                @Override public HttpExecuteResponse call() { return response; }
                @Override public void abort() { requestAborts++; }
            };
        }
        @Override public void close() { closes++; }
    }
}
