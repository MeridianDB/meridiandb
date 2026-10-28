// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class CheckpointVerifierTest {
    private static final DatabaseId DATABASE = new DatabaseId("db");
    private static final EntityId ENTITY = new EntityId("e");
    private static final String ZERO_HASH = "0".repeat(64);

    @Test
    void verifiesIndependentGoldenSegmentAgainstExactKnownJournalPrefix() throws IOException {
        byte[] image = HexFormat.of().parseHex(
                "4d52444e534547000000000100000018"
                + "4d52444e4a4e4c00000000010000000200640062a9cf7c87"
                + "0000000000000001000000000000000100000001000000000000003621f423b2"
                + "4d54584e0000000100000022182a4007"
                + "00000000000000010000000100720000000101000000010065000000000000000007"
                + "538cd524af1a2406823229a1e45d59ada79103d9b9f834e1c0171ef6a3e83478ba0e28fa");
        var id = new SegmentId("d313d28a39ce9900e61fdead724735a02e09ab194861553910fe941d64b26ace");
        var manifest = new CheckpointManifest(DATABASE, 7,
                new JournalPrefix(1, 78, "fc0bccc3589bcab2f55535bf1acf6199dab723cbdbd1526329f511413e9e0dc3"),
                List.of(new SegmentReference(id, 158, 1, 1)));
        var calls = new ArrayList<SegmentId>();
        CheckpointVerifier.verify(manifest, requested -> { calls.add(requested); return image; });
        assertEquals(List.of(id), calls);
        byte[] expectedPrefix = independentPrefix(DATABASE, List.of(record(1, "r", 7)));
        assertEquals(78, expectedPrefix.length);
        assertEquals(manifest.journalPrefix().sha256(), hash(expectedPrefix));
    }

    @Test
    void emptyGenesisVerifiesOnlyItsExactLineageWithoutConsultingASegmentSource() throws IOException {
        var bundle = bundle(DATABASE, Long.MAX_VALUE, List.of());
        CheckpointVerifier.verify(bundle.manifest(), ignored -> { throw new AssertionError("Unexpected segment read"); });
        assertEquals(0, bundle.manifest().journalPrefix().sequence());
        assertEquals(Long.MAX_VALUE, bundle.manifest().generation());
        assertEquals(24, bundle.manifest().journalPrefix().byteLength());
        assertEquals("2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c",
                bundle.manifest().journalPrefix().sha256());
    }

    @Test
    void exactUnicodeOperationOrderNoopsAndFiniteBoundsReconstructAcrossMultipleSegments() throws IOException {
        String exact = "A\u0000é" + (char) 0xd800 + "e" + (char) 0x0301 + (char) 0xdc00;
        var database = new DatabaseId(exact);
        var entity = new EntityId(exact);
        var first = new JournalRecord(1, new Transaction(new RequestId(exact), List.of(
                new WriteOperation.Put(entity, new ValidTimeInterval(null, null), Long.MIN_VALUE),
                new WriteOperation.Retract(entity, new ValidTimeInterval(Long.MIN_VALUE, Long.MAX_VALUE)),
                new WriteOperation.Put(entity, new ValidTimeInterval(Long.MAX_VALUE, null), Long.MAX_VALUE))));
        var second = new JournalRecord(2, new Transaction(new RequestId("é"), List.of(
                new WriteOperation.Retract(new EntityId("absent"), new ValidTimeInterval(null, null)))));
        var third = new JournalRecord(3, new Transaction(new RequestId("e" + (char) 0x0301), List.of(
                new WriteOperation.Put(entity, new ValidTimeInterval(Long.MIN_VALUE, null), 7))));
        var fourth = new JournalRecord(4, new Transaction(new RequestId("repeat-value"), third.transaction().operations()));
        var bundle = bundle(database, 1, List.of(List.of(first, second), List.of(third), List.of(fourth)));
        var calls = new ArrayList<SegmentId>();
        CheckpointVerifier.verify(bundle.manifest(), id -> { calls.add(id); return bundle.images().get(id); });
        assertEquals(bundle.manifest().segments().stream().map(SegmentReference::id).toList(), calls);
        assertEquals(hash(independentPrefix(database, List.of(first, second, third, fourth))),
                bundle.manifest().journalPrefix().sha256());
        assertEquals(4, bundle.manifest().journalPrefix().sequence());
        assertEquals(1, bundle.manifest().generation());
    }

    @Test
    void eachObjectIsFullyConsumedBeforeTheNextObjectIsRequested() throws IOException {
        var bundle = threeSegments();
        var references = bundle.manifest().segments();
        var firstId = references.getFirst().id();
        var secondId = references.get(1).id();
        CheckpointVerifier.verify(bundle.manifest(), id -> {
            if (id.equals(secondId)) {
                // The source may reuse an earlier buffer once its validation is complete.
                // Prefetching objects before consuming the first one would invalidate it.
                java.util.Arrays.fill(bundle.images().get(firstId), (byte) 0);
            }
            return bundle.images().get(id);
        });
    }

    @Test
    void missingFirstObjectStopsBeforeReadingAnyLaterReferences() {
        var bundle = threeSegments();
        var calls = new ArrayList<SegmentId>();
        assertInvalid(bundle.manifest(), id -> { calls.add(id); return null; });
        assertEquals(List.of(bundle.manifest().segments().getFirst().id()), calls);
    }

    @Test
    void missingLaterObjectFailsWholeCheckpointInsteadOfAcceptingVerifiedPrefix() {
        var bundle = threeSegments();
        var calls = new ArrayList<SegmentId>();
        var second = bundle.manifest().segments().get(1).id();
        assertInvalid(bundle.manifest(), id -> {
            calls.add(id);
            return id.equals(second) ? null : bundle.images().get(id);
        });
        assertEquals(bundle.manifest().segments().subList(0, 2).stream().map(SegmentReference::id).toList(), calls);
    }

    @Test
    void ioFailurePropagatesUnchangedAndStopsBeforeLaterObjects() {
        var bundle = threeSegments();
        var failure = new IOException("second segment is unavailable");
        var calls = new ArrayList<SegmentId>();
        var second = bundle.manifest().segments().get(1).id();
        assertSame(failure, assertThrows(IOException.class, () -> CheckpointVerifier.verify(bundle.manifest(), id -> {
            calls.add(id);
            if (id.equals(second)) { throw failure; }
            return bundle.images().get(id);
        })));
        assertEquals(bundle.manifest().segments().subList(0, 2).stream().map(SegmentReference::id).toList(), calls);
    }

    @Test
    void exactReferenceLengthIsCheckedBeforeDecodingTheObject() {
        var bundle = bundle(DATABASE, 1, List.of(List.of(record(1, "first", 7))));
        var original = bundle.manifest().segments().getFirst();
        for (long length : new long[] {original.byteLength() - 1, original.byteLength() + 1}) {
            var manifest = new CheckpointManifest(DATABASE, 1, bundle.manifest().journalPrefix(),
                    List.of(new SegmentReference(original.id(), length, 1, 1)));
            assertInvalid(manifest, bundle.images()::get);
        }
    }

    @Test
    void differentValidSameLengthObjectPropagatesSegmentIdentifierMismatch() {
        var bundle = bundle(DATABASE, 1, List.of(List.of(record(1, "first", 7))));
        byte[] wrong = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(record(1, "first", 8))));
        assertEquals(bundle.manifest().segments().getFirst().byteLength(), wrong.length);
        var failure = assertThrows(SegmentFormatException.class,
                () -> CheckpointVerifier.verify(bundle.manifest(), ignored -> wrong));
        assertEquals(SegmentFormatException.Code.IDENTIFIER_MISMATCH, failure.code());
    }

    @Test
    void malformedReferencedSegmentPropagatesItsFormatFailureAndStops() {
        var bundle = threeSegments();
        var first = bundle.manifest().segments().getFirst().id();
        var damaged = bundle.images().get(first).clone();
        damaged[damaged.length - 1] ^= 1;
        var calls = new ArrayList<SegmentId>();
        var failure = assertThrows(SegmentFormatException.class, () -> CheckpointVerifier.verify(bundle.manifest(), id -> {
            calls.add(id);
            return id.equals(first) ? damaged : bundle.images().get(id);
        }));
        assertEquals(SegmentFormatException.Code.CORRUPT, failure.code());
        assertEquals(List.of(first), calls);
    }

    @Test
    void actualObjectLineageMustMatchManifestEvenWhenItsIdentifierMatches() {
        var bundle = bundle(new DatabaseId("other"), 1, List.of(List.of(record(1, "first", 7))));
        var manifest = new CheckpointManifest(DATABASE, 1,
                new JournalPrefix(1, 1000, ZERO_HASH), bundle.manifest().segments());
        assertInvalid(manifest, bundle.images()::get);
    }

    @Test
    void actualObjectRangeMustMatchDeclaredCoverageEvenWhenItsIdentifierMatches() {
        byte[] image = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(record(2, "second", 7))));
        var id = new SegmentId(hash(image));
        var manifest = new CheckpointManifest(DATABASE, 1, new JournalPrefix(1, 1000, ZERO_HASH),
                List.of(new SegmentReference(id, image.length, 1, 1)));
        assertInvalid(manifest, ignored -> image);
    }

    @Test
    void reconstructedPrefixLengthAndDigestMustBothMatchExactly() {
        var bundle = threeSegments();
        var prefix = bundle.manifest().journalPrefix();
        for (var invalidPrefix : List.of(
                new JournalPrefix(prefix.sequence(), prefix.byteLength() - 1, prefix.sha256()),
                new JournalPrefix(prefix.sequence(), prefix.byteLength() + 1, prefix.sha256()),
                new JournalPrefix(prefix.sequence(), prefix.byteLength(), ZERO_HASH))) {
            var manifest = new CheckpointManifest(DATABASE, 1, invalidPrefix, bundle.manifest().segments());
            assertInvalid(manifest, bundle.images()::get);
        }
    }

    @Test
    void prefixDigestCannotDescribeOnlyTheRecordsWithoutTheirLineageHeader() {
        var bundle = threeSegments();
        byte[] recordsOnly = concat(rawRecord(record(1, "a", 1)), rawRecord(record(2, "b", 2)), rawRecord(record(3, "c", 3)));
        var original = bundle.manifest().journalPrefix();
        var prefix = new JournalPrefix(original.sequence(), original.byteLength(), hash(recordsOnly));
        assertInvalid(new CheckpointManifest(DATABASE, 1, prefix, bundle.manifest().segments()), bundle.images()::get);
    }

    @Test
    void duplicateRequestAcrossSegmentsRejectsEvenAnIdenticalRetryPayload() {
        var first = record(1, "retry", 7);
        var identicalRetry = new JournalRecord(2, first.transaction());
        var changedRetry = record(2, "retry", 8);
        for (var second : List.of(identicalRetry, changedRetry)) {
            var bundle = bundle(DATABASE, 1, List.of(List.of(first), List.of(second)));
            assertInvalid(bundle.manifest(), bundle.images()::get);
        }
    }

    @Test
    void changedGenerationDoesNotChangeVerifiedTemporalPrefix() throws IOException {
        var bundle = threeSegments();
        var highGeneration = new CheckpointManifest(DATABASE, Long.MAX_VALUE,
                bundle.manifest().journalPrefix(), bundle.manifest().segments());
        CheckpointVerifier.verify(highGeneration, bundle.images()::get);
        CheckpointVerifier.verify(bundle.manifest(), bundle.images()::get);
        assertEquals(3, highGeneration.journalPrefix().sequence());
        assertEquals(Long.MAX_VALUE, highGeneration.generation());
    }

    @Test
    void invalidResourceMetadataFailsWithoutAnySegmentSourceCall() {
        var sourceCalls = new AtomicInteger();
        CheckpointVerifier.SegmentSource unused = ignored -> {
            sourceCalls.incrementAndGet();
            throw new AssertionError("Invalid declarations must fail before reading objects");
        };
        var tooLongLineage = new CheckpointManifest(new DatabaseId("x".repeat(32_769)), 1,
                new JournalPrefix(0, 65_558, ZERO_HASH), List.of());
        var prefixTooShort = new CheckpointManifest(DATABASE, 1, new JournalPrefix(0, 22, ZERO_HASH), List.of());
        var prefixTooLarge = new CheckpointManifest(DATABASE, 1,
                new JournalPrefix(0, Long.MAX_VALUE, ZERO_HASH), List.of());
        var refTooLarge = new CheckpointManifest(DATABASE, 1, new JournalPrefix(1, 1000, ZERO_HASH),
                List.of(new SegmentReference(new SegmentId("1".repeat(64)), 32L * 1024 * 1024 + 1, 1, 1)));
        var rangeTooLarge = new CheckpointManifest(DATABASE, 1, new JournalPrefix(Long.MAX_VALUE, 1000, ZERO_HASH),
                List.of(new SegmentReference(new SegmentId("1".repeat(64)), 158, 1, Long.MAX_VALUE)));
        for (var invalid : List.of(tooLongLineage, prefixTooShort, prefixTooLarge, refTooLarge, rangeTooLarge)) {
            assertThrows(ManifestFormatException.class, () -> CheckpointVerifier.verify(invalid, unused));
        }
        assertEquals(0, sourceCalls.get());
    }

    @Test
    void emptyGenesisStillRequiresCorrectHeaderLengthAndDigest() {
        byte[] header = independentPrefix(DATABASE, List.of());
        for (var prefix : List.of(new JournalPrefix(0, header.length + 1L, hash(header)),
                new JournalPrefix(0, header.length, ZERO_HASH))) {
            var manifest = new CheckpointManifest(DATABASE, 1, prefix, List.of());
            assertInvalid(manifest, ignored -> { throw new AssertionError("Genesis must not read segment objects"); });
        }
    }

    @Test
    void sourceBytesAreNotModifiedByVerification() throws IOException {
        var bundle = threeSegments();
        var originals = new LinkedHashMap<SegmentId, byte[]>();
        bundle.images().forEach((id, image) -> originals.put(id, image.clone()));
        CheckpointVerifier.verify(bundle.manifest(), bundle.images()::get);
        originals.forEach((id, expected) -> assertArrayEquals(expected, bundle.images().get(id)));
    }

    @Test
    void nullManifestOrSourceFailsBeforeAnySourceCall() {
        var calls = new AtomicInteger();
        assertThrows(NullPointerException.class, () -> CheckpointVerifier.verify(null, ignored -> {
            calls.incrementAndGet();
            return null;
        }));
        assertEquals(0, calls.get());
        assertThrows(NullPointerException.class, () -> CheckpointVerifier.verify(threeSegments().manifest(), null));
    }

    private static Bundle threeSegments() {
        return bundle(DATABASE, 1, List.of(List.of(record(1, "a", 1)),
                List.of(record(2, "b", 2)), List.of(record(3, "c", 3))));
    }

    private static void assertInvalid(CheckpointManifest manifest, CheckpointVerifier.SegmentSource source) {
        var failure = assertThrows(ManifestFormatException.class, () -> CheckpointVerifier.verify(manifest, source));
        assertEquals(ManifestFormatException.Code.INVALID_CHECKPOINT, failure.code());
    }

    private static Bundle bundle(DatabaseId database, long generation, List<List<JournalRecord>> partitions) {
        var references = new ArrayList<SegmentReference>();
        var images = new LinkedHashMap<SegmentId, byte[]>();
        var records = new ArrayList<JournalRecord>();
        for (var partition : partitions) {
            byte[] image = SegmentCodec.encode(new ImmutableSegment(database, partition));
            var id = new SegmentId(hash(image));
            references.add(new SegmentReference(id, image.length, partition.getFirst().sequence(), partition.getLast().sequence()));
            images.put(id, image);
            records.addAll(partition);
        }
        byte[] prefix = independentPrefix(database, records);
        var manifest = new CheckpointManifest(database, generation,
                new JournalPrefix(records.isEmpty() ? 0 : records.getLast().sequence(), prefix.length, hash(prefix)), references);
        return new Bundle(manifest, images);
    }

    private record Bundle(CheckpointManifest manifest, Map<SegmentId, byte[]> images) { }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), value))));
    }

    // The expected prefix writer intentionally shares no production JournalCodec routines.
    private static byte[] independentPrefix(DatabaseId database, List<JournalRecord> records) {
        byte[] header = bytes(out -> {
            out.writeLong(0x4d52444e4a4e4c00L);
            out.writeInt(1);
            writeString(out, database.value());
        });
        var prefix = new ByteArrayOutputStream();
        prefix.writeBytes(header);
        prefix.writeBytes(integer(checksum(header)));
        for (var record : records) { prefix.writeBytes(rawRecord(record)); }
        return prefix.toByteArray();
    }

    private static byte[] rawRecord(JournalRecord record) {
        byte[] payload = bytes(out -> {
            out.writeLong(record.sequence());
            writeString(out, record.transaction().requestId().value());
            out.writeInt(record.transaction().operations().size());
            for (var operation : record.transaction().operations()) {
                out.writeByte(operation instanceof WriteOperation.Put ? 1 : 2);
                writeString(out, operation.entity().value());
                var interval = operation.interval();
                out.writeByte((interval.fromInclusiveMicros() == null ? 0 : 1)
                        | (interval.toExclusiveMicros() == null ? 0 : 2));
                if (interval.fromInclusiveMicros() != null) { out.writeLong(interval.fromInclusiveMicros()); }
                if (interval.toExclusiveMicros() != null) { out.writeLong(interval.toExclusiveMicros()); }
                if (operation instanceof WriteOperation.Put put) { out.writeLong(put.value()); }
            }
        });
        byte[] prefix = bytes(out -> {
            out.writeInt(0x4d54584e);
            out.writeInt(1);
            out.writeInt(payload.length);
        });
        return concat(prefix, integer(checksum(prefix)), payload, integer(checksum(payload)));
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeInt(value.length());
        for (int i = 0; i < value.length(); i++) { out.writeChar(value.charAt(i)); }
    }

    private static int checksum(byte[] bytes) {
        var crc = new CRC32C();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    private static byte[] integer(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }

    private static String hash(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static byte[] concat(byte[]... fragments) {
        var bytes = new ByteArrayOutputStream();
        for (var fragment : fragments) { bytes.writeBytes(fragment); }
        return bytes.toByteArray();
    }

    private static byte[] bytes(Writer writer) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { writer.write(out); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
        return bytes.toByteArray();
    }

    @FunctionalInterface
    private interface Writer { void write(DataOutputStream out) throws IOException; }
}
