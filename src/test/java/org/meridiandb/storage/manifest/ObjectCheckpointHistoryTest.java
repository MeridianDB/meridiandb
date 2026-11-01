// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphException.Code;
import org.meridiandb.storage.object.ImmutableObjectStore;
import org.meridiandb.storage.object.LocalObjectStore;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.object.VerifiedObject;
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

class ObjectCheckpointHistoryTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final UUID OTHER = new UUID(0, 99);
    private static final DatabaseId DATABASE = new DatabaseId("db");
    private static final ObjectId LINEAGE = ObjectCheckpointHeadVerifier.lineageId(DATABASE);
    private static final JournalRecord FIRST = record(1, "r1", 10);
    private static final JournalRecord SECOND = record(2, "r2", 20);
    private static final JournalRecord THIRD = record(3, "r3", 30);
    private static final ObjectCheckpointGraphs.Limits DEFAULTS = ObjectCheckpointGraphs.DEFAULT_LIMITS;

    @TempDir Path directory;
    private int journalNumber;

    @Test void genesisMaterializesAnEmptyHistoryAfterCheckingItsExactOwnedHeader() throws IOException {
        var fixture = fixture(17, List.of());
        var objects = objects(fixture);
        try (var journal = journal(List.of())) {
            long forcedBytes = journal.forcedBytes();
            var result = verifier(objects, journal).loadHistory(head(fixture), exact(fixture));
            assertEquals(List.of(), result.records());
            assertGraph(result.graph(), head(fixture), fixture);
            assertUsage(result.usage(), fixture);
            assertEquals(List.of(manifestKey(head(fixture))), objects.reads);
            assertEquals(0, journal.forcedSequence());
            assertEquals(forcedBytes, journal.forcedBytes());
            assertEquals(0, objects.creates);
            assertReadersClosed(objects);
        }
    }

    @Test void originalFramesPreserveNoopsOperationOrderUtf16IdentityAndIntervalExtremes() throws IOException {
        var entity = new EntityId("e");
        String exactIdentity = "A\u0000é" + (char) 0xd800 + (char) 0x0301;
        var put = new WriteOperation.Put(entity, new ValidTimeInterval(10L, 20L), 100);
        var records = List.of(
                new JournalRecord(1, new Transaction(new RequestId("é"), List.of(put))),
                new JournalRecord(2, new Transaction(new RequestId("e" + (char) 0x0301), List.of(
                        new WriteOperation.Retract(new EntityId("missing"), new ValidTimeInterval(null, null))))),
                new JournalRecord(3, new Transaction(new RequestId("same-value-no-op"), List.of(put))),
                new JournalRecord(4, new Transaction(new RequestId("retroactive-correction"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(12L, 14L), 130)))),
                new JournalRecord(5, new Transaction(new RequestId(exactIdentity), List.of(
                        new WriteOperation.Put(new EntityId(exactIdentity), new ValidTimeInterval(null, null), Long.MIN_VALUE),
                        new WriteOperation.Retract(new EntityId(exactIdentity), new ValidTimeInterval(Long.MIN_VALUE, Long.MAX_VALUE)),
                        new WriteOperation.Put(entity, new ValidTimeInterval(Long.MAX_VALUE, null), Long.MAX_VALUE)))),
                new JournalRecord(6, new Transaction(new RequestId("retraction"), List.of(
                        new WriteOperation.Retract(entity, new ValidTimeInterval(13L, null))))));
        var fixture = fixture(23, List.of(records.subList(0, 2), records.subList(2, 5), records.subList(5, 6)));
        var objects = objects(fixture);
        try (var journal = journal(records)) {
            var result = verifier(objects, journal).loadHistory(head(fixture), exact(fixture));
            assertEquals(records, result.records());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), result.records().stream().map(JournalRecord::sequence).toList());
            assertEquals(exactIdentity, result.records().get(4).transaction().requestId().value());
            assertEquals(3, result.records().get(4).transaction().operations().size());
            for (int index = 0; index < records.size(); index++) {
                assertArrayEquals(JournalCodec.encodeRecord(records.get(index)), JournalCodec.encodeRecord(result.records().get(index)));
            }
            assertUsage(result.usage(), fixture);
            assertEquals(expectedReads(fixture), objects.reads);
            assertEquals(0, objects.creates);
            assertEquals(0, journal.forcedSequence());
            assertReadersClosed(objects);
        }
    }

    @Test void explicitOlderPinnedHeadExcludesAValidSuffixAndDoesNotDiscoverNewerHeadsOrAncestors() throws IOException {
        var pinned = fixture(17, List.of(List.of(FIRST, SECOND)));
        var newer = fixture(99, List.of(List.of(FIRST, SECOND, THIRD)));
        var declared = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 17), LINEAGE, 5,
                ObjectReference.forBytes(pinned.manifestImage()), pinned.prefix(),
                new ObjectReference(new ObjectId("0".repeat(64)), ObjectCheckpointHeadCodec.ENCODED_BYTES));
        var objects = objects(pinned, newer);
        try (var journal = journal(List.of(FIRST, SECOND, THIRD))) {
            var result = verifier(objects, journal).loadHistory(declared, exact(pinned));
            assertEquals(List.of(FIRST, SECOND), result.records());
            assertEquals(3, journal.appendedSequence());
            assertEquals(declared, result.graph().head());
            assertEquals(5, result.graph().head().revision());
            assertEquals(17, result.graph().manifest().generation());
            assertEquals(2, result.graph().actualPrefix().sequence());
            assertEquals(expectedReads(pinned), objects.reads);
            assertFalse(objects.reads.contains(manifestKey(head(newer))));
            assertTrue(objects.reads.stream().noneMatch(key -> key.value().contains("-head-") || key.value().endsWith("current.head")));
            assertReadersClosed(objects);
        }
    }

    @Test void immutableHistoryOutlivesClosedSourcesAndRetainsNoInputArraysOrCallerHandles() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST), List.of(SECOND)));
        var objects = objects(fixture);
        ObjectCheckpointGraphs.History result;
        try (var journal = journal(List.of(FIRST, SECOND))) {
            long forcedBytes = journal.forcedBytes();
            result = verifier(objects, journal).loadHistory(head(fixture), exact(fixture));
            assertFalse(objects.closed);
            assertEquals(0, objects.creates);
            assertReadersClosed(objects);
            objects.images.values().forEach(image -> Arrays.fill(image, (byte) 0));
            objects.close();
            journal.append(THIRD);
            assertEquals(3, journal.appendedSequence(), "history materialization does not take the journal owner");
            assertEquals(0, journal.forcedSequence());
            assertEquals(forcedBytes, journal.forcedBytes());
        }
        assertEquals(List.of(FIRST, SECOND), result.records());
        assertGraph(result.graph(), head(fixture), fixture);
        assertUsage(result.usage(), fixture);
        assertThrows(UnsupportedOperationException.class, () -> result.records().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.records().add(THIRD));
        assertThrows(UnsupportedOperationException.class, () -> result.records().getFirst().transaction().operations().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.graph().manifest().segments().clear());
    }

    @Test void materializationUsesExactlyTheMetadataVerifierBudgetAndOneReadPerDependency() throws IOException {
        var fixture = fixture(7, List.of(List.of(FIRST), List.of(SECOND, THIRD)));
        var objects = objects(fixture);
        try (var journal = journal(List.of(FIRST, SECOND, THIRD))) {
            var verifier = verifier(objects, journal);
            var metadata = verifier.verify(head(fixture), exact(fixture));
            assertEquals(expectedReads(fixture), objects.reads);
            objects.reads.clear();
            var history = verifier.loadHistory(head(fixture), exact(fixture));
            assertEquals(metadata.usage(), history.usage());
            assertEquals(metadata.graphs().getFirst().head(), history.graph().head());
            assertEquals(metadata.graphs().getFirst().actualPrefix(), history.graph().actualPrefix());
            assertEquals(expectedReads(fixture), objects.reads, "no post-verification fetch or decode pass");
            assertEquals(List.of(FIRST, SECOND, THIRD), history.records());
            history.records().forEach(record -> assertTrue(record.sequence() > 0));
            assertEquals(expectedReads(fixture), objects.reads, "using the immutable result performs no lazy reads");
            assertReadersClosed(objects);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"object-bytes", "journal-bytes", "record-work", "object-reads"})
    void headKnownLimitsRejectBeforeFetchingOrCollectingAnyRecord(String limited) throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST, SECOND)));
        var limits = new ObjectCheckpointGraphs.Limits(
                limited.equals("object-bytes") ? fixture.manifestImage().length - 1 : ObjectCheckpointGraphs.MAX_BYTES,
                limited.equals("journal-bytes") ? fixture.prefix().byteLength() - 1 : ObjectCheckpointGraphs.MAX_BYTES,
                limited.equals("record-work") ? 3 : ObjectCheckpointGraphs.MAX_RECORD_WORK,
                limited.equals("object-reads") ? 0 : ObjectCheckpointGraphs.MAX_OBJECT_READS);
        var objects = objects(fixture);
        try (var journal = journal(List.of(FIRST, SECOND))) {
            assertGraphFailure(Code.LIMIT_EXCEEDED, () -> verifier(objects, journal).loadHistory(head(fixture), limits));
            assertTrue(objects.reads.isEmpty());
            assertEquals(0, journal.forcedSequence());
            assertEquals(0, objects.creates);
            journal.append(THIRD);
            assertEquals(3, journal.appendedSequence());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"object-bytes", "object-reads"})
    void decodedManifestReservesAllSegmentsBeforeTheFirstSegmentFetch(String limited) throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST), List.of(SECOND)));
        var exact = exact(fixture);
        var limits = new ObjectCheckpointGraphs.Limits(exact.maxObjectBytes() - (limited.equals("object-bytes") ? 1 : 0),
                exact.maxJournalBytes(), exact.maxRecordWork(), exact.maxObjectReads() - (limited.equals("object-reads") ? 1 : 0));
        var objects = objects(fixture);
        try (var journal = journal(List.of(FIRST, SECOND))) {
            assertGraphFailure(Code.LIMIT_EXCEEDED, () -> verifier(objects, journal).loadHistory(head(fixture), limits));
            assertEquals(List.of(manifestKey(head(fixture))), objects.reads);
            assertEquals(0, journal.forcedSequence());
            assertEquals(0, objects.creates);
            assertReadersClosed(objects);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"namespace", "publisher", "lineage"})
    void foreignBindingsCannotMaterializeRecordsOrFetchObjects(String foreign) throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST)));
        var objects = objects(fixture);
        try (var journal = journal(foreign.equals("lineage") ? new DatabaseId("foreign") : DATABASE, List.of(FIRST))) {
            var verifier = new ObjectCheckpointGraphs(objects, journal,
                    foreign.equals("namespace") ? OTHER : NAMESPACE, foreign.equals("publisher") ? OTHER : PUBLISHER);
            assertEquals(ObjectCheckpointHeadException.Code.INVALID_BINDING,
                    assertThrows(ObjectCheckpointHeadException.class, () -> verifier.loadHistory(head(fixture), DEFAULTS)).code());
            assertTrue(objects.reads.isEmpty());
            assertEquals(0, journal.forcedSequence());
        }
    }

    @Test void copiedSameLineageDivergenceFailsAfterPrivateCollectionWithoutReturningHistory() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST), List.of(SECOND)));
        var objects = objects(fixture);
        var escaped = new AtomicReference<ObjectCheckpointGraphs.History>();
        var differentFirst = record(1, "r1", 999);
        try (var journal = journal(List.of(differentFirst, SECOND))) {
            assertGraphFailure(Code.JOURNAL_MISMATCH,
                    () -> escaped.set(verifier(objects, journal).loadHistory(head(fixture), exact(fixture))));
            assertNull(escaped.get());
            assertEquals(expectedReads(fixture), objects.reads, "all object records were privately checked before the owner-prefix mismatch");
            assertEquals(0, journal.forcedSequence());
            assertReadersClosed(objects);
            journal.append(THIRD);
            assertEquals(3, journal.appendedSequence());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"sequence", "encoded-bytes"})
    void aShorterActualOwnedPrefixCannotBeReplacedByRemoteRecords(String shorter) throws IOException {
        var actual = shorter.equals("sequence") ? List.of(FIRST) : List.of(FIRST, SECOND);
        var expectedSecond = shorter.equals("encoded-bytes") ? record(2, "r2-with-more-identity-bytes", 20) : SECOND;
        var fixture = fixture(2, List.of(List.of(FIRST, expectedSecond)));
        var objects = objects(fixture);
        var escaped = new AtomicReference<ObjectCheckpointGraphs.History>();
        try (var journal = journal(actual)) {
            assertGraphFailure(Code.JOURNAL_MISMATCH,
                    () -> escaped.set(verifier(objects, journal).loadHistory(head(fixture), DEFAULTS)));
            assertNull(escaped.get());
            assertEquals(shorter.equals("sequence") ? List.of() : expectedReads(fixture), objects.reads);
            assertEquals(0, journal.forcedSequence());
            assertReadersClosed(objects);
            assertEquals(actual.size(), journal.prefix(actual.size(), Long.MAX_VALUE).sequence());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "corrupt", "cleanup"})
    void aLateDependencyFailureDiscardsCollectedHistoryClosesReadersAndStopsFetching(String failure) throws IOException {
        var original = fixture(2, List.of(List.of(FIRST), List.of(SECOND), List.of(THIRD)));
        Fixture fixture = original;
        if (failure.equals("corrupt")) {
            byte[] corrupt = original.segments().get(original.manifest().segments().get(1).id()).clone();
            corrupt[corrupt.length - 1] ^= 1;
            fixture = replaceSegment(original, 1, corrupt);
        }
        var selected = fixture;
        var objects = objects(selected);
        ObjectKey lateKey = segmentKey(selected.manifest().segments().get(1).id());
        var cleanup = new IOException("adapter cleanup failed before transferring the verified image");
        if (failure.equals("missing")) { objects.images.remove(lateKey); }
        if (failure.equals("cleanup")) { objects.cleanupFailureKey = lateKey; objects.cleanupFailure = cleanup; }
        var escaped = new AtomicReference<ObjectCheckpointGraphs.History>();
        try (var journal = journal(List.of(FIRST, SECOND, THIRD))) {
            var thrown = assertThrows(IOException.class,
                    () -> escaped.set(verifier(objects, journal).loadHistory(head(selected), exact(selected))));
            if (failure.equals("missing")) { assertTrue(thrown instanceof NoSuchFileException); }
            if (failure.equals("corrupt")) {
                assertEquals(Code.INVALID_GRAPH, ((ObjectCheckpointGraphException) thrown).code());
                assertEquals(SegmentFormatException.Code.CORRUPT, ((SegmentFormatException) thrown.getCause()).code());
            }
            if (failure.equals("cleanup")) { assertSame(cleanup, thrown); }
            assertNull(escaped.get());
            assertEquals(List.of(manifestKey(head(selected)), segmentKey(selected.manifest().segments().getFirst().id()), lateKey), objects.reads);
            assertEquals(0, objects.creates);
            assertEquals(0, journal.forcedSequence());
            assertReadersClosed(objects);
            journal.append(record(4, "r4", 40));
            assertEquals(4, journal.appendedSequence());
        }
    }

    @Test void materializationUsesTheReservedHeaderRangeBeforeTheNestedPayloadTrap() throws IOException {
        var original = fixture(2, List.of(List.of(FIRST)));
        byte[] trapped = original.segments().values().iterator().next().clone();
        assertEquals(160, trapped.length);
        var bytes = ByteBuffer.wrap(trapped);
        bytes.putLong(48, 65_536).putInt(56, 65_536);
        bytes.putInt(68, crc(trapped, 0, 68));
        bytes.putInt(104, 65_537);
        bytes.putInt(124, crc(trapped, 88, 36));
        System.arraycopy(digest(trapped, 128), 0, trapped, 128, 32);
        assertEquals(1, bytes.getLong(40));
        assertEquals(65_536, bytes.getLong(48));
        assertEquals(65_536, bytes.getInt(56));
        assertEquals(crc(trapped, 0, 68), bytes.getInt(68));
        assertEquals(crc(trapped, 88, 36), bytes.getInt(124));
        assertArrayEquals(digest(trapped, 128), Arrays.copyOfRange(trapped, 128, 160));
        assertEquals(SegmentFormatException.Code.LIMIT_EXCEEDED,
                assertThrows(SegmentFormatException.class, () -> SegmentCodec.decode(trapped, new SegmentId(hash(trapped)))).code());
        var fixture = replaceSegment(original, 0, trapped);
        var objects = objects(fixture);
        try (var journal = journal(List.of(FIRST))) {
            var thrown = assertGraphFailure(Code.INVALID_GRAPH,
                    () -> verifier(objects, journal).loadHistory(head(fixture), exact(fixture)));
            assertEquals(SegmentFormatException.Code.INVALID_HISTORY, ((SegmentFormatException) thrown.getCause()).code());
            assertEquals(2, objects.reads.size());
            assertEquals(0, journal.forcedSequence());
            assertReadersClosed(objects);
        }
    }

    @Test void realLocalObjectAccessReturnsAnIndependentHistoryWithoutRetainingNativeHandles() throws IOException {
        var fixture = fixture(3, List.of(List.of(FIRST), List.of(SECOND)));
        Path objectDirectory = Files.createDirectory(directory.resolve("objects"));
        var objects = new LocalObjectStore(objectDirectory);
        objects.createNew(manifestKey(head(fixture)), fixture.manifestImage());
        for (var entry : fixture.segments().entrySet()) { objects.createNew(segmentKey(entry.getKey()), entry.getValue()); }
        ObjectCheckpointGraphs.History history;
        List<Path> paths;
        try (var stream = Files.list(objectDirectory)) { paths = stream.toList(); }
        assertEquals(3, paths.size());
        try (var journal = journal(List.of(FIRST, SECOND, THIRD))) {
            long forcedBytes = journal.forcedBytes();
            history = verifier(objects, journal).loadHistory(head(fixture), exact(fixture));
            assertEquals(List.of(FIRST, SECOND), history.records());
            assertEquals(0, journal.forcedSequence());
            assertEquals(forcedBytes, journal.forcedBytes());
        }
        for (var path : paths) { Files.delete(path); }
        assertEquals(List.of(FIRST, SECOND), history.records());
        assertUsage(history.usage(), fixture);
        assertGraph(history.graph(), head(fixture), fixture);
    }

    private LocalJournal journal(List<JournalRecord> records) throws IOException { return journal(DATABASE, records); }

    private LocalJournal journal(DatabaseId database, List<JournalRecord> records) throws IOException {
        var journal = LocalJournal.create(directory.resolve("journal-" + journalNumber++), database);
        try {
            for (var record : records) { journal.append(record); }
            return journal;
        } catch (IOException | RuntimeException | Error failure) {
            try { journal.close(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static ObjectCheckpointGraphs verifier(ImmutableObjectStore objects, LocalJournal journal) {
        return new ObjectCheckpointGraphs(objects, journal, NAMESPACE, PUBLISHER);
    }

    private static Fixture fixture(long generation, List<List<JournalRecord>> groups) {
        var references = new ArrayList<SegmentReference>();
        var segments = new LinkedHashMap<SegmentId, byte[]>();
        var journal = new ByteArrayOutputStream();
        journal.writeBytes(JournalCodec.encodeHeader(DATABASE));
        long count = 0;
        for (var group : groups) {
            var segment = new ImmutableSegment(DATABASE, group);
            byte[] image = SegmentCodec.encode(segment);
            var id = new SegmentId(hash(image));
            references.add(new SegmentReference(id, image.length, segment.firstSequence(), segment.lastSequence()));
            segments.put(id, image);
            for (var record : group) { journal.writeBytes(JournalCodec.encodeRecord(record)); count++; }
        }
        byte[] prefix = journal.toByteArray();
        var manifest = new CheckpointManifest(DATABASE, generation, new JournalPrefix(count, prefix.length, hash(prefix)), references);
        return new Fixture(manifest, ManifestCodec.encode(manifest), segments);
    }

    private static Fixture replaceSegment(Fixture original, int index, byte[] image) {
        var references = new ArrayList<>(original.manifest().segments());
        var reference = references.get(index);
        var id = new SegmentId(hash(image));
        references.set(index, new SegmentReference(id, image.length, reference.firstSequence(), reference.lastSequence()));
        var segments = new LinkedHashMap<>(original.segments());
        segments.remove(reference.id());
        segments.put(id, image);
        var manifest = new CheckpointManifest(DATABASE, original.manifest().generation(), original.prefix(), references);
        return new Fixture(manifest, ManifestCodec.encode(manifest), segments);
    }

    private record Fixture(CheckpointManifest manifest, byte[] manifestImage, Map<SegmentId, byte[]> segments) {
        JournalPrefix prefix() { return manifest.journalPrefix(); }
    }

    private static ObjectCheckpointHead head(Fixture fixture) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3), LINEAGE, 1,
                ObjectReference.forBytes(fixture.manifestImage()), fixture.prefix(), null);
    }

    private static ObjectKey manifestKey(ObjectCheckpointHead head) { return ObjectCheckpointGraphs.manifestKey(NAMESPACE, head.manifest().id()); }
    private static ObjectKey segmentKey(SegmentId id) { return ObjectCheckpointGraphs.segmentKey(NAMESPACE, id); }

    private static List<ObjectKey> expectedReads(Fixture fixture) {
        var keys = new ArrayList<ObjectKey>();
        keys.add(manifestKey(head(fixture)));
        fixture.manifest().segments().forEach(reference -> keys.add(segmentKey(reference.id())));
        return keys;
    }

    private static ObjectCheckpointGraphs.Limits exact(Fixture fixture) {
        long bytes = fixture.manifestImage().length + fixture.manifest().segments().stream().mapToLong(SegmentReference::byteLength).sum();
        return new ObjectCheckpointGraphs.Limits(bytes, fixture.prefix().byteLength(), 2 * fixture.prefix().sequence(),
                1 + fixture.manifest().segments().size());
    }

    private static void assertUsage(ObjectCheckpointGraphs.Usage usage, Fixture fixture) {
        var limits = exact(fixture);
        assertEquals(limits.maxObjectBytes(), usage.objectBytes());
        assertEquals(limits.maxJournalBytes(), usage.journalBytes());
        assertEquals(limits.maxRecordWork(), usage.recordWork());
        assertEquals(limits.maxObjectReads(), usage.objectReads());
        assertEquals(1, usage.journalReads());
    }

    private static void assertGraph(ObjectCheckpointGraphs.Graph graph, ObjectCheckpointHead head, Fixture fixture) {
        assertEquals(head, graph.head());
        assertEquals(fixture.manifest(), graph.manifest());
        assertEquals(head.journalPrefix().sequence(), graph.actualPrefix().sequence());
        assertEquals(head.journalPrefix().byteLength(), graph.actualPrefix().byteLength());
        assertEquals(head.journalPrefix().sha256(), graph.actualPrefix().sha256());
        assertEquals(ObjectCheckpointHeadCodec.reference(head), graph.headReference());
    }

    private static void assertReadersClosed(MapObjects objects) {
        for (var reader : objects.readers) { assertThrows(IllegalStateException.class, reader::reference); }
    }

    private static MapObjects objects(Fixture... fixtures) {
        var objects = new MapObjects();
        for (var fixture : fixtures) {
            objects.images.put(manifestKey(head(fixture)), fixture.manifestImage().clone());
            fixture.segments().forEach((id, image) -> objects.images.put(segmentKey(id), image.clone()));
        }
        return objects;
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(new EntityId("e"), new ValidTimeInterval(null, null), value))));
    }

    private static int crc(byte[] image, int offset, int length) {
        var checksum = new CRC32C();
        checksum.update(image, offset, length);
        return (int) checksum.getValue();
    }

    private static byte[] digest(byte[] image, int length) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(image, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
    private static String hash(byte[] image) { return HexFormat.of().formatHex(digest(image, image.length)); }

    @FunctionalInterface private interface Work { void run() throws IOException; }
    private static ObjectCheckpointGraphException assertGraphFailure(Code code, Work work) {
        var failure = assertThrows(ObjectCheckpointGraphException.class, work::run);
        assertEquals(code, failure.code());
        return failure;
    }

    private static final class MapObjects implements ImmutableObjectStore, AutoCloseable {
        private final Map<ObjectKey, byte[]> images = new HashMap<>();
        private final List<ObjectKey> reads = new ArrayList<>();
        private final List<VerifiedObject> readers = new ArrayList<>();
        private ObjectKey cleanupFailureKey;
        private IOException cleanupFailure;
        private int creates;
        private boolean closed;

        @Override public ObjectReference createNew(ObjectKey key, byte[] image) {
            creates++;
            throw new AssertionError("Read-only history materialization cannot create objects");
        }

        @Override public VerifiedObject read(ObjectKey key, ObjectReference expected) throws IOException {
            if (closed) { throw new IOException("Fixture object source is closed"); }
            reads.add(key);
            byte[] image = images.get(key);
            if (image == null) { throw new NoSuchFileException(key.value()); }
            var reader = VerifiedObject.verify(image, expected);
            readers.add(reader);
            if (key.equals(cleanupFailureKey)) {
                reader.close(); // Adapter cleanup can fail before an otherwise valid heap snapshot is transferred.
                throw cleanupFailure;
            }
            return reader;
        }

        @Override public void close() { closed = true; images.clear(); }
    }
}
