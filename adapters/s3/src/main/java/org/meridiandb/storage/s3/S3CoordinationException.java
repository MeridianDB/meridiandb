// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.util.Objects;

/** Failed pure coordination metadata validation; no remote/native outcome is established. */
public final class S3CoordinationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Code { INCOMPLETE, UNSUPPORTED_VERSION, CORRUPT, LIMIT_EXCEEDED,
        INVALID_STATE, INVALID_BINDING, INVALID_TRANSITION, REFERENCE_MISMATCH }
    private final Code code;
    S3CoordinationException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }
    public Code code() { return code; }
}
