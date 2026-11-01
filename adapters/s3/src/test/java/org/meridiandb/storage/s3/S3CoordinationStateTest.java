// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.*;
import static org.meridiandb.storage.s3.CoordinationFixtures.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.storage.s3.S3CoordinationException.Code;
import org.meridiandb.storage.s3.S3CoordinationState.Attempt;
import org.meridiandb.storage.s3.S3CoordinationState.Completion;
import org.meridiandb.storage.s3.S3CoordinationState.Entry;
import org.meridiandb.storage.s3.S3CoordinationState.Mutation;
import org.meridiandb.storage.s3.S3CoordinationState.Operation;

class S3CoordinationStateTest {
    @Test void freshDeclarationsAreEmptyWithoutGrantingAdmission() {
        var state = fresh();
        assertEquals(1, state.generation()); assertEquals(0, state.completedOrdinal());
        assertTrue(state.roots().isEmpty()); assertTrue(state.pending().isEmpty()); assertTrue(state.lastCompletion().isEmpty());
        state.requireConfiguration(TARGET, BINDING, PLACEMENT);
    }

    @ParameterizedTest @ValueSource(strings = {"slash", "host-case", "region", "bucket", "namespace", "publisher", "lineage", "placement"})
    void independentConfigurationPreservesLexicalEndpointAndEveryBinding(String changed) {
        var target = new S3PublicationState.Target(URI.create(changed.equals("slash") ? "http://localhost:9000/"
                : changed.equals("host-case") ? "http://LOCALHOST:9000" : TARGET.endpoint().toString()),
                changed.equals("region") ? "us-west-2" : TARGET.regionId(), changed.equals("bucket") ? "other-bucket" : TARGET.bucket());
        var binding = new S3CheckpointHeads.Binding(changed.equals("namespace") ? new UUID(0, 99) : BINDING.namespaceId(),
                changed.equals("publisher") ? new UUID(0, 99) : BINDING.publisherId(),
                changed.equals("lineage") ? new ObjectId("f".repeat(64)) : BINDING.lineageId());
        code(Code.INVALID_BINDING, () -> fresh().requireConfiguration(target, binding, changed.equals("placement") ? NONCE : PLACEMENT));
    }

    @Test void registeredMetadataAndReturnedCollectionsAreImmutable() {
        byte[] head = ONE.headImage(), manifest = ONE.manifestImage();
        var root = new S3CoordinationState.Root(head, manifest);
        var state = fresh().register(root);
        head[0] ^= 1; manifest[0] ^= 1; root.headImage()[0] ^= 1; root.manifestImage()[0] ^= 1;
        assertArrayEquals(ONE.headImage(), root.headImage()); assertArrayEquals(ONE.manifestImage(), root.manifestImage());
        assertThrows(UnsupportedOperationException.class, () -> state.roots().clear());
        assertThrows(UnsupportedOperationException.class, () -> root.manifest().segments().clear());
        assertEquals(ONE.reference(), state.roots().getFirst().root().reference());
        assertTrue(fresh().roots().isEmpty());
    }

    @Test void rootHeadAndManifestMustBindExactBytesAndIndependentLineage() {
        code(Code.INVALID_STATE, () -> new S3CoordinationState.Root(ONE.headImage(), TWO.manifestImage()));
        code(Code.INVALID_STATE, () -> new S3CoordinationState.Root(new byte[255], ONE.manifestImage()));
        byte[] damaged = ONE.headImage(); damaged[24] ^= 1;
        code(Code.INVALID_STATE, () -> new S3CoordinationState.Root(damaged, ONE.manifestImage()));
        var foreign = S3CoordinationState.fresh(TARGET, new S3CheckpointHeads.Binding(new UUID(0, 99), BINDING.publisherId(), BINDING.lineageId()), PLACEMENT);
        code(Code.INVALID_BINDING, () -> foreign.register(ONE));
        code(Code.LIMIT_EXCEEDED, () -> new S3CoordinationState.Root(ONE.headImage(), new byte[16385]));
    }

