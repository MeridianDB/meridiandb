// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.*;
import static org.meridiandb.storage.s3.CoordinationFixtures.*;
import static org.meridiandb.storage.s3.S3CoordinationStateTest.code;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3CoordinationException.Code;
import org.meridiandb.storage.s3.S3CoordinationState.Attempt;
import org.meridiandb.storage.s3.S3CoordinationState.Completion;

class S3CoordinationStateCodecTest {
    @ParameterizedTest
    @CsvSource({
        "fresh,208,af3343f60cf34a4b5b4906192331a5cbffbc33abb496ee0a66e909ad329479e6",
        "registered,608,209acd900969fcb2bb0bd85a5e844d022fb5d5f6f9f68cde5a7907a8231e7177",
        "pending,809,2aac563e45969bc647f9f6f601f6d3354dd9c94580cf7c8aec2d3290df5e04f7",
        "completed,817,c36d4dd5193686e29cc7fcffbd464c6646c5683caea9e7ee8722b6f25e173e93",
        "replacement,1466,152f58c8bb259bba0cc1efa27e97eb8c627d371a730ec2b1e18df7d5b2f46a49"
    })
    void independentlyAssembledLiteralImagesBindExactBytesLengthsAndFullHashes(String name, int length, String hash) {
        var state = switch (name) {
            case "fresh" -> fresh(); case "registered" -> registered(); case "pending" -> pending();
            case "completed" -> completed(); case "replacement" -> replacement();
            default -> throw new AssertionError(name);
        };
        byte[] expected = vector(name);
        assertEquals(length, expected.length);
        var reference = new ObjectReference(new ObjectId(hash), length);
        assertEquals(reference, ObjectReference.forBytes(expected));
        assertArrayEquals(expected, S3CoordinationStateCodec.encode(state));
        var decoded = S3CoordinationStateCodec.decode(expected, reference);
        assertEquals(reference, decoded.reference());
        assertEquals(reference, S3CoordinationStateCodec.reference(state));
        assertArrayEquals(expected, S3CoordinationStateCodec.encode(decoded.state()));
        expected[20] ^= 1;
        assertEquals(state.generation(), decoded.state().generation());
    }

    @ParameterizedTest @ValueSource(strings = {"fresh", "registered", "pending", "completed", "replacement"})
    void everyTruncationAndEverySingleByteMutationFailsWithoutReturningPartialState(String name) {
        byte[] image = vector(name);
        for (int length = 0; length < image.length; length++) {
            byte[] truncated = Arrays.copyOf(image, length);
            assertThrows(S3CoordinationException.class, () -> S3CoordinationStateCodec.decode(truncated), name + " prefix " + length);
        }
        for (int offset = 0; offset < image.length; offset++) {
            byte[] changed = image.clone(); changed[offset] ^= 1;
            assertThrows(S3CoordinationException.class, () -> S3CoordinationStateCodec.decode(changed), name + " byte " + offset);
        }
    }

    @Test void independentExpectedReferenceIsSeparateFromTheIntegrityFooter() {
        byte[] image = vector("pending");
        code(Code.REFERENCE_MISMATCH, () -> S3CoordinationStateCodec.decode(image, new ObjectReference(new ObjectId("f".repeat(64)), image.length)));
        code(Code.REFERENCE_MISMATCH, () -> S3CoordinationStateCodec.decode(image, new ObjectReference(ObjectReference.forBytes(image).id(), image.length + 1)));
        assertNotEquals(HexFormat.of().formatHex(Arrays.copyOfRange(image, image.length - 32, image.length)), ObjectReference.forBytes(image).id().value());
    }

    @Test void initialHeadSelectionRoundTripsItsDistinctCreateOnlyRole() {
        var mutation = new S3CoordinationState.Mutation(S3CoordinationState.Operation.CREATE_HEAD,
                S3CheckpointHeads.headKey(BINDING.namespaceId()), ONE.reference(), null, null);
        var state = registered().begin(NONCE, mutation);
        var decoded = S3CoordinationStateCodec.decode(S3CoordinationStateCodec.encode(state)).state();
        assertEquals(state.pending(), decoded.pending());
        var complete = decoded.settle(decoded.pending().orElseThrow(), 200);
        assertEquals(mutation, S3CoordinationStateCodec.decode(S3CoordinationStateCodec.encode(complete))
                .state().lastCompletion().orElseThrow().attempt().mutation());
    }

    @Test void retirementPreservesTheExactHistoricalCreateCompletion() {
        var before = completed();
        var retired = before.retire(ONE.reference());
        var decoded = S3CoordinationStateCodec.decode(S3CoordinationStateCodec.encode(retired)).state();
        assertTrue(decoded.roots().getFirst().retired());
        assertEquals(before.lastCompletion(), decoded.lastCompletion());
        assertEquals(1, decoded.completedOrdinal());
        code(Code.INVALID_STATE, () -> decoded.begin(NONCE, create(ONE)));
    }

