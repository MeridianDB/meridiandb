// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.RequestId;

/**
 * Experimental local-file journal with one cooperating writer and separate append/force cutoffs.
 * File force does not establish parent-directory durability or make the memory engine durable.
 * Keep paths stable while open; external writes, unlink/rename and uncooperative handles are unsupported.
 */
public final class LocalJournal implements AutoCloseable {
    public enum TailPolicy { REJECT, PRESERVE_AND_TRUNCATE }

    /** The exact discarded suffix is preserved in a new sibling file before truncation. */
    public record TailRepair(Path evidence, long startOffset, long originalSize) { }

    public static final class IncompleteTailException extends IOException {
        private static final long serialVersionUID = 1L;
        private final long validBytes;
        private final long fileBytes;

        private IncompleteTailException(long validBytes, long fileBytes) {
            super("Incomplete journal candidate at byte " + validBytes + " of " + fileBytes
                    + "; explicit evidence-preserving repair is required");
            this.validBytes = validBytes;
            this.fileBytes = fileBytes;
        }

        public long validBytes() { return validBytes; }
        public long fileBytes() { return fileBytes; }
    }

    // Narrow seams exercise the actual ordering/recovery code with failed and short I/O.
    interface FileAccess extends AutoCloseable {
        int read(ByteBuffer target, long offset) throws IOException;
        int write(ByteBuffer source, long offset) throws IOException;
        long size() throws IOException;
        void force() throws IOException;
        void truncate(long size) throws IOException;
        @Override void close() throws IOException;
    }

    @FunctionalInterface
    interface Backup {
        Path preserve(FileAccess file, long start, long size) throws IOException;
    }

    private final FileAccess file;
    private final DatabaseId databaseId;
    private final Set<RequestId> identities;
    private final TailRepair repair;
    private long appendedSequence;
    private long forcedSequence;
    private long appendedBytes;
    private long forcedBytes;
    private boolean failed;
    private boolean closed;
    private boolean resourcesClosed;
    private boolean replaying;

    private LocalJournal(FileAccess file, JournalRecovery.Result recovered, TailRepair repair) {
        this.file = file;
        this.databaseId = recovered.databaseId();
        this.identities = recovered.identities();
        this.appendedSequence = recovered.sequence();
        this.appendedBytes = recovered.validBytes();
        this.repair = repair;
    }

    /** Create a new file in an existing directory; never overwrite an existing path. */
    public static LocalJournal create(Path path, DatabaseId databaseId) throws IOException {
        JournalCodec.encodeHeader(databaseId); // Reject invalid/oversize lineage before touching a path.
        return create(NioFile.acquire(path, true), databaseId);
    }

    public static LocalJournal open(Path path) throws IOException {
        return open(path, TailPolicy.REJECT);
    }

    public static LocalJournal open(Path path, TailPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy");
        var file = NioFile.acquire(path, false);
        return open(file, policy, (source, start, size) -> preserveTail(file.path, source, start, size));
    }

    static LocalJournal create(FileAccess file, DatabaseId databaseId) throws IOException {
        try {
            var header = JournalCodec.encodeHeader(databaseId);
            if (file.size() != 0) {
                throw new IOException("New journal must be empty");
            }
            JournalRecovery.writeFully(file, ByteBuffer.wrap(header), 0);
            file.force();
            var result = new JournalRecovery.Result(databaseId, 0, header.length, header.length, new HashSet<>());
            var journal = new LocalJournal(file, result, null);
            journal.forcedBytes = header.length;
            return journal;
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(file, failure);
            throw failure;
        }
    }

