// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.meridiandb.storage.internal.LocalFileOwnership;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;

/**
 * Exclusive local storage for exact S3 publication metadata in a dedicated trusted, stable,
 * already durably named directory. Performs no remote operation or graph verification.
 */
public final class LocalS3PublicationState implements AutoCloseable {
    public static final int MAX_DIRECTORY_ENTRIES = 16_384;
    public static final int MAX_GENERATIONS = 1024;
    static final String LOCK = ".meridiandb-s3-publication.lock";
    private static final Pattern SELECTED = Pattern.compile("publication-([0-9]{19})-([0-9a-f]{64})\\.state");

    public static final class DirectorySyncUnsupportedException extends IOException {
        private static final long serialVersionUID = 1L;
        DirectorySyncUnsupportedException(Throwable cause) {
            super("Directory open/force is unavailable; local publication state requires this capability", cause);
        }
    }
    public static final class UncertainInstallationException extends IOException {
        private static final long serialVersionUID = 1L;
        UncertainInstallationException(Throwable cause) {
            super("State rename was attempted; close and reopen to establish selected state", cause);
        }
    }

    enum Boundary { LOCK_SYNCED, CANDIDATE_SYNCED, STATE_RENAMED, DIRECTORY_SYNCED }
    @FunctionalInterface interface PublicationProbe { void reached(Boundary boundary) throws IOException; }
    @FunctionalInterface interface DirectoryProbe { void force(FileChannel directory) throws IOException; }
    interface Namespace extends AutoCloseable {
        List<String> entries() throws IOException;
        byte[] read(String name) throws IOException;
        void writeNew(String name, byte[] image) throws IOException;
        void forceFile(String name) throws IOException;
        void atomicMove(String from, String to) throws IOException;
        void forceDirectory() throws IOException;
        @Override void close() throws IOException;
    }
    interface FileAccess extends AutoCloseable {
        long size() throws IOException;
        int read(ByteBuffer target, long offset) throws IOException;
        int write(ByteBuffer source, long offset) throws IOException;
        void force() throws IOException;
        @Override void close() throws IOException;
    }
    private record Selected(String name, long generation, ObjectId id) { }
    private final Namespace namespace;
    private final PublicationProbe probe;
    private S3PublicationState current;
    private boolean failed;
    private boolean closed;
    private boolean resourcesClosed;
    private boolean operating;

