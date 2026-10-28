// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

/** Experimental semantic failure; invalid typed arguments use their constructor exceptions. */
public final class EngineException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code { INVALID_CUTOFF, REQUEST_ID_REUSED, COMMIT_EXHAUSTED }

    private final Code code;

    EngineException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
