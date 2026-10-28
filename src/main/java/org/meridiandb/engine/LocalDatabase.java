// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.LocalCheckpointReader;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;

/**
 * Experimental local temporal database with explicit read-only and writable open modes.
 * Recovery verifies complete history before exposure. Writable startup synchronizes journal/name;
 * new writes prepare privately, append/force and then publish one complete immutable state.
 * Failed append attempts have uncertain outcomes and require close/reopen; no automatic repair.
 */
public final class LocalDatabase implements AutoCloseable {
    // The production source has already verified checkpoint/journal binding. This narrow owned-source
    // seam exercises partial recovery and cleanup failures; it is not another backend.
    interface RecoverySource extends AutoCloseable {
        DatabaseId databaseId();
        OptionalLong checkpointSequence();
        void replayCheckpoint(Consumer<? super JournalRecord> consumer) throws IOException;
        void replayJournal(Consumer<? super JournalRecord> consumer) throws IOException;
        long journalSequence();
        @Override void close() throws IOException;
    }

    interface WriteSource extends RecoverySource {
        void synchronize() throws IOException;
        void append(JournalRecord record) throws IOException;
        void force() throws IOException;
    }

    /** A write was attempted; its presence must be established by closing and reopening storage. */
    public static final class UncertainCommitException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String requestIdentity;
        private final String databaseIdentity;
        private final long proposedSequence;
        UncertainCommitException(RequestId requestId, CommitPosition proposedPosition, Throwable cause) {
            super("Commit outcome is uncertain; close and reopen before resolving request " + requestId.value(), cause);
            this.requestIdentity = requestId.value();
            this.databaseIdentity = proposedPosition.databaseId().value();
            this.proposedSequence = proposedPosition.sequence();
        }
        public RequestId requestId() { return new RequestId(requestIdentity); }
        public CommitPosition proposedPosition() {
            return new CommitPosition(new DatabaseId(databaseIdentity), proposedSequence);
        }
    }

    private final RecoverySource source;
    private final WriteSource writer;
    private final Optional<CommitPosition> checkpoint;
    private volatile InMemoryEngine state;
    private boolean resourcesClosed;
    private boolean operating;
    private volatile boolean failed;

    private LocalDatabase(RecoverySource source, WriteSource writer, InMemoryEngine state, Optional<CommitPosition> checkpoint) {
        this.source = source;
        this.writer = writer;
        this.state = state;
        this.checkpoint = checkpoint;
    }

    /** Open an existing journal without automatic tail repair; all owners are released on failure. */
    public static LocalDatabase open(Path directory) throws IOException {
        return open(directory, false);
    }

    /** Recover and synchronize all complete history before enabling new or retry acknowledgements. */
    public static LocalDatabase openWritable(Path directory) throws IOException {
        return open(directory, true);
    }

    /** Initialize an existing fresh durably named directory; failures retain storage for inspection. */
    public static LocalDatabase create(Path directory, DatabaseId databaseId) throws IOException {
        JournalCodec.encodeHeader(databaseId); // Reject invalid/oversize lineage before any store mutation.
        var store = LocalCheckpointStore.open(directory);
        LocalJournal journal = null;
        LocalCheckpointReader reader = null;
        try {
            journal = store.initialize(databaseId);
            reader = store.readLatest(journal).orElseThrow();
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(reader, failure);
            closeAfterFailure(journal, failure);
            closeAfterFailure(store, failure);
            throw failure;
        }
        return recoverWritable(new LocalSource(store, journal, reader));
    }

    private static LocalDatabase open(Path directory, boolean writable) throws IOException {
        var store = LocalCheckpointStore.open(directory);
        LocalJournal journal = null;
        LocalCheckpointReader reader = null;
        try {
            journal = LocalJournal.open(store.journalPath());
            reader = store.readLatest(journal).orElse(null);
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(reader, failure);
            closeAfterFailure(journal, failure);
            closeAfterFailure(store, failure);
            throw failure;
        }
        var source = new LocalSource(store, journal, reader);
        return writable ? recoverWritable(source) : recover(source);
    }

    /** Takes ownership even on failure; never exposes an engine under construction. */
    static LocalDatabase recover(RecoverySource source) throws IOException {
        return rebuild(source, null);
    }

    static LocalDatabase recoverWritable(WriteSource source) throws IOException {
        return rebuild(source, source);
    }

    private static LocalDatabase rebuild(RecoverySource source, WriteSource writer) throws IOException {
        Objects.requireNonNull(source, "source");
        InMemoryEngine engine = null;
        try {
            var databaseId = Objects.requireNonNull(source.databaseId(), "databaseId");
            var selected = Objects.requireNonNull(source.checkpointSequence(), "checkpointSequence");
            long cutoff = selected.orElse(0);
            long journalHead = source.journalSequence();
            if (cutoff < 0 || journalHead < cutoff) { throw new IOException("Invalid checkpoint/journal recovery bounds"); }
            engine = new InMemoryEngine(databaseId);
            var rebuilding = engine;
            if (selected.isPresent()) {
                source.replayCheckpoint(record -> {
                    if (record.sequence() > cutoff) { throw invalid("Checkpoint exceeds its declared cutoff"); }
                    rebuilding.restore(record);
                });
                if (engine.head().sequence() != cutoff) { throw new IOException("Checkpoint history is incomplete"); }
            }
            long[] previous = {0};
            source.replayJournal(record -> {
                if (previous[0] == Long.MAX_VALUE || record.sequence() != previous[0] + 1
                        || record.sequence() > journalHead) { throw invalid("Noncontiguous journal recovery history"); }
                previous[0] = record.sequence();
                if (record.sequence() > cutoff) { rebuilding.restore(record); }
            });
            if (previous[0] != journalHead || engine.head().sequence() != journalHead) {
                throw new IOException("Journal history is incomplete");
            }
            var checkpoint = selected.isPresent() ? Optional.of(new CommitPosition(databaseId, cutoff))
                    : Optional.<CommitPosition>empty();
            if (writer != null) { writer.synchronize(); }
            return new LocalDatabase(source, writer, engine, checkpoint);
        } catch (IOException | RuntimeException | Error failure) {
            if (engine != null) { engine.close(); }
            var primary = failure instanceof UncheckedIOException wrapped ? wrapped.getCause() : failure;
            closeAfterFailure(source, primary);
            if (primary instanceof IOException io) { throw io; }
            throw failure;
        }
    }

    public CommitPosition head() { return capture().head(); }

    /** The selected checkpoint cutoff, which can precede the fully replayed head. */
    public Optional<CommitPosition> checkpointPosition() { capture(); return checkpoint; }

    public Optional<CommitPosition> lookupRequest(RequestId requestId) { return capture().lookupRequest(requestId); }

    public ReadView openView(ReadCutoff cutoff) { return new ReadView(capture().openView(cutoff)); }

    /** Success follows owner-journal force and atomic publication; exact retries allocate no new commit. */
    public synchronized CommitResult transact(Transaction request) throws IOException {
        var engine = capture();
        if (writer == null) { throw new IllegalStateException("Local database was opened read-only"); }
        if (operating) { throw new IllegalStateException("Reentrant local transaction"); }
        var prepared = engine.prepare(request);
        var result = prepared.result();
        if (result.replayed()) { return result; }
        var record = new JournalRecord(result.position().sequence(), request);
        JournalCodec.encodeRecord(record); // Bounds/encoding preflight before an append attempt.
        operating = true;
        try {
            writer.append(record); // May take effect and then throw; any failure from here is uncertain.
            writer.force();
            return engine.publish(prepared); // Valid path allocates no state or result after force.
        } catch (IOException | RuntimeException failure) {
            poison(engine);
            throw new UncertainCommitException(request.requestId(), result.position(), failure);
        } catch (Error failure) {
            poison(engine);
            throw failure;
        } finally {
            operating = false;
        }
    }

    private void poison(InMemoryEngine engine) {
        failed = true;
        state = null;
        engine.close();
    }

    /** Invalidates views before releasing locks. Failed resource cleanup can be retried. */
    @Override public synchronized void close() throws IOException {
        if (operating) { throw new IOException("Cannot close a database inside a write callback"); }
        var engine = state;
        state = null;
        if (engine != null) { engine.close(); }
        if (!resourcesClosed) {
            source.close();
            resourcesClosed = true;
        }
    }

    private InMemoryEngine capture() {
        var engine = state;
        if (engine == null) { throw new IllegalStateException(failed
                ? "Local database failed; close and reopen to resolve the outcome" : "Local database is closed"); }
        return engine;
    }

    public static final class ReadView implements AutoCloseable {
        private final InMemoryEngine.ReadView delegate;
        private ReadView(InMemoryEngine.ReadView delegate) { this.delegate = delegate; }
        public CommitPosition position() { return delegate.position(); }
        public TemporalResult query(TemporalQuery query) { return delegate.query(query); }
        @Override public void close() { delegate.close(); }
    }

    private static UncheckedIOException invalid(String message) { return new UncheckedIOException(new IOException(message)); }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource != null) {
            try { resource.close(); }
            catch (Exception | Error closeFailure) { if (closeFailure != failure) { failure.addSuppressed(closeFailure); } }
        }
    }

    private static final class LocalSource implements WriteSource {
        private final LocalCheckpointStore store;
        private final LocalJournal journal;
        private final LocalCheckpointReader reader;
        LocalSource(LocalCheckpointStore store, LocalJournal journal, LocalCheckpointReader reader) {
            this.store = store;
            this.journal = journal;
            this.reader = reader;
        }
        @Override public DatabaseId databaseId() { return journal.databaseId(); }
        @Override public OptionalLong checkpointSequence() {
            return reader == null ? OptionalLong.empty() : OptionalLong.of(reader.manifest().journalPrefix().sequence());
        }
        @Override public long journalSequence() { return journal.appendedSequence(); }
        @Override public void synchronize() throws IOException { store.synchronizeJournal(journal); }
        @Override public void append(JournalRecord record) throws IOException { journal.append(record); }
        @Override public void force() throws IOException { journal.force(); }
        @Override public void replayCheckpoint(Consumer<? super JournalRecord> consumer) throws IOException {
            if (reader != null) {
                for (int index = 0; index < reader.manifest().segments().size(); index++) {
                    try (var segment = reader.openSegment(index)) { segment.segment().records().forEach(consumer); }
                }
            }
        }
        @Override public void replayJournal(Consumer<? super JournalRecord> consumer) throws IOException { journal.replay(consumer); }
        @Override public void close() throws IOException {
            if (reader != null) { reader.close(); }
            IOException failure = null;
            try { journal.close(); } catch (IOException journalFailure) { failure = journalFailure; }
            try { store.close(); } catch (IOException storeFailure) {
                if (failure == null) { failure = storeFailure; } else { failure.addSuppressed(storeFailure); }
            }
            if (failure != null) { throw failure; }
        }
    }
}