    // Package seam for independently injected storage/failure checks, never a public admission path.
    LocalS3PublicationState(Namespace namespace, S3PublicationState current, PublicationProbe probe) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.current = Objects.requireNonNull(current, "current");
        this.probe = probe;
    }

    /** Initialize only an empty directory and independently provisioned fresh remote namespace. */
    public static LocalS3PublicationState create(Path directory, S3PublicationState.Target target,
                                                 S3CheckpointHeads.Binding binding) throws IOException {
        return acquire(directory, target, binding, true, null, channel -> channel.force(true));
    }
    /** Require existing selected state, validate managed configuration, then synchronize before use. */
    public static LocalS3PublicationState open(Path directory, S3PublicationState.Target target,
                                               S3CheckpointHeads.Binding binding) throws IOException {
        return acquire(directory, target, binding, false, null, channel -> channel.force(true));
    }
    static LocalS3PublicationState createObserved(Path directory, S3PublicationState.Target target,
            S3CheckpointHeads.Binding binding, PublicationProbe probe) throws IOException {
        return acquire(directory, target, binding, true, Objects.requireNonNull(probe, "probe"), channel -> channel.force(true));
    }
    static LocalS3PublicationState openObserved(Path directory, S3PublicationState.Target target,
            S3CheckpointHeads.Binding binding, PublicationProbe probe) throws IOException {
        return acquire(directory, target, binding, false, Objects.requireNonNull(probe, "probe"), channel -> channel.force(true));
    }
    static LocalS3PublicationState acquire(Path directory, S3PublicationState.Target target,
            S3CheckpointHeads.Binding binding, boolean create, PublicationProbe probe, DirectoryProbe directoryProbe) throws IOException {
        var fresh = S3PublicationState.fresh(target, binding); // Pure configuration validation before path effects.
        var namespace = NioNamespace.acquire(directory, create, Objects.requireNonNull(directoryProbe, "directoryProbe"));
        try {
            if (create) {
                namespace.forceFile(LOCK);
                namespace.forceDirectory();
                var store = new LocalS3PublicationState(namespace, fresh, probe);
                store.observe(Boundary.LOCK_SYNCED);
                store.install(fresh, true);
                return store;
            }
            var selected = latest(namespace.entries());
            if (selected == null) { throw new IOException("Initialized publication state is missing"); }
            var state = decode(namespace, selected);
            state.requireConfiguration(target, binding);
            // A prior uncertain installation may be visible without its last force completing.
            namespace.forceFile(LOCK);
            namespace.forceFile(selected.name());
            namespace.forceDirectory();
            return new LocalS3PublicationState(namespace, state, probe);
        } catch (IOException | RuntimeException | Error failure) {
            try { namespace.close(); }
            catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
            throw failure;
        }
    }

    public synchronized S3PublicationState state() throws IOException { usable(); return current; }
    /** Return only after exact pending metadata has been installed and synchronized locally. */
    public synchronized S3PublicationState prepare(byte[] candidate) throws IOException {
        usable();
        return install(current.prepare(candidate), false);
    }
    /** Install pure exact-candidate recognition; the caller still supplies provenance/graph evidence. */
    public synchronized S3PublicationState recordSelected(S3HeadObservation observation) throws IOException {
        usable();
        return install(current.recordSelected(observation), false);
    }

    /** Package admission check before a cooperating preparer's force/upload effects; install rechecks it. */
    synchronized void requirePreparationCapacity() throws IOException {
        usable();
        operating = true;
        try { requireSlot(false); }
        catch (IOException | RuntimeException | Error failure) { failed = true; throw failure; }
        finally { operating = false; }
    }

    private void requireSlot(boolean initial) throws IOException {
        var entries = namespace.entries();
        var selected = latest(entries);
        if (initial ? selected != null : selected == null) { throw new IOException("Unexpected selected publication state"); }
        if (!initial) {
            var actual = decode(namespace, selected);
            if (!S3PublicationStateCodec.reference(actual).equals(S3PublicationStateCodec.reference(current))) {
                throw new IOException("Selected local publication state changed");
            }
        }
        long count = entries.stream().filter(name -> SELECTED.matcher(name).matches()).count();
        if (count >= MAX_GENERATIONS || entries.size() >= MAX_DIRECTORY_ENTRIES) {
            throw new IOException("No capacity for another publication-state generation");
        }
    }

    private S3PublicationState install(S3PublicationState next, boolean initial) throws IOException {
        byte[] image = S3PublicationStateCodec.encode(next); // Pure preflight precedes storage work.
        ObjectReference reference = ObjectReference.forBytes(image);
        boolean renameAttempted = false;
        operating = true;
        try {
            requireSlot(initial);
            String pending = "pending-publication-" + next.generation() + "-" + UUID.randomUUID() + ".state";
            String destination = String.format(Locale.ROOT, "publication-%019d-%s.state", next.generation(), reference.id().value());
            namespace.writeNew(pending, image); // Complete write, force and successful close.
            observe(Boundary.CANDIDATE_SYNCED);
            renameAttempted = true;
            namespace.atomicMove(pending, destination);
            observe(Boundary.STATE_RENAMED);
            namespace.forceDirectory();
            observe(Boundary.DIRECTORY_SYNCED);
            current = next;
            return next;
        } catch (IOException | RuntimeException failure) {
            failed = true;
            if (renameAttempted) { throw new UncertainInstallationException(failure); }
            throw failure;
        } catch (Error failure) {
            failed = true;
            throw failure;
        } finally { operating = false; }
    }

    private static Selected latest(List<String> entries) throws IOException {
        if (entries.size() > MAX_DIRECTORY_ENTRIES) { throw new IOException("Publication-state directory limit exceeded"); }
        var generations = new HashSet<Long>();
        Selected latest = null;
        for (String name : entries) {
            if (!name.startsWith("publication-")) { continue; }
            var matcher = SELECTED.matcher(name);
            if (!matcher.matches()) { throw new IOException("Malformed selected publication-state name"); }
            long generation;
            try { generation = Long.parseLong(matcher.group(1)); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid publication-state generation", invalid); }
            if (generation <= 0 || !generations.add(generation) || generations.size() > MAX_GENERATIONS) {
                throw new IOException("Invalid, duplicate or over-limit publication-state generation");
            }
            if (latest == null || generation > latest.generation()) {
                latest = new Selected(name, generation, new ObjectId(matcher.group(2)));
            }
        }
        return latest;
    }
    private static S3PublicationState decode(Namespace namespace, Selected selected) throws IOException {
        byte[] image = namespace.read(selected.name());
        var decoded = S3PublicationStateCodec.decode(image, new ObjectReference(selected.id(), image.length));
        if (decoded.state().generation() != selected.generation()) { throw new IOException("State generation differs from selected filename"); }
        return decoded.state();
    }
    private void observe(Boundary boundary) throws IOException { if (probe != null) { probe.reached(boundary); } }
    private void usable() throws IOException {
        if (closed || failed || operating) {
            throw new IOException(closed ? "Publication state owner is closed" : failed
                    ? "Publication state failed; close and reopen" : "Reentrant publication-state operation");
        }
    }
    @Override public synchronized void close() throws IOException {
        if (operating) { throw new IOException("Cannot close during a publication-state operation"); }
        closed = true;
        if (!resourcesClosed) { namespace.close(); resourcesClosed = true; }
    }

    static byte[] read(FileAccess file) throws IOException {
        try (file) {
            long size = file.size();
            if (size < 0 || size > S3PublicationStateCodec.MAX_BYTES) { throw new IOException("Publication state exceeds read cap"); }
            var buffer = ByteBuffer.allocate((int) size);
            transfer(file, buffer, false);
            var eof = ByteBuffer.allocate(1);
            int count;
            int zeros = 0;
            do {
                count = file.read(eof, size);
                if (count == 0 && ++zeros == 16) { throw new IOException("EOF probe made no progress"); }
            } while (count == 0);
            if (count != -1 || eof.position() != 0 || file.size() != size) { throw new IOException("Publication-state size changed"); }
            return buffer.array();
        }
    }
    static void write(FileAccess file, byte[] image) throws IOException {
        try (file) {
            if (file.size() != 0) { throw new IOException("New publication-state candidate must be empty"); }
            transfer(file, ByteBuffer.wrap(image), true);
            if (file.size() != image.length) { throw new IOException("Publication-state size changed during write"); }
            file.force();
        }
    }
    private static void transfer(FileAccess file, ByteBuffer buffer, boolean write) throws IOException {
        long offset = 0;
        int zeros = 0;
        while (buffer.hasRemaining()) {
            int before = buffer.position();
            int requested = buffer.remaining();
            int count = write ? file.write(buffer, offset) : file.read(buffer, offset);
            if (count == -1 && !write) { throw new EOFException("Publication state shrank during read"); }
            if (count < 0 || count > requested || buffer.position() - before != count) { throw new IOException("Invalid publication-state I/O progress"); }
            if (count == 0 && ++zeros == 16) { throw new IOException("Publication-state I/O made no progress"); }
            if (count > 0) { offset += count; zeros = 0; }
        }
    }

    static final class NioNamespace implements Namespace {
        private final Path directory;
        final LocalFileOwnership.OwnedChannel owned; // Package seam for native-ownership cleanup checks.
        private final FileChannel directoryChannel;
        private boolean directoryClosed;
        private Throwable directoryUncertain;
        private NioNamespace(Path directory, LocalFileOwnership.OwnedChannel owned, FileChannel directoryChannel) {
            this.directory = directory; this.owned = owned; this.directoryChannel = directoryChannel;
        }
        static NioNamespace acquire(Path requested, boolean create, DirectoryProbe probe) throws IOException {
            Objects.requireNonNull(requested, "directory");
            if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Existing nonsymlink directory required"); }
            Path directory = requested.toRealPath();
            // Explicit supported-placement policy, not inference from an arbitrary access/I/O error.
            // The default Windows provider has no selected directory-channel sync implementation here.
            if (System.getProperty("os.name").startsWith("Windows")
                    && directory.getFileSystem().equals(FileSystems.getDefault())) {
                throw new DirectorySyncUnsupportedException(new UnsupportedOperationException("Default Windows directory synchronization is not supported by this adapter"));
            }
            FileChannel dir = null;
            try { dir = FileChannel.open(directory, StandardOpenOption.READ); probe.force(dir); }
            catch (UnsupportedOperationException unavailable) {
                if (dir != null) { try { dir.close(); } catch (IOException | RuntimeException | Error cleanup) { unavailable.addSuppressed(cleanup); } }
                throw new DirectorySyncUnsupportedException(unavailable);
            } catch (IOException | RuntimeException | Error failure) {
                if (dir != null) { try { dir.close(); } catch (IOException | RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); } }
                throw failure;
            }
            LocalFileOwnership.OwnedChannel owned = null;
            try {
                var entries = list(directory);
                Path lock = directory.resolve(LOCK);
                if (create && !entries.isEmpty()) { throw new IOException("Publication-state creation requires an empty directory"); }
                if (!create && !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Existing regular publication-state lock required"); }
                if (!create) {
                    for (String name : entries) {
                        if (!name.equals(LOCK) && Files.isSameFile(lock, directory.resolve(name))) { throw new IOException("Publication-state lock aliases another entry"); }
                    }
                }
                owned = create ? LocalFileOwnership.openControl(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                        : LocalFileOwnership.openControl(lock, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Publication-state lock path changed"); }
                try { if (owned.channel().tryLock() == null) { throw new IOException("Publication-state owner is held by another process"); } }
                catch (OverlappingFileLockException overlap) { throw new IOException("Publication-state ownership overlaps", overlap); }
                return new NioNamespace(directory, owned, dir);
            } catch (IOException | RuntimeException | Error failure) {
                if (owned != null) { try { owned.close(); } catch (IOException | RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); } }
                try { dir.close(); } catch (IOException | RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        private static List<String> list(Path directory) throws IOException {
            var names = new ArrayList<String>();
            try (var stream = Files.newDirectoryStream(directory)) {
                for (Path entry : stream) {
                    if (names.size() == MAX_DIRECTORY_ENTRIES) { throw new IOException("Publication-state directory limit exceeded"); }
                    names.add(entry.getFileName().toString());
                }
            }
            return names;
        }
        @Override public List<String> entries() throws IOException { return list(directory); }
        private Path regular(String name) throws IOException {
            Path path = directory.resolve(name);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Publication state must be a regular file"); }
            if (!name.equals(LOCK) && Files.isSameFile(path, directory.resolve(LOCK))) { throw new IOException("State aliases its ownership file"); }
            return path;
        }
        @Override public byte[] read(String name) throws IOException { return LocalS3PublicationState.read(new NioFile(regular(name), false)); }
        @Override public void writeNew(String name, byte[] image) throws IOException { LocalS3PublicationState.write(new NioFile(directory.resolve(name), true), image); }
        @Override public void forceFile(String name) throws IOException {
            if (name.equals(LOCK)) { owned.channel().force(true); }
            else { try (var file = new NioFile(regular(name), false, true)) { file.force(); } }
        }
        @Override public void atomicMove(String from, String to) throws IOException {
            if (Files.exists(directory.resolve(to), LinkOption.NOFOLLOW_LINKS)) { throw new FileAlreadyExistsException(to); }
            Files.move(directory.resolve(from), directory.resolve(to), StandardCopyOption.ATOMIC_MOVE);
        }
        @Override public void forceDirectory() throws IOException { directoryChannel.force(true); }
        @Override public void close() throws IOException {
            Throwable failure = null;
            try { owned.close(); } catch (IOException | RuntimeException | Error cleanup) { failure = cleanup; }
            try {
                if (directoryUncertain != null) { throw new IOException("Directory cleanup uncertain; restart process", directoryUncertain); }
                if (!directoryClosed) {
                    try { directoryChannel.close(); directoryClosed = true; }
                    catch (IOException | RuntimeException | Error cleanup) { directoryUncertain = cleanup; throw cleanup; }
                }
            } catch (IOException | RuntimeException | Error cleanup) { if (failure == null) { failure = cleanup; } else if (failure != cleanup) { failure.addSuppressed(cleanup); } }
            if (failure instanceof IOException io) { throw io; }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
        }
    }
    private static final class NioFile implements FileAccess {
        private final LocalFileOwnership.OwnedChannel owned;
        NioFile(Path path, boolean create) throws IOException { this(path, create, create); }
        NioFile(Path path, boolean create, boolean write) throws IOException {
            owned = create ? LocalFileOwnership.openData(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                    : LocalFileOwnership.openData(path, write ? StandardOpenOption.WRITE : StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        }
        @Override public long size() throws IOException { return owned.channel().size(); }
        @Override public int read(ByteBuffer target, long offset) throws IOException { return owned.channel().read(target, offset); }
        @Override public int write(ByteBuffer source, long offset) throws IOException { return owned.channel().write(source, offset); }
        @Override public void force() throws IOException { owned.channel().force(true); }
        @Override public void close() throws IOException { owned.close(); }
    }
}
