// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;

/**
 * Verified durable preparation only: never sends a mutable head PUT or reconciles selection.
 * All supplied resources remain caller-owned. A result is bounded historical evidence, not a lease.
 */
public final class S3PublicationPreparer {
    enum Boundary { GRAPH_VERIFIED, JOURNAL_SYNCED, INTENT_INSTALLED, STATE_SYNCED }
    @FunctionalInterface interface PreparationProbe { void reached(Boundary boundary) throws IOException; }

    public static final class Preparation {
        private final S3PublicationState pending;
        private final ObjectCheckpointGraphs.Verification verification;
        private final ObjectReference intentReference;
        private final boolean intentCreated;
        private final long forcedSequence;
        private Preparation(S3PublicationState pending, ObjectCheckpointGraphs.Verification verification,
                            ObjectReference intentReference, boolean intentCreated, long forcedSequence) {
            this.pending = pending;
            this.verification = verification;
            this.intentReference = intentReference;
            this.intentCreated = intentCreated;
            this.forcedSequence = forcedSequence;
        }
        public S3PublicationState pending() { return pending; }
        public ObjectCheckpointGraphs.Verification verification() { return verification; }
        public ObjectReference intentReference() { return intentReference; }
        public boolean intentCreated() { return intentCreated; }
        public long forcedSequence() { return forcedSequence; }
    }

    private final S3ObjectStore objects;
    private final S3CheckpointHeads heads;
    private final LocalS3PublicationState local;
    private final LocalCheckpointStore checkpoints;
    private final LocalJournal journal;
    private final PreparationProbe probe;
    private boolean operating;

    public S3PublicationPreparer(S3ObjectStore objects, S3CheckpointHeads heads, LocalS3PublicationState local,
                                 LocalCheckpointStore checkpoints, LocalJournal journal) {
        this(objects, heads, local, checkpoints, journal, null);
    }
    S3PublicationPreparer(S3ObjectStore objects, S3CheckpointHeads heads, LocalS3PublicationState local,
                           LocalCheckpointStore checkpoints, LocalJournal journal, PreparationProbe probe) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.heads = Objects.requireNonNull(heads, "heads");
        this.local = Objects.requireNonNull(local, "local");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.probe = probe;
    }

    /**
     * Require already installed immutable dependencies. Serialize cooperating resource methods in
     * local-state, checkpoint-store, journal, head-client, object-client order through installation.
     */
    public Preparation prepare(byte[] candidate, ObjectCheckpointGraphs.Limits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        synchronized (local) {
            synchronized (checkpoints) {
                synchronized (journal) {
                    synchronized (heads) {
                        synchronized (objects) {
                            if (operating) { throw new IOException("Reentrant publication preparation"); }
                            operating = true;
                            try { return prepareLocked(candidate, limits); }
                            finally { operating = false; }
                        }
                    }
                }
            }
        }
    }

    private Preparation prepareLocked(byte[] candidate, ObjectCheckpointGraphs.Limits limits) throws IOException {
        var state = local.state();
        var proposed = state.prepare(candidate); // Own exact bounded bytes and reject existing pending/invalid transition first.
        var intent = proposed.pending().orElseThrow();
        byte[] image = intent.image();
        requireResources(state, objects, heads, checkpoints, journal);
        local.requirePreparationCapacity(); // Storage failures poison; no force/upload effects precede this admission.
        var observed = heads.read();
        var expected = state.expected();
        if (expected.isEmpty()) {
            if (observed.isPresent()) { throw new IOException("Fresh local state cannot adopt an existing remote head"); }
        } else if (observed.isEmpty() || !Arrays.equals(expected.orElseThrow().image(), observed.orElseThrow().image())
                || !expected.orElseThrow().token().equals(observed.orElseThrow().token())) {
            throw new IOException("Remote head differs from original durable expected bytes or token");
        }

        var graphs = new ObjectCheckpointGraphs(objects, journal, state.binding().namespaceId(), state.binding().publisherId());
        var checked = expected.isEmpty() ? graphs.verify(intent.head(), limits)
                : graphs.verifySuccessor(expected.orElseThrow().head(), intent.head(), limits);
        observe(Boundary.GRAPH_VERIFIED);
        checkpoints.synchronizeJournal(journal); // Force actual owned journal and its name through existing owners.
        observe(Boundary.JOURNAL_SYNCED);
        var installed = objects.createOrVerify(intentKey(state.binding().namespaceId(), intent.reference().id()), image);
        if (!installed.reference().equals(intent.reference())) { throw new IOException("Immutable intent identity differs"); }
        observe(Boundary.INTENT_INSTALLED);
        var pending = local.prepare(image); // Recheck capacity and force exact original precondition before success.
        observe(Boundary.STATE_SYNCED);
        return new Preparation(pending, checked, intent.reference(), installed.created(), journal.forcedSequence());
    }

    public static ObjectKey intentKey(UUID namespace, ObjectId id) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(id, "id");
        if (namespace.equals(new UUID(0, 0))) { throw new IllegalArgumentException("Namespace must not be nil"); }
        return new ObjectKey("ns-" + namespace.toString().replace("-", "") + "-head-" + id.value() + ".bin");
    }

    static void requireResources(S3PublicationState state, S3ObjectStore objects, S3CheckpointHeads heads,
                                 LocalCheckpointStore checkpoints, LocalJournal journal) throws IOException {
        objects.requireTarget(state.target());
        heads.requireConfiguration(state.target(), state.binding());
        if (!state.binding().lineageId().equals(ObjectCheckpointHeadVerifier.lineageId(journal.databaseId()))) {
            throw new IllegalArgumentException("Durable publication lineage differs from actual owned journal");
        }
        if (!journal.path().orElseThrow(() -> new IOException("Real owned local journal required")).equals(checkpoints.journalPath())) {
            throw new IOException("Journal must occupy the owned checkpoint directory's transactions.journal");
        }
    }
    private void observe(Boundary boundary) throws IOException { if (probe != null) { probe.reached(boundary); } }
}
