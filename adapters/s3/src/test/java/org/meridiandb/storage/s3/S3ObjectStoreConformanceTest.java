// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.meridiandb.storage.object.AbstractImmutableObjectStoreConformance;
import org.meridiandb.storage.object.ImmutableObjectStore;

/** Executes the unchanged core contract through the real SDK and real loopback HTTP. */
final class S3ObjectStoreConformanceTest extends AbstractImmutableObjectStoreConformance {
    private S3ProtocolFixture fixture;
    private final List<S3ObjectStore> stores = new ArrayList<>();

    @BeforeEach void start() throws IOException { fixture = new S3ProtocolFixture(); }

    @Override protected ImmutableObjectStore store() {
        var store = S3ObjectStoreTest.connect(fixture);
        stores.add(store);
        return store;
    }

    @AfterEach void stop() throws IOException {
        try {
            for (var store : stores) { store.close(); }
            assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString());
        } finally { fixture.close(); }
    }
}
