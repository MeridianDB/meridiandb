// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadException.Code;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.segment.SegmentCodec;

class ObjectCheckpointHeadCodecTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final UUID FIRST_PUBLICATION = new UUID(0, 3);
    private static final UUID NEXT_PUBLICATION = new UUID(0, 4);
    private static final ObjectId LINEAGE = new ObjectId(
            "2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c");
    private static final ObjectId ZERO_ID = new ObjectId("0".repeat(64));
    private static final ObjectReference EMPTY_MANIFEST = new ObjectReference(new ObjectId(
            "bd068a065754e52824efaf91c44bc7234985e9a93c024dcb90e6951152e66982"), 136);
    private static final ObjectReference NONEMPTY_MANIFEST = new ObjectReference(new ObjectId(
            "e40b7bf552979c50d92fa672bba19ed93f2716fca847410d5406157f59cfae8e"), 192);
    private static final JournalPrefix EMPTY_PREFIX = new JournalPrefix(0, 24, LINEAGE.value());
    private static final JournalPrefix NONEMPTY_PREFIX = new JournalPrefix(1, 78,
            "fc0bccc3589bcab2f55535bf1acf6199dab723cbdbd1526329f511413e9e0dc3");
    private static final ObjectReference GENESIS_REFERENCE = new ObjectReference(new ObjectId(
            "51d39de57bc442d01e3bd6ffdb69e42b47405f7e69a00de5e680b8db019fc66b"), 256);
    private static final ObjectReference SUCCESSOR_REFERENCE = new ObjectReference(new ObjectId(
            "105b3638d6ca7fbda71139d696bba5157e48cb38543cdc91e6b368c78f9891e8"), 256);
    private static final ObjectCheckpointHead GENESIS = new ObjectCheckpointHead(NAMESPACE, PUBLISHER,
            FIRST_PUBLICATION, LINEAGE, 1, EMPTY_MANIFEST, EMPTY_PREFIX, null);
    private static final ObjectCheckpointHead SUCCESSOR = new ObjectCheckpointHead(NAMESPACE, PUBLISHER,
            NEXT_PUBLICATION, LINEAGE, 2, NONEMPTY_MANIFEST, NONEMPTY_PREFIX, GENESIS_REFERENCE);
    private static final long MAX_SEQUENCE = (long) ManifestCodec.MAX_SEGMENTS * SegmentCodec.MAX_RECORDS;

    // Independently assembled using Python struct's big-endian fields and hashlib SHA-256,
    // without calling the production head encoder. The manifest/lineage values are existing
    // independent manifest golden vectors for database "db"; the first remote revision need
    // not equal the referenced manifest's physical generation.
    private static final byte[] GOLDEN_GENESIS = HexFormat.of().parseHex(
            "4d52444e48454144000000010000000000000000000000000000000000000001"
            + "0000000000000000000000000000000200000000000000000000000000000003"
            + "2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c"
            + "0000000000000001bd068a065754e52824efaf91c44bc7234985e9a93c024dcb"
            + "90e6951152e6698200000088000000000000000000000000000000182b00012c"
            + "047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c00000000"
            + "0000000000000000000000000000000000000000000000000000000000000000"
            + "86222d7c582232f063d7d35504395c33d65bb3acffbb678e4ddce9ed8e0dbd0d");
    private static final byte[] GOLDEN_SUCCESSOR = HexFormat.of().parseHex(
            "4d52444e48454144000000010000000100000000000000000000000000000001"
            + "0000000000000000000000000000000200000000000000000000000000000004"
            + "2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c"
            + "0000000000000002e40b7bf552979c50d92fa672bba19ed93f2716fca847410d"
            + "5406157f59cfae8e000000c00000000000000001000000000000004efc0bccc3"
            + "589bcab2f55535bf1acf6199dab723cbdbd1526329f511413e9e0dc351d39de5"
            + "7bc442d01e3bd6ffdb69e42b47405f7e69a00de5e680b8db019fc66b00000100"
            + "2931f18fe9fa896d5d089a86bb40c5dab626e48ce0fa010c411f16e7dce62ce3");

    @Test void matchesIndependentGenesisBytesAndCompleteObjectReference() {
        assertEquals(256, ObjectCheckpointHeadCodec.ENCODED_BYTES);
        assertArrayEquals(GOLDEN_GENESIS, ObjectCheckpointHeadCodec.encode(GENESIS));
        var decoded = ObjectCheckpointHeadCodec.decode(GOLDEN_GENESIS);
        assertEquals(GENESIS, decoded.head());
        assertEquals(GENESIS_REFERENCE, decoded.reference());
        assertEquals(GENESIS_REFERENCE, ObjectCheckpointHeadCodec.reference(GENESIS));
        assertEquals(decoded, ObjectCheckpointHeadCodec.decode(GOLDEN_GENESIS, GENESIS_REFERENCE));
        assertNull(decoded.head().predecessor());
    }

    @Test void matchesIndependentSuccessorAndExactPredecessorReference() {
        assertArrayEquals(GOLDEN_SUCCESSOR, ObjectCheckpointHeadCodec.encode(SUCCESSOR));
        var decoded = ObjectCheckpointHeadCodec.decode(GOLDEN_SUCCESSOR, SUCCESSOR_REFERENCE);
        assertEquals(SUCCESSOR, decoded.head());
        assertEquals(SUCCESSOR_REFERENCE, decoded.reference());
        assertEquals(GENESIS_REFERENCE, decoded.head().predecessor());
        assertEquals(SUCCESSOR_REFERENCE, ObjectCheckpointHeadCodec.reference(SUCCESSOR));
    }

    @Test void integrityFooterIsDistinctFromWholeHeadIdentity() {
        assertEquals("86222d7c582232f063d7d35504395c33d65bb3acffbb678e4ddce9ed8e0dbd0d",
                HexFormat.of().formatHex(Arrays.copyOfRange(GOLDEN_GENESIS, 224, 256)));
        assertEquals("2931f18fe9fa896d5d089a86bb40c5dab626e48ce0fa010c411f16e7dce62ce3",
                HexFormat.of().formatHex(Arrays.copyOfRange(GOLDEN_SUCCESSOR, 224, 256)));
        assertNotEquals(GENESIS_REFERENCE.id().value(), HexFormat.of().formatHex(digest(GOLDEN_GENESIS, 224)));
        assertNotEquals(SUCCESSOR_REFERENCE.id().value(), HexFormat.of().formatHex(digest(GOLDEN_SUCCESSOR, 224)));
        assertEquals(GENESIS_REFERENCE.id().value(), HexFormat.of().formatHex(digest(GOLDEN_GENESIS, 256)));
        assertEquals(SUCCESSOR_REFERENCE.id().value(), HexFormat.of().formatHex(digest(GOLDEN_SUCCESSOR, 256)));
    }

    @Test void firstRemoteRevisionMayReferenceAnAlreadyNonemptyJournal() {
        var first = head(1, NONEMPTY_MANIFEST, NONEMPTY_PREFIX, null);
        assertEquals(first, ObjectCheckpointHeadCodec.decode(ObjectCheckpointHeadCodec.encode(first)).head());
    }

    @Test void decoderRetainsNoInputArrayAndEncodingReturnsFreshBytes() {
        byte[] supplied = GOLDEN_SUCCESSOR.clone();
        var decoded = ObjectCheckpointHeadCodec.decode(supplied);
        Arrays.fill(supplied, (byte) 0);
        assertEquals(SUCCESSOR, decoded.head());
        assertEquals(SUCCESSOR_REFERENCE, decoded.reference());
        byte[] encoded = ObjectCheckpointHeadCodec.encode(decoded.head());
        encoded[16] ^= 1;
        assertArrayEquals(GOLDEN_SUCCESSOR, ObjectCheckpointHeadCodec.encode(decoded.head()));
        assertEquals(SUCCESSOR_REFERENCE, ObjectCheckpointHeadCodec.reference(decoded.head()));
    }

    @Test void everyTruncatedLengthIsIncompleteWithoutReturningAPartialValue() {
        for (int length = 0; length < 256; length++) {
            byte[] truncated = Arrays.copyOf(GOLDEN_SUCCESSOR, length);
            assertCode(Code.INCOMPLETE, truncated);
        }
    }

    @Test void trailingAndOversizedImagesExceedTheFixedFormatLimit() {
        assertCode(Code.LIMIT_EXCEEDED, Arrays.copyOf(GOLDEN_GENESIS, 257));
        assertCode(Code.LIMIT_EXCEEDED, Arrays.copyOf(GOLDEN_GENESIS, 4096));
    }

    @Test void nullCodecArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadCodec.encode(null));
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadCodec.reference(null));
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadCodec.decode(null));
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadCodec.decode(null, GENESIS_REFERENCE));
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadCodec.decode(GOLDEN_GENESIS, null));
    }

    @Test void alteredMagicIsCorruptEvenWithARecomputedFooter() {
        byte[] image = GOLDEN_GENESIS.clone();
        image[0] ^= 1;
        assertCode(Code.CORRUPT, rehash(image));
    }

    @Test void unsupportedVersionsAreNeverAcceptedAsVersionOne() {
        for (int version : new int[] {0, 2, -1, Integer.MAX_VALUE}) {
            assertCode(Code.UNSUPPORTED_VERSION, withInt(GOLDEN_GENESIS, 8, version));
        }
    }

    @Test void unknownFlagsAreRejectedInsteadOfDiscarded() {
        for (int flags : new int[] {2, 3, -1, Integer.MIN_VALUE}) {
            assertCode(Code.CORRUPT, withInt(GOLDEN_SUCCESSOR, 12, flags));
        }
    }

    @Test void absentPredecessorRequiresCanonicalZeroHashAndLength() {
        byte[] image = GOLDEN_GENESIS.clone();
        image[188] = 1;
        assertCode(Code.CORRUPT, rehash(image));
        image = GOLDEN_GENESIS.clone();
        image[219] = 1;
        assertCode(Code.CORRUPT, rehash(image));
        assertCode(Code.CORRUPT, withInt(GOLDEN_GENESIS, 220, 256));
    }

    @Test void presentPredecessorRequiresTheExactVersionOneLength() {
        for (int length : new int[] {0, 1, 255, 257, Integer.MAX_VALUE, -1}) {
            assertCode(Code.INVALID_HEAD, withInt(GOLDEN_SUCCESSOR, 220, length));
        }
    }

    @Test void payloadAndFooterCorruptionCannotPassWithoutARecomputedDigest() {
        for (int offset = 16; offset < 256; offset++) {
            byte[] image = GOLDEN_SUCCESSOR.clone();
            image[offset] ^= 1;
            assertCode(Code.CORRUPT, image);
        }
    }

    @Test void expectedIdentityIsIndependentOfAValidIntegrityFooter() {
        var failure = assertThrows(ObjectCheckpointHeadException.class,
                () -> ObjectCheckpointHeadCodec.decode(GOLDEN_GENESIS, new ObjectReference(ZERO_ID, 256)));
        assertEquals(Code.REFERENCE_MISMATCH, failure.code());
    }

    @Test void expectedReferenceLengthMustAlsoMatchTheCompleteImage() {
        for (int length : new int[] {0, 255, 257}) {
            var expected = new ObjectReference(GENESIS_REFERENCE.id(), length);
            var failure = assertThrows(ObjectCheckpointHeadException.class,
                    () -> ObjectCheckpointHeadCodec.decode(GOLDEN_GENESIS, expected));
            assertEquals(Code.REFERENCE_MISMATCH, failure.code());
        }
    }

    @Test void invalidFormatIsRejectedBeforeCheckingTheExpectedReference() {
        byte[] damaged = GOLDEN_GENESIS.clone();
        damaged[100] ^= 1;
        var wrong = new ObjectReference(ZERO_ID, 256);
        assertEquals(Code.CORRUPT, assertThrows(ObjectCheckpointHeadException.class,
                () -> ObjectCheckpointHeadCodec.decode(damaged, wrong)).code());
    }

    @Test void nilIdentitiesHaveNoValidWireRepresentation() {
        for (int offset : new int[] {16, 32, 48}) {
            byte[] image = GOLDEN_GENESIS.clone();
            Arrays.fill(image, offset, offset + 16, (byte) 0);
            assertCode(Code.INVALID_HEAD, rehash(image));
        }
    }

    @Test void constructorsRequireIdentityAndMetadataValues() {
        assertThrows(NullPointerException.class, () -> new ObjectCheckpointHead(null, PUBLISHER,
                FIRST_PUBLICATION, LINEAGE, 1, EMPTY_MANIFEST, EMPTY_PREFIX, null));
        assertThrows(NullPointerException.class, () -> new ObjectCheckpointHead(NAMESPACE, null,
                FIRST_PUBLICATION, LINEAGE, 1, EMPTY_MANIFEST, EMPTY_PREFIX, null));
        assertThrows(NullPointerException.class, () -> new ObjectCheckpointHead(NAMESPACE, PUBLISHER,
                null, LINEAGE, 1, EMPTY_MANIFEST, EMPTY_PREFIX, null));
        assertThrows(NullPointerException.class, () -> new ObjectCheckpointHead(NAMESPACE, PUBLISHER,
                FIRST_PUBLICATION, null, 1, EMPTY_MANIFEST, EMPTY_PREFIX, null));
        assertThrows(NullPointerException.class, () -> head(1, null, EMPTY_PREFIX, null));
        assertThrows(NullPointerException.class, () -> head(1, EMPTY_MANIFEST, null, null));
    }

    @Test void constructorsRejectNilNamespacePublisherAndPublicationIds() {
        var nil = new UUID(0, 0);
        assertThrows(IllegalArgumentException.class, () -> new ObjectCheckpointHead(nil, PUBLISHER,
                FIRST_PUBLICATION, LINEAGE, 1, EMPTY_MANIFEST, EMPTY_PREFIX, null));
        assertThrows(IllegalArgumentException.class, () -> new ObjectCheckpointHead(NAMESPACE, nil,
                FIRST_PUBLICATION, LINEAGE, 1, EMPTY_MANIFEST, EMPTY_PREFIX, null));
        assertThrows(IllegalArgumentException.class, () -> new ObjectCheckpointHead(NAMESPACE, PUBLISHER,
                nil, LINEAGE, 1, EMPTY_MANIFEST, EMPTY_PREFIX, null));
    }

    @Test void constructorsRejectNonpositiveRevisions() {
        for (long revision : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> head(revision, EMPTY_MANIFEST, EMPTY_PREFIX, null));
        }
    }

    @Test void predecessorPresenceDistinguishesFirstAndLaterRevisions() {
        assertThrows(IllegalArgumentException.class, () -> head(1, EMPTY_MANIFEST, EMPTY_PREFIX, GENESIS_REFERENCE));
        assertThrows(IllegalArgumentException.class, () -> head(2, EMPTY_MANIFEST, EMPTY_PREFIX, null));
        assertCode(Code.INVALID_HEAD, withLong(GOLDEN_SUCCESSOR, 96, 1));
        assertCode(Code.INVALID_HEAD, withLong(GOLDEN_GENESIS, 96, 2));
    }

    @Test void constructorsRejectNoncanonicalPredecessorLength() {
        for (int length : new int[] {0, 1, 255, 257}) {
            var predecessor = new ObjectReference(GENESIS_REFERENCE.id(), length);
            assertThrows(IllegalArgumentException.class, () -> head(2, EMPTY_MANIFEST, EMPTY_PREFIX, predecessor));
        }
    }

    @Test void manifestReferenceHasItsOwnSmallerPositiveResourceBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> head(1, new ObjectReference(ZERO_ID, 0), EMPTY_PREFIX, null));
        assertThrows(IllegalArgumentException.class,
                () -> head(1, new ObjectReference(ZERO_ID, ManifestCodec.MAX_MANIFEST_BYTES + 1), EMPTY_PREFIX, null));
        assertDoesNotThrow(() -> head(1, new ObjectReference(ZERO_ID, 1), EMPTY_PREFIX, null));
        assertDoesNotThrow(() -> head(1, new ObjectReference(ZERO_ID, ManifestCodec.MAX_MANIFEST_BYTES), EMPTY_PREFIX, null));
    }

    @Test void zeroSequenceRequiresTheExactDeclaredLineageHash() {
        assertThrows(IllegalArgumentException.class,
                () -> head(1, EMPTY_MANIFEST, new JournalPrefix(0, 24, ZERO_ID.value()), null));
        byte[] image = GOLDEN_GENESIS.clone();
        image[156] ^= 1;
        assertCode(Code.INVALID_HEAD, rehash(image));
    }

    @Test void prefixDeclarationsAreBoundedByTheManifestAndSegmentDomains() {
        assertThrows(IllegalArgumentException.class,
                () -> head(1, EMPTY_MANIFEST, new JournalPrefix(MAX_SEQUENCE + 1, 24, LINEAGE.value()), null));
        assertThrows(IllegalArgumentException.class,
                () -> head(1, EMPTY_MANIFEST, new JournalPrefix(1, ManifestCodec.MAX_PREFIX_BYTES + 1, LINEAGE.value()), null));
    }

    @Test void upperBoundsAndUnsignedUuidBitsSurviveCanonicalEncoding() {
        var wide = new UUID(-1, Long.MIN_VALUE);
        var largest = new ObjectCheckpointHead(wide, wide, wide, LINEAGE, Long.MAX_VALUE,
                new ObjectReference(ZERO_ID, ManifestCodec.MAX_MANIFEST_BYTES),
                new JournalPrefix(MAX_SEQUENCE, ManifestCodec.MAX_PREFIX_BYTES, ZERO_ID.value()), GENESIS_REFERENCE);
        byte[] image = ObjectCheckpointHeadCodec.encode(largest);
        assertEquals(largest, ObjectCheckpointHeadCodec.decode(image).head());
        var input = ByteBuffer.wrap(image);
        assertEquals(-1L, input.getLong(16));
        assertEquals(Long.MIN_VALUE, input.getLong(24));
        assertEquals(Long.MAX_VALUE, input.getLong(96));
        assertEquals(MAX_SEQUENCE, input.getLong(140));
        assertEquals(ManifestCodec.MAX_PREFIX_BYTES, input.getLong(148));
    }

    @Test void malformedNumericDeclarationsCannotHideBehindAValidFooter() {
        assertCode(Code.INVALID_HEAD, withLong(GOLDEN_SUCCESSOR, 96, 0));
        assertCode(Code.INVALID_HEAD, withLong(GOLDEN_SUCCESSOR, 96, Long.MIN_VALUE));
        assertCode(Code.INVALID_HEAD, withInt(GOLDEN_SUCCESSOR, 136, 0));
        assertCode(Code.INVALID_HEAD, withInt(GOLDEN_SUCCESSOR, 136, ManifestCodec.MAX_MANIFEST_BYTES + 1));
        assertCode(Code.INVALID_HEAD, withInt(GOLDEN_SUCCESSOR, 136, -1));
        assertCode(Code.INVALID_HEAD, withLong(GOLDEN_SUCCESSOR, 140, -1));
        assertCode(Code.INVALID_HEAD, withLong(GOLDEN_SUCCESSOR, 140, MAX_SEQUENCE + 1));
        assertCode(Code.INVALID_HEAD, withLong(GOLDEN_SUCCESSOR, 148, 21));
        assertCode(Code.INVALID_HEAD, withLong(GOLDEN_SUCCESSOR, 148, ManifestCodec.MAX_PREFIX_BYTES + 1));
    }

    private static ObjectCheckpointHead head(long revision, ObjectReference manifest,
                                             JournalPrefix prefix, ObjectReference predecessor) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, FIRST_PUBLICATION,
                LINEAGE, revision, manifest, prefix, predecessor);
    }

    private static void assertCode(Code expected, byte[] image) {
        assertEquals(expected, assertThrows(ObjectCheckpointHeadException.class,
                () -> ObjectCheckpointHeadCodec.decode(image)).code());
    }

    private static byte[] withInt(byte[] source, int offset, int value) {
        byte[] image = source.clone();
        ByteBuffer.wrap(image).putInt(offset, value);
        return rehash(image);
    }

    private static byte[] withLong(byte[] source, int offset, long value) {
        byte[] image = source.clone();
        ByteBuffer.wrap(image).putLong(offset, value);
        return rehash(image);
    }

    private static byte[] rehash(byte[] image) {
        System.arraycopy(digest(image, 224), 0, image, 224, 32);
        return image;
    }

    private static byte[] digest(byte[] image, int length) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(image, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
}
