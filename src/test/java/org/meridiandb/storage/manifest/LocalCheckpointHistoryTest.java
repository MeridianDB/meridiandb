// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

class LocalCheckpointHistoryTest {
    private static final DatabaseId DATABASE = new DatabaseId("history-db");
    private static final EntityId ENTITY = new EntityId("salary");
    private static final ValidTimeInterval WINDOW = new ValidTimeInterval(0L, 4L);
    private static final List<JournalRecord> RECORDS = List.of(
            new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(new WriteOperation.Put(ENTITY, WINDOW, 10)))),
            new JournalRecord(2, new Transaction(new RequestId("new-noop"), List.of(new WriteOperation.Put(ENTITY, WINDOW, 10)))),
            new JournalRecord(3, new Transaction(new RequestId("remove"), List.of(new WriteOperation.Retract(ENTITY, WINDOW)))));

    @TempDir
    Path directory;

    @Test
    void onePassReturnsLiteralOriginalHistoryWithExactEncodedUsageAndNoContinuingResources() throws IOException {
        var fixture = fixture(RECORDS);
        LocalCheckpointHistory result;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            result = store.loadLatestHistory(fixture.journal, exact(fixture)).orElseThrow();
            assertEquals(fixture.id(), result.manifestId());
            assertEquals(fixture.manifest, result.manifest());
            assertEquals(prefix(RECORDS), result.actualPrefix());
            assertEquals(RECORDS, result.records(), "Original no-op identity and retraction remain in complete history");
            // V1: 40-byte lineage; 92/94/82-byte frames; 264-byte manifest; 306/202-byte segments.
            assertEquals(new LocalCheckpointHistory.Usage(772, 308, 6, 3), result.usage());
            assertEquals(List.of(new Read(fixture.name(), 772), new Read(fixture.segmentNames.getFirst(), 306),
                    new Read(fixture.segmentNames.getLast(), 202)), fixture.namespace.reads);
            assertEquals(List.of(new PrefixCall(3, 308)), fixture.journal.boundedCalls);
            assertEquals(0, fixture.journal.unboundedCalls);
            assertEquals(0, fixture.namespace.readerAcquires);
            assertEquals(0, fixture.namespace.protectionCloses);
            assertEquals(directory.resolve(LocalCheckpointStore.JOURNAL), store.journalPath());
            assertThrows(UnsupportedOperationException.class, () -> result.records().clear());
            assertThrows(UnsupportedOperationException.class, () -> result.records().getFirst().transaction().operations().clear());
        }
        fixture.namespace.files.values().forEach(image -> Arrays.fill(image, (byte) 0));
        assertEquals(RECORDS, result.records(), "Heap history survives owner close and source-image changes");
        assertEquals(1, fixture.namespace.closes);
    }

    @Test
    void absentSelectedManifestAcceptsZeroBudgetsWithoutReadingObjectsOrJournal() throws IOException {
        var namespace = new MemoryNamespace(directory);
        namespace.files.put("unselected.segment", new byte[] {1});
        namespace.files.put("notes.txt", new byte[] {2});
        var journal = new MemoryJournal(RECORDS);
        try (var store = new LocalCheckpointStore(namespace)) {
            assertTrue(store.loadLatestHistory(journal, new LocalCheckpointHistory.Limits(0, 0, 0)).isEmpty());
            assertTrue(namespace.reads.isEmpty());
            assertTrue(journal.boundedCalls.isEmpty());
            assertEquals(0, journal.unboundedCalls);
            assertEquals(0, namespace.readerAcquires);
            assertEquals(directory.resolve(LocalCheckpointStore.JOURNAL), store.journalPath());
        }
    }

    @Test
    void genesisUsesOnlyMetadataAndTheExactHeaderPrefixWithZeroRecordWork() throws IOException {
        var fixture = fixture(List.of());
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var result = store.loadLatestHistory(fixture.journal, new LocalCheckpointHistory.Limits(152, 40, 0)).orElseThrow();
            assertTrue(result.records().isEmpty());
            assertEquals(new LocalCheckpointHistory.Usage(152, 40, 0, 1), result.usage());
            assertEquals(List.of(new Read(fixture.name(), 152)), fixture.namespace.reads);
            assertEquals(List.of(new PrefixCall(0, 40)), fixture.journal.boundedCalls);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"objects", "journal", "records"})
    void eachOffByOneAggregateBudgetRejectsBeforeAnySegmentOrActualPrefixRead(String budget) throws IOException {
        var fixture = fixture(RECORDS);
        var limits = new LocalCheckpointHistory.Limits(budget.equals("objects") ? 771 : 772,
                budget.equals("journal") ? 307 : 308, budget.equals("records") ? 5 : 6);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.loadLatestHistory(fixture.journal, limits));
            assertEquals(List.of(new Read(fixture.name(), Math.toIntExact(limits.maxObjectBytes()))), fixture.namespace.reads);
            assertTrue(fixture.journal.boundedCalls.isEmpty());
            assertPoisoned(store, fixture);
        }
    }

    @Test
    void metadataAndStoredSegmentsCannotReadPastTheirIndividualCaps() throws IOException {
        var metadata = fixture(RECORDS);
        try (var store = new LocalCheckpointStore(metadata.namespace)) {
            assertThrows(IOException.class, () -> store.loadLatestHistory(metadata.journal,
                    new LocalCheckpointHistory.Limits(263, 308, 6)));
            assertEquals(List.of(new Read(metadata.name(), 263)), metadata.namespace.reads);
            assertTrue(metadata.journal.boundedCalls.isEmpty());
            assertPoisoned(store, metadata);
        }
        var segment = fixture(RECORDS);
        String name = segment.segmentNames.getLast();
        segment.namespace.files.put(name, Arrays.copyOf(segment.namespace.files.get(name), 203));
        try (var store = new LocalCheckpointStore(segment.namespace)) {
            assertThrows(IOException.class, () -> store.loadLatestHistory(segment.journal, exact(segment)));
            assertEquals(new Read(name, 202), segment.namespace.reads.getLast());
            assertTrue(segment.journal.boundedCalls.isEmpty());
            assertPoisoned(store, segment);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata", "segment"})
    void failedGrowthProbeCanReturnOnlyOneExtraByteAndClosesWithoutExposingHistory(String object) throws IOException {
        var fixture = fixture(RECORDS);
        fixture.namespace.growthName = object.equals("metadata") ? fixture.name() : fixture.segmentNames.getLast();
        int admittedBytes = object.equals("metadata") ? 264 : 202;
        var limits = new LocalCheckpointHistory.Limits(object.equals("metadata") ? 264 : 772, 308, 6);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var failure = assertThrows(IOException.class, () -> store.loadLatestHistory(fixture.journal, limits));
            assertTrue(failure.getMessage().contains("changed"), "Positive EOF probe proves unexpected growth");
            var input = fixture.namespace.opened.getLast();
            assertEquals(admittedBytes, input.image.length);
            assertEquals(object.equals("metadata") ? 265 : 203, input.returnedBytes);
            assertEquals(admittedBytes + 1L, input.returnedBytes, "Failed EOF validation returns one bounded extra byte");
            assertEquals(1, input.closes);
            assertTrue(fixture.journal.boundedCalls.isEmpty());
            assertEquals(0, fixture.journal.unboundedCalls);
            assertPoisoned(store, fixture);
        }
    }

    @Test
    void nullAndOutOfRangeLimitsFailBeforeStorageEffectsAndLeaveTheStoreUsable() throws IOException {
        var fixture = fixture(RECORDS);
        assertEquals(64L * 1024 * 1024, LocalCheckpointHistory.MAX_OBJECT_BYTES);
        assertEquals(64L * 1024 * 1024, LocalCheckpointHistory.MAX_JOURNAL_BYTES);
        assertEquals(262_144, LocalCheckpointHistory.MAX_RECORD_WORK);
        for (long[] invalid : List.of(new long[] {-1, 0, 0}, new long[] {0, -1, 0}, new long[] {0, 0, -1},
                new long[] {LocalCheckpointHistory.MAX_OBJECT_BYTES + 1, 0, 0},
                new long[] {0, LocalCheckpointHistory.MAX_JOURNAL_BYTES + 1, 0},
                new long[] {0, 0, LocalCheckpointHistory.MAX_RECORD_WORK + 1})) {
            assertThrows(IllegalArgumentException.class, () -> new LocalCheckpointHistory.Limits(invalid[0], invalid[1], invalid[2]));
        }
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(NullPointerException.class, () -> store.loadLatestHistory((LocalCheckpointStore.BoundedJournalAccess) null, exact(fixture)));
            assertThrows(NullPointerException.class, () -> store.loadLatestHistory(fixture.journal, null));
            assertTrue(fixture.namespace.reads.isEmpty());
            assertEquals(0, fixture.namespace.enumerations);
            assertEquals(RECORDS, store.loadLatestHistory(fixture.journal, exact(fixture)).orElseThrow().records());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "duplicate-generation", "filename-generation"})
    void strictSelectedNamesAndMetadataGenerationAreRequired(String defect) throws IOException {
        var fixture = fixture(RECORDS);
        switch (defect) {
            case "malformed" -> fixture.namespace.extraNames.add("checkpoint-broken.manifest");
            case "duplicate-generation" -> fixture.namespace.select(manifest(7, List.of(), List.of()));
            case "filename-generation" -> fixture.namespace.files.put(LocalCheckpointStore.generationName(8, fixture.id()),
                    fixture.namespace.files.remove(fixture.name()));
            default -> throw new AssertionError(defect);
        }
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.loadLatestHistory(fixture.journal, LocalCheckpointHistory.Limits.DEFAULT));
            assertEquals(defect.equals("filename-generation") ? 1 : 0, fixture.namespace.reads.size());
            assertTrue(fixture.journal.boundedCalls.isEmpty());
            assertPoisoned(store, fixture);
        }
    }

    @Test
    void corruptNewestMetadataFailsWithoutReadingOrFallingBackToAnOlderValidGeneration() throws IOException {
        var fixture = fixture(RECORDS);
        String old = fixture.namespace.select(manifest(2, List.of(), List.of()));
        fixture.namespace.files.get(fixture.name())[0] ^= 1;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(ManifestFormatException.class, () -> store.loadLatestHistory(fixture.journal, LocalCheckpointHistory.Limits.DEFAULT));
            assertEquals(List.of(new Read(fixture.name(), ManifestCodec.MAX_MANIFEST_BYTES)), fixture.namespace.reads);
            assertFalse(fixture.namespace.reads.stream().anyMatch(read -> read.name().equals(old)));
            assertPoisoned(store, fixture);
        }
    }

    @Test
    void foreignJournalLineageRejectsBeforeGraphFetchEvenWithInsufficientBudgets() throws IOException {
        var fixture = fixture(RECORDS);
        fixture.journal.database = new DatabaseId("foreign");
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var failure = assertThrows(IOException.class, () -> store.loadLatestHistory(fixture.journal,
                    new LocalCheckpointHistory.Limits(264, 0, 0)));
            assertTrue(failure.getMessage().contains("lineage"));
            assertEquals(List.of(new Read(fixture.name(), 264)), fixture.namespace.reads);
            assertTrue(fixture.journal.boundedCalls.isEmpty());
            assertPoisoned(store, fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"payload", "footer"})
    void unreservedHeaderRangeRejectsBeforeItsIndependentPayloadOrFooterTrap(String trap) throws IOException {
        byte[] image = trappedSegment(trap);
        var reference = new SegmentReference(new SegmentId(hash(image)), image.length, 1, 1);
        var namespace = new MemoryNamespace(directory);
        namespace.files.put(LocalCheckpointStore.segmentName(reference.id()), image);
        var declared = manifest(7, List.of(RECORDS.getFirst()), List.of(reference));
        String selected = namespace.select(declared);
        var journal = new MemoryJournal(List.of(RECORDS.getFirst()));
        var raw = assertThrows(SegmentFormatException.class, () -> SegmentCodec.decode(image));
        assertEquals(trap.equals("payload") ? SegmentFormatException.Code.LIMIT_EXCEEDED : SegmentFormatException.Code.CORRUPT,
                raw.code(), "The independent trap is observable without bounded range admission");
        try (var store = new LocalCheckpointStore(namespace)) {
            var failure = assertThrows(SegmentFormatException.class,
                    () -> store.loadLatestHistory(journal, LocalCheckpointHistory.Limits.DEFAULT));
            assertEquals(SegmentFormatException.Code.INVALID_HISTORY, failure.code());
            assertTrue(failure.getMessage().contains("expected sequence range"));
            assertEquals(List.of(new Read(selected, ManifestCodec.MAX_MANIFEST_BYTES),
                    new Read(LocalCheckpointStore.segmentName(reference.id()), image.length)), namespace.reads);
            assertTrue(journal.boundedCalls.isEmpty());
            assertThrows(IOException.class, store::journalPath);
        }
    }

    @Test
    void duplicateRequestIdentityAcrossSegmentsCannotExposeAnEarlierCollectedPrefix() throws IOException {
        var repeated = new JournalRecord(3, RECORDS.getFirst().transaction());
        var fixture = fixture(List.of(RECORDS.get(0), RECORDS.get(1), repeated));
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var failure = assertThrows(ManifestFormatException.class,
                    () -> store.loadLatestHistory(fixture.journal, LocalCheckpointHistory.Limits.DEFAULT));
            assertEquals(ManifestFormatException.Code.INVALID_CHECKPOINT, failure.code());
            assertEquals(3, fixture.namespace.reads.size());
            assertTrue(fixture.journal.boundedCalls.isEmpty());
            assertPoisoned(store, fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "length", "sequence"})
    void completeGraphStillRequiresTheExactActualOwnerPrefix(String mismatch) throws IOException {
        var fixture = fixture(RECORDS);
        fixture.journal.mismatch = mismatch;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.loadLatestHistory(fixture.journal, LocalCheckpointHistory.Limits.DEFAULT));
            assertEquals(3, fixture.namespace.reads.size());
            assertEquals(List.of(new PrefixCall(3, 308)), fixture.journal.boundedCalls);
            assertEquals(0, fixture.journal.unboundedCalls);
            assertPoisoned(store, fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void lateObjectCloseFailurePropagatesUnchangedAndPoisonsWithoutActualPrefixOrResult(String kind) throws IOException {
        var fixture = fixture(RECORDS);
        Throwable failure = switch (kind) {
            case "io" -> new IOException("Native close uncertain");
            case "runtime" -> new IllegalStateException("Native close uncertain");
            case "error" -> new AssertionError("Native close uncertain");
            default -> throw new AssertionError(kind);
        };
        fixture.namespace.closeFailureName = fixture.segmentNames.getLast();
        fixture.namespace.objectCloseFailure = failure;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertSame(failure, assertThrows(failure.getClass(),
                    () -> store.loadLatestHistory(fixture.journal, LocalCheckpointHistory.Limits.DEFAULT)));
            assertEquals(3, fixture.namespace.reads.size());
            assertEquals(1, fixture.namespace.opened.getLast().closes);
            assertTrue(fixture.journal.boundedCalls.isEmpty());
            assertPoisoned(store, fixture);
        }
    }

    @Test
    void repeatedAcquisitionRereadsEveryObjectAndRejectsLateMutationWithoutPersistentCache() throws IOException {
        var fixture = fixture(RECORDS);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var first = store.loadLatestHistory(fixture.journal, exact(fixture)).orElseThrow();
            var second = store.loadLatestHistory(fixture.journal, LocalCheckpointHistory.Limits.DEFAULT).orElseThrow();
            assertEquals(first.records(), second.records());
            assertEquals(6, fixture.namespace.reads.size());
            for (String name : List.of(fixture.name(), fixture.segmentNames.getFirst(), fixture.segmentNames.getLast())) {
                assertEquals(2, fixture.namespace.reads.stream().filter(read -> read.name().equals(name)).count());
            }
            assertEquals(List.of(new PrefixCall(3, 308), new PrefixCall(3, 308)), fixture.journal.boundedCalls);
            assertEquals(0, fixture.namespace.readerAcquires);
            byte[] late = fixture.namespace.files.get(fixture.segmentNames.getLast());
            late[late.length - 1] ^= 1;
            var failure = assertThrows(SegmentFormatException.class,
                    () -> store.loadLatestHistory(fixture.journal, LocalCheckpointHistory.Limits.DEFAULT));
            assertEquals(SegmentFormatException.Code.CORRUPT, failure.code());
            assertEquals(9, fixture.namespace.reads.size(), "Late mutation is read after the earlier private records");
            assertEquals(2, fixture.journal.boundedCalls.size(), "Failed graph never reaches actual-prefix observation");
            assertEquals(RECORDS, first.records());
            assertPoisoned(store, fixture);
        }
    }

    @Test
    void ordinaryReadLatestKeepsItsIndependentVerificationReadsAndReaderProtection() throws IOException {
        var fixture = fixture(RECORDS);
        try (var store = new LocalCheckpointStore(fixture.namespace);
             var first = store.readLatest(fixture.journal).orElseThrow();
             var second = store.readLatest(fixture.journal).orElseThrow()) {
            assertEquals(fixture.manifest, first.manifest());
            assertEquals(first.manifestId(), second.manifestId());
            assertEquals(6, fixture.namespace.reads.size());
            assertEquals(2, fixture.journal.unboundedCalls);
            assertTrue(fixture.journal.boundedCalls.isEmpty());
            assertEquals(2, fixture.namespace.readerAcquires);
        }
        assertEquals(2, fixture.namespace.protectionCloses);
    }

    @Test
    void reentrantReadAndCloseAreRejectedInsideAcquisitionWhileTheOuterCallSucceeds() throws IOException {
        var fixture = fixture(RECORDS);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            fixture.namespace.readHook = () -> {
                assertThrows(IOException.class, store::close);
                assertThrows(IOException.class, store::journalPath);
                assertThrows(IOException.class, () -> store.loadLatestHistory(fixture.journal, LocalCheckpointHistory.Limits.DEFAULT));
            };
            assertEquals(RECORDS, store.loadLatestHistory(fixture.journal, exact(fixture)).orElseThrow().records());
        }
    }

    private Fixture fixture(List<JournalRecord> records) {
        var namespace = new MemoryNamespace(directory);
        var references = new ArrayList<SegmentReference>();
        if (!records.isEmpty()) {
            int split = Math.min(2, records.size());
            references.add(segment(namespace, records.subList(0, split)));
            if (split < records.size()) { references.add(segment(namespace, records.subList(split, records.size()))); }
        }
        var manifest = manifest(7, records, references);
        namespace.select(manifest);
        return new Fixture(namespace, new MemoryJournal(records), manifest,
                references.stream().map(reference -> LocalCheckpointStore.segmentName(reference.id())).toList());
    }

    private static SegmentReference segment(MemoryNamespace namespace, List<JournalRecord> records) {
        var segment = new ImmutableSegment(DATABASE, records);
        byte[] image = SegmentCodec.encode(segment);
        var id = new SegmentId(hash(image));
        namespace.files.put(LocalCheckpointStore.segmentName(id), image);
        return new SegmentReference(id, image.length, segment.firstSequence(), segment.lastSequence());
    }

    private static CheckpointManifest manifest(long generation, List<JournalRecord> records, List<SegmentReference> references) {
        var prefix = prefix(records);
        return new CheckpointManifest(DATABASE, generation,
                new JournalPrefix(prefix.sequence(), prefix.byteLength(), prefix.sha256()), references);
    }

    private static LocalJournal.Prefix prefix(List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(DATABASE));
        records.forEach(record -> bytes.writeBytes(JournalCodec.encodeRecord(record)));
        return new LocalJournal.Prefix(records.size(), bytes.size(), hash(bytes.toByteArray()));
    }

    private static LocalCheckpointHistory.Limits exact(Fixture fixture) {
        long bytes = fixture.namespace.files.get(fixture.name()).length
                + fixture.manifest.segments().stream().mapToLong(SegmentReference::byteLength).sum();
        return new LocalCheckpointHistory.Limits(bytes, fixture.manifest.journalPrefix().byteLength(),
                2 * fixture.manifest.journalPrefix().sequence());
    }

    private static void assertPoisoned(LocalCheckpointStore store, Fixture fixture) {
        int reads = fixture.namespace.reads.size();
        assertThrows(IOException.class, store::journalPath);
        assertThrows(IOException.class, () -> store.loadLatestHistory(fixture.journal, LocalCheckpointHistory.Limits.DEFAULT));
        assertEquals(reads, fixture.namespace.reads.size());
    }

    private static byte[] trappedSegment(String trap) {
        byte[] image = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(RECORDS.getFirst())));
        int lineageBytes = JournalCodec.encodeHeader(DATABASE).length;
        int headerBytes = 48 + lineageBytes;
        var buffer = ByteBuffer.wrap(image);
        buffer.putLong(16 + lineageBytes + 8, SegmentCodec.MAX_RECORDS);
        buffer.putInt(16 + lineageBytes + 16, SegmentCodec.MAX_RECORDS);
        buffer.putInt(headerBytes - 4, crc(image, 0, headerBytes - 4));
        if (trap.equals("payload")) {
            buffer.putInt(headerBytes + 8, JournalCodec.MAX_PAYLOAD_BYTES + 1);
            buffer.putInt(headerBytes + 12, crc(image, headerBytes, 12));
            var digest = ManifestCodec.sha256();
            digest.update(image, 0, image.length - 32);
            System.arraycopy(digest.digest(), 0, image, image.length - 32, 32);
        } else { image[image.length - 1] ^= 1; }
        return image;
    }

    private static int crc(byte[] image, int offset, int length) {
        var crc = new CRC32C();
        crc.update(image, offset, length);
        return (int) crc.getValue();
    }

    private static String hash(byte[] image) { return HexFormat.of().formatHex(ManifestCodec.sha256().digest(image)); }
    private record Read(String name, int maxBytes) { }
    private record PrefixCall(long sequence, long maxBytes) { }
    private record Fixture(MemoryNamespace namespace, MemoryJournal journal, CheckpointManifest manifest, List<String> segmentNames) {
        ManifestId id() { return ManifestCodec.decode(ManifestCodec.encode(manifest)).id(); }
        String name() { return LocalCheckpointStore.generationName(manifest.generation(), id()); }
    }

    private static final class MemoryJournal implements LocalCheckpointStore.BoundedJournalAccess {
        final List<JournalRecord> records;
        final List<PrefixCall> boundedCalls = new ArrayList<>();
        DatabaseId database = DATABASE;
        String mismatch = "";
        int unboundedCalls;
        MemoryJournal(List<JournalRecord> records) { this.records = List.copyOf(records); }
        @Override public DatabaseId databaseId() { return database; }
        @Override public LocalJournal.Prefix prefix(long sequence) {
            unboundedCalls++;
            return LocalCheckpointHistoryTest.prefix(records.subList(0, Math.toIntExact(sequence)));
        }
        @Override public LocalJournal.Prefix prefix(long sequence, long maxBytes) throws IOException {
            boundedCalls.add(new PrefixCall(sequence, maxBytes));
            var actual = LocalCheckpointHistoryTest.prefix(records.subList(0, Math.toIntExact(sequence)));
            if (actual.byteLength() > maxBytes) { throw new IOException("Actual prefix exceeds bound"); }
            return switch (mismatch) {
                case "hash" -> new LocalJournal.Prefix(sequence, actual.byteLength(), "0".repeat(64));
                case "length" -> new LocalJournal.Prefix(sequence, actual.byteLength() - 1, actual.sha256());
                case "sequence" -> new LocalJournal.Prefix(sequence + 1, actual.byteLength(), actual.sha256());
                default -> actual;
            };
        }
        @Override public void force() { throw new AssertionError("History acquisition must not force or publish"); }
    }

    private static final class MemoryNamespace implements LocalCheckpointStore.Namespace {
        final Path directory;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<String> extraNames = new ArrayList<>();
        final List<Read> reads = new ArrayList<>();
        final List<MemoryFile> opened = new ArrayList<>();
        String closeFailureName;
        String growthName;
        Throwable objectCloseFailure;
        Runnable readHook;
        int enumerations;
        int readerAcquires;
        int protectionCloses;
        int closes;
        MemoryNamespace(Path directory) { this.directory = directory; }
        @Override public Path directory() { return directory; }
        @Override public List<String> entries() {
            enumerations++;
            var entries = new ArrayList<>(files.keySet());
            entries.addAll(extraNames);
            return entries.reversed();
        }
        @Override public byte[] read(String name, int maxBytes) throws IOException {
            reads.add(new Read(name, maxBytes));
            if (readHook != null) { readHook.run(); }
            byte[] image = files.get(name);
            if (image == null) { throw new IOException("Missing object"); }
            var file = new MemoryFile(image, name.equals(closeFailureName) ? objectCloseFailure : null, name.equals(growthName));
            opened.add(file);
            return LocalCheckpointStore.read(file, maxBytes);
        }
        @Override public LocalReaderBarrier.Protection acquireReader() {
            readerAcquires++;
            return new LocalReaderBarrier.Protection() {
                @Override public void checkValid() { }
                @Override public void close() { protectionCloses++; }
            };
        }
        @Override public Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() { throw new AssertionError("Heap acquisition needs no barrier"); }
        @Override public void checkObjectAliases(String name, List<String> entries) { }
        @Override public void writeNew(String name, byte[] image) { throw new AssertionError("Unexpected write"); }
        @Override public void forceFile(String name) { throw new AssertionError("Unexpected force"); }
        @Override public void atomicMove(String from, String to) { throw new AssertionError("Unexpected rename"); }
        @Override public void delete(String name) { throw new AssertionError("Unexpected deletion"); }
        @Override public void forceDirectory() { throw new AssertionError("Unexpected directory force"); }
        @Override public void close() { closes++; }
        String select(CheckpointManifest manifest) {
            byte[] image = ManifestCodec.encode(manifest);
            String name = LocalCheckpointStore.generationName(manifest.generation(), ManifestCodec.decode(image).id());
            files.put(name, image);
            return name;
        }
    }

    private static final class MemoryFile implements LocalCheckpointStore.FileAccess {
        final byte[] image;
        final Throwable closeFailure;
        final boolean probeGrowth;
        boolean growthReturned;
        long returnedBytes;
        int closes;
        MemoryFile(byte[] image, Throwable closeFailure, boolean probeGrowth) {
            this.image = image.clone();
            this.closeFailure = closeFailure;
            this.probeGrowth = probeGrowth;
        }
        @Override public long size() { return image.length; }
        @Override public int read(ByteBuffer target, long offset) {
            if (offset >= image.length) {
                if (probeGrowth && !growthReturned && offset == image.length) {
                    growthReturned = true;
                    target.put((byte) 1);
                    returnedBytes++;
                    return 1;
                }
                return -1;
            }
            int count = Math.min(target.remaining(), image.length - Math.toIntExact(offset));
            target.put(image, Math.toIntExact(offset), count);
            returnedBytes += count;
            return count;
        }
        @Override public int write(ByteBuffer source, long offset) { throw new AssertionError("Unexpected write"); }
        @Override public void force() { throw new AssertionError("Unexpected force"); }
        @Override public void close() throws IOException {
            closes++;
            if (closeFailure instanceof IOException io) { throw io; }
            if (closeFailure instanceof RuntimeException runtime) { throw runtime; }
            if (closeFailure instanceof Error error) { throw error; }
        }
    }
}
