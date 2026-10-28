// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

/** Strict immutable-object failure; no valid-prefix or tail-repair result is returned. */
public final class SegmentFormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INCOMPLETE, UNSUPPORTED_VERSION, CORRUPT, LIMIT_EXCEEDED, INVALID_HISTORY, IDENTIFIER_MISMATCH
    }

    private final Code code;

    SegmentFormatException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
