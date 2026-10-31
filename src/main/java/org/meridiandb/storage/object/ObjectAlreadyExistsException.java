// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import java.io.IOException;

/** Conditional immutable creation found an existing name; it does not prove identical content. */
public final class ObjectAlreadyExistsException extends IOException {
    private static final long serialVersionUID = 1L;

    public ObjectAlreadyExistsException(ObjectKey key, IOException cause) {
        super("Object already exists: " + key.value(), cause);
    }
}