    @Test void conditionalDeleteRoundTripsRetirementOriginalTagAndBothTerminalDeclarations() {
        var retired = registered().retire(ONE.reference());
        var mutation = new S3CoordinationState.Mutation(S3CoordinationState.Operation.DELETE_IMMUTABLE,
                create(ONE).key(), ONE.reference(), null, TOKEN);
        var state = retired.begin(NONCE, mutation);
        var decoded = S3CoordinationStateCodec.decode(S3CoordinationStateCodec.encode(state)).state();
        assertEquals(state.pending(), decoded.pending());
        assertTrue(decoded.roots().getFirst().retired());
        for (int status : new int[] {204, 412}) {
            var complete = decoded.settle(decoded.pending().orElseThrow(), status);
            var reopened = S3CoordinationStateCodec.decode(S3CoordinationStateCodec.encode(complete)).state();
            assertEquals(new Completion(state.pending().orElseThrow(), status), reopened.lastCompletion().orElseThrow());
            assertTrue(reopened.pending().isEmpty());
            assertTrue(reopened.roots().getFirst().retired());
        }
    }

    @ParameterizedTest
    @CsvSource({"8,2,UNSUPPORTED_VERSION", "12,1048577,LIMIT_EXCEEDED", "12,171,CORRUPT", "112,4,CORRUPT",
        "116,-1,CORRUPT", "116,33,LIMIT_EXCEEDED", "120,1,CORRUPT", "128,-1,CORRUPT", "128,0,CORRUPT",
        "128,2049,LIMIT_EXCEEDED", "153,129,LIMIT_EXCEEDED", "166,64,LIMIT_EXCEEDED"})
    void checksumValidHeaderAndTextBoundViolationsAreRejectedBeforeNestedWork(int offset, int value, Code expected) {
        byte[] image = vector("fresh"); ByteBuffer.wrap(image).putInt(offset, value); resign(image);
        code(expected, () -> S3CoordinationStateCodec.decode(image));
    }

    @ParameterizedTest
    @CsvSource({"176,4,CORRUPT", "176,3,INVALID_STATE", "436,-1,CORRUPT", "436,16385,LIMIT_EXCEEDED",
        "436,1000,INCOMPLETE", "452,21,INVALID_STATE", "452,2147483646,INVALID_STATE",
        "536,-1,INVALID_STATE", "536,4097,LIMIT_EXCEEDED"})
    void rootAndNestedReferenceBoundsAreChargedBeforeNestedDecoding(int offset, int value, Code expected) {
        byte[] image = vector("registered"); ByteBuffer.wrap(image).putInt(offset, value); resign(image);
        code(expected, () -> S3CoordinationStateCodec.decode(image));
    }

    @ParameterizedTest
    @CsvSource({"616,0,CORRUPT", "616,5,CORRUPT", "620,1,CORRUPT", "624,-1,INVALID_STATE",
        "660,129,LIMIT_EXCEEDED", "660,-1,CORRUPT", "773,1025,LIMIT_EXCEEDED"})
    void attemptFieldsRefuseUnsupportedOperationsAndUnboundedAllocations(int offset, int value, Code expected) {
        byte[] image = vector("pending"); ByteBuffer.wrap(image).putInt(offset, value); resign(image);
        code(expected, () -> S3CoordinationStateCodec.decode(image));
    }

    @ParameterizedTest @ValueSource(strings = {"zero-generation", "nil-placement", "nil-namespace", "nil-publisher", "negative-watermark",
        "watermark-without-completion", "non-ascii", "foreign-attempt", "gap", "nil-nonce", "exhausted-pending-generation", "bad-status", "completion-reserved"})
    void checksumValidSemanticDamageStillFailsClosed(String kind) {
        byte[] image = vector(kind.startsWith("completion") || kind.equals("bad-status") ? "completed" : "pending");
        var bytes = ByteBuffer.wrap(image);
        switch (kind) {
            case "zero-generation" -> bytes.putLong(16, 0);
            case "nil-placement" -> { bytes.putLong(24, 0); bytes.putLong(32, 0); }
            case "nil-namespace" -> { bytes.putLong(40, 0); bytes.putLong(48, 0); }
            case "nil-publisher" -> { bytes.putLong(56, 0); bytes.putLong(64, 0); }
            case "negative-watermark" -> bytes.putLong(104, -1);
            case "watermark-without-completion" -> bytes.putLong(104, 1);
            case "non-ascii" -> image[132] = (byte) 0x80;
            case "foreign-attempt" -> bytes.putLong(584, 99);
            case "gap" -> bytes.putLong(592, 2);
            case "nil-nonce" -> { bytes.putLong(600, 0); bytes.putLong(608, 0); }
            case "exhausted-pending-generation" -> bytes.putLong(16, Long.MAX_VALUE);
            case "bad-status" -> bytes.putInt(777, 500);
            case "completion-reserved" -> bytes.putInt(781, 1);
            default -> throw new AssertionError(kind);
        }
        resign(image);
        assertThrows(S3CoordinationException.class, () -> S3CoordinationStateCodec.decode(image));
    }