    @Test void catalogOrderingIsCanonicalAndTombstonesCannotBeReusedOrRevived() {
        var forward = fresh().register(ONE).register(TWO);
        var reverse = fresh().register(TWO).register(ONE);
        assertArrayEquals(S3CoordinationStateCodec.encode(forward), S3CoordinationStateCodec.encode(reverse));
        code(Code.INVALID_TRANSITION, () -> forward.register(ONE));
        var retained = forward.retain(ONE.reference(), true);
        code(Code.INVALID_TRANSITION, () -> retained.retire(ONE.reference()));
        var retired = retained.retain(ONE.reference(), false).retire(ONE.reference());
        assertTrue(retired.roots().stream().filter(e -> e.root().reference().equals(ONE.reference())).findFirst().orElseThrow().retired());
        code(Code.INVALID_TRANSITION, () -> retired.register(ONE));
        code(Code.INVALID_TRANSITION, () -> retired.retain(ONE.reference(), false));
        code(Code.INVALID_TRANSITION, () -> retired.retire(ONE.reference()));
        code(Code.INVALID_STATE, () -> new Entry(ONE, true, true));
    }

    @ParameterizedTest @ValueSource(strings = {"register", "retain", "retire", "begin"})
    void unresolvedAttemptFreezesAllCatalogAndOtherAttemptTransitions(String action) {
        var state = pending(); byte[] before = S3CoordinationStateCodec.encode(state);
        code(Code.INVALID_TRANSITION, () -> {
            switch (action) {
                case "register" -> state.register(TWO);
                case "retain" -> state.retain(ONE.reference(), true);
                case "retire" -> state.retire(ONE.reference());
                case "begin" -> state.begin(NONCE, create(ONE));
                default -> throw new AssertionError(action);
            }
        });
        assertArrayEquals(before, S3CoordinationStateCodec.encode(state));
        assertTrue(S3CoordinationStateCodec.decode(before).state().pending().isPresent());
    }

    @ParameterizedTest @ValueSource(strings = {"placement", "ordinal", "nonce", "operation", "key", "content", "original", "condition"})
    void onlyTheExactOriginalCompoundAttemptCanBeSettled(String changed) {
        var state = replacement(); var original = state.pending().orElseThrow(); var m = original.mutation();
        var mutation = new Mutation(changed.equals("operation") ? Operation.DELETE_IMMUTABLE : m.operation(),
                changed.equals("key") ? new ObjectKey("other-key") : m.key(),
                changed.equals("content") ? ONE.reference() : m.content(),
                changed.equals("operation") ? null : changed.equals("original") ? TWO.reference() : m.originalHead(),
                changed.equals("condition") ? new S3HeadToken("\"other\"") : m.condition());
        var altered = new Attempt(changed.equals("placement") ? NONCE : original.placementId(),
                changed.equals("ordinal") ? 3 : original.ordinal(), changed.equals("nonce") ? PLACEMENT : original.nonce(), mutation);
        code(Code.INVALID_TRANSITION, () -> state.settle(altered, 200));
        assertEquals(original, state.pending().orElseThrow());
    }

    @ParameterizedTest @ValueSource(ints = {0, 201, 204, 403, 404, 409, 500})
    void arbitraryOrAmbiguousStatusesCannotClearPending(int status) {
        var state = pending(); code(Code.INVALID_STATE, () -> state.settle(state.pending().orElseThrow(), status));
        assertEquals(0, state.completedOrdinal()); assertTrue(state.pending().isPresent());
    }

    @Test void terminalDeclarationsAdvanceOneOrdinalAndKeepExactLastEvidence() {
        var state = pending(); var original = state.pending().orElseThrow();
        var complete = state.settle(original, 200);
        assertEquals(new Completion(original, 200), complete.lastCompletion().orElseThrow());
        assertEquals(1, complete.completedOrdinal()); assertTrue(complete.pending().isEmpty());
        var next = complete.begin(NONCE, create(ONE));
        assertEquals(2, next.pending().orElseThrow().ordinal());
        assertNotEquals(original, next.pending().orElseThrow(), "Nonce reuse alone is not compound ID reuse");
        code(Code.INVALID_TRANSITION, () -> next.settle(original, 200));
        var rejected = next.settle(next.pending().orElseThrow(), 412);
        assertEquals(2, rejected.completedOrdinal()); assertEquals(412, rejected.lastCompletion().orElseThrow().status());
        code(Code.INVALID_TRANSITION, () -> rejected.settle(original, 200));
        assertEquals(0, fresh().completedOrdinal());
    }

