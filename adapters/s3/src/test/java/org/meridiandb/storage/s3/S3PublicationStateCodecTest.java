// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadException;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;

class S3PublicationStateCodecTest {
    private static final URI ENDPOINT = URI.create("http://localhost:9000");
    private static final String REGION = "us-east-1";
    private static final String BUCKET = "bucket";
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final ObjectId LINEAGE = new ObjectId(
            "2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c");
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, LINEAGE);
    private static final S3PublicationState.Target TARGET = new S3PublicationState.Target(ENDPOINT, REGION, BUCKET);
    private static final S3HeadToken TOKEN = new S3HeadToken("\"tag-1\"");
    private static final int ENDPOINT_LENGTH_OFFSET = 96;
    private static final int REGION_LENGTH_OFFSET = 121;
    private static final int BUCKET_LENGTH_OFFSET = 134;
    private static final int EXPECTED_HEAD_OFFSET = 144;
    private static final int TOKEN_LENGTH_OFFSET = 400;
    private static final int CREATE_OPERATION_OFFSET = 144;
    private static final int REPLACE_OPERATION_OFFSET = 411;

    // Independently assembled with Python struct big-endian fields and hashlib,
    // using the database "db" head vectors. No production state encoder produced
    // these expected bytes, integrity footers or full-image references.
    private static final byte[] HEAD_ONE = HexFormat.of().parseHex(
            "4d52444e484541440000000100000000000000000000000000000000000000010000000000000000"
            + "0000000000000002000000000000000000000000000000032b00012c047967ee602848bacc071ddf"
            + "fbe0ba95f58167c85d2122842a82ee8c0000000000000001bd068a065754e52824efaf91c44bc723"
            + "4985e9a93c024dcb90e6951152e6698200000088000000000000000000000000000000182b00012c"
            + "047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c000000000000000000000000"
            + "00000000000000000000000000000000000000000000000086222d7c582232f063d7d35504395c33"
            + "d65bb3acffbb678e4ddce9ed8e0dbd0d");
    private static final byte[] HEAD_TWO = HexFormat.of().parseHex(
            "4d52444e484541440000000100000001000000000000000000000000000000010000000000000000"
            + "0000000000000002000000000000000000000000000000042b00012c047967ee602848bacc071ddf"
            + "fbe0ba95f58167c85d2122842a82ee8c0000000000000002e40b7bf552979c50d92fa672bba19ed9"
            + "3f2716fca847410d5406157f59cfae8e000000c00000000000000001000000000000004efc0bccc3"
            + "589bcab2f55535bf1acf6199dab723cbdbd1526329f511413e9e0dc351d39de57bc442d01e3bd6ff"
            + "db69e42b47405f7e69a00de5e680b8db019fc66b000001002931f18fe9fa896d5d089a86bb40c5da"
            + "b626e48ce0fa010c411f16e7dce62ce3");
    private static final byte[] GOLDEN_FRESH = HexFormat.of().parseHex(
            "4d52444e5350535400000001000000b0000000000000000100000000000000000000000000000000"
            + "0000000000000001000000000000000000000000000000022b00012c047967ee602848bacc071ddf"
            + "fbe0ba95f58167c85d2122842a82ee8c00000015687474703a2f2f6c6f63616c686f73743a393030"
            + "300000000975732d656173742d31000000066275636b6574335e81471912b76cb0a304b4724bcb30"
            + "4143df99d145c016e12d8f545e930690");
    private static final byte[] GOLDEN_CREATE = HexFormat.of().parseHex(
            "4d52444e5350535400000001000001b4000000000000000200000002000000000000000000000000"
            + "0000000000000001000000000000000000000000000000022b00012c047967ee602848bacc071ddf"
            + "fbe0ba95f58167c85d2122842a82ee8c00000015687474703a2f2f6c6f63616c686f73743a393030"
            + "300000000975732d656173742d31000000066275636b6574000000014d52444e4845414400000001"
            + "00000000000000000000000000000000000000010000000000000000000000000000000200000000"
            + "0000000000000000000000032b00012c047967ee602848bacc071ddffbe0ba95f58167c85d212284"
            + "2a82ee8c0000000000000001bd068a065754e52824efaf91c44bc7234985e9a93c024dcb90e69511"
            + "52e6698200000088000000000000000000000000000000182b00012c047967ee602848bacc071ddf"
            + "fbe0ba95f58167c85d2122842a82ee8c000000000000000000000000000000000000000000000000"
            + "00000000000000000000000086222d7c582232f063d7d35504395c33d65bb3acffbb678e4ddce9ed"
            + "8e0dbd0d5266bdb0d2d6b76807e313256c1e616fc157444e32b88e53aef7c542abdaafe5");
    private static final byte[] GOLDEN_SELECTED = HexFormat.of().parseHex(
            "4d52444e5350535400000001000001bb000000000000000300000001000000000000000000000000"
            + "0000000000000001000000000000000000000000000000022b00012c047967ee602848bacc071ddf"
            + "fbe0ba95f58167c85d2122842a82ee8c00000015687474703a2f2f6c6f63616c686f73743a393030"
            + "300000000975732d656173742d31000000066275636b65744d52444e484541440000000100000000"
            + "00000000000000000000000000000001000000000000000000000000000000020000000000000000"
            + "00000000000000032b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c"
            + "0000000000000001bd068a065754e52824efaf91c44bc7234985e9a93c024dcb90e6951152e66982"
            + "00000088000000000000000000000000000000182b00012c047967ee602848bacc071ddffbe0ba95"
            + "f58167c85d2122842a82ee8c00000000000000000000000000000000000000000000000000000000"
            + "000000000000000086222d7c582232f063d7d35504395c33d65bb3acffbb678e4ddce9ed8e0dbd0d"
            + "00000007227461672d3122fbf0429cd22a2cb98dd8efdfaa98b255a187c52caa9fe468f31b986c55"
            + "d8512e");
    private static final byte[] GOLDEN_REPLACE = HexFormat.of().parseHex(
            "4d52444e5350535400000001000002bf000000000000000400000003000000000000000000000000"
            + "0000000000000001000000000000000000000000000000022b00012c047967ee602848bacc071ddf"
            + "fbe0ba95f58167c85d2122842a82ee8c00000015687474703a2f2f6c6f63616c686f73743a393030"
            + "300000000975732d656173742d31000000066275636b65744d52444e484541440000000100000000"
            + "00000000000000000000000000000001000000000000000000000000000000020000000000000000"
            + "00000000000000032b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c"
            + "0000000000000001bd068a065754e52824efaf91c44bc7234985e9a93c024dcb90e6951152e66982"
            + "00000088000000000000000000000000000000182b00012c047967ee602848bacc071ddffbe0ba95"
            + "f58167c85d2122842a82ee8c00000000000000000000000000000000000000000000000000000000"
            + "000000000000000086222d7c582232f063d7d35504395c33d65bb3acffbb678e4ddce9ed8e0dbd0d"
            + "00000007227461672d3122000000024d52444e484541440000000100000001000000000000000000"
            + "0000000000000100000000000000000000000000000002000000000000000000000000000000042b"
            + "00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c0000000000000002e4"
            + "0b7bf552979c50d92fa672bba19ed93f2716fca847410d5406157f59cfae8e000000c00000000000"
            + "000001000000000000004efc0bccc3589bcab2f55535bf1acf6199dab723cbdbd1526329f511413e"
            + "9e0dc351d39de57bc442d01e3bd6ffdb69e42b47405f7e69a00de5e680b8db019fc66b0000010029"
            + "31f18fe9fa896d5d089a86bb40c5dab626e48ce0fa010c411f16e7dce62ce3f8046fd0d3c4ce71f1"
            + "3f3431ae6bfb7cd410fb7e00d408d7e59010dd38d18725");

    @Test void matchesIndependentFourStateGoldenImages() {
        var fresh = S3PublicationState.fresh(TARGET, BINDING);
        var create = fresh.prepare(HEAD_ONE);
        var selected = create.recordSelected(observation(HEAD_ONE));
        var replace = selected.prepare(HEAD_TWO);
        assertArrayEquals(GOLDEN_FRESH, S3PublicationStateCodec.encode(fresh));
        assertArrayEquals(GOLDEN_CREATE, S3PublicationStateCodec.encode(create));
        assertArrayEquals(GOLDEN_SELECTED, S3PublicationStateCodec.encode(selected));
        assertArrayEquals(GOLDEN_REPLACE, S3PublicationStateCodec.encode(replace));
        assertArrayEquals(GOLDEN_FRESH, raw(1, 0, null, null, 0, null));
        assertArrayEquals(GOLDEN_REPLACE, raw(4, 3, HEAD_ONE, TOKEN.value(), 2, HEAD_TWO));
    }

    @Test void restoresAllFourDeclarationsWithExactOperationAndSavedPrecondition() {
        for (byte[] image : goldens()) {
            var state = S3PublicationStateCodec.decode(image).state();
            assertEquals(TARGET, state.target());
            assertEquals(BINDING, state.binding());
            assertArrayEquals(image, S3PublicationStateCodec.encode(state));
        }
        var fresh = S3PublicationStateCodec.decode(GOLDEN_FRESH).state();
        assertEquals(1, fresh.generation());
        assertTrue(fresh.expected().isEmpty());
        assertTrue(fresh.pending().isEmpty());
        var create = S3PublicationStateCodec.decode(GOLDEN_CREATE).state();
        assertEquals(2, create.generation());
        assertTrue(create.expected().isEmpty());
        assertEquals(S3PublicationState.Operation.CREATE_IF_ABSENT, create.pending().orElseThrow().operation());
        assertEquals("*", create.pending().orElseThrow().precondition());
        assertArrayEquals(HEAD_ONE, create.pending().orElseThrow().image());
        var selected = S3PublicationStateCodec.decode(GOLDEN_SELECTED).state();
        assertEquals(3, selected.generation());
        assertArrayEquals(HEAD_ONE, selected.expected().orElseThrow().image());
        assertEquals(TOKEN, selected.expected().orElseThrow().token());
        assertTrue(selected.pending().isEmpty());
        var replace = S3PublicationStateCodec.decode(GOLDEN_REPLACE).state();
        assertEquals(4, replace.generation());
        assertEquals(S3PublicationState.Operation.REPLACE_IF_MATCH, replace.pending().orElseThrow().operation());
        assertEquals(TOKEN.value(), replace.pending().orElseThrow().precondition());
        assertArrayEquals(HEAD_TWO, replace.pending().orElseThrow().image());
    }

    @Test void completeReferencesMatchIndependentHashesAndByteCounts() {
        var references = List.of(
                reference("927ea2d667f3d11e026573278755fa48cbcdaa4587ac0e4928c39aca2457b108", 176),
                reference("30ee3c225239e21967be336daed148cf7cb79d8aba6433dc140a1e534b2b4971", 436),
                reference("ab8b070fc9f9f596780fa2ff437479dedddd81c6590e9292a499f2e5754f5ff9", 443),
                reference("2c55fe6ae7b08cbfd273b01416cbaf568964530f9705d842527e33371411f040", 703));
        for (int index = 0; index < references.size(); index++) {
            var image = goldens().get(index);
            var expected = references.get(index);
            var decoded = S3PublicationStateCodec.decode(image, expected);
            assertEquals(expected, decoded.reference());
            assertEquals(expected, S3PublicationStateCodec.reference(decoded.state()));
        }
    }

    @Test void footerDigestAndFullImageIdentityCoverDifferentBytes() {
        for (byte[] image : goldens()) {
            int footer = image.length - 32;
            var footerHash = Arrays.copyOfRange(image, footer, image.length);
            assertArrayEquals(digest(Arrays.copyOf(image, footer)), footerHash);
            var full = S3PublicationStateCodec.decode(image).reference();
            assertNotEquals(full.id().value(), HexFormat.of().formatHex(footerHash));
            assertEquals(full.id().value(), HexFormat.of().formatHex(digest(image)));
        }
    }

    @Test void everyTruncatedPrefixIsIncompleteWithoutReturningAState() {
        for (byte[] image : goldens()) {
            for (int length = 0; length < image.length; length++) {
                assertCode(S3PublicationStateException.Code.INCOMPLETE, Arrays.copyOf(image, length));
            }
        }
    }

    @Test void everySingleByteMutationIsDetected() {
        for (byte[] original : goldens()) {
            for (int offset = 0; offset < original.length; offset++) {
                var changed = original.clone();
                changed[offset] ^= 1;
                assertThrows(S3PublicationStateException.class, () -> S3PublicationStateCodec.decode(changed),
                        "length " + original.length + ", mutated offset " + offset);
            }
        }
    }

    @Test void unsupportedVersionsAreRejectedWithValidFooters() {
        for (int version : new int[] {0, 2, -1, Integer.MAX_VALUE}) {
            assertCode(S3PublicationStateException.Code.UNSUPPORTED_VERSION, withInt(GOLDEN_FRESH, 8, version));
        }
    }

    @Test void magicUnknownFlagsAndReservedBitsFailClosedWithValidFooters() {
        var magic = GOLDEN_FRESH.clone();
        magic[0] ^= 1;
        assertCode(S3PublicationStateException.Code.CORRUPT, rehash(magic));
        for (int value : new int[] {4, 8, -1, Integer.MIN_VALUE}) {
            assertCode(S3PublicationStateException.Code.CORRUPT, withInt(GOLDEN_REPLACE, 24, value));
        }
        for (int value : new int[] {1, -1, Integer.MIN_VALUE}) {
            assertCode(S3PublicationStateException.Code.CORRUPT, withInt(GOLDEN_FRESH, 28, value));
        }
    }

    @Test void completeLengthRejectsMalformedDeclarationsAndTrailingBytes() {
        for (int value : new int[] {-1, 0, 139, GOLDEN_FRESH.length - 1}) {
            assertCode(S3PublicationStateException.Code.CORRUPT, withInt(GOLDEN_FRESH, 12, value));
        }
        assertCode(S3PublicationStateException.Code.INCOMPLETE,
                withInt(GOLDEN_FRESH, 12, GOLDEN_FRESH.length + 1));
        assertCode(S3PublicationStateException.Code.CORRUPT,
                Arrays.copyOf(GOLDEN_FRESH, GOLDEN_FRESH.length + 1));
        assertCode(S3PublicationStateException.Code.LIMIT_EXCEEDED,
                withInt(GOLDEN_FRESH, 12, 4097));
        assertCode(S3PublicationStateException.Code.LIMIT_EXCEEDED, new byte[4097]);
        assertEquals(4096, S3PublicationStateCodec.MAX_BYTES);
    }

    @Test void validFooterDoesNotPermitUnflaggedOrPaddedFields() {
        assertCode(S3PublicationStateException.Code.CORRUPT, raw(1, 0, HEAD_ONE, TOKEN.value(), 0, null));
        assertCode(S3PublicationStateException.Code.CORRUPT, raw(3, 1, HEAD_ONE, TOKEN.value(), 1, HEAD_ONE));
    }

    @Test void negativeTextLengthsAreCorrupt() {
        for (int offset : new int[] {ENDPOINT_LENGTH_OFFSET, REGION_LENGTH_OFFSET, BUCKET_LENGTH_OFFSET}) {
            for (int length : new int[] {-1, Integer.MIN_VALUE}) {
                assertCode(S3PublicationStateException.Code.CORRUPT, withInt(GOLDEN_FRESH, offset, length));
            }
        }
        assertCode(S3PublicationStateException.Code.CORRUPT, withInt(GOLDEN_SELECTED, TOKEN_LENGTH_OFFSET, -1));
    }

    @Test void perFieldCapsAreCheckedBeforeCopyingTheirDeclaredBodies() {
        assertCode(S3PublicationStateException.Code.LIMIT_EXCEEDED,
                withInt(GOLDEN_FRESH, ENDPOINT_LENGTH_OFFSET, 2049));
        assertCode(S3PublicationStateException.Code.LIMIT_EXCEEDED,
                withInt(GOLDEN_FRESH, REGION_LENGTH_OFFSET, 129));
        assertCode(S3PublicationStateException.Code.LIMIT_EXCEEDED,
                withInt(GOLDEN_FRESH, BUCKET_LENGTH_OFFSET, 64));
        assertCode(S3PublicationStateException.Code.LIMIT_EXCEEDED,
                withInt(GOLDEN_SELECTED, TOKEN_LENGTH_OFFSET, 1025));
    }

    @Test void incompleteInternalFieldsRemainIncompleteWithACompleteValidOuterFooter() {
        assertCode(S3PublicationStateException.Code.INCOMPLETE,
                withInt(GOLDEN_FRESH, ENDPOINT_LENGTH_OFFSET, 100));
        assertCode(S3PublicationStateException.Code.INCOMPLETE,
                raw(3, 1, Arrays.copyOf(HEAD_ONE, 255), null, 0, null));
        assertCode(S3PublicationStateException.Code.INCOMPLETE,
                raw(2, 2, null, null, 1, Arrays.copyOf(HEAD_ONE, 255)));
    }

    @Test void emptyTargetFieldsAndTokenAreSemanticFailures() {
        for (String[] target : new String[][] {{"", REGION, BUCKET},
                {ENDPOINT.toString(), "", BUCKET}, {ENDPOINT.toString(), REGION, ""}}) {
            assertCode(S3PublicationStateException.Code.INVALID_STATE,
                    raw(1, 0, target[0], target[1], target[2], null, null, 0, null));
        }
        assertCode(S3PublicationStateException.Code.INVALID_STATE, raw(3, 1, HEAD_ONE, "", 0, null));
    }

    @Test void stateTextRejectsWhitespaceControlDelAndNonAsciiWithoutReplacement() {
        for (int offset : new int[] {100, 125, 138, TOKEN_LENGTH_OFFSET + 5}) {
            for (int value : new int[] {0, 9, 32, 127, 128, 255}) {
                var image = offset >= TOKEN_LENGTH_OFFSET ? GOLDEN_SELECTED.clone() : GOLDEN_FRESH.clone();
                image[offset] = (byte) value;
                assertCode(S3PublicationStateException.Code.INVALID_STATE, rehash(image));
            }
        }
    }

    @Test void unsupportedEndpointOriginsAndBucketsRemainInvalidAfterDecoding() {
        for (String endpoint : List.of("file:///tmp", "relative", "http:opaque", "http://user@localhost",
                "http://localhost/path", "http://localhost?x=1", "http://localhost#fragment")) {
            assertCode(S3PublicationStateException.Code.INVALID_STATE,
                    raw(1, 0, endpoint, REGION, BUCKET, null, null, 0, null));
        }
        for (String bucket : List.of("aa", "Bucket", "with.dot", "bucket--x-s3", "-bucket", "bucket-")) {
            assertCode(S3PublicationStateException.Code.INVALID_STATE,
                    raw(1, 0, ENDPOINT.toString(), REGION, bucket, null, null, 0, null));
        }
    }

    @Test void generationIsPositiveAndIndependentOfExpectedPendingFlagParity() {
        for (long generation : new long[] {0, -1, Long.MIN_VALUE}) {
            assertCode(S3PublicationStateException.Code.INVALID_STATE, raw(generation, 0, null, null, 0, null));
        }
        for (long generation : new long[] {2, 17, Long.MAX_VALUE}) {
            var image = raw(generation, 0, null, null, 0, null);
            var state = S3PublicationStateCodec.decode(image).state();
            assertEquals(generation, state.generation());
            assertArrayEquals(image, S3PublicationStateCodec.encode(state));
        }
        var ready = raw(2, 1, HEAD_ONE, TOKEN.value(), 0, null);
        assertEquals(2, S3PublicationStateCodec.decode(ready).state().generation());
    }

    @Test void nilNamespaceAndPublisherAreInvalidBindingsInsideSavedState() {
        for (int offset : new int[] {32, 48}) {
            var image = GOLDEN_FRESH.clone();
            Arrays.fill(image, offset, offset + 16, (byte) 0);
            var failure = assertCode(S3PublicationStateException.Code.INVALID_STATE, rehash(image));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }

    @Test void corruptNestedExpectedHeadMapsToInvalidStateAndRetainsItsCause() {
        var head = HEAD_ONE.clone();
        head[head.length - 1] ^= 1;
        var failure = assertCode(S3PublicationStateException.Code.INVALID_STATE,
                raw(3, 1, head, TOKEN.value(), 0, null));
        assertEquals(ObjectCheckpointHeadException.Code.CORRUPT,
                assertInstanceOf(ObjectCheckpointHeadException.class, failure.getCause()).code());
    }

    @Test void corruptOrUnsupportedPendingHeadMapsToInvalidStateAndRetainsItsCause() {
        var badFooter = HEAD_ONE.clone();
        badFooter[255] ^= 1;
        var corrupt = assertCode(S3PublicationStateException.Code.INVALID_STATE,
                raw(2, 2, null, null, 1, badFooter));
        assertInstanceOf(ObjectCheckpointHeadException.class, corrupt.getCause());
        var unsupported = HEAD_ONE.clone();
        ByteBuffer.wrap(unsupported).putInt(8, 2);
        rehash(unsupported);
        var version = assertCode(S3PublicationStateException.Code.INVALID_STATE,
                raw(2, 2, null, null, 1, unsupported));
        assertEquals(ObjectCheckpointHeadException.Code.UNSUPPORTED_VERSION,
                assertInstanceOf(ObjectCheckpointHeadException.class, version.getCause()).code());
    }

    @Test void expectedAndPendingHeadsMustMatchTheOuterSavedBinding() {
        for (byte[] original : List.of(GOLDEN_CREATE, GOLDEN_SELECTED, GOLDEN_REPLACE)) {
            for (int offset : new int[] {47, 63, 64}) {
                var image = original.clone();
                image[offset] ^= 4;
                var failure = assertCode(S3PublicationStateException.Code.INVALID_STATE, rehash(image));
                assertEquals(S3PublicationStateException.Code.INVALID_BINDING,
                        assertInstanceOf(S3PublicationStateException.class, failure.getCause()).code());
            }
        }
    }

    @Test void illegalAndContradictoryOperationTagsAreNotNormalized() {
        for (int operation : new int[] {0, 2, 3, -1, Integer.MAX_VALUE}) {
            assertCode(S3PublicationStateException.Code.INVALID_STATE,
                    withInt(GOLDEN_CREATE, CREATE_OPERATION_OFFSET, operation));
        }
        for (int operation : new int[] {0, 1, 3, -1}) {
            assertCode(S3PublicationStateException.Code.INVALID_STATE,
                    withInt(GOLDEN_REPLACE, REPLACE_OPERATION_OFFSET, operation));
        }
    }

    @Test void savedPendingCandidatesMustPreserveGenesisAndAdjacentSuccessorRules() {
        var noGenesis = assertCode(S3PublicationStateException.Code.INVALID_STATE,
                raw(2, 2, null, null, 1, HEAD_TWO));
        assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION,
                assertInstanceOf(S3PublicationStateException.class, noGenesis.getCause()).code());
        var repeated = assertCode(S3PublicationStateException.Code.INVALID_STATE,
                raw(4, 3, HEAD_ONE, TOKEN.value(), 2, HEAD_ONE));
        assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION,
                assertInstanceOf(S3PublicationStateException.class, repeated.getCause()).code());
    }

    @Test void malformedSavedStrongTokensRemainSemanticFailures() {
        for (String token : List.of("plain", "W/\"weak\"", "*", "\"one\",\"two\"", "\"")) {
            var failure = assertCode(S3PublicationStateException.Code.INVALID_STATE, raw(3, 1, HEAD_ONE, token, 0, null));
            assertNotNull(failure.getCause());
        }
    }

    @Test void expectedReferenceChecksBothLengthAndFullIdentityWithoutReplacingValidation() {
        assertEquals(S3PublicationStateException.Code.REFERENCE_MISMATCH,
                assertThrows(S3PublicationStateException.class, () -> S3PublicationStateCodec.decode(GOLDEN_FRESH,
                        reference("0".repeat(64), GOLDEN_FRESH.length))).code());
        assertEquals(S3PublicationStateException.Code.REFERENCE_MISMATCH,
                assertThrows(S3PublicationStateException.class, () -> S3PublicationStateCodec.decode(GOLDEN_FRESH,
                        reference("0".repeat(64), GOLDEN_FRESH.length - 1))).code());
        var corrupt = GOLDEN_FRESH.clone();
        corrupt[corrupt.length - 1] ^= 1;
        var hashMatchingCorruption = ObjectReference.forBytes(corrupt);
        assertEquals(S3PublicationStateException.Code.CORRUPT,
                assertThrows(S3PublicationStateException.class,
                        () -> S3PublicationStateCodec.decode(corrupt, hashMatchingCorruption)).code());
    }

    @Test void allMaximumFieldsFitTheBoundedCanonicalImage() {
        String endpoint = "http://" + "a".repeat(2048 - 7);
        String token = "\"" + "x".repeat(1022) + "\"";
        var image = raw(4, 3, endpoint, "r".repeat(128), "b".repeat(63), HEAD_ONE, token, 2, HEAD_TWO);
        assertEquals(3923, image.length);
        var decoded = S3PublicationStateCodec.decode(image);
        assertEquals(endpoint, decoded.state().target().endpoint().toString());
        assertEquals(128, decoded.state().target().regionId().length());
        assertEquals(63, decoded.state().target().bucket().length());
        assertEquals(token, decoded.state().pending().orElseThrow().precondition());
        assertArrayEquals(image, S3PublicationStateCodec.encode(decoded.state()));
    }

    @Test void endpointSpellingAndOpaqueAsciiTokenSurviveWithoutCanonicalization() {
        String endpoint = "http://LOCALHOST:09000/";
        String token = "\"opaque,head\\token\"";
        var image = raw(3, 1, endpoint, "custom-region-1", BUCKET, HEAD_ONE, token, 0, null);
        var state = S3PublicationStateCodec.decode(image).state();
        assertEquals(endpoint, state.target().endpoint().toString());
        assertEquals(endpoint, state.expected().orElseThrow().endpoint().toString());
        assertEquals(token, state.expected().orElseThrow().token().value());
        assertArrayEquals(image, S3PublicationStateCodec.encode(state));
    }

    @Test void decodedStateOwnsInputAndEveryReturnedCandidateOrExpectedImage() {
        var image = GOLDEN_REPLACE.clone();
        var decoded = S3PublicationStateCodec.decode(image);
        assertArrayEquals(GOLDEN_REPLACE, image);
        Arrays.fill(image, (byte) 0);
        var candidate = decoded.state().pending().orElseThrow().image();
        var expected = decoded.state().expected().orElseThrow().image();
        Arrays.fill(candidate, (byte) 0);
        Arrays.fill(expected, (byte) 0);
        assertArrayEquals(HEAD_TWO, decoded.state().pending().orElseThrow().image());
        assertArrayEquals(HEAD_ONE, decoded.state().expected().orElseThrow().image());
        assertArrayEquals(GOLDEN_REPLACE, S3PublicationStateCodec.encode(decoded.state()));
        var output = S3PublicationStateCodec.encode(decoded.state());
        Arrays.fill(output, (byte) 0);
        assertArrayEquals(GOLDEN_REPLACE, S3PublicationStateCodec.encode(decoded.state()));
        assertEquals(ObjectReference.forBytes(GOLDEN_REPLACE), decoded.reference());
    }

    @Test void codecNullInputsAreRejected() {
        assertThrows(NullPointerException.class, () -> S3PublicationStateCodec.encode(null));
        assertThrows(NullPointerException.class, () -> S3PublicationStateCodec.reference(null));
        assertThrows(NullPointerException.class, () -> S3PublicationStateCodec.decode(null));
        assertThrows(NullPointerException.class,
                () -> S3PublicationStateCodec.decode(null, ObjectReference.forBytes(GOLDEN_FRESH)));
        assertThrows(NullPointerException.class, () -> S3PublicationStateCodec.decode(GOLDEN_FRESH, null));
    }

    private static List<byte[]> goldens() {
        return List.of(GOLDEN_FRESH, GOLDEN_CREATE, GOLDEN_SELECTED, GOLDEN_REPLACE);
    }

    private static S3HeadObservation observation(byte[] head) {
        return S3HeadObservation.restore(ENDPOINT, BUCKET, head, TOKEN);
    }

    private static ObjectReference reference(String hash, int bytes) {
        return new ObjectReference(new ObjectId(hash), bytes);
    }

    private static S3PublicationStateException assertCode(S3PublicationStateException.Code code, byte[] image) {
        var failure = assertThrows(S3PublicationStateException.class, () -> S3PublicationStateCodec.decode(image));
        assertEquals(code, failure.code(), failure.getMessage());
        return failure;
    }

    private static byte[] withInt(byte[] original, int offset, int value) {
        var image = original.clone();
        ByteBuffer.wrap(image).putInt(offset, value);
        return rehash(image);
    }

    private static byte[] rehash(byte[] image) {
        int footer = image.length - 32;
        System.arraycopy(digest(Arrays.copyOf(image, footer)), 0, image, footer, 32);
        return image;
    }

    private static byte[] digest(byte[] image) {
        try { return MessageDigest.getInstance("SHA-256").digest(image); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static byte[] raw(long generation, int flags, byte[] expected, String token, int operation, byte[] candidate) {
        return raw(generation, flags, ENDPOINT.toString(), REGION, BUCKET, expected, token, operation, candidate);
    }

    // Scalar writers follow the wire contract directly; no production state encoder is used.
    private static byte[] raw(long generation, int flags, String endpoint, String region, String bucket,
                              byte[] expected, String token, int operation, byte[] candidate) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeLong(0x4d52444e53505354L);
            output.writeInt(1);
            output.writeInt(0);
            output.writeLong(generation);
            output.writeInt(flags);
            output.writeInt(0);
            output.writeLong(0);
            output.writeLong(1);
            output.writeLong(0);
            output.writeLong(2);
            output.write(HexFormat.of().parseHex(LINEAGE.value()));
            text(output, endpoint);
            text(output, region);
            text(output, bucket);
            if (expected != null) {
                output.write(expected);
                if (token != null) { text(output, token); }
            }
            if (candidate != null) {
                output.writeInt(operation);
                output.write(candidate);
            }
        } catch (IOException impossible) { throw new AssertionError(impossible); }
        var image = Arrays.copyOf(bytes.toByteArray(), bytes.size() + 32);
        ByteBuffer.wrap(image).putInt(12, image.length);
        return rehash(image);
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] image = value.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(image.length);
        output.write(image);
    }
}
