// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

/** A failed complete-manifest encoding, decoding or checkpoint consistency check. */
public final class ManifestFormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INCOMPLETE, UNSUPPORTED_VERSION, CORRUPT, LIMIT_EXCEEDED, INVALID_CHECKPOINT, IDENTIFIER_MISMATCH
    }

    private final Code code;

    ManifestFormatException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