    @ParameterizedTest @ValueSource(strings = {"put-token", "create-token", "replace-no-token", "replace-no-original", "delete-no-token", "wrong-original-size"})
    void operationConditionAndOriginalIdentityShapesAreStrict(String kind) {
        code(Code.INVALID_STATE, () -> {
            switch (kind) {
                case "put-token" -> new Mutation(Operation.CREATE_IMMUTABLE, create(ONE).key(), ONE.reference(), null, TOKEN);
                case "create-token" -> new Mutation(Operation.CREATE_HEAD, S3CheckpointHeads.headKey(BINDING.namespaceId()), ONE.reference(), null, TOKEN);
                case "replace-no-token" -> new Mutation(Operation.REPLACE_HEAD, replace().key(), TWO.reference(), ONE.reference(), null);
                case "replace-no-original" -> new Mutation(Operation.REPLACE_HEAD, replace().key(), TWO.reference(), null, TOKEN);
                case "delete-no-token" -> new Mutation(Operation.DELETE_IMMUTABLE, create(ONE).key(), ONE.reference(), null, null);
                case "wrong-original-size" -> new Mutation(Operation.REPLACE_HEAD, replace().key(), TWO.reference(), new ObjectReference(ONE.reference().id(), 255), TOKEN);
                default -> throw new AssertionError(kind);
            }
        });
    }

    @ParameterizedTest @ValueSource(strings = {"namespace", "kind", "hash", "length", "current-delete", "live-delete", "retired-create"})
    void arbitraryKeysReferencesAndUnsafeRolesAreRejected(String kind) {
        var base = registered(); var create = create(ONE);
        var key = switch (kind) {
            case "namespace" -> S3PublicationPreparer.intentKey(new UUID(0, 99), ONE.reference().id());
            case "kind" -> ObjectCheckpointGraphs.manifestKey(BINDING.namespaceId(), ONE.reference().id());
            case "hash" -> S3PublicationPreparer.intentKey(BINDING.namespaceId(), new ObjectId("f".repeat(64)));
            case "current-delete" -> S3CheckpointHeads.headKey(BINDING.namespaceId());
            default -> create.key();
        };
        boolean delete = kind.endsWith("delete");
        var state = kind.equals("retired-create") ? base.retire(ONE.reference()) : base;
        var mutation = new Mutation(delete ? Operation.DELETE_IMMUTABLE : Operation.CREATE_IMMUTABLE, key,
                kind.equals("length") ? new ObjectReference(ONE.reference().id(), 255) : ONE.reference(), null, delete ? TOKEN : null);
        code(Code.INVALID_STATE, () -> state.begin(NONCE, mutation));
    }

    @Test void headReplacementRequiresTheExactAdjacentRegisteredOriginalAndCandidate() {
        var state = fresh().register(ONE).register(TWO);
        var pending = state.begin(NONCE, replace());
        assertEquals(ONE.reference(), pending.pending().orElseThrow().mutation().originalHead());
        assertEquals(TOKEN, pending.pending().orElseThrow().mutation().condition());
        code(Code.INVALID_STATE, () -> registered().begin(NONCE, replace()));
        code(Code.INVALID_STATE, () -> state.begin(NONCE,
                new Mutation(Operation.CREATE_HEAD, replace().key(), TWO.reference(), null, null)));
        code(Code.INVALID_STATE, () -> state.begin(NONCE,
                new Mutation(Operation.REPLACE_HEAD, replace().key(), TWO.reference(), TWO.reference(), TOKEN)));
    }

