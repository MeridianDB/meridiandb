// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;

/**
 * Experimental online recovery from an exact durable expected S3 selection, with explicit write mode.
 * Owns acquired local metadata and database resources; supplied S3 clients remain caller-owned.
 * The startup observation is historical evidence, not a continuing remote lease or writer fence.
 */
public final class S3Database implements AutoCloseable {
    enum Boundary { HEAD_OBSERVED, RECOVERED }
    @FunctionalInterface interface RecoveryProbe { void reached(Boundary boundary) throws IOException; }
    // Public factories select concrete core modes. This package seam injects faults into real recovery.
    @FunctionalInterface interface CoreOpener {
        LocalDatabase open(Path directory, S3ObjectStore objects, S3CheckpointHeads.Binding binding,
                ObjectCheckpointHead head, ObjectCheckpointGraphs.Limits limits, long minimum) throws IOException;
    }
    private static final CoreOpener READ_ONLY = (directory, objects, binding, head, limits, minimum) ->
            LocalDatabase.openFromObjectCheckpoint(directory, objects, binding.namespaceId(), binding.publisherId(), head, limits, minimum);
    private static final CoreOpener WRITABLE = (directory, objects, binding, head, limits, minimum) ->
            LocalDatabase.openWritableFromObjectCheckpoint(directory, objects, binding.namespaceId(), binding.publisherId(), head, limits, minimum);

    private final LocalS3PublicationState local;
    private final LocalDatabase database;
    private final S3HeadObservation observation;
    private volatile boolean closed;
    // Writing and cleanup are serialized on the private core instance; queries do not take that monitor.
    private boolean writing;
    private boolean resourcesClosed;

    private S3Database(LocalS3PublicationState local, LocalDatabase database, S3HeadObservation observation) {
        this.local = local;
        this.database = database;
        this.observation = observation;
    }

    public static S3Database open(Path databaseDirectory, Path stateDirectory,
            S3PublicationState.Target target, S3CheckpointHeads.Binding binding,
            S3ObjectStore objects, S3CheckpointHeads heads, ObjectCheckpointGraphs.Limits limits) throws IOException {
        return open(databaseDirectory, stateDirectory, target, binding, objects, heads, limits, 0);
    }

    /** Require independently known journal coverage; unknown cleanly lost tails cannot be inferred. */
    public static S3Database open(Path databaseDirectory, Path stateDirectory,
            S3PublicationState.Target target, S3CheckpointHeads.Binding binding,
            S3ObjectStore objects, S3CheckpointHeads heads, ObjectCheckpointGraphs.Limits limits,
            long minimumJournalSequence) throws IOException {
        return open(databaseDirectory, stateDirectory, target, binding, objects, heads, limits, minimumJournalSequence, null);
    }

    static S3Database open(Path databaseDirectory, Path stateDirectory,
            S3PublicationState.Target target, S3CheckpointHeads.Binding binding,
            S3ObjectStore objects, S3CheckpointHeads heads, ObjectCheckpointGraphs.Limits limits,
            long minimumJournalSequence, RecoveryProbe probe) throws IOException {
        return open(databaseDirectory, stateDirectory, target, binding, objects, heads, limits, minimumJournalSequence, probe, READ_ONLY);
    }

    /** Admit exact managed selection, then synchronize actual local history before enabling writes. */
    public static S3Database openWritable(Path databaseDirectory, Path stateDirectory,
            S3PublicationState.Target target, S3CheckpointHeads.Binding binding,
            S3ObjectStore objects, S3CheckpointHeads heads, ObjectCheckpointGraphs.Limits limits) throws IOException {
        return openWritable(databaseDirectory, stateDirectory, target, binding, objects, heads, limits, 0);
    }

    public static S3Database openWritable(Path databaseDirectory, Path stateDirectory,
            S3PublicationState.Target target, S3CheckpointHeads.Binding binding,
            S3ObjectStore objects, S3CheckpointHeads heads, ObjectCheckpointGraphs.Limits limits,
            long minimumJournalSequence) throws IOException {
        return openWritable(databaseDirectory, stateDirectory, target, binding, objects, heads, limits, minimumJournalSequence, null);
    }

    static S3Database openWritable(Path databaseDirectory, Path stateDirectory,
            S3PublicationState.Target target, S3CheckpointHeads.Binding binding,
            S3ObjectStore objects, S3CheckpointHeads heads, ObjectCheckpointGraphs.Limits limits,
            long minimumJournalSequence, RecoveryProbe probe) throws IOException {
        return openWritable(databaseDirectory, stateDirectory, target, binding, objects, heads, limits, minimumJournalSequence, probe, WRITABLE);
    }

