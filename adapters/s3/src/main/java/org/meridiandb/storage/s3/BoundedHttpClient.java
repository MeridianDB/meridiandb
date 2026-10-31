// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpMethod;

/** Bounds bodies that the SDK would otherwise unmarshal before the adapter sees them. */
final class BoundedHttpClient implements SdkHttpClient {
    static final int MAX_CONTROL_BYTES = 64 * 1024;
    private final SdkHttpClient delegate;

    BoundedHttpClient(SdkHttpClient delegate) { this.delegate = delegate; }

    @Override public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        var operation = delegate.prepareRequest(request);
        return new ExecutableHttpRequest() {
            @Override public void abort() { operation.abort(); }

            @Override public HttpExecuteResponse call() throws IOException {
                var response = operation.call();
                // Only successful GET bodies reach our bounded whole-image transformer.
                // Buffer other responses before the SDK's XML parser; never pass native resources on.
                if (request.httpRequest().method() == SdkHttpMethod.GET
                        && response.httpResponse().statusCode() == 200) { return response; }
                if (response.responseBody().isEmpty()) { return response; }
                byte[] body;
                var input = response.responseBody().orElseThrow();
                try {
                    try (input) { body = boundedBody(input); }
                } catch (IOException | RuntimeException | Error failure) {
                    BoundedHttpClient.abort(input, failure);
                    throw failure;
                }
                if (request.httpRequest().method() == SdkHttpMethod.PUT
                        && response.httpResponse().isSuccessful() && body.length != 0) {
                    throw new IOException("Unexpected body in successful S3 PUT response; stored outcome may be uncertain");
                }
                return HttpExecuteResponse.builder().response(response.httpResponse())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(body))).build();
            }
        };
    }

    private static byte[] boundedBody(AbortableInputStream input) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int zeros = 0;
        while (true) {
            int count;
            try { count = input.read(buffer, 0, Math.min(buffer.length, MAX_CONTROL_BYTES + 1 - output.size())); }
            catch (IOException | RuntimeException | Error failure) { abort(input, failure); throw failure; }
            if (count == -1) { return output.toByteArray(); }
            if (count == 0) {
                if (++zeros >= 16) { throw rejected(input, "S3 control response made no progress"); }
                continue;
            }
            zeros = 0;
            if (count > MAX_CONTROL_BYTES - output.size()) {
                throw rejected(input, "S3 control response exceeds 64 KiB");
            }
            output.write(buffer, 0, count);
        }
    }

    private static IOException rejected(AbortableInputStream input, String message) {
        var failure = new IOException(message);
        abort(input, failure);
        return failure;
    }

    static void abort(AbortableInputStream input, Throwable primary) {
        try { input.abort(); }
        catch (RuntimeException | Error cleanup) { if (cleanup != primary) { primary.addSuppressed(cleanup); } }
    }

    @Override public String clientName() { return "MeridianDB-bounded-" + delegate.clientName(); }
    @Override public void close() { delegate.close(); }
}