    static LocalJournal open(FileAccess file, TailPolicy policy, Backup backup) throws IOException {
        try {
            Objects.requireNonNull(policy, "policy");
            var result = JournalRecovery.scan(file, record -> { });
            TailRepair repair = null;
            if (result.incomplete()) {
                if (policy == TailPolicy.REJECT) {
                    throw new IncompleteTailException(result.validBytes(), result.fileBytes());
                }
                Path evidence = Objects.requireNonNull(backup.preserve(file, result.validBytes(), result.fileBytes()), "evidence");
                // The exact candidate suffix is forced and closed before this destructive boundary.
                if (file.size() != result.fileBytes()) {
                    throw new IOException("Journal changed before tail repair");
                }
                file.truncate(result.validBytes());
                file.force();
                repair = new TailRepair(evidence, result.validBytes(), result.fileBytes());
            }
            var journal = new LocalJournal(file, result, repair);
            if (repair != null) {
                journal.forcedSequence = result.sequence();
                journal.forcedBytes = result.validBytes();
            }
            return journal;
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(file, failure);
            throw failure;
        }
    }

    public DatabaseId databaseId() { return databaseId; }
    public Optional<TailRepair> repair() { return Optional.ofNullable(repair); }
    public synchronized long appendedSequence() { return appendedSequence; }
    public synchronized long forcedSequence() { return forcedSequence; }
    public synchronized long appendedBytes() { return appendedBytes; }
    public synchronized long forcedBytes() { return forcedBytes; }

    /** Append one next transaction. Success alone is not a force or an engine acknowledgement. */
    public synchronized void append(JournalRecord record) throws IOException {
        usable();
        Objects.requireNonNull(record, "record");
        if (appendedSequence == Long.MAX_VALUE || record.sequence() != appendedSequence + 1) {
            throw new IllegalArgumentException("Append requires the next contiguous journal sequence");
        }
        if (identities.contains(record.transaction().requestId())) {
            throw new IllegalArgumentException("Request identity already exists in this journal");
        }
        byte[] bytes = JournalCodec.encodeRecord(record);
        long end = Math.addExact(appendedBytes, bytes.length);
        try {
            unchangedSize();
            JournalRecovery.writeFully(file, ByteBuffer.wrap(bytes), appendedBytes);
            appendedSequence = record.sequence();
            appendedBytes = end;
            identities.add(record.transaction().requestId());
        } catch (IOException | RuntimeException failure) {
            failed = true;
            throw failure;
        }
    }

    /** Force all complete appended bytes and return the sequence covered by this successful call. */
    public synchronized long force() throws IOException {
        usable();
        try {
            unchangedSize();
            file.force();
            forcedSequence = appendedSequence;
            forcedBytes = appendedBytes;
            return forcedSequence;
        } catch (IOException | RuntimeException failure) {
            failed = true;
            throw failure;
        }
    }

    /** Replay exact original records sequentially. The consumer must not mutate this journal. */
    public synchronized void replay(Consumer<? super JournalRecord> consumer) throws IOException {
        usable();
        Objects.requireNonNull(consumer, "consumer");
        replaying = true;
        try {
            unchangedSize();
            var result = JournalRecovery.scan(file, record -> {
                try {
                    consumer.accept(record);
                } catch (RuntimeException callbackFailure) {
                    throw new CallbackFailure(callbackFailure);
                }
            });
            if (!result.databaseId().equals(databaseId) || result.sequence() != appendedSequence
                    || result.validBytes() != appendedBytes || result.incomplete()) {
                throw new IOException("Journal changed during replay");
            }
        } catch (CallbackFailure callbackFailure) {
            throw (RuntimeException) callbackFailure.getCause();
        } catch (IOException | JournalFormatException failure) {
            failed = true;
            throw failure;
        } finally {
            replaying = false;
        }
    }

    /** Close never implicitly forces appended bytes; a failed release can be retried. */
    @Override
    public synchronized void close() throws IOException {
        if (replaying) {
            throw new IOException("Journal cannot close inside its replay callback");
        }
        closed = true;
        if (!resourcesClosed) {
            file.close();
            resourcesClosed = true;
        }
    }

    private void usable() throws IOException {
        if (closed || failed || replaying) {
            throw new IOException(closed ? "Journal is closed" : failed
                    ? "Journal failed; close and inspect/recover before retry" : "Journal cannot mutate or replay inside its replay callback");
        }
    }