    static S3Database openWritable(Path databaseDirectory, Path stateDirectory,
            S3PublicationState.Target target, S3CheckpointHeads.Binding binding,
            S3ObjectStore objects, S3CheckpointHeads heads, ObjectCheckpointGraphs.Limits limits,
            long minimumJournalSequence, RecoveryProbe probe, CoreOpener opener) throws IOException {
        return open(databaseDirectory, stateDirectory, target, binding, objects, heads, limits, minimumJournalSequence, probe, opener);
    }

    private static S3Database open(Path databaseDirectory, Path stateDirectory,
            S3PublicationState.Target target, S3CheckpointHeads.Binding binding,
            S3ObjectStore objects, S3CheckpointHeads heads, ObjectCheckpointGraphs.Limits limits,
            long minimumJournalSequence, RecoveryProbe probe, CoreOpener opener) throws IOException {
        Objects.requireNonNull(databaseDirectory, "databaseDirectory");
        Objects.requireNonNull(stateDirectory, "stateDirectory");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(heads, "heads");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(opener, "opener");
        if (minimumJournalSequence < 0) { throw new IllegalArgumentException("Minimum journal sequence must be nonnegative"); }
        if (databaseDirectory.toRealPath().equals(stateDirectory.toRealPath())) {
            throw new IllegalArgumentException("Publication metadata requires a separate directory");
        }
        var local = LocalS3PublicationState.open(stateDirectory, target, binding);
        LocalDatabase database = null;
        try {
            synchronized (local) {
                synchronized (heads) {
                    synchronized (objects) {
                        var state = local.state();
                        if (state.pending().isPresent()) {
                            throw new IOException("Pending publication requires explicit reconciliation before recovery");
                        }
                        var expected = state.expected().orElseThrow(() -> new IOException("Durable expected selection required"));
                        heads.requireConfiguration(target, binding);
                        objects.requireTarget(target);
                        var observed = heads.read().orElseThrow(() -> new IOException("Expected S3 head is missing"));
                        if (!Arrays.equals(expected.image(), observed.image()) || !expected.token().equals(observed.token())) {
                            throw new IOException("Fresh S3 head differs from durable expected bytes or token");
                        }
                        observe(probe, Boundary.HEAD_OBSERVED);
                        database = opener.open(databaseDirectory, objects, binding, observed.head(), limits, minimumJournalSequence);
                        observe(probe, Boundary.RECOVERED);
                        return new S3Database(local, database, observed);
                    }
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(database, failure);
            closeAfterFailure(local, failure);
            throw failure;
        }
    }

    public CommitPosition head() { usable(); return database.head(); }
    public Optional<CommitPosition> checkpointPosition() { usable(); return database.checkpointPosition(); }
    public Optional<CommitPosition> lookupRequest(RequestId requestId) { usable(); return database.lookupRequest(requestId); }
    public LocalDatabase.ReadView openView(ReadCutoff cutoff) { usable(); return database.openView(cutoff); }
    /** Same-GET complete bytes/token used for startup admission; no observation is refreshed here. */
    public S3HeadObservation observation() { usable(); return observation; }

    /** Force-backed local commit or original retry; read-only opens reject transactions. */
    public CommitResult transact(Transaction request) throws IOException {
        synchronized (database) {
            usable();
            if (writing) { throw new IllegalStateException("Reentrant S3 database transaction"); }
            writing = true;
            try { return database.transact(request); }
            finally { writing = false; }
        }
    }

    /** Invalidate views first, attempt both owned resources and permit cleanup retry after failure. */
    @Override public void close() throws IOException {
        synchronized (database) {
            if (writing) { throw new IOException("Cannot close an S3 database inside a write callback"); }
            closeOwned();
        }
    }

    private void closeOwned() throws IOException {
        closed = true;
        if (resourcesClosed) { return; }
        Throwable failure = null;
        try { database.close(); }
        catch (IOException | RuntimeException | Error rejected) { failure = rejected; }
        try { local.close(); }
        catch (IOException | RuntimeException | Error rejected) {
            if (failure == null) { failure = rejected; }
            else if (failure != rejected) { failure.addSuppressed(rejected); }
        }
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        resourcesClosed = true;
    }

    private void usable() {
        if (closed) { throw new IllegalStateException("S3 database is closed"); }
        database.head(); // Propagate the core's poisoned/closed state, including for observation().
    }
    private static void observe(RecoveryProbe probe, Boundary boundary) throws IOException {
        if (probe != null) { probe.reached(boundary); }
    }
    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) { return; }
        try { resource.close(); }
        catch (Exception | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
    }
}
