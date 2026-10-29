// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.meridiandb.storage.internal.LocalFileOwnership;

/**
 * Create-only local segment I/O in an existing, trusted, stable directory.
 * File force is not directory-entry durability or manifest publication. Failed candidates remain
 * for inspection; external mutation, path replacement and automatic reclamation are unsupported.
 */
public final class LocalSegmentFile {
    private static final int MAX_ZERO_PROGRESS = 16;

    // Narrow seam for deterministic short/failed I/O and close-boundary tests.
    interface FileAccess extends AutoCloseable {
        int read(ByteBuffer target, long offset) throws IOException;
        int write(ByteBuffer source, long offset) throws IOException;
        long size() throws IOException;
        void force() throws IOException;
        @Override void close() throws IOException;
    }

    private LocalSegmentFile() { }

    /**
     * Encode before creating a new path, write completely, force file contents/metadata and close.
     * Never replaces an existing path, including an earlier failed candidate. Success is not a
     * checkpoint or a portable guarantee that the new directory entry survives power loss.
     */
    public static SegmentId writeNew(Path path, ImmutableSegment segment) throws IOException {
        Objects.requireNonNull(path, "path");
        byte[] image = SegmentCodec.encode(segment);
        return write(NioFile.create(path), image);
    }

    /**
     * Read and validate the complete bounded object against an independently expected identifier.
     * The file is closed before the heap-owned reader is returned; no partial reader escapes.
     */
    public static LocalSegmentReader open(Path path, SegmentId expected) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(expected, "expected");
        return read(NioFile.open(path), expected);
    }

    static SegmentId write(FileAccess file, byte[] image) throws IOException {
        SegmentId id;
        try (file) {
            if (file.size() != 0) {
                throw new IOException("New segment must be empty");
            }
            var source = ByteBuffer.wrap(image);
            long offset = 0;
            int zeros = 0;
            while (source.hasRemaining()) {
                int count = file.write(source, offset);
                if (count < 0) {
                    throw new IOException("Negative segment write result");
                }
                if (count == 0 && ++zeros >= MAX_ZERO_PROGRESS) {
                    throw new IOException("Segment write made no progress");
                }
                if (count > 0) {
                    offset += count;
                    zeros = 0;
                }
            }
            if (file.size() != image.length) {
                throw new IOException("Segment size changed during write");
            }
            file.force();
            id = identifier(image);
        }
        return id; // A failed close must not report successful creation.
    }

    static LocalSegmentReader read(FileAccess file, SegmentId expected) throws IOException {
        SegmentCodec.DecodedSegment decoded;
        try (file) {
            Objects.requireNonNull(expected, "expected");
            long size = file.size();
            if (size < 0) {
                throw new IOException("Negative segment file size");
            }
            if (size > SegmentCodec.MAX_SEGMENT_BYTES) {
                throw new SegmentFormatException(SegmentFormatException.Code.LIMIT_EXCEEDED,
                        "Segment file exceeds v1 byte limit");
            }
            var target = ByteBuffer.allocate((int) size);
            long offset = 0;
            int zeros = 0;
            while (target.hasRemaining()) {
                int count = file.read(target, offset);
                if (count < 0) {
                    throw new EOFException("Segment shrank during read");
                }
                if (count == 0 && ++zeros >= MAX_ZERO_PROGRESS) {
                    throw new IOException("Segment read made no progress");
                }
                if (count > 0) {
                    offset += count;
                    zeros = 0;
                }
            }
            // Probe EOF as well as size: never accept an unnoticed appended suffix.
            var extra = ByteBuffer.allocate(1);
            zeros = 0;
            int count;
            do {
                count = file.read(extra, size);
                if (count == 0 && ++zeros >= MAX_ZERO_PROGRESS) {
                    throw new IOException("Segment EOF read made no progress");
                }
            } while (count == 0);
            if (count > 0 || file.size() != size) {
                throw new IOException("Segment size changed during read");
            }
            decoded = SegmentCodec.decode(target.array(), expected);
        }
        return new LocalSegmentReader(decoded); // No reader escapes a failed file close.
    }

    private static SegmentId identifier(byte[] image) {
        try {
            return new SegmentId(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError("Java must provide SHA-256", unavailable);
        }
    }

    private static final class NioFile implements FileAccess {
        private final FileChannel channel;
        private final LocalFileOwnership.OwnedChannel owned;
        private NioFile(LocalFileOwnership.OwnedChannel owned) { this.owned = owned; this.channel = owned.channel(); }
        static NioFile create(Path path) throws IOException { return acquire(path, true); }
        static NioFile open(Path path) throws IOException {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Segment must be a regular local file: " + path);
            }
            return acquire(path, false);
        }
        private static NioFile acquire(Path path, boolean create) throws IOException {
            var owned = create ? LocalFileOwnership.openData(path, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
                    : LocalFileOwnership.openData(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Segment path changed"); }
                return new NioFile(owned);
            } catch (IOException | RuntimeException | Error failure) {
                try { owned.close(); }
                catch (IOException | RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }

        @Override public int read(ByteBuffer target, long offset) throws IOException { return channel.read(target, offset); }
        @Override public int write(ByteBuffer source, long offset) throws IOException { return channel.write(source, offset); }
        @Override public long size() throws IOException { return channel.size(); }
        @Override public void force() throws IOException { channel.force(true); }
        @Override public void close() throws IOException { owned.close(); }
    }
}
