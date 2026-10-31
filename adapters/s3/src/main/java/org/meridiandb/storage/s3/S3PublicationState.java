// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadException;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectReference;

/**
 * Immutable declared expected/pending metadata, without client credentials, I/O or durability.
 * Pure recognition of a supplied candidate observation proves neither a GET nor a verified graph.
 */
public final class S3PublicationState {
    public static final int MAX_ENDPOINT_BYTES = 2048;
    public static final int MAX_REGION_BYTES = 128;

    public record Target(URI endpoint, String regionId, String bucket) {
        public Target {
            Objects.requireNonNull(endpoint, "endpoint");
            ascii(endpoint.toString(), MAX_ENDPOINT_BYTES, "endpoint");
            ascii(regionId, MAX_REGION_BYTES, "regionId");
            ascii(bucket, 63, "bucket");
            S3ClientOwner.validateTarget(endpoint, bucket);
        }
    }

    public enum Operation { CREATE_IF_ABSENT, REPLACE_IF_MATCH }

    public static final class Intent {
        private final Operation operation;
        private final String precondition;
        private final byte[] image;
        private final ObjectCheckpointHeadCodec.DecodedHead decoded;
        private Intent(S3HeadObservation expected, byte[] image, ObjectCheckpointHeadCodec.DecodedHead decoded) {
            operation = expected == null ? Operation.CREATE_IF_ABSENT : Operation.REPLACE_IF_MATCH;
            precondition = expected == null ? "*" : expected.token().value();
            this.image = image;
            this.decoded = decoded;
        }
        public Operation operation() { return operation; }
        public String precondition() { return precondition; }
        public byte[] image() { return image.clone(); }
        public ObjectCheckpointHead head() { return decoded.head(); }
        public ObjectReference reference() { return decoded.reference(); }
    }

    private final long generation;
    private final Target target;
    private final S3CheckpointHeads.Binding binding;
    private final S3HeadObservation expected;
    private final Intent pending;

    private S3PublicationState(long generation, Target target, S3CheckpointHeads.Binding binding,
                               S3HeadObservation expected, byte[] candidate) {
        if (generation <= 0) { throw new IllegalArgumentException("State generation must be positive"); }
        this.generation = generation;
        this.target = Objects.requireNonNull(target, "target");
        this.binding = Objects.requireNonNull(binding, "binding");
        if (expected != null) { requireObservation(expected); }
        this.expected = expected;
        pending = candidate == null ? null : intent(candidate);
    }

    /** Declares uninitialized local/namespace state; does not inspect a remote key or create local files. */
    public static S3PublicationState fresh(Target target, S3CheckpointHeads.Binding binding) {
        return new S3PublicationState(1, target, binding, null, null);
    }

    // Decoding restores declarations, not proof of transition history, observation or durable installation.
    static S3PublicationState restore(long generation, Target target, S3CheckpointHeads.Binding binding,
                                      S3HeadObservation expected, byte[] candidate) {
        return new S3PublicationState(generation, target, binding, expected, candidate);
    }

    /** Preserve the original observation/precondition and supplied candidate identity without regeneration. */
    public S3PublicationState prepare(byte[] candidate) {
        if (pending != null) { throw failure(S3PublicationStateException.Code.INVALID_TRANSITION, "Pending intent requires reconciliation", null); }
        Objects.requireNonNull(candidate, "candidate");
        return new S3PublicationState(nextGeneration(), target, binding, expected, candidate);
    }

    /**
     * Recognize only an exact supplied candidate observation. The caller must independently verify
     * its provenance, complete graph/owned prefix and durable installation before claiming selection.
     */
    public S3PublicationState recordSelected(S3HeadObservation selected) {
        if (pending == null) { throw failure(S3PublicationStateException.Code.INVALID_TRANSITION, "No pending intent to recognize", null); }
        requireObservation(Objects.requireNonNull(selected, "selected"));
        if (!Arrays.equals(pending.image, selected.image())) {
            throw failure(S3PublicationStateException.Code.INVALID_TRANSITION, "Observation is not the exact pending candidate", null);
        }
        return new S3PublicationState(nextGeneration(), target, binding, selected, null);
    }

    /** Compare independent managed configuration, including the original lexical endpoint spelling. */
    public void requireConfiguration(Target configuredTarget, S3CheckpointHeads.Binding configuredBinding) {
        Objects.requireNonNull(configuredTarget, "configuredTarget");
        Objects.requireNonNull(configuredBinding, "configuredBinding");
        if (!sameEndpoint(target.endpoint(), configuredTarget.endpoint()) || !target.regionId().equals(configuredTarget.regionId())
                || !target.bucket().equals(configuredTarget.bucket()) || !binding.equals(configuredBinding)) {
            throw failure(S3PublicationStateException.Code.INVALID_BINDING, "Publication configuration differs", null);
        }
    }

    public long generation() { return generation; }
    public Target target() { return target; }
    public S3CheckpointHeads.Binding binding() { return binding; }
    public Optional<S3HeadObservation> expected() { return Optional.ofNullable(expected); }
    public Optional<Intent> pending() { return Optional.ofNullable(pending); }

    private Intent intent(byte[] candidate) {
        byte[] image = S3HeadObservation.snapshot(candidate);
        var decoded = ObjectCheckpointHeadCodec.decode(image);
        requireBinding(decoded.head());
        if (expected == null) {
            if (decoded.head().revision() != 1) {
                throw failure(S3PublicationStateException.Code.INVALID_TRANSITION, "Genesis requires revision one", null);
            }
        } else {
            try { ObjectCheckpointHeadVerifier.requireSuccessor(expected.head(), decoded.head()); }
            catch (ObjectCheckpointHeadException invalid) {
                throw failure(S3PublicationStateException.Code.INVALID_TRANSITION, "Invalid adjacent pending candidate", invalid);
            }
        }
        return new Intent(expected, image, decoded);
    }

    private void requireObservation(S3HeadObservation observation) {
        if (!sameEndpoint(target.endpoint(), observation.endpoint()) || !target.bucket().equals(observation.bucket())) {
            throw failure(S3PublicationStateException.Code.INVALID_BINDING, "Observation scope differs", null);
        }
        requireBinding(observation.head());
    }

    private void requireBinding(ObjectCheckpointHead head) {
        try { ObjectCheckpointHeadVerifier.requireBinding(head, binding.namespaceId(), binding.publisherId(), binding.lineageId()); }
        catch (ObjectCheckpointHeadException invalid) {
            throw failure(S3PublicationStateException.Code.INVALID_BINDING, "Head binding differs", invalid);
        }
    }

    private long nextGeneration() {
        if (generation == Long.MAX_VALUE) {
            throw failure(S3PublicationStateException.Code.INVALID_TRANSITION, "Local state generation exhausted", null);
        }
        return generation + 1;
    }

    private static boolean sameEndpoint(URI one, URI two) { return one.toString().equals(two.toString()); }
    private static void ascii(String value, int maxBytes, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty() || value.length() > maxBytes) { throw new IllegalArgumentException(name + " exceeds supported bounds"); }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x21 || ch > 0x7e) { throw new IllegalArgumentException(name + " requires printable nonspace ASCII"); }
        }
    }
    private static S3PublicationStateException failure(S3PublicationStateException.Code code, String message, Throwable cause) {
        return new S3PublicationStateException(code, message, cause);
    }
}