    @Test void trailingFieldsAndNoncanonicalRootOrderingAreNotSilentlyNormalized() {
        byte[] image = vector("fresh"), extended = Arrays.copyOf(image, image.length + 1);
        code(Code.CORRUPT, () -> S3CoordinationStateCodec.decode(extended));
        ByteBuffer.wrap(extended).putInt(12, extended.length); resign(extended);
        code(Code.CORRUPT, () -> S3CoordinationStateCodec.decode(extended));
        byte[] two = S3CoordinationStateCodec.encode(registered().register(TWO));
        int firstLength = 264 + ByteBuffer.wrap(two).getInt(436);
        int secondLength = 264 + ByteBuffer.wrap(two).getInt(176 + firstLength + 260);
        byte[] first = Arrays.copyOfRange(two, 176, 176 + firstLength);
        System.arraycopy(two, 176 + firstLength, two, 176, secondLength);
        System.arraycopy(first, 0, two, 176 + secondLength, firstLength); resign(two);
        code(Code.INVALID_STATE, () -> S3CoordinationStateCodec.decode(two));
    }

    @Test void restoredDeclarationsRequireContiguousAttemptsAndMatchingCompletionWatermark() {
        var original = pending().pending().orElseThrow();
        code(Code.INVALID_STATE, () -> S3CoordinationState.restore(2, TARGET, BINDING, PLACEMENT, registered().roots(), 1, null, null));
        code(Code.INVALID_STATE, () -> S3CoordinationState.restore(2, TARGET, BINDING, PLACEMENT, registered().roots(), 0, new Completion(original, 200), null));
        code(Code.INVALID_STATE, () -> S3CoordinationState.restore(3, TARGET, BINDING, PLACEMENT, registered().roots(), 0, null,
                new Attempt(PLACEMENT, 2, NONCE, create(ONE))));
        code(Code.INVALID_BINDING, () -> S3CoordinationState.restore(3, TARGET, BINDING, PLACEMENT, registered().roots(), 0, null,
                new Attempt(new UUID(0, 99), 1, NONCE, create(ONE))));
        code(Code.INVALID_STATE, () -> S3CoordinationState.restore(Long.MAX_VALUE, TARGET, BINDING, PLACEMENT, registered().roots(), 0, null, original));
        code(Code.LIMIT_EXCEEDED, () -> S3CoordinationStateCodec.decode(new byte[S3CoordinationStateCodec.MAX_BYTES + 1]));
    }

    @Test void theMaximumManifestBoundAndCompletionGrowthFitBelowTheImageCap() {
        // Independent size arithmetic: a 16,272-byte journal header plus 112 fixed manifest bytes.
        var databaseId = new org.meridiandb.temporal.DatabaseId("u".repeat(8126));
        byte[] header = org.meridiandb.storage.journal.JournalCodec.encodeHeader(databaseId);
        assertEquals(16272, header.length);
        var prefix = new org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix(0, header.length, ObjectReference.forBytes(header).id().value());
        var binding = new S3CheckpointHeads.Binding(BINDING.namespaceId(), BINDING.publisherId(), ObjectReference.forBytes(header).id());
        var state = S3CoordinationState.fresh(TARGET, binding, PLACEMENT);
        S3CoordinationState.Root last = null;
        for (int i = 1; i <= 32; i++) {
            byte[] manifest = org.meridiandb.storage.manifest.ManifestCodec.encode(
                    new org.meridiandb.storage.manifest.CheckpointManifest(databaseId, i, prefix, List.of()));
            assertEquals(16384, manifest.length);
            var head = new org.meridiandb.storage.manifest.ObjectCheckpointHead(binding.namespaceId(), binding.publisherId(), new UUID(1, i), binding.lineageId(),
                    1, ObjectReference.forBytes(manifest), prefix, null);
            last = new S3CoordinationState.Root(org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec.encode(head), manifest);
            state = state.register(last);
        }
        var request = new S3CoordinationState.Mutation(S3CoordinationState.Operation.CREATE_IMMUTABLE,
                S3PublicationPreparer.intentKey(binding.namespaceId(), last.reference().id()), last.reference(), null, null);
        state = state.begin(NONCE, request); state = state.settle(state.pending().orElseThrow(), 200); state = state.begin(NONCE, request);
        byte[] image = S3CoordinationStateCodec.encode(state);
        assertEquals(533354, image.length);
        assertTrue(image.length < S3CoordinationStateCodec.MAX_BYTES);
        assertArrayEquals(image, S3CoordinationStateCodec.encode(S3CoordinationStateCodec.decode(image).state()));
    }

    private static void resign(byte[] image) {
        try {
            var digest = MessageDigest.getInstance("SHA-256"); digest.update(image, 0, image.length - 32);
            System.arraycopy(digest.digest(), 0, image, image.length - 32, 32);
        } catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
}
