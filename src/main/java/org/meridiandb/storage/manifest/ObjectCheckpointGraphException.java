// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.io.IOException;
import java.util.Objects;

/** Failed bounded graph verification; no partial result, force receipt or selection is established. */
public final class ObjectCheckpointGraphException extends IOException {
    private static final long serialVersionUID = 1L;
    public enum Code { LIMIT_EXCEEDED, INVALID_GRAPH, JOURNAL_MISMATCH }
    private final Code code;

    ObjectCheckpointGraphException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() { return code; }
}
