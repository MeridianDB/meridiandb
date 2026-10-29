// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.meridiandb.storage.internal.LocalFileOwnership;

/** Cooperating live-reader barrier, independent of store lifetime. Never removes its name. */
final class LocalReaderBarrier {
    static final String NAME = ".meridiandb-readers.lock";

    interface Protection extends AutoCloseable {
        void checkValid() throws IOException;
        @Override void close() throws IOException;
    }

    interface Handle extends AutoCloseable {
        boolean tryLock(boolean shared) throws IOException;
        boolean isShared();
        boolean isValid();
        boolean isOpen();
        void release() throws IOException;
        @Override void close() throws IOException;
    }

    @FunctionalInterface interface Opener { Handle open(Path path) throws IOException; }

    private static final Registry PROCESS = new Registry(path -> {
        var owned = LocalFileOwnership.openControl(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try { return new NioHandle(owned); }
        catch (RuntimeException | Error failure) {
            try { owned.close(); }
            catch (IOException | RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    });

    private LocalReaderBarrier() { }

    /** Caller holds exclusive store ownership until admission or reclamation completes. */
    static Optional<Protection> acquire(Path directory, boolean shared) throws IOException {
        validateName(directory);
        Path path = directory.resolve(NAME);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            int entries = 0;
            try (var stream = Files.newDirectoryStream(directory)) {
                for (var ignored : stream) {
                    if (++entries >= LocalCheckpointStore.MAX_DIRECTORY_ENTRIES) {
                        throw new IOException("No directory capacity for reader protection");
                    }
                }
            }
        }
        return PROCESS.acquire(path, shared);
    }

    /** Attribute/identity checks only: never open another handle to a protected inode. */
    static void validateName(Path directory) throws IOException {
        Path path = directory.resolve(NAME);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return; }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != 0) {
            throw new IOException("Reader barrier must be an empty regular nonsymlink file");
        }
        for (String reserved : new String[] {LocalCheckpointStore.JOURNAL, LocalCheckpointStore.LOCK}) {
            Path owned = directory.resolve(reserved);
            if (Files.exists(owned, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(path, owned)) {
                throw new IOException("Reader barrier must not alias the journal or store lock");
            }
        }
    }

    static void rejectObjectAlias(Path directory, Path object) throws IOException {
        for (String reserved : new String[] {LocalCheckpointStore.JOURNAL, LocalCheckpointStore.LOCK, NAME}) {
            Path owned = directory.resolve(reserved);
            if (Files.exists(owned, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(object, owned)) {
                throw new IOException("Checkpoint object must not alias an ownership or journal file");
            }
        }
        rejectActiveAlias(object);
    }

    static void rejectActiveAlias(Path object) throws IOException { PROCESS.rejectAlias(object); }

    /** One channel per canonical guard identity, for one loaded library copy in a JVM. */
    static final class Registry {
        private final Opener opener;
        private final Map<Path, Entry> entries = new HashMap<>();
        Registry(Opener opener) { this.opener = Objects.requireNonNull(opener, "opener"); }

        synchronized Optional<Protection> acquire(Path requested, boolean shared) throws IOException {
            Path path = requested.toAbsolutePath().getParent().toRealPath().resolve(requested.getFileName());
            for (var entry : entries.values()) {
                if (!entry.path.equals(path)) {
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(path, entry.path)) {
                        throw new IOException("Reader barrier aliases another registered path");
                    }
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Registered reader barrier name is missing or changed");
                }
                healthy(entry);
                if (!shared || !entry.shared) { return Optional.empty(); }
                if (entry.references == Integer.MAX_VALUE) { throw new IOException("Reader reference capacity exhausted"); }
                var result = Optional.<Protection>of(new Lease(this, entry));
                entry.references++;
                return result;
            }
            var handle = Objects.requireNonNull(opener.open(path), "handle");
            Entry entry = null;
            final boolean locked;
            try {
                entry = new Entry(path, handle, shared);
                entries.put(path, entry); // Reserve before attempting lock or any cleanup.
                locked = entry.handle.tryLock(shared);
                if (locked) {
                    if (shared && !entry.handle.isShared()) {
                        throw new LocalCheckpointStore.SharedReaderLockUnsupportedException();
                    }
                    if (!shared && entry.handle.isShared()) { throw new IOException("Exclusive protection requires an exclusive lock"); }
                    healthy(entry);
                    entry.references = 1;
                    return Optional.of(new Lease(this, entry));
                }
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    if (entry == null) { closeHandle(handle); }
                    else { cleanup(entry); }
                }
                catch (IOException | RuntimeException | Error closeFailure) {
                    if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
                }
                throw failure;
            }
            cleanup(entry); // A known conflict still needs successful channel cleanup.
            return Optional.empty();
        }

        synchronized void rejectAlias(Path object) throws IOException {
            if (!Files.exists(object, LinkOption.NOFOLLOW_LINKS)) { return; }
            for (var entry : entries.values()) {
                if (Files.isSameFile(object, entry.path)) {
                    throw new IOException("Path aliases a registered reader barrier");
                }
            }
        }

        private void healthy(Entry entry) throws IOException {
            if (entry.failed || !entry.handle.isOpen() || !entry.handle.isValid()) {
                throw new IOException("Reader protection is invalid or cleanup is uncertain");
            }
        }

        private synchronized void check(Lease lease) throws IOException {
            if (lease.closing || lease.done) { throw new IOException("Reader protection is closed"); }
            healthy(lease.entry);
        }

        private synchronized void release(Lease lease) throws IOException {
            if (lease.done) { return; }
            lease.closing = true;
            var entry = lease.entry;
            if (entry.references > 1) {
                entry.references--;
                lease.done = true;
                return;
            }
            try { cleanup(entry); }
            finally { if (!entry.handle.isOpen()) { lease.done = true; } }
        }

        private void cleanup(Entry entry) throws IOException {
            entry.failed = true;
            try { closeHandle(entry.handle); }
            finally {
                // Keep the reservation until actual channel close, including after a failed close.
                if (!entry.handle.isOpen()) { entries.remove(entry.path, entry); }
            }
        }

        private static void closeHandle(Handle handle) throws IOException {
            Throwable failure = null;
            try { if (handle.isValid()) { handle.release(); } }
            catch (IOException | RuntimeException | Error releaseFailure) { failure = releaseFailure; }
            try { handle.close(); }
            catch (IOException | RuntimeException | Error closeFailure) {
                if (failure == null) { failure = closeFailure; }
                else if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
            }
            if (handle.isOpen() && failure == null) { failure = new IOException("Reader channel did not close"); }
            if (failure instanceof IOException io) { throw io; }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
        }
    }

