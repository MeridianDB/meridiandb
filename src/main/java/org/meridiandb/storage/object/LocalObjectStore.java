// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import org.meridiandb.storage.internal.LocalFileOwnership;

/**
 * Flat objects in an existing trusted stable local directory. Each new image is forced and closed;
 * parent-directory durability and checkpoint selection remain the publisher's responsibilities.
 * No long-lived native handles or store-level writer/reader/reclamation ownership are acquired.
 */
public final class LocalObjectStore implements ImmutableObjectStore {
    private static final int MAX_ZERO_PROGRESS = 16;
    private final Path directory;

    interface FileAccess extends AutoCloseable {
        int read(ByteBuffer target, long offset) throws IOException;
        int write(ByteBuffer source, long offset) throws IOException;
        long size() throws IOException;
        void force() throws IOException;
        @Override void close() throws IOException;
    }

    public LocalObjectStore(Path directory) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory").toRealPath();
        if (!Files.isDirectory(this.directory)) { throw new IOException("Object namespace must be an existing directory"); }
    }

    @Override public ObjectReference createNew(ObjectKey key, byte[] image) throws IOException {
        Objects.requireNonNull(key, "key");
        byte[] snapshot = ObjectReference.snapshot(image); // Preflight before opening a path.
        FileAccess file;
        try { file = NioFile.acquire(directory.resolve(key.value()), true); }
        catch (FileAlreadyExistsException exists) { throw new ObjectAlreadyExistsException(key, exists); }
        return write(file, snapshot);
    }

    @Override public VerifiedObject read(ObjectKey key, ObjectReference expected) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expected, "expected");
        return read(NioFile.acquire(directory.resolve(key.value()), false), expected);
    }

    static ObjectReference write(FileAccess file, byte[] image) throws IOException {
        Throwable primary = null;
        try {
            ObjectReference expected = ObjectReference.forBytes(image);
            if (file.size() != 0) { throw new IOException("New object must be empty"); }
            var buffer = ByteBuffer.wrap(image);
            transfer(file, buffer, true);
            if (file.size() != image.length) { throw new IOException("Object size changed during write"); }
            file.force();
            return expected;
        } catch (IOException | RuntimeException | Error failure) { primary = failure; throw failure; }
        finally { close(file, primary); }
    }

    static VerifiedObject read(FileAccess file, ObjectReference expected) throws IOException {
        Throwable primary = null;
        try {
            Objects.requireNonNull(expected, "expected");
            if (file.size() != expected.byteLength()) {
                throw new ObjectIntegrityException(ObjectIntegrityException.Code.LENGTH_MISMATCH, "Stored object length differs");
            }
            var buffer = ByteBuffer.allocate(expected.byteLength());
            transfer(file, buffer, false);
            var extra = ByteBuffer.allocate(1);
            int zeros = 0;
            int count;
            do {
                count = file.read(extra, expected.byteLength());
                if (count == 0 && ++zeros >= MAX_ZERO_PROGRESS) { throw new IOException("Object EOF read made no progress"); }
            } while (count == 0);
            if (count != -1 || file.size() != expected.byteLength()) {
                throw new IOException("Object size changed during read");
            }
            return VerifiedObject.verify(buffer.array(), expected);
        } catch (IOException | RuntimeException | Error failure) { primary = failure; throw failure; }
        finally { close(file, primary); }
    }

    private static void transfer(FileAccess file, ByteBuffer buffer, boolean writing) throws IOException {
        long offset = 0;
        int zeros = 0;
        while (buffer.hasRemaining()) {
            int before = buffer.position();
            int count = writing ? file.write(buffer, offset) : file.read(buffer, offset);
            if (count < 0) {
                if (!writing && count == -1) { throw new EOFException("Object shrank during read"); }
                throw new IOException("Invalid object I/O result");
            }
            if (buffer.position() - before != count) { throw new IOException("Object I/O count differs from progress"); }
            if (count == 0 && ++zeros >= MAX_ZERO_PROGRESS) { throw new IOException("Object I/O made no progress"); }
            if (count > 0) { offset += count; zeros = 0; }
        }
    }

    private static void close(FileAccess file, Throwable primary) throws IOException {
        try { file.close(); }
        catch (IOException | RuntimeException | Error failure) {
            if (primary == null) { throw failure; }
            if (failure != primary) { primary.addSuppressed(failure); }
        }
    }

    private static final class NioFile implements FileAccess {
        private final LocalFileOwnership.OwnedChannel owned;
        private final FileChannel channel;
        private NioFile(LocalFileOwnership.OwnedChannel owned) { this.owned = owned; this.channel = owned.channel(); }

        static NioFile acquire(Path path, boolean create) throws IOException {
            if (!create && !Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new IOException("Object must be a regular non-symbolic-link file");
            }
            var owned = create ? LocalFileOwnership.openData(path, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
                    : LocalFileOwnership.openData(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try {
                if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                    throw new IOException("Object path changed");
                }
                return new NioFile(owned);
            } catch (IOException | RuntimeException | Error failure) {
                try { owned.close(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
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
