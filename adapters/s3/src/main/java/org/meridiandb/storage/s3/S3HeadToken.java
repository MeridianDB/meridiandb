// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.util.Objects;

/** One bounded strong opaque ASCII ETag, including quotes; not a content ID or writer fence. */
public record S3HeadToken(String value) {
    public static final int MAX_BYTES = 1024;

    public S3HeadToken {
        Objects.requireNonNull(value, "value");
        if (value.length() < 2 || value.length() > MAX_BYTES
                || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            throw new IllegalArgumentException("Expected one quoted strong ETag of at most 1024 ASCII bytes");
        }
        for (int i = 1; i < value.length() - 1; i++) {
            char ch = value.charAt(i);
            if (ch < 0x21 || ch > 0x7e || ch == '"') {
                throw new IllegalArgumentException("ETag contains unsupported or ambiguous characters");
            }
        }
    }
}