    private static final class Entry {
        final Path path;
        final Handle handle;
        final boolean shared;
        int references;
        boolean failed;
        Entry(Path path, Handle handle, boolean shared) { this.path = path; this.handle = handle; this.shared = shared; }
    }

    private static final class Lease implements Protection {
        private final Registry registry;
        private final Entry entry;
        private boolean closing;
        private boolean done;
        Lease(Registry registry, Entry entry) { this.registry = registry; this.entry = entry; }
        @Override public void checkValid() throws IOException { registry.check(this); }
        @Override public void close() throws IOException { registry.release(this); }
    }

    private static final class NioHandle implements Handle {
        private final FileChannel channel;
        private FileLock lock;
        private boolean quarantined;
        private final LocalFileOwnership.OwnedChannel owned;
        NioHandle(LocalFileOwnership.OwnedChannel owned) { this.owned = owned; this.channel = owned.channel(); }
        @Override public boolean tryLock(boolean shared) throws IOException {
            try { lock = channel.tryLock(0, 1, shared); }
            catch (OverlappingFileLockException overlap) {
                quarantined = true; // Closing this duplicate could release another JVM owner's lock.
                throw new IOException("Unexpected overlapping reader lock; use one loaded adapter copy per JVM", overlap);
            }
            if (lock == null) { return false; }
            channel.force(true); // Establish the permanent empty file through this sole channel.
            return true;
        }
        @Override public boolean isShared() { return lock != null && lock.isShared(); }
        @Override public boolean isValid() { return lock != null && lock.isValid(); }
        @Override public boolean isOpen() { return !owned.isClosed(); }
        @Override public void release() throws IOException { lock.release(); }
        @Override public void close() throws IOException {
            if (quarantined) { throw new IOException("Overlapping reader channel retained; restart the process before reuse"); }
            owned.close();
        }
    }
}
