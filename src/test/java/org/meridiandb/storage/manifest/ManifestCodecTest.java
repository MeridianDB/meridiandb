// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;

class ManifestCodecTest {
    private static final DatabaseId DATABASE = new DatabaseId("db");
    private static final String ZERO_HASH = "0".repeat(64);
    private static final SegmentId SEGMENT_ID = new SegmentId(
            "d313d28a39ce9900e61fdead724735a02e09ab194861553910fe941d64b26ace");
    private static final String PREFIX_HASH = "fc0bccc3589bcab2f55535bf1acf6199dab723cbdbd1526329f511413e9e0dc3";
    private static final CheckpointManifest MANIFEST = new CheckpointManifest(DATABASE, 7,
            new JournalPrefix(1, 78, PREFIX_HASH), List.of(new SegmentReference(SEGMENT_ID, 158, 1, 1)));
    private static final CheckpointManifest GENESIS = new CheckpointManifest(DATABASE, 1,
            new JournalPrefix(0, 24, "2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c"), List.of());

    // These literals were assembled independently using the documented big-endian fields,
    // bitwise Castagnoli CRC32C (0x82f63b78), and independent SHA-256 computation.
    private static final byte[] GOLDEN = HexFormat.of().parseHex(
            "4d52444e4d414e000000000100000018"
            + "4d52444e4a4e4c00000000010000000200640062a9cf7c87"
            + "00000000000000070000000000000001000000000000004e"
            + "fc0bccc3589bcab2f55535bf1acf6199dab723cbdbd1526329f511413e9e0dc3"
            + "00000001d19e02c8"
            + "d313d28a39ce9900e61fdead724735a02e09ab194861553910fe941d64b26ace"
            + "000000000000009e00000000000000010000000000000001"
            + "9e3dd8ae82bd0a3238a899500d9d5d3ac917dcc1fdee93484c058358190629d4");
    private static final String GOLDEN_ID = "e40b7bf552979c50d92fa672bba19ed93f2716fca847410d5406157f59cfae8e";
    private static final String GOLDEN_FOOTER = "9e3dd8ae82bd0a3238a899500d9d5d3ac917dcc1fdee93484c058358190629d4";
    private static final byte[] GOLDEN_GENESIS = HexFormat.of().parseHex(
            "4d52444e4d414e000000000100000018"
            + "4d52444e4a4e4c00000000010000000200640062a9cf7c87"
            + "000000000000000100000000000000000000000000000018"
            + "2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c"
            + "00000000f902125c"
            + "f83266075753b47172d1d76b041c5559bbccded92f693164194ad155e6cb6e94");

    @Test
    void matchesIndependentNonemptyGoldenAndDistinctWholeObjectIdentifier() {
        assertArrayEquals(GOLDEN, ManifestCodec.encode(MANIFEST));
        var decoded = ManifestCodec.decode(GOLDEN);
        assertEquals(MANIFEST, decoded.manifest());
        assertEquals(new ManifestId(GOLDEN_ID), decoded.id());
        assertEquals(decoded, ManifestCodec.decode(GOLDEN, new ManifestId(GOLDEN_ID)));
        assertEquals(GOLDEN_ID, hex(digest(GOLDEN)));
        assertNotEquals(GOLDEN_FOOTER, decoded.id().value());
        assertEquals(GOLDEN_FOOTER, hex(Arrays.copyOfRange(GOLDEN, 160, 192)));
    }

    @Test
    void matchesIndependentEmptyGenesisGolden() {
        assertArrayEquals(GOLDEN_GENESIS, ManifestCodec.encode(GENESIS));
        var decoded = ManifestCodec.decode(GOLDEN_GENESIS);
        assertEquals(GENESIS, decoded.manifest());
        assertEquals("bd068a065754e52824efaf91c44bc7234985e9a93c024dcb90e6951152e66982", decoded.id().value());
        assertTrue(decoded.manifest().segments().isEmpty());
        assertEquals(0, decoded.manifest().journalPrefix().sequence());
    }

    @Test
    void identifiersAndPrefixHashesRequireCanonicalLowercaseHex() {
        assertDoesNotThrow(() -> new ManifestId("abcdef0123456789".repeat(4)));
        for (String invalid : List.of("", "0".repeat(63), "0".repeat(65), "A".repeat(64),
                "g".repeat(64), "0".repeat(63) + "\n")) {
            assertThrows(IllegalArgumentException.class, () -> new ManifestId(invalid));
            assertThrows(IllegalArgumentException.class, () -> new JournalPrefix(0, 24, invalid));
        }
        assertThrows(NullPointerException.class, () -> new ManifestId(null));
        assertThrows(NullPointerException.class, () -> new JournalPrefix(0, 24, null));
    }

