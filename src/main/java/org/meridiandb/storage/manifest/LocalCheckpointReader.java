// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import org.meridiandb.storage.segment.LocalSegmentFile;
import org.meridiandb.storage.segment.LocalSegmentReader;

/**
 * A verified generation's immutable metadata and lazy segment access. May outlive its store;
 * published files are retained. Child heap readers and previously returned values survive close.
 */
public final class LocalCheckpointReader implements AutoCloseable {
    private Path directory;
    private ManifestCodec.DecodedManifest decoded;

    LocalCheckpointReader(Path directory, ManifestCodec.DecodedManifest decoded) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.decoded = Objects.requireNonNull(decoded, "decoded");
    }

    public synchronized ManifestId manifestId() { return usable().id(); }
    public synchronized CheckpointManifest manifest() { return usable().manifest(); }

    /** Load one bounded segment and return a separate heap-owned reader after complete verification. */
    public synchronized LocalSegmentReader openSegment(int index) throws IOException {
        var manifest = usable().manifest();
        var reference = manifest.segments().get(index);
        Path path = directory.resolve(LocalCheckpointStore.segmentName(reference.id()));
        if (Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .size() != reference.byteLength()) { throw new IOException("Referenced segment size differs"); }
        var reader = LocalSegmentFile.open(path, reference.id());
        var segment = reader.segment();
        if (!segment.databaseId().equals(manifest.databaseId()) || segment.firstSequence() != reference.firstSequence()
                || segment.lastSequence() != reference.lastSequence()) {
            reader.close();
            throw new IOException("Referenced segment lineage or range differs");
        }
        return reader;
    }

    @Override public synchronized void close() {
        decoded = null;
        directory = null;
    }

    private ManifestCodec.DecodedManifest usable() {
        if (decoded == null) { throw new IllegalStateException("Checkpoint reader is closed"); }
        return decoded;
    }
}
