// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3CoordinationException.Code;

/**
 * Immutable coordination declarations only. No path, SDK, lock, durable installation or provenance.
 * Package transitions will be used by a concrete owner; decoded/caller metadata is never a capability.
 */
public final class S3CoordinationState {
    public static final int MAX_ROOTS = 32;
    public static final int MAX_ROOT_MANIFEST_BYTES = 16 * 1024;
    public static final int MAX_SEGMENT_REFERENCES = 4096;

    /** Complete head/manifest metadata, without segment bodies or actual journal verification. */
    public static final class Root {
        private final byte[] headImage;
        private final byte[] manifestImage;
        private final ObjectCheckpointHeadCodec.DecodedHead decoded;
        private final CheckpointManifest manifest;
        public Root(byte[] headImage, byte[] manifestImage) {
            Objects.requireNonNull(headImage, "headImage");
            Objects.requireNonNull(manifestImage, "manifestImage");
            if (headImage.length != ObjectCheckpointHeadCodec.ENCODED_BYTES) { throw invalid("A root requires one complete head"); }
            if (manifestImage.length > MAX_ROOT_MANIFEST_BYTES) { throw limit("Root manifest exceeds coordination cap"); }
            this.headImage = headImage.clone();
            this.manifestImage = manifestImage.clone();
            try {
                decoded = ObjectCheckpointHeadCodec.decode(this.headImage);
                manifest = ObjectCheckpointHeadVerifier.bindManifest(decoded.head(), this.manifestImage).manifest();
            } catch (IllegalArgumentException | org.meridiandb.storage.manifest.ObjectCheckpointHeadException rejected) {
                throw failure(Code.INVALID_STATE, "Root head/manifest metadata differs", rejected);
            }
        }
        public byte[] headImage() { return headImage.clone(); }
        public byte[] manifestImage() { return manifestImage.clone(); }
        public ObjectReference reference() { return decoded.reference(); }
        public ObjectCheckpointHead head() { return decoded.head(); }
        public CheckpointManifest manifest() { return manifest; }
    }

    /** Retired is a declaration/tombstone, not evidence of completed physical removal. */
    public record Entry(Root root, boolean retained, boolean retired) {
        public Entry {
            Objects.requireNonNull(root, "root");
            if (retained && retired) { throw invalid("A retained root cannot be retired"); }
        }
    }
    public enum Operation { CREATE_IMMUTABLE, CREATE_HEAD, REPLACE_HEAD, DELETE_IMMUTABLE }

    /** originalHead is required only for REPLACE_HEAD; conditions remain original opaque tags. */
    public record Mutation(Operation operation, ObjectKey key, ObjectReference content,
                           ObjectReference originalHead, S3HeadToken condition) {
        public Mutation {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(content, "content");
            boolean conditional = operation == Operation.REPLACE_HEAD || operation == Operation.DELETE_IMMUTABLE;
            if (conditional != (condition != null)) { throw invalid("Operation requires its exact condition shape"); }
            if ((operation == Operation.REPLACE_HEAD) != (originalHead != null)) { throw invalid("Replacement requires exact original head identity"); }
            if (originalHead != null && originalHead.byteLength() != ObjectCheckpointHeadCodec.ENCODED_BYTES) {
                throw invalid("Original head length differs");
            }
        }
    }
    public record Attempt(UUID placementId, long ordinal, UUID nonce, Mutation mutation) {
        public Attempt {
            identity(placementId, "placementId"); identity(nonce, "nonce");
            if (ordinal <= 0) { throw invalid("Attempt ordinal must be positive"); }
            Objects.requireNonNull(mutation, "mutation");
        }
    }

    /** A declared terminal result only; the future effect owner must establish its actual provenance. */
    public record Completion(Attempt attempt, int status) {
        public Completion {
            Objects.requireNonNull(attempt, "attempt");
            int success = attempt.mutation().operation() == Operation.DELETE_IMMUTABLE ? 204 : 200;
            if (status != success && status != 412) { throw invalid("Unsupported terminal declaration"); }
        }
    }

    private final long generation;
    private final S3PublicationState.Target target;
    private final S3CheckpointHeads.Binding binding;
    private final UUID placementId;
    private final List<Entry> roots;
    private final long completedOrdinal;
    private final Completion lastCompletion;
    private final Attempt pending;

