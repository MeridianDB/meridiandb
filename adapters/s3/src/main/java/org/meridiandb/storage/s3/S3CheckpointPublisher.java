// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;

/**
 * Submit one exact durable pending intent or reconcile it without a PUT. Owns no supplied resource.
 * A reconciled checkpoint is historical evidence, not a lease, engine acknowledgement or deletion right.
 */
public final class S3CheckpointPublisher {
    public enum WriteReply { NOT_ATTEMPTED, ACKNOWLEDGED, FAILED }
    enum Boundary { PREPARED, BEFORE_WRITE, WRITE_FINISHED, CANDIDATE_VERIFIED, STATE_SYNCED }
    @FunctionalInterface interface PublicationProbe { void reached(Boundary boundary) throws IOException; }

    public static final class Result {
        private final S3PublicationState state;
        private final ObjectCheckpointGraphs.Usage usage;
        private final WriteReply reply;
        private final S3HeadWriteException.Code failureCode;
        private final long forcedSequence;
        private Result(S3PublicationState state, ObjectCheckpointGraphs.Usage usage, WriteReply reply,
                       Throwable failure, long forcedSequence) {
            this.state = state;
            this.usage = usage;
            this.reply = reply;
            failureCode = failure instanceof S3HeadWriteException head ? head.code() : null;
            this.forcedSequence = forcedSequence;
        }
        public S3PublicationState state() { return state; }
        public S3HeadObservation observation() { return state.expected().orElseThrow(); }
        public ObjectCheckpointGraphs.Usage usage() { return usage; }
        public WriteReply writeReply() { return reply; }
        public boolean writeAttempted() { return reply != WriteReply.NOT_ATTEMPTED; }
        public boolean writeAcknowledged() { return reply == WriteReply.ACKNOWLEDGED; }
        public Optional<S3HeadWriteException.Code> writeFailureCode() { return Optional.ofNullable(failureCode); }
        public long forcedSequence() { return forcedSequence; }
    }

    private final S3ObjectStore objects;
    private final S3CheckpointHeads heads;
    private final LocalS3PublicationState local;
    private final LocalCheckpointStore checkpoints;
    private final LocalJournal journal;
    private final PublicationProbe probe;
    private boolean operating;