    private void unchangedSize() throws IOException {
        if (file.size() != appendedBytes) {
            throw new IOException("Journal size changed outside this writer");
        }
    }

    private static final class CallbackFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        CallbackFailure(RuntimeException cause) { super(cause); }
    }

    private static void closeAfterFailure(FileAccess file, Throwable failure) {
        try {
            file.close();
        } catch (IOException | RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Path preserveTail(Path path, FileAccess source, long start, long size) throws IOException {
        Path evidence = path.resolveSibling(path.getFileName() + ".tail-at-" + start + "-" + UUID.randomUUID() + ".bin");
        // CREATE_NEW keeps earlier evidence. Partial evidence is retained on failure; original is untouched.
        try (var output = FileChannel.open(evidence, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.allocate(64 * 1024);
            long offset = start;
            while (offset < size) {
                buffer.clear().limit((int) Math.min(buffer.capacity(), size - offset));
                JournalRecovery.readFully(source, buffer, offset);
                offset += buffer.position();
                buffer.flip();
                int zeros = 0;
                while (buffer.hasRemaining()) {
                    int n = output.write(buffer);
                    if (n == 0 && ++zeros >= 16) {
                        throw new IOException("Tail evidence write made no progress");
                    }
                    if (n > 0) { zeros = 0; }
                }
            }
            output.force(true);
        }
        return evidence;
    }

    private static final class NioFile implements FileAccess {
        // Reserve before opening: closing a losing same-file channel can drop another JVM lock on POSIX.
        private static final Set<Path> OWNED = new HashSet<>();
        private final Path path;
        private final FileChannel channel;
        private final FileLock lock;

        private NioFile(Path path, FileChannel channel, FileLock lock) {
            this.path = path;
            this.channel = channel;
            this.lock = lock;
        }

        static NioFile acquire(Path requested, boolean create) throws IOException {
            Objects.requireNonNull(requested, "path");
            Path absolute = requested.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(absolute)) {
                throw new IOException("Journal path must not be a symbolic link");
            }
            Path path = absolute.getParent().toRealPath().resolve(absolute.getFileName());
            synchronized (OWNED) {
                for (Path active : OWNED) {
                    if (active.equals(path) || (Files.exists(path) && Files.isSameFile(active, path))) {
                        throw new IOException("Journal is already owned in this JVM: " + path);
                    }
                }
                OWNED.add(path);
            }
            FileChannel channel = null;
            try {
                channel = create
                        ? FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                                StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
                        : FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Journal must be a regular local file");
                }
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException overlap) {
                    throw new IOException("Journal is already locked in this JVM", overlap);
                }
                if (lock == null) {
                    throw new IOException("Journal is locked by another process: " + path);
                }
                return new NioFile(path, channel, lock);
            } catch (IOException | RuntimeException | Error failure) {
                if (channel != null) {
                    try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                }
                if (channel == null || !channel.isOpen()) {
                    synchronized (OWNED) { OWNED.remove(path); }
                }
                throw failure;
            }
        }

        @Override public int read(ByteBuffer target, long offset) throws IOException { return channel.read(target, offset); }
        @Override public int write(ByteBuffer source, long offset) throws IOException { return channel.write(source, offset); }
        @Override public long size() throws IOException { return channel.size(); }
        @Override public void force() throws IOException { channel.force(true); }
        @Override public void truncate(long size) throws IOException { channel.truncate(size); }

        @Override public void close() throws IOException {
            IOException failure = null;
            try {
                if (lock.isValid()) { lock.release(); }
            } catch (IOException releaseFailure) {
                failure = releaseFailure;
            }
            try {
                channel.close();
            } catch (IOException closeFailure) {
                if (failure == null) { failure = closeFailure; }
                else { failure.addSuppressed(closeFailure); }
            } finally {
                if (!channel.isOpen()) {
                    synchronized (OWNED) { OWNED.remove(path); }
                }
            }
            if (failure != null) { throw failure; }
        }
    }
}