    @Test void sharedDependencyCannotBeDeletedUntilEveryDeclaringRootIsRetired() {
        var id = new SegmentId("a".repeat(64));
        var segment = new CheckpointManifest.SegmentReference(id, 160, 1, 1);
        var first = root(10, List.of(segment)); var second = root(11, List.of(segment));
        var both = fresh().register(first).register(second).retire(first.reference());
        var mutation = new Mutation(Operation.DELETE_IMMUTABLE, ObjectCheckpointGraphs.segmentKey(BINDING.namespaceId(), id),
                new ObjectReference(new ObjectId(id.value()), 160), null, TOKEN);
        code(Code.INVALID_STATE, () -> both.begin(NONCE, mutation));
        var retired = both.retire(second.reference()); var deleting = retired.begin(NONCE, mutation);
        var completed = deleting.settle(deleting.pending().orElseThrow(), 204);
        assertEquals(204, completed.lastCompletion().orElseThrow().status());
        assertEquals(2, completed.roots().size()); assertTrue(completed.roots().stream().allMatch(Entry::retired));
    }

    @Test void sameObjectKeyCannotHaveConflictingLengthDeclarations() {
        var id = new SegmentId("b".repeat(64));
        var first = root(10, List.of(new CheckpointManifest.SegmentReference(id, 160, 1, 1)));
        var conflicting = root(11, List.of(new CheckpointManifest.SegmentReference(id, 161, 1, 1)));
        code(Code.INVALID_STATE, () -> fresh().register(first).register(conflicting));
    }

    @Test void rootAndAggregateReferenceCapacitiesAreBoundedAndRetirementDoesNotEraseHistory() {
        var state = fresh();
        for (int i = 1; i <= 32; i++) { state = state.register(root(i, List.of())); }
        var full = state;
        assertEquals(32, full.roots().size());
        code(Code.LIMIT_EXCEEDED, () -> full.register(root(33, List.of())));
        var retired = full.retire(full.roots().getFirst().root().reference());
        code(Code.LIMIT_EXCEEDED, () -> retired.register(root(34, List.of())));
        var refs = new ArrayList<CheckpointManifest.SegmentReference>();
        for (int i = 1; i <= 129; i++) { refs.add(new CheckpointManifest.SegmentReference(new SegmentId(String.format("%064x", i)), 160, i, i)); }
        state = fresh();
        for (int i = 1; i <= 32; i++) { state = state.register(root(i, refs.subList(0, 128))); }
        byte[] exact = S3CoordinationStateCodec.encode(state);
        assertEquals(32, S3CoordinationStateCodec.decode(exact).state().roots().size());
        var oversized = new ArrayList<Entry>(state.roots());
        var replacement = root(99, refs);
        oversized.set(0, new Entry(replacement, false, false));
        oversized.sort(java.util.Comparator.comparing(e -> e.root().reference().id().value()));
        code(Code.LIMIT_EXCEEDED, () -> S3CoordinationState.restore(33, TARGET, BINDING, PLACEMENT, oversized, 0, null, null));
    }

    @Test void beginReservesBothGenerationAdvancesAndOrdinalExhaustionIsSeparate() {
        var roots = registered().roots();
        for (long generation : new long[] {Long.MAX_VALUE, Long.MAX_VALUE - 1}) {
            var state = S3CoordinationState.restore(generation, TARGET, BINDING, PLACEMENT, roots, 0, null, null);
            code(Code.INVALID_TRANSITION, () -> state.begin(NONCE, create(ONE)));
        }
        var near = S3CoordinationState.restore(Long.MAX_VALUE - 2, TARGET, BINDING, PLACEMENT, roots, 0, null, null).begin(NONCE, create(ONE));
        assertEquals(Long.MAX_VALUE, near.settle(near.pending().orElseThrow(), 200).generation());
        var last = new Attempt(PLACEMENT, Long.MAX_VALUE, NONCE, create(ONE));
        var exhausted = S3CoordinationState.restore(4, TARGET, BINDING, PLACEMENT, roots, Long.MAX_VALUE, new Completion(last, 200), null);
        code(Code.INVALID_TRANSITION, () -> exhausted.begin(NONCE, create(ONE)));
    }

    static void code(Code expected, Runnable action) { assertEquals(expected, assertThrows(S3CoordinationException.class, action::run).code()); }
}
