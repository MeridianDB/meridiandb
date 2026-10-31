// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadException;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3PublicationState.Operation;
import org.meridiandb.storage.s3.S3PublicationState.Target;
import org.meridiandb.storage.s3.S3PublicationStateException.Code;
import org.meridiandb.temporal.DatabaseId;

class S3PublicationStateTest {
    private static final URI ENDPOINT = URI.create("https://example.test");
    private static final Target TARGET = new Target(ENDPOINT, "us-east-1", "bucket");
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final UUID OTHER = new UUID(0, 99);
    private static final ObjectId LINEAGE = new ObjectId(
            "2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c");
    private static final ObjectId ZERO = new ObjectId("0".repeat(64));
    private static final ObjectReference MANIFEST = new ObjectReference(new ObjectId(
            "bd068a065754e52824efaf91c44bc7234985e9a93c024dcb90e6951152e66982"), 136);
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, LINEAGE);
    private static final ObjectCheckpointHead FIRST = head(NAMESPACE, PUBLISHER, LINEAGE, 3, 1, null);
    private static final byte[] FIRST_IMAGE = ObjectCheckpointHeadCodec.encode(FIRST);
    private static final ObjectCheckpointHead SECOND = next(FIRST, 4);
    private static final byte[] SECOND_IMAGE = ObjectCheckpointHeadCodec.encode(SECOND);
    private static final String FIRST_TOKEN = "\"opaque-first-token\"";

    @Test void freshStatePreservesIndependentConfigurationWithoutInventingObservationOrIntent() {
        var state = S3PublicationState.fresh(TARGET, BINDING);
        assertEquals(1, state.generation());
        assertEquals(ENDPOINT.toString(), state.target().endpoint().toString());
        assertEquals("us-east-1", state.target().regionId());
        assertEquals("bucket", state.target().bucket());
        assertEquals(BINDING, state.binding());
        assertTrue(state.expected().isEmpty());
        assertTrue(state.pending().isEmpty());
        assertDoesNotThrow(() -> state.requireConfiguration(TARGET, BINDING));
    }

    @Test void createPreparationCapturesSuppliedExactIdentityAndLeavesFreshStateUnchanged() {
        var fresh = fresh();
        var pending = fresh.prepare(FIRST_IMAGE);
        assertEquals(2, pending.generation());
        assertTrue(pending.expected().isEmpty());
        assertIntent(pending, Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
        assertEquals(FIRST.publicationId(), pending.pending().orElseThrow().head().publicationId());
        assertEquals(1, fresh.generation());
        assertTrue(fresh.expected().isEmpty());
        assertTrue(fresh.pending().isEmpty());
        assertIntent(fresh.prepare(FIRST_IMAGE), Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
    }

    @Test void exactCandidateRecognitionAdvancesLocalGenerationAndRetainsItsOpaqueObservation() {
        var pending = fresh().prepare(FIRST_IMAGE);
        var selected = observation(FIRST_IMAGE, FIRST_TOKEN);
        var ready = pending.recordSelected(selected);
        assertEquals(3, ready.generation());
        assertTrue(ready.pending().isEmpty());
        assertObservation(selected, ready.expected().orElseThrow());
        assertEquals(2, pending.generation());
        assertTrue(pending.expected().isEmpty());
        assertIntent(pending, Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
    }

    @Test void replacementCapturesOriginalObservationAndRecognitionCreatesTheNextReadyState() {
        var ready = ready(FIRST_TOKEN);
        var pending = ready.prepare(SECOND_IMAGE);
        assertEquals(4, pending.generation());
        assertObservation(ready.expected().orElseThrow(), pending.expected().orElseThrow());
        assertIntent(pending, Operation.REPLACE_IF_MATCH, FIRST_TOKEN, SECOND_IMAGE);
        var selected = observation(SECOND_IMAGE, "\"second-token\"");
        var complete = pending.recordSelected(selected);
        assertEquals(5, complete.generation());
        assertTrue(complete.pending().isEmpty());
        assertObservation(selected, complete.expected().orElseThrow());
        assertIntent(pending, Operation.REPLACE_IF_MATCH, FIRST_TOKEN, SECOND_IMAGE);
        assertArrayEquals(FIRST_IMAGE, ready.expected().orElseThrow().image());
    }

    @ParameterizedTest @ValueSource(strings = {"\"*\"", "\"a,b\"", "\"back\\slash\"", "\"\""})
    void replacementPreconditionPreservesQuotedOpaquePunctuationExactly(String token) {
        var pending = ready(token).prepare(SECOND_IMAGE);
        assertIntent(pending, Operation.REPLACE_IF_MATCH, token, SECOND_IMAGE);
        assertEquals(token, pending.expected().orElseThrow().token().value());
        assertNotEquals(Operation.CREATE_IF_ABSENT, pending.pending().orElseThrow().operation());
        if (token.equals("\"*\"")) { assertNotEquals("*", pending.pending().orElseThrow().precondition()); }
    }

    @Test void anotherPrepareCannotReplaceAnUnresolvedCreateIntent() {
        var pending = fresh().prepare(FIRST_IMAGE);
        var competing = ObjectCheckpointHeadCodec.encode(head(NAMESPACE, PUBLISHER, LINEAGE, 9, 1, null));
        assertState(Code.INVALID_TRANSITION, () -> pending.prepare(FIRST_IMAGE));
        assertState(Code.INVALID_TRANSITION, () -> pending.prepare(competing));
        assertEquals(2, pending.generation());
        assertIntent(pending, Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
    }

    @Test void anotherPrepareCannotRefreshOrRebaseAnUnresolvedReplacement() {
        var pending = ready(FIRST_TOKEN).prepare(SECOND_IMAGE);
        assertState(Code.INVALID_TRANSITION, () -> pending.prepare(FIRST_IMAGE));
        assertState(Code.INVALID_TRANSITION, () -> pending.prepare(ObjectCheckpointHeadCodec.encode(next(FIRST, 9))));
        assertIntent(pending, Operation.REPLACE_IF_MATCH, FIRST_TOKEN, SECOND_IMAGE);
        assertArrayEquals(FIRST_IMAGE, pending.expected().orElseThrow().image());
    }

    @Test void aReadyStateCannotAdoptOrRefreshAnObservationWithoutPendingIntent() {
        assertState(Code.INVALID_TRANSITION, () -> fresh().recordSelected(observation(FIRST_IMAGE, FIRST_TOKEN)));
        var ready = ready(FIRST_TOKEN);
        assertState(Code.INVALID_TRANSITION,
                () -> ready.recordSelected(observation(FIRST_IMAGE, "\"refreshed-token\"")));
        assertState(Code.INVALID_TRANSITION,
                () -> ready.recordSelected(observation(SECOND_IMAGE, "\"new-head-token\"")));
        assertEquals(FIRST_TOKEN, ready.expected().orElseThrow().token().value());
        assertEquals(3, ready.generation());
    }

    @Test void anOldHeadWithAFreshOrContentLookingTokenCannotResolvePendingReplacement() {
        var pending = ready(FIRST_TOKEN).prepare(SECOND_IMAGE);
        String candidateLookingToken = "\"" + ObjectReference.forBytes(SECOND_IMAGE).id().value() + "\"";
        assertState(Code.INVALID_TRANSITION,
                () -> pending.recordSelected(observation(FIRST_IMAGE, candidateLookingToken)));
        assertIntent(pending, Operation.REPLACE_IF_MATCH, FIRST_TOKEN, SECOND_IMAGE);
        assertEquals(4, pending.generation());
    }

    @Test void aCompetingCandidateWithTheSameRevisionAndPrefixCannotResolveOriginalIntent() {
        var pending = ready(FIRST_TOKEN).prepare(SECOND_IMAGE);
        byte[] competing = ObjectCheckpointHeadCodec.encode(next(FIRST, 9));
        assertEquals(SECOND.revision(), ObjectCheckpointHeadCodec.decode(competing).head().revision());
        assertEquals(SECOND.journalPrefix(), ObjectCheckpointHeadCodec.decode(competing).head().journalPrefix());
        assertState(Code.INVALID_TRANSITION,
                () -> pending.recordSelected(observation(competing, "\"competing-token\"")));
        assertIntent(pending, Operation.REPLACE_IF_MATCH, FIRST_TOKEN, SECOND_IMAGE);
    }

    @Test void exactImageRecognitionDoesNotPretendToAuthenticateOrRequireANewProviderToken() {
        var pending = ready(FIRST_TOKEN).prepare(SECOND_IMAGE);
        var declared = observation(SECOND_IMAGE, FIRST_TOKEN);
        var recognized = pending.recordSelected(declared);
        assertArrayEquals(SECOND_IMAGE, recognized.expected().orElseThrow().image());
        assertEquals(FIRST_TOKEN, recognized.expected().orElseThrow().token().value());
        assertTrue(recognized.pending().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"https://EXAMPLE.test", "https://example.test/", "https://example.test:443"})
    void selectedObservationMustRetainTheOriginalEndpointSpelling(String endpoint) {
        var pending = fresh().prepare(FIRST_IMAGE);
        var other = S3HeadObservation.restore(URI.create(endpoint), "bucket", FIRST_IMAGE, new S3HeadToken(FIRST_TOKEN));
        assertState(Code.INVALID_BINDING, () -> pending.recordSelected(other));
        assertIntent(pending, Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
    }

    @Test void selectedObservationCannotMoveTheIntentToAnotherBucket() {
        var pending = fresh().prepare(FIRST_IMAGE);
        var other = S3HeadObservation.restore(ENDPOINT, "other-bucket", FIRST_IMAGE, new S3HeadToken(FIRST_TOKEN));
        assertState(Code.INVALID_BINDING, () -> pending.recordSelected(other));
        assertIntent(pending, Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
    }

    @ParameterizedTest @ValueSource(strings = {"namespace", "publisher", "lineage"})
    void preparingOrRecognizingForeignBindingsKeepsStateBindingErrors(String changed) {
        var foreign = head(changed.equals("namespace") ? OTHER : NAMESPACE,
                changed.equals("publisher") ? OTHER : PUBLISHER,
                changed.equals("lineage") ? ZERO : LINEAGE, 3, 1, null);
        byte[] foreignImage = ObjectCheckpointHeadCodec.encode(foreign);
        var failure = assertState(Code.INVALID_BINDING, () -> fresh().prepare(foreignImage));
        assertTrue(failure.getCause() instanceof ObjectCheckpointHeadException);
        var pending = fresh().prepare(FIRST_IMAGE);
        assertState(Code.INVALID_BINDING, () -> pending.recordSelected(observation(foreignImage, FIRST_TOKEN)));
        assertIntent(pending, Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
    }

    @Test void configurationComparisonUsesLexicalEndpointAndExactRegionBucketAndBindings() {
        var state = fresh();
        var equivalentUri = URI.create("https://EXAMPLE.test");
        assertEquals(ENDPOINT, equivalentUri, "URI equality alone is insufficient for persisted scope");
        for (var target : List.of(
                new Target(equivalentUri, "us-east-1", "bucket"),
                new Target(URI.create("https://example.test/"), "us-east-1", "bucket"),
                new Target(ENDPOINT, "US-EAST-1", "bucket"),
                new Target(ENDPOINT, "us-west-2", "bucket"),
                new Target(ENDPOINT, "us-east-1", "other-bucket"))) {
            assertState(Code.INVALID_BINDING, () -> state.requireConfiguration(target, BINDING));
        }
        for (var binding : List.of(new S3CheckpointHeads.Binding(OTHER, PUBLISHER, LINEAGE),
                new S3CheckpointHeads.Binding(NAMESPACE, OTHER, LINEAGE),
                new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, ZERO))) {
            assertState(Code.INVALID_BINDING, () -> state.requireConfiguration(TARGET, binding));
        }
        assertDoesNotThrow(() -> state.requireConfiguration(new Target(URI.create(ENDPOINT.toString()), "us-east-1", "bucket"), BINDING));
    }

    @Test void initialIntentRequiresRevisionOneRatherThanAnArbitraryUnobservedSuccessor() {
        assertState(Code.INVALID_TRANSITION, () -> fresh().prepare(SECOND_IMAGE));
        assertTrue(fresh().pending().isEmpty());
    }

    @Test void replacementRetainsTheExactAdjacentPredecessorAndPublicationRules() {
        var ready = ready(FIRST_TOKEN);
        var reference = ObjectCheckpointHeadCodec.reference(FIRST);
        for (var candidate : List.of(
                FIRST,
                head(NAMESPACE, PUBLISHER, LINEAGE, 4, 3, reference),
                head(NAMESPACE, PUBLISHER, LINEAGE, 4, 2, new ObjectReference(ZERO, 256)),
                head(NAMESPACE, PUBLISHER, LINEAGE, 3, 2, reference))) {
            var failure = assertState(Code.INVALID_TRANSITION,
                    () -> ready.prepare(ObjectCheckpointHeadCodec.encode(candidate)));
            assertTrue(failure.getCause() instanceof ObjectCheckpointHeadException);
        }
        assertTrue(ready.pending().isEmpty());
        assertEquals(FIRST_TOKEN, ready.expected().orElseThrow().token().value());
    }

    @Test void malformedCandidateKeepsItsNativeHeadCodecOrArgumentFailure() {
        var state = fresh();
        for (int size : new int[] {0, 255, 257}) {
            assertThrows(IllegalArgumentException.class, () -> state.prepare(new byte[size]));
        }
        byte[] corrupt = FIRST_IMAGE.clone();
        corrupt[64] ^= 1;
        var failure = assertThrows(ObjectCheckpointHeadException.class, () -> state.prepare(corrupt));
        assertEquals(ObjectCheckpointHeadException.Code.CORRUPT, failure.code());
        assertEquals(1, state.generation());
        assertTrue(state.pending().isEmpty());
        assertTrue(state.expected().isEmpty());
    }

    @Test void inputAndReturnedCopiesCannotMutateEitherExpectedOrPendingImage() {
        byte[] firstInput = FIRST_IMAGE.clone();
        var creating = fresh().prepare(firstInput);
        firstInput[0] ^= 1;
        byte[] firstOutput = creating.pending().orElseThrow().image();
        firstOutput[0] ^= 1;
        assertIntent(creating, Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
        byte[] observedInput = FIRST_IMAGE.clone();
        var observed = observation(observedInput, FIRST_TOKEN);
        var ready = creating.recordSelected(observed);
        observedInput[0] ^= 1;
        ready.expected().orElseThrow().image()[0] ^= 1;
        byte[] candidateInput = SECOND_IMAGE.clone();
        var replacing = ready.prepare(candidateInput);
        candidateInput[0] ^= 1;
        replacing.pending().orElseThrow().image()[0] ^= 1;
        assertArrayEquals(FIRST_IMAGE, replacing.expected().orElseThrow().image());
        assertIntent(replacing, Operation.REPLACE_IF_MATCH, FIRST_TOKEN, SECOND_IMAGE);
    }

    @Test void targetAcceptsExactTextBoundsAndOpaqueNoncatalogRegionIds() {
        var endpoint = URI.create("https://" + "a".repeat(2040));
        var target = new Target(endpoint, "r".repeat(128), "b".repeat(63));
        assertEquals(2048, target.endpoint().toString().length());
        assertEquals(128, target.regionId().length());
        assertEquals(63, target.bucket().length());
        var custom = new Target(ENDPOINT, "private-region:zone,revision\\2", "bucket");
        assertEquals("private-region:zone,revision\\2", fresh(custom, BINDING).target().regionId());
    }

    @Test void targetRejectsOverlongNonAsciiWhitespaceAndInvalidServiceScope() {
        assertThrows(IllegalArgumentException.class,
                () -> new Target(URI.create("https://" + "a".repeat(2041)), "region", "bucket"));
        assertThrows(IllegalArgumentException.class, () -> new Target(ENDPOINT, "r".repeat(129), "bucket"));
        assertThrows(IllegalArgumentException.class, () -> new Target(ENDPOINT, "region", "b".repeat(64)));
        for (String region : List.of("", "with space", "tab\tvalue", "line\nvalue", "\u007f", "\u0080", "\ud800")) {
            assertThrows(IllegalArgumentException.class, () -> new Target(ENDPOINT, region, "bucket"));
        }
        for (String endpoint : List.of("file:///tmp", "https://user@example.test", "https://example.test/path",
                "https://example.test/?query=1", "https://example.test/#fragment", "https://example.test/\u00e9")) {
            assertThrows(IllegalArgumentException.class, () -> new Target(URI.create(endpoint), "region", "bucket"));
        }
        for (String bucket : List.of("", "ab", "Bucket", "bucket.with.dots", "bucket--x-s3")) {
            assertThrows(IllegalArgumentException.class, () -> new Target(ENDPOINT, "region", bucket));
        }
    }

    @Test void maximumQuotedTokenIsRetainedWithoutTruncationOrUnquoting() {
        String token = "\"" + "x".repeat(S3HeadToken.MAX_BYTES - 2) + "\"";
        var pending = ready(token).prepare(SECOND_IMAGE);
        assertEquals(S3HeadToken.MAX_BYTES, pending.pending().orElseThrow().precondition().length());
        assertIntent(pending, Operation.REPLACE_IF_MATCH, token, SECOND_IMAGE);
    }

    @Test void asciiTargetPolicyDoesNotNarrowCanonicalUtf16LineageBindings() {
        var database = new DatabaseId("x".repeat(JournalCodec.MAX_ID_CODE_UNITS - 1) + "\ud800");
        byte[] header = JournalCodec.encodeHeader(database);
        var lineage = ObjectReference.forBytes(header).id();
        var prefix = new JournalPrefix(0, header.length, lineage.value());
        var manifest = new CheckpointManifest(database, 17, prefix, List.of());
        var binding = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, lineage);
        var head = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, FIRST.publicationId(), lineage, 1,
                ObjectReference.forBytes(ManifestCodec.encode(manifest)), prefix, null);
        var state = fresh(TARGET, binding).prepare(ObjectCheckpointHeadCodec.encode(head));
        assertEquals(65_556, header.length);
        assertEquals(lineage, state.binding().lineageId());
        assertEquals(head, state.pending().orElseThrow().head());
        assertEquals(256, state.pending().orElseThrow().image().length);
    }

    @Test void nullInputsKeepNormalDirectModelNullFailures() {
        assertThrows(NullPointerException.class, () -> S3PublicationState.fresh(null, BINDING));
        assertThrows(NullPointerException.class, () -> S3PublicationState.fresh(TARGET, null));
        assertThrows(NullPointerException.class, () -> new Target(null, "region", "bucket"));
        assertThrows(NullPointerException.class, () -> new Target(ENDPOINT, null, "bucket"));
        assertThrows(NullPointerException.class, () -> new Target(ENDPOINT, "region", null));
        assertThrows(NullPointerException.class, () -> fresh().prepare(null));
        assertThrows(NullPointerException.class, () -> fresh().prepare(FIRST_IMAGE).recordSelected(null));
        assertThrows(NullPointerException.class, () -> fresh().requireConfiguration(null, BINDING));
        assertThrows(NullPointerException.class, () -> fresh().requireConfiguration(TARGET, null));
    }

    @Test void restoredLocalGenerationIsPositiveAndIndependentOfHeadRevisionOrTransitionParity() {
        var prior = head(NAMESPACE, PUBLISHER, LINEAGE, 3, 19, new ObjectReference(ZERO, 256));
        var priorImage = ObjectCheckpointHeadCodec.encode(prior);
        var state = S3PublicationState.restore(2, TARGET, BINDING, observation(priorImage, FIRST_TOKEN), null);
        var pending = state.prepare(ObjectCheckpointHeadCodec.encode(next(prior, 4)));
        assertEquals(3, pending.generation());
        assertEquals(20, pending.pending().orElseThrow().head().revision());
        var initialPending = S3PublicationState.restore(42, TARGET, BINDING, null, FIRST_IMAGE);
        assertEquals(43, initialPending.recordSelected(observation(FIRST_IMAGE, FIRST_TOKEN)).generation());
        assertIntent(initialPending, Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
        assertTrue(S3PublicationState.restore(88, TARGET, BINDING, null, null).pending().isEmpty());
    }

    @Test void directRestoreRejectsNonpositiveGeneration() {
        for (long generation : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> S3PublicationState.restore(generation, TARGET, BINDING, null, null));
        }
    }

    @Test void generationOverflowCannotPrepareOrRecognizeAndLeavesOriginalMetadataIntact() {
        var ready = S3PublicationState.restore(Long.MAX_VALUE, TARGET, BINDING, null, null);
        assertState(Code.INVALID_TRANSITION, () -> ready.prepare(FIRST_IMAGE));
        assertTrue(ready.expected().isEmpty());
        assertTrue(ready.pending().isEmpty());
        var pending = S3PublicationState.restore(Long.MAX_VALUE, TARGET, BINDING, null, FIRST_IMAGE);
        assertState(Code.INVALID_TRANSITION, () -> pending.recordSelected(observation(FIRST_IMAGE, FIRST_TOKEN)));
        assertIntent(pending, Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
        var lastReady = S3PublicationState.restore(Long.MAX_VALUE - 1, TARGET, BINDING, null, null);
        assertEquals(Long.MAX_VALUE, lastReady.prepare(FIRST_IMAGE).generation());
    }

    @Test void restoredPendingStatePreservesItsOriginalPreconditionWithoutRebasing() {
        var expected = observation(FIRST_IMAGE, "\"original,opaque\\token\"");
        var pending = S3PublicationState.restore(14, TARGET, BINDING, expected, SECOND_IMAGE);
        assertEquals(14, pending.generation());
        assertIntent(pending, Operation.REPLACE_IF_MATCH, expected.token().value(), SECOND_IMAGE);
        assertState(Code.INVALID_TRANSITION, () -> pending.prepare(ObjectCheckpointHeadCodec.encode(next(FIRST, 9))));
        assertState(Code.INVALID_TRANSITION,
                () -> pending.recordSelected(observation(FIRST_IMAGE, "\"fresh-old-head-token\"")));
        assertObservation(expected, pending.expected().orElseThrow());
        assertIntent(pending, Operation.REPLACE_IF_MATCH, expected.token().value(), SECOND_IMAGE);
    }

    @Test void directRestoreStillValidatesExpectedScopeAndPendingAdjacency() {
        var foreignScope = S3HeadObservation.restore(URI.create("https://EXAMPLE.test"), "bucket", FIRST_IMAGE,
                new S3HeadToken(FIRST_TOKEN));
        assertState(Code.INVALID_BINDING,
                () -> S3PublicationState.restore(7, TARGET, BINDING, foreignScope, null));
        assertState(Code.INVALID_TRANSITION,
                () -> S3PublicationState.restore(7, TARGET, BINDING, observation(FIRST_IMAGE, FIRST_TOKEN), FIRST_IMAGE));
        assertState(Code.INVALID_TRANSITION,
                () -> S3PublicationState.restore(7, TARGET, BINDING, null, SECOND_IMAGE));
    }

    private static S3PublicationState fresh() { return fresh(TARGET, BINDING); }
    private static S3PublicationState fresh(Target target, S3CheckpointHeads.Binding binding) {
        return S3PublicationState.fresh(target, binding);
    }
    private static S3PublicationState ready(String token) {
        return fresh().prepare(FIRST_IMAGE).recordSelected(observation(FIRST_IMAGE, token));
    }
    private static S3HeadObservation observation(byte[] image, String token) {
        return S3HeadObservation.restore(ENDPOINT, "bucket", image, new S3HeadToken(token));
    }
    private static ObjectCheckpointHead head(UUID namespace, UUID publisher, ObjectId lineage,
                                             long publication, long revision, ObjectReference predecessor) {
        return new ObjectCheckpointHead(namespace, publisher, new UUID(0, publication), lineage, revision,
                MANIFEST, new JournalPrefix(0, 24, lineage.value()), predecessor);
    }
    private static ObjectCheckpointHead next(ObjectCheckpointHead previous, long publication) {
        return head(previous.namespaceId(), previous.publisherId(), previous.lineageId(), publication,
                previous.revision() + 1, ObjectCheckpointHeadCodec.reference(previous));
    }
    private static void assertIntent(S3PublicationState state, Operation operation, String precondition, byte[] image) {
        var intent = state.pending().orElseThrow();
        assertEquals(operation, intent.operation());
        assertEquals(precondition, intent.precondition());
        assertArrayEquals(image, intent.image());
        var decoded = ObjectCheckpointHeadCodec.decode(image);
        assertEquals(decoded.head(), intent.head());
        assertEquals(decoded.reference(), intent.reference());
    }
    private static void assertObservation(S3HeadObservation expected, S3HeadObservation actual) {
        assertEquals(expected.endpoint().toString(), actual.endpoint().toString());
        assertEquals(expected.bucket(), actual.bucket());
        assertEquals(expected.key(), actual.key());
        assertEquals(expected.head(), actual.head());
        assertEquals(expected.reference(), actual.reference());
        assertEquals(expected.token(), actual.token());
        assertArrayEquals(expected.image(), actual.image());
    }
    private static S3PublicationStateException assertState(Code code, Runnable operation) {
        var failure = assertThrows(S3PublicationStateException.class, operation::run);
        assertEquals(code, failure.code());
        return failure;
    }
}
