// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.object.ImmutableObjectStore;

/** Test-only access to the existing concrete core factory and its per-invocation boundary probe. */
public final class S3WritableFaultSupport {
    @FunctionalInterface public interface Probe { void reached(String boundary) throws IOException; }

    private S3WritableFaultSupport() { }

    public static LocalDatabase open(Path directory, ImmutableObjectStore objects, UUID namespace, UUID publisher,
            ObjectCheckpointHead head, ObjectCheckpointGraphs.Limits limits, long minimumJournalSequence,
            Probe probe) throws IOException {
        Objects.requireNonNull(probe, "probe");
        return LocalDatabase.openWritableFromObjectCheckpoint(directory, objects, namespace, publisher, head, limits,
                minimumJournalSequence, boundary -> probe.reached(boundary.name()));
    }
}
