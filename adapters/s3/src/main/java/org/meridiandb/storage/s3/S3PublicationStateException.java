// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.util.Objects;

/** Failed pure publication-state decoding or transition; no I/O or durable selection is implied. */
public final class S3PublicationStateException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Code {
        INCOMPLETE, UNSUPPORTED_VERSION, CORRUPT, LIMIT_EXCEEDED, INVALID_STATE,
        REFERENCE_MISMATCH, INVALID_BINDING, INVALID_TRANSITION
    }
    private final Code code;

    S3PublicationStateException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }
    public Code code() { return code; }
}
