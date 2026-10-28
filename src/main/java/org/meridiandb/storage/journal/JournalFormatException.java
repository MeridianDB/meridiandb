// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

/** A format failure, distinct from filesystem failure or a durability acknowledgement. */
public final class JournalFormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INCOMPLETE_HEADER, INCOMPLETE_TAIL, UNSUPPORTED_VERSION, CORRUPT, LIMIT_EXCEEDED, INVALID_HISTORY
    }

    private final Code code;

    JournalFormatException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
