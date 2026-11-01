// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.IOException;
import java.util.Objects;

/** No reconciled selection result; inspect/reopen exact local state before any later operation. */
public final class S3CheckpointPublicationException extends IOException {
    private static final long serialVersionUID = 1L;
    public enum Code { REMOTE_CONFLICT, STILL_PENDING, LIMIT_EXCEEDED }
    private final Code code;
    private final boolean writeAttempted;
    S3CheckpointPublicationException(Code code, boolean writeAttempted, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.writeAttempted = writeAttempted;
    }
    public Code code() { return code; }
    public boolean writeAttempted() { return writeAttempted; }
}