    public S3CheckpointPublisher(S3ObjectStore objects, S3CheckpointHeads heads, LocalS3PublicationState local,
                                  LocalCheckpointStore checkpoints, LocalJournal journal) {
        this(objects, heads, local, checkpoints, journal, null);
    }
    S3CheckpointPublisher(S3ObjectStore objects, S3CheckpointHeads heads, LocalS3PublicationState local,
                           LocalCheckpointStore checkpoints, LocalJournal journal, PublicationProbe probe) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.heads = Objects.requireNonNull(heads, "heads");
        this.local = Objects.requireNonNull(local, "local");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.probe = probe;
    }

    /** At most one SDK conditional PUT, using only the original durable candidate/precondition. */
    public Result submit(ObjectCheckpointGraphs.Limits limits) throws IOException { return operate(limits, true); }
    /** No PUT. Only a fresh exact-candidate observation can resolve pending state. */
    public Result reconcile(ObjectCheckpointGraphs.Limits limits) throws IOException { return operate(limits, false); }

    private Result operate(ObjectCheckpointGraphs.Limits limits, boolean submit) throws IOException {
        Objects.requireNonNull(limits, "limits");
        synchronized (local) {
            synchronized (checkpoints) {
                synchronized (journal) {
                    synchronized (heads) {
                        synchronized (objects) {
                            if (operating) { throw new IOException("Reentrant checkpoint publication"); }
                            operating = true;
                            try { return locked(limits, submit); }
                            finally { operating = false; }
                        }
                    }
                }
            }
        }
    }

    private Result locked(ObjectCheckpointGraphs.Limits limits, boolean submit) throws IOException {
        var state = local.state();
        var intent = state.pending().orElseThrow(() -> new S3PublicationStateException(
                S3PublicationStateException.Code.INVALID_TRANSITION, "Durable pending publication intent required", null));
        if (state.generation() == Long.MAX_VALUE) {
            throw new S3PublicationStateException(S3PublicationStateException.Code.INVALID_TRANSITION,
                    "Local result generation is exhausted before selection", null);
        }
        S3PublicationPreparer.requireResources(state, objects, heads, checkpoints, journal);
        local.requirePreparationCapacity(); // Reserve a result slot before any remote selection effect.
        try (var object = objects.read(S3PublicationPreparer.intentKey(state.binding().namespaceId(), intent.reference().id()), intent.reference())) {
            if (!object.reference().equals(intent.reference()) || !Arrays.equals(object.bytes(), intent.image())) {
                throw new IOException("Stored immutable intent differs from exact durable pending image");
            }
        }
        var observed = heads.read();
        if (isCandidate(observed, state)) {
            var verified = verify(state, limits);
            return finish(observed.orElseThrow(), verified.usage(), WriteReply.NOT_ATTEMPTED, null);
        }
        requireOriginal(observed, state, false);
        if (!submit) { throw failure(S3CheckpointPublicationException.Code.STILL_PENDING, false, "Original head or absence does not resolve pending intent", null); }

        var before = verify(state, limits);
        var remaining = remaining(limits, before.usage());
        reserveRepeat(before.usage(), remaining); // Both complete passes must fit before force/PUT, not after success.
        observe(Boundary.PREPARED);
        checkpoints.synchronizeJournal(journal);
        observe(Boundary.BEFORE_WRITE);
        Throwable writeFailure = null;
        WriteReply reply;
        try {
            if (state.expected().isEmpty()) { heads.createIfAbsent(intent.image()); }
            else { heads.replace(state.expected().orElseThrow(), intent.image()); }
            reply = WriteReply.ACKNOWLEDGED;
        } catch (IOException | RuntimeException failed) {
            writeFailure = failed;
            reply = WriteReply.FAILED;
        }
        try {
            observe(Boundary.WRITE_FINISHED);
            var after = heads.read();
            if (!isCandidate(after, state)) {
                throw failure(S3CheckpointPublicationException.Code.STILL_PENDING, true,
                        "Conditional attempt is not reconciled as the exact pending candidate", writeFailure);
            }
            var verified = verify(state, remaining);
            return finish(after.orElseThrow(), add(before.usage(), verified.usage()), reply, writeFailure);
        } catch (IOException | RuntimeException unresolved) {
            if (unresolved instanceof S3CheckpointPublicationException known && known.writeAttempted()) { throw known; }
            var pending = failure(S3CheckpointPublicationException.Code.STILL_PENDING, true,
                    "Selection/result installation is uncertain; inspect exact local state before retry", unresolved);
            if (writeFailure != null && writeFailure != unresolved) { pending.addSuppressed(writeFailure); }
            throw pending;
        }
    }

    private ObjectCheckpointGraphs.Verification verify(S3PublicationState state, ObjectCheckpointGraphs.Limits limits) throws IOException {
        var verifier = new ObjectCheckpointGraphs(objects, journal, state.binding().namespaceId(), state.binding().publisherId());
        var candidate = state.pending().orElseThrow().head();
        return state.expected().isEmpty() ? verifier.verify(candidate, limits)
                : verifier.verifySuccessor(state.expected().orElseThrow().head(), candidate, limits);
    }
    private Result finish(S3HeadObservation observation, ObjectCheckpointGraphs.Usage usage, WriteReply reply, Throwable failure) throws IOException {
        observe(Boundary.CANDIDATE_VERIFIED);
        checkpoints.synchronizeJournal(journal);
        var selected = local.recordSelected(observation);
        observe(Boundary.STATE_SYNCED);
        return new Result(selected, usage, reply, failure, journal.forcedSequence());
    }
    private static boolean isCandidate(Optional<S3HeadObservation> observed, S3PublicationState state) {
        return observed.isPresent() && Arrays.equals(observed.orElseThrow().image(), state.pending().orElseThrow().image());
    }
    private static void requireOriginal(Optional<S3HeadObservation> observed, S3PublicationState state, boolean attempted) throws IOException {
        var expected = state.expected();
        if (expected.isEmpty() && observed.isEmpty()) { return; }
        if (expected.isPresent() && observed.isPresent()
                && Arrays.equals(expected.orElseThrow().image(), observed.orElseThrow().image())
                && expected.orElseThrow().token().equals(observed.orElseThrow().token())) { return; }
        throw failure(S3CheckpointPublicationException.Code.REMOTE_CONFLICT, attempted,
                "Remote head differs from original expected state; pending intent is retained", null);
    }
    private static ObjectCheckpointGraphs.Limits remaining(ObjectCheckpointGraphs.Limits limit, ObjectCheckpointGraphs.Usage used) {
        return new ObjectCheckpointGraphs.Limits(limit.maxObjectBytes() - used.objectBytes(), limit.maxJournalBytes() - used.journalBytes(),
                limit.maxRecordWork() - used.recordWork(), limit.maxObjectReads() - used.objectReads());
    }
    private static void reserveRepeat(ObjectCheckpointGraphs.Usage used, ObjectCheckpointGraphs.Limits left) throws IOException {
        if (used.objectBytes() > left.maxObjectBytes() || used.journalBytes() > left.maxJournalBytes()
                || used.recordWork() > left.maxRecordWork() || used.objectReads() > left.maxObjectReads()) {
            throw failure(S3CheckpointPublicationException.Code.LIMIT_EXCEEDED, false, "Both complete graph passes must fit before conditional selection", null);
        }
    }
    private static ObjectCheckpointGraphs.Usage add(ObjectCheckpointGraphs.Usage one, ObjectCheckpointGraphs.Usage two) {
        return new ObjectCheckpointGraphs.Usage(one.objectBytes() + two.objectBytes(), one.journalBytes() + two.journalBytes(),
                one.recordWork() + two.recordWork(), one.objectReads() + two.objectReads(), one.journalReads() + two.journalReads());
    }
    private void observe(Boundary boundary) throws IOException { if (probe != null) { probe.reached(boundary); } }
    private static S3CheckpointPublicationException failure(S3CheckpointPublicationException.Code code, boolean attempted, String message, Throwable cause) {
        return new S3CheckpointPublicationException(code, attempted, message, cause);
    }
}
