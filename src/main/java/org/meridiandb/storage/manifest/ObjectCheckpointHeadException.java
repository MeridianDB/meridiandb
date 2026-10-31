// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.util.Objects;

/** Failed pure head decoding, metadata binding or adjacent-transition validation. */
public final class ObjectCheckpointHeadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INCOMPLETE, UNSUPPORTED_VERSION, CORRUPT, LIMIT_EXCEEDED, INVALID_HEAD,
        REFERENCE_MISMATCH, INVALID_BINDING, INVALID_TRANSITION, INVALID_MANIFEST
    }

    private final Code code;

    ObjectCheckpointHeadException(Code code, String message) { this(code, message, null); }

    ObjectCheckpointHeadException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() { return code; }
}