    @Test
    void declarationTypesRejectNullsInvalidNumbersAndEmptyCoverageContradictions() {
        assertThrows(NullPointerException.class, () -> new SegmentReference(null, 158, 1, 1));
        assertThrows(NullPointerException.class, () -> new CheckpointManifest(null, 1, GENESIS.journalPrefix(), List.of()));
        assertThrows(NullPointerException.class, () -> new CheckpointManifest(DATABASE, 1, null, List.of()));
        assertThrows(NullPointerException.class, () -> new CheckpointManifest(DATABASE, 1, GENESIS.journalPrefix(), null));
        assertThrows(NullPointerException.class, () -> new CheckpointManifest(DATABASE, 1,
                MANIFEST.journalPrefix(), Arrays.asList((SegmentReference) null)));
        for (long generation : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CheckpointManifest(DATABASE, generation, GENESIS.journalPrefix(), List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> new JournalPrefix(-1, 24, ZERO_HASH));
        assertThrows(IllegalArgumentException.class, () -> new JournalPrefix(0, 21, ZERO_HASH));
        assertThrows(IllegalArgumentException.class, () -> new SegmentReference(SEGMENT_ID, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SegmentReference(SEGMENT_ID, 158, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SegmentReference(SEGMENT_ID, 158, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckpointManifest(DATABASE, 1, MANIFEST.journalPrefix(), List.of()));
    }

    @Test
    void rangesCoverAContiguousPrefixInDeclaredOrderWithDistinctObjectIds() {
        var first = new SegmentReference(id(1), 200, 1, 2);
        var second = new SegmentReference(id(2), 200, 3, 4);
        var prefix = new JournalPrefix(4, 200, ZERO_HASH);
        assertDoesNotThrow(() -> new CheckpointManifest(DATABASE, 1, prefix, List.of(first, second)));
        for (var references : List.of(
                List.of(second, first),
                List.of(first, new SegmentReference(id(2), 200, 2, 4)),
                List.of(first, new SegmentReference(id(2), 200, 4, 4)),
                List.of(first, new SegmentReference(id(1), 200, 3, 4)))) {
            assertThrows(IllegalArgumentException.class, () -> new CheckpointManifest(DATABASE, 1, prefix, references));
        }
        assertThrows(IllegalArgumentException.class, () -> new CheckpointManifest(DATABASE, 1,
                new JournalPrefix(3, 200, ZERO_HASH), List.of(first, second)));
    }

    @Test
    void declarationsOwnAnImmutableReferenceListAndCodecOwnsNoInputArray() {
        var references = new ArrayList<>(MANIFEST.segments());
        var manifest = new CheckpointManifest(DATABASE, 7, MANIFEST.journalPrefix(), references);
        references.clear();
        assertEquals(MANIFEST, manifest);
        assertThrows(UnsupportedOperationException.class, () -> manifest.segments().clear());
        var bytes = GOLDEN.clone();
        var decoded = ManifestCodec.decode(bytes);
        Arrays.fill(bytes, (byte) 0);
        assertEquals(MANIFEST, decoded.manifest());
        assertEquals(new ManifestId(GOLDEN_ID), decoded.id());
        var encoded = ManifestCodec.encode(manifest);
        Arrays.fill(encoded, (byte) 0);
        assertArrayEquals(GOLDEN, ManifestCodec.encode(manifest));
    }

    @Test
    void exactUtf16MultipleReferencesAndGenerationRemainSeparateFromTemporalCutoff() {
        String identity = "A\u0000é" + (char) 0xd800 + "e" + (char) 0x0301 + (char) 0xdc00;
        var manifest = new CheckpointManifest(new DatabaseId(identity), Long.MAX_VALUE,
                new JournalPrefix(4, 400, ZERO_HASH), List.of(
                        new SegmentReference(id(1), 200, 1, 1),
                        new SegmentReference(id(2), 200, 2, 4)));
        var decoded = ManifestCodec.decode(ManifestCodec.encode(manifest));
        assertEquals(manifest, decoded.manifest());
        assertEquals(Long.MAX_VALUE, decoded.manifest().generation());
        assertEquals(4, decoded.manifest().journalPrefix().sequence());
        assertEquals(identity, decoded.manifest().databaseId().value());
    }

    @Test
    void expectedIdentifierRejectsAnotherValidObjectAndTheIntegrityFooter() {
        var genesisId = ManifestCodec.decode(GOLDEN_GENESIS).id();
        assertCode(ManifestFormatException.Code.IDENTIFIER_MISMATCH,
                () -> ManifestCodec.decode(GOLDEN, genesisId));
        assertCode(ManifestFormatException.Code.IDENTIFIER_MISMATCH,
                () -> ManifestCodec.decode(GOLDEN, new ManifestId(GOLDEN_FOOTER)));
    }

    @Test
    void everyTruncatedImageFailsWithoutReturningAnyDeclaration() {
        for (byte[] complete : List.of(GOLDEN, GOLDEN_GENESIS)) {
            for (int length = 0; length < complete.length; length++) {
                byte[] truncated = Arrays.copyOf(complete, length);
                assertCode(ManifestFormatException.Code.INCOMPLETE, () -> ManifestCodec.decode(truncated));
            }
        }
    }

    @Test
    void everySingleByteMutationAcrossHeaderReferenceAndFooterIsDetected() {
        for (int offset = 0; offset < GOLDEN.length; offset++) {
            var image = GOLDEN.clone();
            image[offset] ^= 1;
            assertThrows(ManifestFormatException.class, () -> ManifestCodec.decode(image), "manifest byte " + offset);
        }
    }

    @Test
    void rejectsTrailingBytesAndConcatenatedManifests() {
        assertCode(ManifestFormatException.Code.CORRUPT, () -> ManifestCodec.decode(concat(GOLDEN, new byte[] {0})));
        assertCode(ManifestFormatException.Code.CORRUPT, () -> ManifestCodec.decode(concat(GOLDEN, GOLDEN_GENESIS)));
    }

    @Test
    void unsupportedOuterAndNestedVersionsAreRejectedWithValidIntegrityFields() {
        var outer = GOLDEN.clone();
        ByteBuffer.wrap(outer).putInt(8, 2);
        resignHeaderAndFooter(outer);
        assertCode(ManifestFormatException.Code.UNSUPPORTED_VERSION, () -> ManifestCodec.decode(outer));
        var nested = GOLDEN.clone();
        ByteBuffer.wrap(nested).putInt(24, 2);
        replaceChecksum(nested, 16, 20);
        resignHeaderAndFooter(nested);
        assertCode(ManifestFormatException.Code.UNSUPPORTED_VERSION, () -> ManifestCodec.decode(nested));
    }

    @Test
    void unknownMagicAndInnerChecksumsCannotBeHiddenByAValidFooter() {
        var outer = GOLDEN.clone();
        outer[0] ^= 1;
        resignHeaderAndFooter(outer);
        assertCode(ManifestFormatException.Code.CORRUPT, () -> ManifestCodec.decode(outer));
        var nested = GOLDEN.clone();
        nested[16] ^= 1;
        replaceChecksum(nested, 16, 20);
        resignHeaderAndFooter(nested);
        assertCode(ManifestFormatException.Code.CORRUPT, () -> ManifestCodec.decode(nested));
        var checksum = GOLDEN.clone();
        checksum[39] ^= 1;
        resignHeaderAndFooter(checksum);
        assertCode(ManifestFormatException.Code.CORRUPT, () -> ManifestCodec.decode(checksum));
        var outerCrc = GOLDEN.clone();
        outerCrc[103] ^= 1;
        resignFooter(outerCrc);
        assertCode(ManifestFormatException.Code.CORRUPT, () -> ManifestCodec.decode(outerCrc));
    }

    @Test
    void malformedNestedIdentityAndTrailingNestedBytesAreCorruptCompleteObjects() {
        var missing = nestedHeader("db");
        ByteBuffer.wrap(missing).putInt(12, 3);
        replaceChecksum(missing, 0, 20);
        assertCode(ManifestFormatException.Code.CORRUPT,
                () -> ManifestCodec.decode(raw(missing, 7, 1, 78, PREFIX_HASH, 1, rawReference(SEGMENT_ID, 158, 1, 1))));
        var empty = nestedHeader("db");
        ByteBuffer.wrap(empty).putInt(12, 0);
        replaceChecksum(empty, 0, 20);
        assertCode(ManifestFormatException.Code.CORRUPT,
                () -> ManifestCodec.decode(raw(empty, 7, 1, 78, PREFIX_HASH, 1, rawReference(SEGMENT_ID, 158, 1, 1))));
        var oversizedIdentity = nestedHeader("db");
        ByteBuffer.wrap(oversizedIdentity).putInt(12, 32_769);
        replaceChecksum(oversizedIdentity, 0, 20);
        assertCode(ManifestFormatException.Code.LIMIT_EXCEEDED,
                () -> ManifestCodec.decode(raw(oversizedIdentity, 7, 1, 78, PREFIX_HASH,
                        1, rawReference(SEGMENT_ID, 158, 1, 1))));
        var extra = concat(nestedHeader("db"), new byte[] {0, 0});
        assertCode(ManifestFormatException.Code.CORRUPT,
                () -> ManifestCodec.decode(raw(extra, 7, 1, 78, PREFIX_HASH, 1, rawReference(SEGMENT_ID, 158, 1, 1))));
    }

    @Test
    void malformedNestedLengthsAreRejectedBeforeTrustingThem() {
        for (int length : new int[] {-1, 0, 21, 23}) {
            var image = GOLDEN.clone();
            ByteBuffer.wrap(image).putInt(12, length);
            assertCode(ManifestFormatException.Code.CORRUPT, () -> ManifestCodec.decode(image));
        }
        var tooLarge = GOLDEN.clone();
        ByteBuffer.wrap(tooLarge).putInt(12, Integer.MAX_VALUE);
        assertCode(ManifestFormatException.Code.LIMIT_EXCEEDED, () -> ManifestCodec.decode(tooLarge));
    }

    @Test
    void invalidGenerationPrefixAndReferenceFieldsFailAfterIntegrityIsResigned() {
        for (long generation : new long[] {0, -1, Long.MIN_VALUE}) {
            assertCode(ManifestFormatException.Code.INVALID_CHECKPOINT,
                    () -> ManifestCodec.decode(raw(nestedHeader("db"), generation, 1, 78, PREFIX_HASH,
                            1, rawReference(SEGMENT_ID, 158, 1, 1))));
        }
        for (long[] prefix : new long[][] {{-1, 78}, {1, 21}, {1, 22}, {2, 78}}) {
            assertCode(ManifestFormatException.Code.INVALID_CHECKPOINT,
                    () -> ManifestCodec.decode(raw(nestedHeader("db"), 7, prefix[0], prefix[1], PREFIX_HASH,
                            1, rawReference(SEGMENT_ID, 158, 1, 1))));
        }
        for (long[] reference : new long[][] {{0, 1, 1}, {158, 0, 1}, {158, 2, 1}, {158, 2, 2}}) {
            assertCode(ManifestFormatException.Code.INVALID_CHECKPOINT,
                    () -> ManifestCodec.decode(raw(nestedHeader("db"), 7, 1, 78, PREFIX_HASH, 1,
                            rawReference(SEGMENT_ID, reference[0], reference[1], reference[2]))));
        }
    }

    @Test
    void duplicateIdsGapsOverlapAndReorderedCoverageAreRejected() {
        for (byte[] references : List.of(
                concat(rawReference(id(1), 100, 1, 1), rawReference(id(1), 100, 2, 2)),
                concat(rawReference(id(1), 100, 1, 1), rawReference(id(2), 100, 3, 3)),
                concat(rawReference(id(1), 100, 1, 2), rawReference(id(2), 100, 2, 3)),
                concat(rawReference(id(2), 100, 2, 2), rawReference(id(1), 100, 1, 1)))) {
            assertCode(ManifestFormatException.Code.INVALID_CHECKPOINT,
                    () -> ManifestCodec.decode(raw(nestedHeader("db"), 1, 2, 200, ZERO_HASH, 2, references)));
        }
    }

    @Test
    void invalidCountsFailBeforeAllocatingAReferenceList() {
        assertCode(ManifestFormatException.Code.CORRUPT,
                () -> ManifestCodec.decode(raw(nestedHeader("db"), 1, 0, 24, ZERO_HASH, -1, new byte[0])));
        for (int count : new int[] {4097, Integer.MAX_VALUE}) {
            assertCode(ManifestFormatException.Code.LIMIT_EXCEEDED,
                    () -> ManifestCodec.decode(raw(nestedHeader("db"), 1, 0, 24, ZERO_HASH, count, new byte[0])));
        }
    }

    @Test
    void declaredCountMustMatchWholeReferenceBytes() {
        assertCode(ManifestFormatException.Code.INCOMPLETE,
                () -> ManifestCodec.decode(raw(nestedHeader("db"), 7, 1, 78, PREFIX_HASH, 2,
                        rawReference(SEGMENT_ID, 158, 1, 1))));
        assertCode(ManifestFormatException.Code.CORRUPT,
                () -> ManifestCodec.decode(raw(nestedHeader("db"), 7, 1, 78, PREFIX_HASH, 0,
                        rawReference(SEGMENT_ID, 158, 1, 1))));
    }

    @Test
    void maximumReferenceCountRoundTripsAndTheNextCountFailsEncoding() {
        assertEquals(4096, ManifestCodec.MAX_SEGMENTS);
        var references = new ArrayList<SegmentReference>(4097);
        for (int i = 1; i <= 4096; i++) {
            references.add(new SegmentReference(id(i), 158, i, i));
        }
        var maximum = new CheckpointManifest(DATABASE, 1, new JournalPrefix(4096, 1_000_000, ZERO_HASH), references);
        assertEquals(maximum, ManifestCodec.decode(ManifestCodec.encode(maximum)).manifest());
        references.add(new SegmentReference(id(4097), 158, 4097, 4097));
        var tooMany = new CheckpointManifest(DATABASE, 1, new JournalPrefix(4097, 1_000_000, ZERO_HASH), references);
        assertCode(ManifestFormatException.Code.LIMIT_EXCEEDED, () -> ManifestCodec.encode(tooMany));
    }

    @Test
    void maximumLineageRoundTripsAndNextCodeUnitFailsBeforeOutputAllocation() {
        var maximum = new CheckpointManifest(new DatabaseId("x".repeat(32_768)), 1,
                new JournalPrefix(0, 65_556, ZERO_HASH), List.of());
        assertEquals(maximum, ManifestCodec.decode(ManifestCodec.encode(maximum)).manifest());
        var tooLarge = new CheckpointManifest(new DatabaseId("x".repeat(32_769)), 1,
                new JournalPrefix(0, 65_558, ZERO_HASH), List.of());
        assertCode(ManifestFormatException.Code.LIMIT_EXCEEDED, () -> ManifestCodec.encode(tooLarge));
    }

    @Test
    void referenceLengthAndRecordCountLimitsApplyToEncodingAndDecodingWithoutOverflow() {
        var boundary = new CheckpointManifest(DATABASE, 1, new JournalPrefix(65_536, 1_000_000, ZERO_HASH),
                List.of(new SegmentReference(id(1), 32L * 1024 * 1024, 1, 65_536)));
        assertEquals(boundary, ManifestCodec.decode(ManifestCodec.encode(boundary)).manifest());
        for (long[] reference : new long[][] {
                {32L * 1024 * 1024 + 1, 65_536}, {158, 65_537}, {158, Long.MAX_VALUE}}) {
            var invalid = new CheckpointManifest(DATABASE, 1,
                    new JournalPrefix(reference[1], 1_000_000, ZERO_HASH),
                    List.of(new SegmentReference(id(1), reference[0], 1, reference[1])));
            assertCode(ManifestFormatException.Code.LIMIT_EXCEEDED, () -> ManifestCodec.encode(invalid));
            assertCode(ManifestFormatException.Code.LIMIT_EXCEEDED,
                    () -> ManifestCodec.decode(raw(nestedHeader("db"), 1, reference[1], 1_000_000,
                            ZERO_HASH, 1, rawReference(id(1), reference[0], 1, reference[1]))));
        }
    }

    @Test
    void prefixByteLimitIsBoundedAndDoesNotOverflowForLongMaximum() {
        assertEquals(4096L * 32 * 1024 * 1024 + 65_556, ManifestCodec.MAX_PREFIX_BYTES);
        var maximum = new CheckpointManifest(DATABASE, 1,
                new JournalPrefix(1, ManifestCodec.MAX_PREFIX_BYTES, ZERO_HASH), MANIFEST.segments());
        assertEquals(maximum, ManifestCodec.decode(ManifestCodec.encode(maximum)).manifest());
        for (long length : new long[] {ManifestCodec.MAX_PREFIX_BYTES + 1, Long.MAX_VALUE}) {
            var invalid = new CheckpointManifest(DATABASE, 1, new JournalPrefix(1, length, ZERO_HASH), MANIFEST.segments());
            assertCode(ManifestFormatException.Code.LIMIT_EXCEEDED, () -> ManifestCodec.encode(invalid));
            assertCode(ManifestFormatException.Code.LIMIT_EXCEEDED,
                    () -> ManifestCodec.decode(raw(nestedHeader("db"), 1, 1, length, ZERO_HASH,
                            1, rawReference(SEGMENT_ID, 158, 1, 1))));
        }
    }

    @Test
    void manifestWholeImageSizeLimitPrecedesParsing() {
        assertEquals(1024 * 1024, ManifestCodec.MAX_MANIFEST_BYTES);
        assertCode(ManifestFormatException.Code.LIMIT_EXCEEDED,
                () -> ManifestCodec.decode(new byte[ManifestCodec.MAX_MANIFEST_BYTES + 1]));
        var atLimit = Arrays.copyOf(GOLDEN, ManifestCodec.MAX_MANIFEST_BYTES);
        assertCode(ManifestFormatException.Code.CORRUPT, () -> ManifestCodec.decode(atLimit));
    }

    @Test
    void nullCodecInputsAndDecodedRecordMembersAreRejected() {
        assertThrows(NullPointerException.class, () -> ManifestCodec.encode(null));
        assertThrows(NullPointerException.class, () -> ManifestCodec.decode(null));
        assertThrows(NullPointerException.class, () -> ManifestCodec.decode(GOLDEN, null));
        assertThrows(NullPointerException.class, () -> new ManifestCodec.DecodedManifest(null, MANIFEST));
        assertThrows(NullPointerException.class, () -> new ManifestCodec.DecodedManifest(new ManifestId(GOLDEN_ID), null));
    }

    private static SegmentId id(int value) { return new SegmentId(String.format("%064x", value)); }

    private static void assertCode(ManifestFormatException.Code code, Runnable action) {
        assertEquals(code, assertThrows(ManifestFormatException.class, action::run).code());
    }

    private static byte[] nestedHeader(String value) {
        byte[] fields = bytes(out -> {
            out.writeLong(0x4d52444e4a4e4c00L);
            out.writeInt(1);
            out.writeInt(value.length());
            for (int i = 0; i < value.length(); i++) { out.writeChar(value.charAt(i)); }
        });
        return concat(fields, ByteBuffer.allocate(4).putInt(checksum(fields, 0, fields.length)).array());
    }

    private static byte[] rawReference(SegmentId id, long length, long first, long last) {
        return bytes(out -> {
            out.write(HexFormat.of().parseHex(id.value()));
            out.writeLong(length);
            out.writeLong(first);
            out.writeLong(last);
        });
    }

    private static byte[] raw(byte[] lineage, long generation, long sequence, long prefixLength,
            String prefixHash, int count, byte[] references) {
        byte[] fields = bytes(out -> {
            out.writeLong(0x4d52444e4d414e00L);
            out.writeInt(1);
            out.writeInt(lineage.length);
            out.write(lineage);
            out.writeLong(generation);
            out.writeLong(sequence);
            out.writeLong(prefixLength);
            out.write(HexFormat.of().parseHex(prefixHash));
            out.writeInt(count);
        });
        byte[] content = concat(fields,
                ByteBuffer.allocate(4).putInt(checksum(fields, 0, fields.length)).array(), references);
        return concat(content, digest(content));
    }

    private static void resignHeaderAndFooter(byte[] image) {
        replaceChecksum(image, 0, 76 + ByteBuffer.wrap(image).getInt(12));
        resignFooter(image);
    }

    private static void resignFooter(byte[] image) {
        byte[] prefix = Arrays.copyOf(image, image.length - 32);
        System.arraycopy(digest(prefix), 0, image, prefix.length, 32);
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        var crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private static void replaceChecksum(byte[] bytes, int offset, int length) {
        ByteBuffer.wrap(bytes).putInt(offset + length, checksum(bytes, offset, length));
    }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }

    private static byte[] bytes(Writer writer) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { writer.write(out); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... fragments) {
        var bytes = new ByteArrayOutputStream();
        for (var fragment : fragments) { bytes.writeBytes(fragment); }
        return bytes.toByteArray();
    }

    @FunctionalInterface
    private interface Writer { void write(DataOutputStream out) throws IOException; }
}