    private S3CoordinationState(long generation, S3PublicationState.Target target, S3CheckpointHeads.Binding binding,
            UUID placementId, List<Entry> roots, long completedOrdinal, Completion lastCompletion, Attempt pending) {
        if (generation <= 0 || completedOrdinal < 0) { throw invalid("Invalid coordination generation or ordinal"); }
        this.generation = generation;
        this.target = Objects.requireNonNull(target, "target");
        this.binding = Objects.requireNonNull(binding, "binding");
        identity(placementId, "placementId"); this.placementId = placementId;
        if (roots.size() > MAX_ROOTS) { throw limit("Root catalog capacity exceeded"); }
        this.roots = List.copyOf(roots);
        String previous = null;
        int references = 0;
        var publications = new HashSet<UUID>();
        var objects = new HashMap<ObjectKey, ObjectReference>();
        for (var entry : this.roots) {
            var root = entry.root();
            String id = root.reference().id().value();
            if (previous != null && previous.compareTo(id) >= 0) { throw invalid("Root catalog must be strictly ordered without duplicates"); }
            previous = id;
            if (!publications.add(root.head().publicationId())) { throw invalid("Publication identity is reused in root catalog"); }
            try { ObjectCheckpointHeadVerifier.requireBinding(root.head(), binding.namespaceId(), binding.publisherId(), binding.lineageId()); }
            catch (org.meridiandb.storage.manifest.ObjectCheckpointHeadException rejected) {
                throw failure(Code.INVALID_BINDING, "Root binding differs", rejected);
            }
            references += root.manifest().segments().size();
            if (references > MAX_SEGMENT_REFERENCES) { throw limit("Aggregate segment-reference work exceeded"); }
            addObjects(objects, root);
        }
        if ((completedOrdinal == 0) != (lastCompletion == null)) { throw invalid("Completion watermark lacks exact last record"); }
        if (lastCompletion != null) {
            requireAttemptBinding(lastCompletion.attempt());
            if (lastCompletion.attempt().ordinal() != completedOrdinal) { throw invalid("Last completion ordinal differs"); }
            validateMutation(lastCompletion.attempt().mutation(), false);
        }
        if (pending != null) {
            requireAttemptBinding(pending);
            if (generation == Long.MAX_VALUE) { throw invalid("Pending state has no completion generation"); }
            if (completedOrdinal == Long.MAX_VALUE || pending.ordinal() != completedOrdinal + 1) { throw invalid("Pending ordinal is not contiguous"); }
            validateMutation(pending.mutation(), true);
        }
        this.completedOrdinal = completedOrdinal;
        this.lastCompletion = lastCompletion;
        this.pending = pending;
    }

    public static S3CoordinationState fresh(S3PublicationState.Target target, S3CheckpointHeads.Binding binding, UUID placementId) {
        return new S3CoordinationState(1, target, binding, placementId, List.of(), 0, null, null);
    }
    static S3CoordinationState restore(long generation, S3PublicationState.Target target, S3CheckpointHeads.Binding binding,
            UUID placementId, List<Entry> roots, long completedOrdinal, Completion completion, Attempt pending) {
        return new S3CoordinationState(generation, target, binding, placementId, roots, completedOrdinal, completion, pending);
    }
    public long generation() { return generation; }
    public S3PublicationState.Target target() { return target; }
    public S3CheckpointHeads.Binding binding() { return binding; }
    public UUID placementId() { return placementId; }
    public List<Entry> roots() { return roots; }
    public long completedOrdinal() { return completedOrdinal; }
    public Optional<Completion> lastCompletion() { return Optional.ofNullable(lastCompletion); }
    public Optional<Attempt> pending() { return Optional.ofNullable(pending); }

    public void requireConfiguration(S3PublicationState.Target configured, S3CheckpointHeads.Binding expected, UUID placement) {
        Objects.requireNonNull(configured, "configured"); Objects.requireNonNull(expected, "expected"); Objects.requireNonNull(placement, "placement");
        if (!target.endpoint().toString().equals(configured.endpoint().toString()) || !target.regionId().equals(configured.regionId())
                || !target.bucket().equals(configured.bucket()) || !binding.equals(expected) || !placementId.equals(placement)) {
            throw failure(Code.INVALID_BINDING, "Coordination configuration differs", null);
        }
    }

