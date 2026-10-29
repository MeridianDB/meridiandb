// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.meridiandb.storage.internal.LocalFileOwnership;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;

/**
 * Exclusive local checkpoint publisher in an existing trusted, stable, durably named directory.
 * All dependencies share that directory. Atomic rename and directory force are required, without
 * fallback. This protocol does not integrate engine acknowledgement or reclaim any history.
 */
public final class LocalCheckpointStore implements AutoCloseable {
    public static final int MAX_DIRECTORY_ENTRIES = 16_384;
    public static final int MAX_GENERATIONS = 1024;
    static final String JOURNAL = "transactions.journal";
    static final String LOCK = ".meridiandb-checkpoints.lock";
    private static final Pattern GENERATION = Pattern.compile("checkpoint-([0-9]{19})-([0-9a-f]{64})\\.manifest");

    public static final class DirectorySyncUnsupportedException extends IOException {
        private static final long serialVersionUID = 1L;
        DirectorySyncUnsupportedException(Throwable cause) {
            super("Directory open/force is unavailable; checkpoint publication requires this capability", cause);
        }
    }

    public static final class SharedReaderLockUnsupportedException extends IOException {
        private static final long serialVersionUID = 1L;
        SharedReaderLockUnsupportedException() {
            super("Shared file locking is unavailable; lazy checkpoint readers require this capability");
        }
    }

    public static final class UncertainPublicationException extends IOException {
        private static final long serialVersionUID = 1L;
        UncertainPublicationException(Throwable cause) {
            super("Atomic publication was attempted; close and reopen to establish the selected generation", cause);
        }
    }

    // Per-instance package seam; production callers have no observer or global fault state.
    enum PublicationBoundary { JOURNAL_CREATED, DEPENDENCIES_SYNCED, CANDIDATE_SYNCED, MANIFEST_RENAMED, DIRECTORY_SYNCED }
    @FunctionalInterface interface PublicationProbe { void reached(PublicationBoundary boundary) throws IOException; }

    @FunctionalInterface
    interface DirectoryProbe {
        void force(FileChannel directory) throws IOException;
    }

    interface Namespace extends AutoCloseable {
        Path directory();
        LocalReaderBarrier.Protection acquireReader() throws IOException;
        Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() throws IOException;
        List<String> entries() throws IOException;
        byte[] read(String name, int maxBytes) throws IOException;
        void writeNew(String name, byte[] image) throws IOException;
        void forceFile(String name) throws IOException;
        void atomicMove(String from, String to) throws IOException;
        void forceDirectory() throws IOException;
        @Override void close() throws IOException;
    }

    interface JournalAccess {
        DatabaseId databaseId();
        LocalJournal.Prefix prefix(long sequence) throws IOException;
        void force() throws IOException;
    }

    interface FileAccess extends AutoCloseable {
        int read(ByteBuffer target, long offset) throws IOException;
        int write(ByteBuffer source, long offset) throws IOException;
        long size() throws IOException;
        void force() throws IOException;
        @Override void close() throws IOException;
    }

    private record Generation(String name, long number, ManifestId id) { }
    private final PublicationProbe probe;
    private final Namespace namespace;
    private boolean failed;
    private boolean closed;
    private boolean resourcesClosed;
    private boolean operating;

    LocalCheckpointStore(Namespace namespace) { this(namespace, null); }

