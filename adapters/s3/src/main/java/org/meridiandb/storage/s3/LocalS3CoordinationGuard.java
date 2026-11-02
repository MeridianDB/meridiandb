// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.meridiandb.storage.internal.LocalFileOwnership;

/** Existing permanent native guard only. No creation, enrollment, ledger, SDK or remote authority. */
final class LocalS3CoordinationGuard {
    static final String NAME = ".meridiandb-s3-coordination.lock";
    static final int MAX_PLACEMENTS = 256;
    static final int MAX_LEASES = 1024;

    static final class SharedLockUnsupportedException extends IOException {
        private static final long serialVersionUID = 1L;
        SharedLockUnsupportedException() { super("Coordination requires actual shared native locks"); }
    }
    static final class IdentityUnsupportedException extends IOException {
        private static final long serialVersionUID = 1L;
        IdentityUnsupportedException() { super("Coordination requires a default-filesystem file key"); }
    }
    interface Handle extends AutoCloseable {
        boolean tryLock(boolean shared) throws IOException;
        boolean isShared();
        boolean isValid();
        boolean isOpen();
        void force() throws IOException;
        void release() throws IOException;
        @Override void close() throws IOException;
    }
    @FunctionalInterface interface Opener { Handle open(Path path) throws IOException; }

    private static final Registry PROCESS = new Registry(path -> {
        var owned = LocalFileOwnership.openControl(path, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try { return new NioHandle(owned); }
        catch (RuntimeException | Error failure) {
            try { owned.close(); }
            catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
            throw failure;
        }
    });
    private LocalS3CoordinationGuard() { }

    /** No implicit creation. The future ledger owns fresh initialization and directory durability. */
    static Optional<Lease> acquire(Path directory, boolean shared) throws IOException {
        Objects.requireNonNull(directory, "directory");
        return PROCESS.acquire(directory.resolve(NAME), shared);
    }

    /** One loaded adapter copy per JVM; all participants must use the same trusted stable placement. */
    static final class Registry {
        private final Opener opener;
        private final int placementLimit;
        private final Map<Path, Entry> entries = new HashMap<>();
        Registry(Opener opener) { this(opener, MAX_PLACEMENTS); }
        Registry(Opener opener, int placementLimit) {
            this.opener = Objects.requireNonNull(opener, "opener");
            if (placementLimit < 1 || placementLimit > MAX_PLACEMENTS) { throw new IllegalArgumentException("Invalid placement cap"); }
            this.placementLimit = placementLimit;
        }

        synchronized Optional<Lease> acquire(Path requested, boolean shared) throws IOException {
            Objects.requireNonNull(requested, "requested");
            if (requested.getFileSystem() != FileSystems.getDefault()) { throw new IdentityUnsupportedException(); }
            Path absolute = requested.toAbsolutePath();
            Path path = absolute.getParent().toRealPath().resolve(absolute.getFileName());
            final Object identity;
            try { identity = identity(path); }
            catch (IOException | RuntimeException | Error failure) {
                var existing = entries.get(path);
                if (existing != null) { existing.failed = true; }
                throw failure;
            }
            for (var entry : entries.values()) {
                if (!entry.path.equals(path)) {
                    if (entry.identity.equals(identity)) { throw new IOException("Coordination guard aliases a different registered path"); }
                    continue;
                }
                healthy(entry);
                if (!shared || !entry.shared) { return Optional.empty(); }
                if (entry.references == MAX_LEASES) { throw new IOException("Coordination lease capacity exhausted"); }
                var result = Optional.of(new Lease(this, entry));
                entry.references++;
                return result;
            }
            if (entries.size() == placementLimit) { throw new IOException("Coordination placement capacity exhausted"); }
            var entry = new Entry(path, identity, shared);
            var result = Optional.of(new Lease(this, entry));
            entries.put(path, entry); // Allocate/reserve before opening; reentrant admission sees busy state.
            entry.operating = true;
            try {
                entry.handle = Objects.requireNonNull(opener.open(path), "handle");
                if (!entry.handle.tryLock(shared)) {
                    entry.operating = false;
                    cleanup(entry);
                    return Optional.empty();
                }
                if (shared && !entry.handle.isShared()) { throw new SharedLockUnsupportedException(); }
                if (!shared && entry.handle.isShared()) { throw new IOException("Exclusive coordination requires an exclusive lock"); }
                entry.operating = false;
                healthy(entry); // Recheck path identity after native acquisition, before exposing a lease.
                entry.references = 1;
                return result;
            } catch (IOException | RuntimeException | Error failure) {
                entry.operating = false;
                if (entry.handle == null) { entries.remove(path, entry); }
                else if (!entry.closed) {
                    try { cleanup(entry); }
                    catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
                }
                throw failure;
            }
        }

        private void healthy(Entry entry) throws IOException {
            if (entry.failed || entry.operating || entry.handle == null) {
                throw new IOException("Coordination guard is invalid, busy or cleanup is uncertain");
            }
            try {
                if (!entry.handle.isOpen() || !entry.handle.isValid()) { throw new IOException("Coordination native ownership was lost"); }
                if (!entry.identity.equals(identity(entry.path))) { throw new IOException("Coordination guard name was replaced"); }
            } catch (IOException | RuntimeException | Error failure) { entry.failed = true; throw failure; }
        }
        private synchronized void check(Lease lease) throws IOException {
            if (lease.closing || lease.done) { throw new IOException("Coordination lease is closed"); }
            healthy(lease.entry);
        }
        private synchronized void force(Lease lease) throws IOException {
            check(lease);
            var entry = lease.entry;
            entry.operating = true;
            try { entry.handle.force(); }
            catch (IOException | RuntimeException | Error failure) { entry.failed = true; throw failure; }
            finally { entry.operating = false; }
            healthy(entry);
        }
        private synchronized void release(Lease lease) throws IOException {
            if (lease.done) { return; }
            var entry = lease.entry;
            if (entry.operating) { throw new IOException("Reentrant coordination cleanup"); }
            lease.closing = true; // Invalidate access before attempting independent native cleanup.
            if (entry.references > 1) {
                entry.references--;
                lease.done = true;
                return;
            }
            try { cleanup(entry); }
            finally { if (entry.closed) { lease.done = true; } }
        }
        private void cleanup(Entry entry) throws IOException {
            entry.failed = true;
            if (entry.closeUncertain != null) { throw new IOException("Native coordination close is uncertain; restart the process", entry.closeUncertain); }
            entry.operating = true;
            Throwable failure = null;
            try {
                try { if (entry.handle.isValid()) { entry.handle.release(); } }
                catch (IOException | RuntimeException | Error releaseFailure) { failure = releaseFailure; }
                try {
                    entry.handle.close();
                    if (entry.handle.isOpen()) { throw new IOException("Coordination channel did not close"); }
                    entry.closed = true;
                    entry.references = 0;
                    entries.remove(entry.path, entry);
                } catch (IOException | RuntimeException | Error closeFailure) {
                    entry.closeUncertain = closeFailure; // isOpen()==false cannot prove a thrown close succeeded.
                    if (failure == null) { failure = closeFailure; }
                    else if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
                }
            } finally { entry.operating = false; }
            if (failure instanceof IOException io) { throw io; }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
        }
    }

    private static Object identity(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != 0) { throw new IOException("Coordination guard must be an empty regular nonsymlink file"); }
        Object key = attributes.fileKey();
        if (key == null) { throw new IdentityUnsupportedException(); }
        return key;
    }
    private static final class Entry {
        final Path path;
        final Object identity;
        final boolean shared;
        Handle handle;
        int references;
        boolean failed;
        boolean operating;
        boolean closed;
        Throwable closeUncertain;
        Entry(Path path, Object identity, boolean shared) { this.path = path; this.identity = identity; this.shared = shared; }
    }
    static final class Lease implements AutoCloseable {
        private final Registry registry;
        private final Entry entry;
        private boolean closing;
        private boolean done;
        private Lease(Registry registry, Entry entry) { this.registry = registry; this.entry = entry; }
        void checkValid() throws IOException { registry.check(this); }
        void force() throws IOException { registry.force(this); }
        @Override public void close() throws IOException { registry.release(this); }
    }
    private static final class NioHandle implements Handle {
        private final LocalFileOwnership.OwnedChannel owned;
        private FileLock lock;
        private boolean quarantined;
        NioHandle(LocalFileOwnership.OwnedChannel owned) { this.owned = owned; }
        @Override public boolean tryLock(boolean shared) throws IOException {
            try { lock = owned.channel().tryLock(0, 1, shared); }
            catch (OverlappingFileLockException overlap) {
                quarantined = true;
                throw new IOException("Unexpected overlapping coordination lock; use one loaded adapter copy per JVM", overlap);
            }
            return lock != null;
        }
        @Override public boolean isShared() { return lock != null && lock.isShared(); }
        @Override public boolean isValid() { return lock != null && lock.isValid(); }
        @Override public boolean isOpen() { return owned.channel().isOpen(); }
        @Override public void force() throws IOException { owned.channel().force(true); }
        @Override public void release() throws IOException { lock.release(); }
        @Override public void close() throws IOException {
            if (quarantined) { throw new IOException("Overlapping coordination channel retained; restart the process"); }
            owned.close();
        }
    }
}
