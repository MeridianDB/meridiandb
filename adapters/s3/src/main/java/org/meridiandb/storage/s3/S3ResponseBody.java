// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.IOException;
import org.meridiandb.storage.object.ObjectIntegrityException;
import org.meridiandb.storage.object.ObjectReference;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Complete bounded body, consumed inside the SDK timer with observed native cleanup. */
final class S3ResponseBody {
    private S3ResponseBody() { }

    static byte[] complete(GetObjectResponse response, AbortableInputStream input, int expectedLength) throws IOException {
        try {
            try (input) {
                try {
                    if (expectedLength < 0 || expectedLength > ObjectReference.MAX_BYTES) {
                        throw new IOException("Expected body length exceeds supported bounds");
                    }
                    if (response.sdkHttpResponse().statusCode() != 200 || response.contentRange() != null) {
                        throw new IOException("Expected a complete S3 200 response without Content-Range");
                    }
                    if (response.contentLength() == null || response.contentLength() != expectedLength) {
                        throw new ObjectIntegrityException(ObjectIntegrityException.Code.LENGTH_MISMATCH,
                                "S3 Content-Length differs from expected complete object");
                    }
                    byte[] image = new byte[expectedLength];
                    int offset = 0;
                    int zeros = 0;
                    while (offset < image.length) {
                        int count = input.read(image, offset, image.length - offset);
                        if (count < 0) { throw new IOException("Short S3 object body"); }
                        if (count == 0 && ++zeros >= 16) { throw new IOException("S3 object read made no progress"); }
                        if (count > 0) { offset += count; zeros = 0; }
                    }
                    if (input.read() != -1) { throw new IOException("S3 body exceeds expected complete object"); }
                    return image;
                } catch (IOException | RuntimeException | Error failure) {
                    BoundedHttpClient.abort(input, failure);
                    throw failure;
                }
            }
        } catch (IOException failure) {
            BoundedHttpClient.abort(input, failure);
            throw failure;
        }
    }
}