    LocalCheckpointStore(Namespace namespace, PublicationProbe probe) {
        this.probe = probe;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /** Capability is probed before any lock or candidate file is created. */
    public static LocalCheckpointStore open(Path directory) throws IOException {
        return open(directory, channel -> channel.force(true));
    }

    static LocalCheckpointStore open(Path directory, DirectoryProbe probe) throws IOException {
        return new LocalCheckpointStore(NioNamespace.acquire(directory, Objects.requireNonNull(probe, "probe")));
    }

    static LocalCheckpointStore openObserved(Path directory, PublicationProbe probe) throws IOException {
        Objects.requireNonNull(probe, "probe");
        return new LocalCheckpointStore(NioNamespace.acquire(directory, channel -> channel.force(true)), probe);
    }

    private void observe(PublicationBoundary boundary) throws IOException {
        if (probe != null) { probe.reached(boundary); }
    }

    public synchronized Path journalPath() throws IOException {
        usable();
        return namespace.directory().resolve(JOURNAL);
    }

    /**
     * Initialize a fresh store with an owned journal and selected genesis generation one.
     * Only this store's ownership file may already exist. Failure retains all candidate evidence.
     * The returned journal is owned by the caller and must be closed separately.
     */
    public synchronized LocalJournal initialize(DatabaseId databaseId) throws IOException {
        usable();
        JournalCodec.encodeHeader(databaseId); // Invalid lineage must not create a journal candidate.
        LocalJournal journal = null;
        operating = true;
        try {
            if (namespace.entries().stream().anyMatch(name -> !name.equals(LOCK))) {
                throw new IOException("Database initialization requires a fresh directory");
            }
            journal = LocalJournal.create(namespace.directory().resolve(JOURNAL), databaseId);
            observe(PublicationBoundary.JOURNAL_CREATED);
            var prefix = journal.prefix(0);
            var genesis = new CheckpointManifest(databaseId, 1,
                    new CheckpointManifest.JournalPrefix(0, prefix.byteLength(), prefix.sha256()), List.of());
            operating = false; // publish owns the guarded I/O phase; the store monitor remains held.
            publish(genesis, journal);
            return journal;
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            if (journal != null) {
                try { journal.close(); }
                catch (IOException | RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw failure;
        } finally {
            operating = false;
        }
    }

    /** Establish a recovered owner's file and name before enabling durable write acknowledgements. */
    public synchronized void synchronizeJournal(LocalJournal journal) throws IOException {
        usable();
        var owned = access(journal);
        operating = true;
        try {
            owned.force();
            namespace.forceDirectory();
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    /** Create a new content-addressed segment; an existing candidate is never replaced or repaired. */
    public synchronized SegmentId writeSegment(ImmutableSegment segment) throws IOException {
        usable();
        byte[] image = SegmentCodec.encode(segment);
        var id = new SegmentId(HexFormat.of().formatHex(ManifestCodec.sha256().digest(image)));
        operating = true;
        try {
            requireCapacity(false);
            namespace.writeNew(segmentName(id), image);
            return id;
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    public synchronized ManifestId publish(CheckpointManifest manifest, LocalJournal journal) throws IOException {
        usable();
        return publish(manifest, access(journal));
    }

    synchronized ManifestId publish(CheckpointManifest manifest, JournalAccess journal) throws IOException {
        usable();
        Objects.requireNonNull(journal, "journal");
        byte[] image = ManifestCodec.encode(manifest); // Validate caller metadata before storage work.
        var id = ManifestCodec.decode(image).id();
        operating = true;
        try {
            var previous = loadLatest(journal);
            long generation = previous.map(value -> value.manifest().generation()).orElse(0L);
            if (generation == Long.MAX_VALUE || manifest.generation() != generation + 1
                    || (previous.isPresent() && manifest.journalPrefix().sequence()
                        < previous.orElseThrow().manifest().journalPrefix().sequence())) {
                throw new IllegalArgumentException("Publication requires the next generation and a nondecreasing cutoff");
            }
            requireCapacity(true);
            verify(manifest, journal);
            return publishVerified(manifest, image, id, journal);
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    /**
     * Install a pure compaction plan for the exact currently selected generation and publish its
     * replacement at the same journal cutoff. Existing content is verified byte for byte before
     * reuse; missing objects are created, never replaced. Even unchanged layouts publish the next
     * generation. All older objects, failed candidates and the complete journal remain retained.
     * Close any database owner before acquiring this store and its journal for compaction.
     */
    public synchronized ManifestId publishCompaction(HistoryCompactor.Plan plan, LocalJournal journal) throws IOException {
        usable();
        return publishCompaction(plan, access(journal));
    }

    synchronized ManifestId publishCompaction(HistoryCompactor.Plan plan, JournalAccess journal) throws IOException {
        usable();
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(journal, "journal");
        var replacement = plan.replacementManifest();
        byte[] manifestImage = ManifestCodec.encode(replacement);
        var id = ManifestCodec.decode(manifestImage).id();
        operating = true;
        try {
            loadLatest(journal, plan.sourceManifestId())
                    .orElseThrow(() -> new IOException("Compaction requires a selected source generation"));
            // Plan construction is private and validates immutable metadata/history. Recheck the
            // selected stored graph and actual owned journal above before any installation effect.
            var entries = namespace.entries();
            var existing = new HashSet<>(entries);
            int missing = 0;
            for (int index = 0; index < plan.segments().size(); index++) {
                String name = segmentName(replacement.segments().get(index).id());
                if (existing.contains(name)) {
                    byte[] expected = SegmentCodec.encode(plan.segments().get(index));
                    byte[] stored = namespace.read(name, expected.length);
                    if (!Arrays.equals(expected, stored)) {
                        throw new IOException("Existing compaction object differs from its expected complete image: " + name);
                    }
                } else {
                    missing++;
                }
            }
            // Reserve every missing object and the candidate before creating the first one. The
            // rename replaces the candidate name, so it requires no additional directory slot.
            if ((long) entries.size() + missing + 1 + (existing.contains(LocalReaderBarrier.NAME) ? 0 : 1) > MAX_DIRECTORY_ENTRIES
                    || entries.stream().filter(name -> name.startsWith("checkpoint-")).count() >= MAX_GENERATIONS) {
                throw new IOException("No capacity for complete compaction installation and publication");
            }
            for (int index = 0; index < plan.segments().size(); index++) {
                String name = segmentName(replacement.segments().get(index).id());
                if (!existing.contains(name)) {
                    namespace.writeNew(name, SegmentCodec.encode(plan.segments().get(index)));
                }
            }
            verify(replacement, journal);
            return publishVerified(replacement, manifestImage, id, journal);
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    /** Shared force/selection protocol; caller holds the monitor and guarded I/O phase. */
    private ManifestId publishVerified(CheckpointManifest manifest, byte[] image, ManifestId id,
                                       JournalAccess journal) throws IOException {
        boolean moving = false;
        try {
            journal.force(); // Through the owning journal, never a second same-file channel.
            for (var reference : manifest.segments()) {
                namespace.forceFile(segmentName(reference.id()));
            }
            namespace.forceDirectory(); // Persist all dependency names, including the journal name.
            observe(PublicationBoundary.DEPENDENCIES_SYNCED);
            String pending = "pending-" + manifest.generation() + "-" + UUID.randomUUID() + ".manifest";
            namespace.writeNew(pending, image); // Full write, force and successful close before rename.
            observe(PublicationBoundary.CANDIDATE_SYNCED);
            String selected = generationName(manifest.generation(), id);
            moving = true; // A move may take effect and then throw: never infer rollback from an exception.
            namespace.atomicMove(pending, selected);
            observe(PublicationBoundary.MANIFEST_RENAMED);
            namespace.forceDirectory();
            observe(PublicationBoundary.DIRECTORY_SYNCED);
            return id;
        } catch (IOException | RuntimeException failure) {
            if (moving) { throw new UncertainPublicationException(failure); }
            throw failure;
        }
    }

    public synchronized Optional<LocalCheckpointReader> readLatest(LocalJournal journal) throws IOException {
        usable();
        return readLatest(access(journal));
    }

    synchronized Optional<LocalCheckpointReader> readLatest(JournalAccess journal) throws IOException {
        usable();
        Objects.requireNonNull(journal, "journal");
        operating = true;
        try {
            var selected = loadLatest(journal);
            if (selected.isEmpty()) { return Optional.empty(); }
            // Store ownership excludes reclamation throughout verification and admission. Acquire
            // independent protection before exposing the reader or permitting store close.
            var protection = namespace.acquireReader();
            try {
                return Optional.of(new LocalCheckpointReader(namespace.directory(), selected.orElseThrow(), protection));
            } catch (RuntimeException | Error failure) {
                try { protection.close(); }
                catch (IOException | RuntimeException | Error closeFailure) {
                    if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
                }
                throw failure;
            }
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    /** Internal foundation for reclamation. No deletion operation is exposed by this adapter yet. */
    synchronized Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() throws IOException {
        usable();
        operating = true;
        try { return namespace.tryReclamationBarrier(); }
        catch (IOException | RuntimeException | Error failure) { failed = true; throw failure; }
        finally { operating = false; }
    }

    private Optional<ManifestCodec.DecodedManifest> loadLatest(JournalAccess journal) throws IOException {
        return loadLatest(journal, null);
    }

    private Optional<ManifestCodec.DecodedManifest> loadLatest(JournalAccess journal, ManifestId expectedSource) throws IOException {
        List<String> entries = namespace.entries();
        if (entries.size() > MAX_DIRECTORY_ENTRIES) { throw new IOException("Checkpoint directory entry limit exceeded"); }
        Generation latest = null;
        var seen = new HashSet<Long>();
        for (String name : entries) {
            if (!name.startsWith("checkpoint-")) { continue; }
            var match = GENERATION.matcher(name);
            if (!match.matches()) { throw new IOException("Malformed selected checkpoint name: " + name); }
            final long number;
            try { number = Long.parseLong(match.group(1)); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid checkpoint generation", invalid); }
            if (number <= 0 || !seen.add(number)) { throw new IOException("Invalid or repeated checkpoint generation"); }
            if (seen.size() > MAX_GENERATIONS) { throw new IOException("Retained checkpoint generation limit exceeded"); }
            var generation = new Generation(name, number, new ManifestId(match.group(2)));
            if (latest == null || number > latest.number()) { latest = generation; }
        }
        if (latest == null) { return Optional.empty(); }
        if (expectedSource != null && !latest.id().equals(expectedSource)) {
            throw new IOException("Compaction source is no longer the selected generation");
        }
        var decoded = ManifestCodec.decode(namespace.read(latest.name(), ManifestCodec.MAX_MANIFEST_BYTES), latest.id());
        if (decoded.manifest().generation() != latest.number()) { throw new IOException("Filename generation differs from manifest"); }
        verify(decoded.manifest(), journal); // Fail on damaged newest state; never silently fall back.
        return Optional.of(decoded);
    }

    private void requireCapacity(boolean newGeneration) throws IOException {
        var entries = namespace.entries();
        if ((long) entries.size() + 1 + (entries.contains(LocalReaderBarrier.NAME) ? 0 : 1) > MAX_DIRECTORY_ENTRIES) {
            throw new IOException("No capacity for another checkpoint object and permanent reader barrier");
        }
        if (newGeneration && entries.stream().filter(name -> name.startsWith("checkpoint-")).count() >= MAX_GENERATIONS) {
            throw new IOException("No capacity for another retained generation");
        }
    }

    private void verify(CheckpointManifest manifest, JournalAccess journal) throws IOException {
        if (!manifest.databaseId().equals(journal.databaseId())) { throw new IOException("Checkpoint and journal lineage differ"); }
        CheckpointVerifier.verify(manifest, id -> namespace.read(segmentName(id), SegmentCodec.MAX_SEGMENT_BYTES));
        var actual = journal.prefix(manifest.journalPrefix().sequence());
        var expected = manifest.journalPrefix();
        if (actual.sequence() != expected.sequence() || actual.byteLength() != expected.byteLength()
                || !actual.sha256().equals(expected.sha256())) {
            throw new IOException("Stored journal prefix differs from checkpoint history");
        }
    }

    private JournalAccess access(LocalJournal journal) throws IOException {
        Objects.requireNonNull(journal, "journal");
        if (!journal.path().orElseThrow(() -> new IOException("A real owned local journal is required"))
                .equals(namespace.directory().resolve(JOURNAL))) {
            throw new IOException("Journal must occupy this store's transactions.journal path");
        }
        return new JournalAccess() {
            @Override public DatabaseId databaseId() { return journal.databaseId(); }
            @Override public LocalJournal.Prefix prefix(long sequence) throws IOException { return journal.prefix(sequence); }
            @Override public void force() throws IOException { journal.force(); }
        };
    }

    static String segmentName(SegmentId id) { return Objects.requireNonNull(id, "id").value() + ".segment"; }
    static String generationName(long generation, ManifestId id) {
        return String.format(Locale.ROOT, "checkpoint-%019d-%s.manifest", generation, id.value());
    }

    @Override public synchronized void close() throws IOException {
        if (operating) { throw new IOException("Cannot close store inside a storage callback"); }
        closed = true;
        if (!resourcesClosed) {
            namespace.close();
            resourcesClosed = true;
        }
    }

    private void usable() throws IOException {
        if (closed || failed || operating) {
            throw new IOException(closed ? "Checkpoint store is closed" : failed
                    ? "Checkpoint store failed; close and reopen before retry" : "Reentrant checkpoint operation");
        }
    }

    static byte[] read(FileAccess file, int maxBytes) throws IOException {
        try (file) {
            long size = file.size();
            if (size < 0 || size > maxBytes) { throw new IOException("Checkpoint object exceeds read bounds"); }
            var buffer = ByteBuffer.allocate((int) size);
            long offset = 0;
            int zeros = 0;
            while (buffer.hasRemaining()) {
                int count = file.read(buffer, offset);
                if (count < 0) { throw new EOFException("Checkpoint object shrank during read"); }
                if (count == 0 && ++zeros >= 16) { throw new IOException("Checkpoint object read made no progress"); }
                if (count > 0) { offset += count; zeros = 0; }
            }
            var probe = ByteBuffer.allocate(1);
            zeros = 0;
            int count;
            do {
                count = file.read(probe, size);
                if (count == 0 && ++zeros >= 16) { throw new IOException("Checkpoint EOF read made no progress"); }
            } while (count == 0);
            if (count > 0 || file.size() != size) { throw new IOException("Checkpoint object size changed"); }
            return buffer.array();
        }
    }

    static void write(FileAccess file, byte[] image) throws IOException {
        try (file) {
            if (file.size() != 0) { throw new IOException("New checkpoint object must be empty"); }
            var buffer = ByteBuffer.wrap(image);
            long offset = 0;
            int zeros = 0;
            while (buffer.hasRemaining()) {
                int count = file.write(buffer, offset);
                if (count < 0 || (count == 0 && ++zeros >= 16)) { throw new IOException("Checkpoint write made no progress"); }
                if (count > 0) { offset += count; zeros = 0; }
            }
            if (file.size() != image.length) { throw new IOException("Checkpoint object size changed during write"); }
            file.force();
        }
    }

    private static final class NioFile implements FileAccess {
        private final FileChannel channel;
        private final LocalFileOwnership.OwnedChannel owned;
        NioFile(LocalFileOwnership.OwnedChannel owned) { this.owned = owned; this.channel = owned.channel(); }
        static NioFile open(Path path, StandardOpenOption option) throws IOException { return open(path, option, false); }
        static NioFile create(Path path) throws IOException { return open(path, StandardOpenOption.WRITE, true); }
        private static NioFile open(Path path, StandardOpenOption option, boolean create) throws IOException {
            var owned = create ? LocalFileOwnership.openData(path, option, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
                    : LocalFileOwnership.openData(path, option, LinkOption.NOFOLLOW_LINKS);
            try { return new NioFile(owned); }
            catch (RuntimeException | Error failure) {
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

    private static final class NioNamespace implements Namespace {
        private final Path directory;
        private final Path lockPath;
        private final FileChannel directoryChannel;
        private final FileLock lock;
        private final LocalFileOwnership.OwnedChannel owned;

        private NioNamespace(Path directory, Path lockPath, FileChannel directoryChannel, FileLock lock, LocalFileOwnership.OwnedChannel owned) {
            this.directory = directory;
            this.lockPath = lockPath;
            this.directoryChannel = directoryChannel;
            this.lock = lock;
            this.owned = owned;
        }

        static NioNamespace acquire(Path requested, DirectoryProbe probe) throws IOException {
            Objects.requireNonNull(requested, "directory");
            if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Store requires an existing nonsymlink directory"); }
            Path directory = requested.toRealPath();
            FileChannel dir = null;
            try {
                dir = FileChannel.open(directory, StandardOpenOption.READ);
                probe.force(dir); // Required probe before lock-file creation or any other mutation.
            } catch (IOException | UnsupportedOperationException unavailable) {
                if (dir != null) {
                    try { dir.close(); } catch (IOException closeFailure) { unavailable.addSuppressed(closeFailure); }
                }
                throw new DirectorySyncUnsupportedException(unavailable);
            } catch (RuntimeException | Error failure) {
                if (dir != null) {
                    try { dir.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                }
                throw failure;
            }
            Path lockPath = directory.resolve(LOCK);
            FileChannel channel = null;
            LocalFileOwnership.OwnedChannel owned = null;
            try {
                LocalReaderBarrier.validateName(directory);
                LocalReaderBarrier.rejectActiveAlias(lockPath);
                LocalReaderBarrier.rejectActiveAlias(directory.resolve(JOURNAL));
                var existing = listNames(directory);
                if ((long) existing.size() + (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) ? 0 : 1)
                        + (existing.contains(LocalReaderBarrier.NAME) ? 0 : 1) > MAX_DIRECTORY_ENTRIES) {
                    throw new IOException("No directory capacity for checkpoint ownership file");
                }
                if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Checkpoint lock must be a regular file");
                }
                Path journalPath = directory.resolve(JOURNAL);
                if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSameFile(lockPath, journalPath)) {
                    throw new IOException("Checkpoint lock must not alias the journal");
                }
                owned = LocalFileOwnership.openControl(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                channel = owned.channel();
                if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Checkpoint lock path changed"); }
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException overlap) { throw new IOException("Checkpoint store lock overlaps", overlap); }
                if (lock == null) { throw new IOException("Checkpoint store is locked by another process"); }
                return new NioNamespace(directory, lockPath, dir, lock, owned);
            } catch (IOException | RuntimeException | Error failure) {
                if (owned != null) {
                    try { owned.close(); }
                    catch (IOException | RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
                }
                try { dir.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }

        @Override public Path directory() { return directory; }
        @Override public LocalReaderBarrier.Protection acquireReader() throws IOException {
            return acquireBarrier(true).orElseThrow(() -> new IOException("Reader barrier is held exclusively"));
        }
        @Override public Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() throws IOException {
            return acquireBarrier(false);
        }
        private Optional<LocalReaderBarrier.Protection> acquireBarrier(boolean shared) throws IOException {
            var acquired = LocalReaderBarrier.acquire(directory, shared);
            if (acquired.isEmpty()) { return acquired; }
            try { forceDirectory(); return acquired; }
            catch (IOException | RuntimeException | Error failure) {
                try { acquired.orElseThrow().close(); }
                catch (IOException | RuntimeException | Error closeFailure) {
                    if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
                }
                throw failure;
            }
        }
        @Override public List<String> entries() throws IOException { return listNames(directory); }
        private static List<String> listNames(Path directory) throws IOException {
            var names = new ArrayList<String>();
            try (var stream = Files.newDirectoryStream(directory)) {
                for (var entry : stream) {
                    if (names.size() == MAX_DIRECTORY_ENTRIES) { throw new IOException("Checkpoint directory entry limit exceeded"); }
                    names.add(entry.getFileName().toString());
                }
            }
            return names;
        }
        @Override public byte[] read(String name, int maxBytes) throws IOException {
            Path path = regular(name);
            return LocalCheckpointStore.read(NioFile.open(path, StandardOpenOption.READ), maxBytes);
        }
        @Override public void writeNew(String name, byte[] image) throws IOException {
            LocalCheckpointStore.write(NioFile.create(directory.resolve(name)), image);
        }
        @Override public void forceFile(String name) throws IOException {
            Path path = regular(name);
            try (var file = NioFile.open(path, StandardOpenOption.WRITE)) { file.force(); }
        }
        private Path regular(String name) throws IOException {
            Path path = directory.resolve(name);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Checkpoint object must be a regular file: " + name); }
            LocalReaderBarrier.rejectObjectAlias(directory, path);
            return path;
        }
        @Override public void atomicMove(String from, String to) throws IOException {
            Path destination = directory.resolve(to);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { throw new FileAlreadyExistsException(destination.toString()); }
            // Exclusive cooperating ownership and stable paths protect the precondition. No fallback.
            Files.move(directory.resolve(from), destination, StandardCopyOption.ATOMIC_MOVE);
        }
        @Override public void forceDirectory() throws IOException { directoryChannel.force(true); }
        @Override public void close() throws IOException {
            Throwable failure = null;
            try { if (lock.isValid()) { lock.release(); } }
            catch (IOException | RuntimeException | Error releaseFailure) { failure = releaseFailure; }
            for (var resource : new AutoCloseable[] {owned, directoryChannel}) {
                try { resource.close(); }
                catch (Exception | Error closeFailure) {
                    if (failure == null) { failure = closeFailure; }
                    else if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
                }
            }
            if (failure instanceof IOException io) { throw io; }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
            if (failure != null) { throw new IOException("Checkpoint cleanup failed", failure); }
        }
    }
}