    S3CoordinationState register(Root root) {
        clean(); Objects.requireNonNull(root, "root");
        if (roots.size() == MAX_ROOTS) { throw limit("Root catalog capacity exhausted"); }
        if (roots.stream().anyMatch(entry -> entry.root().reference().equals(root.reference()))) { throw transition("Root identity already registered or retired"); }
        var entries = new ArrayList<>(roots); entries.add(new Entry(root, false, false));
        entries.sort(java.util.Comparator.comparing(entry -> entry.root().reference().id().value()));
        return changed(entries, completedOrdinal, lastCompletion, null);
    }
    S3CoordinationState retain(ObjectReference root, boolean retained) { return update(root, retained, false); }
    S3CoordinationState retire(ObjectReference root) { return update(root, false, true); }
    private S3CoordinationState update(ObjectReference root, boolean retained, boolean retire) {
        clean(); Objects.requireNonNull(root, "root");
        var entries = new ArrayList<>(roots);
        for (int i = 0; i < entries.size(); i++) {
            var before = entries.get(i);
            if (!before.root().reference().equals(root)) { continue; }
            if (before.retired() || (retire && before.retained())) { throw transition("Retired/retained root cannot enter requested state"); }
            entries.set(i, new Entry(before.root(), retained, retire));
            return changed(entries, completedOrdinal, lastCompletion, null);
        }
        throw transition("Root identity is not registered");
    }
    S3CoordinationState begin(UUID nonce, Mutation mutation) {
        clean();
        if (generation > Long.MAX_VALUE - 2) { throw transition("Intent and completion generations must both fit"); }
        if (completedOrdinal == Long.MAX_VALUE) { throw transition("Attempt ordinal exhausted"); }
        validateMutation(Objects.requireNonNull(mutation, "mutation"), true);
        return changed(roots, completedOrdinal, lastCompletion, new Attempt(placementId, completedOrdinal + 1, nonce, mutation));
    }
    S3CoordinationState settle(Attempt original, int status) {
        Objects.requireNonNull(original, "original");
        if (pending == null || !pending.equals(original)) { throw transition("Completion does not identify exact pending attempt"); }
        var completion = new Completion(original, status);
        return changed(roots, original.ordinal(), completion, null);
    }
    private S3CoordinationState changed(List<Entry> entries, long completed, Completion completion, Attempt attempt) {
        if (generation == Long.MAX_VALUE) { throw transition("Coordination generation exhausted"); }
        return new S3CoordinationState(generation + 1, target, binding, placementId, entries, completed, completion, attempt);
    }
    private void clean() { if (pending != null) { throw transition("Unresolved individual attempt blocks transition"); } }
    private void requireAttemptBinding(Attempt attempt) {
        if (!placementId.equals(attempt.placementId())) { throw failure(Code.INVALID_BINDING, "Attempt placement differs", null); }
    }

    private void validateMutation(Mutation mutation, boolean current) {
        var active = new HashMap<ObjectKey, ObjectReference>();
        var retired = new HashMap<ObjectKey, ObjectReference>();
        var all = new HashMap<ObjectReference, Entry>();
        for (var entry : roots) {
            addObjects(entry.retired() ? retired : active, entry.root());
            all.put(entry.root().reference(), entry);
        }
        var op = mutation.operation();
        if (op == Operation.CREATE_HEAD || op == Operation.REPLACE_HEAD) {
            if (!mutation.key().equals(S3CheckpointHeads.headKey(binding.namespaceId()))) { throw invalid("Head mutation key differs"); }
            var candidate = all.get(mutation.content());
            if (candidate == null || (current && candidate.retired())) { throw invalid("Head mutation requires a registered live root"); }
            if (op == Operation.CREATE_HEAD) {
                if (candidate.root().head().revision() != 1) { throw invalid("Initial selection requires revision one"); }
            } else {
                var previous = all.get(mutation.originalHead());
                if (previous == null || (current && previous.retired())) { throw invalid("Original expected head is not registered/live"); }
                try { ObjectCheckpointHeadVerifier.requireSuccessor(previous.root().head(), candidate.root().head()); }
                catch (org.meridiandb.storage.manifest.ObjectCheckpointHeadException rejected) {
                    throw failure(Code.INVALID_STATE, "Replacement is not exact adjacent metadata", rejected);
                }
            }
        } else {
            boolean matchesActive = mutation.content().equals(active.get(mutation.key()));
            boolean matchesRetired = mutation.content().equals(retired.get(mutation.key()));
            if (op == Operation.CREATE_IMMUTABLE && !(matchesActive || (!current && matchesRetired))) {
                throw invalid("Immutable creation requires registered live dependency");
            }
            if (op == Operation.DELETE_IMMUTABLE && (!matchesRetired || (current && active.containsKey(mutation.key())))) {
                throw invalid("Deletion declaration requires retired, unshared dependency");
            }
        }
    }
    private void addObjects(Map<ObjectKey, ObjectReference> map, Root root) {
        put(map, S3PublicationPreparer.intentKey(binding.namespaceId(), root.reference().id()), root.reference());
        put(map, ObjectCheckpointGraphs.manifestKey(binding.namespaceId(), root.head().manifest().id()), root.head().manifest());
        for (var segment : root.manifest().segments()) {
            put(map, ObjectCheckpointGraphs.segmentKey(binding.namespaceId(), segment.id()),
                    new ObjectReference(new ObjectId(segment.id().value()), Math.toIntExact(segment.byteLength())));
        }
    }
    private static void put(Map<ObjectKey, ObjectReference> map, ObjectKey key, ObjectReference reference) {
        var previous = map.putIfAbsent(key, reference);
        if (previous != null && !previous.equals(reference)) { throw invalid("Shared key has conflicting declared reference"); }
    }
    private static void identity(UUID id, String name) {
        Objects.requireNonNull(id, name);
        if (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0) { throw invalid(name + " must be nonnil"); }
    }
    static S3CoordinationException failure(Code code, String message, Throwable cause) { return new S3CoordinationException(code, message, cause); }
    private static S3CoordinationException invalid(String message) { return failure(Code.INVALID_STATE, message, null); }
    private static S3CoordinationException limit(String message) { return failure(Code.LIMIT_EXCEEDED, message, null); }
    private static S3CoordinationException transition(String message) { return failure(Code.INVALID_TRANSITION, message, null); }
}
