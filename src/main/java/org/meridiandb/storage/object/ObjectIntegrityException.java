// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import java.io.IOException;

/** Complete bytes disagree with independently supplied length or content identity. */
public final class ObjectIntegrityException extends IOException {
    private static final long serialVersionUID = 1L;
    public enum Code { LENGTH_MISMATCH, ID_MISMATCH }
    private final Code code;

    public ObjectIntegrityException(Code code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() { return code; }
}
