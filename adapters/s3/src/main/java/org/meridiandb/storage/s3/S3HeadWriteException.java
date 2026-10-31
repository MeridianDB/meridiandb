// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.IOException;
import java.util.Objects;

/** An observed write failure; no code resolves a prior in-flight intent or permits automatic rebasing. */
public final class S3HeadWriteException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Code { PRECONDITION_FAILED, CONCURRENT_OPERATION, MISSING_KEY, SERVICE_FAILURE, UNCERTAIN }
    private final Code code;
    private final int statusCode;

    S3HeadWriteException(Code code, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.statusCode = statusCode;
    }

    public Code code() { return code; }
    /** Zero when no service status is available. A service error may still follow an effect. */
    public int statusCode() { return statusCode; }
}
