// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
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

class HistoryCompactorTest {
    private static final DatabaseId DATABASE = new DatabaseId("compact-db");
    private static final EntityId ENTITY = new EntityId("e");
    private static final long TOTAL_BYTES = 64L * 1024 * 1024;
    private static final long TOTAL_RECORDS = 262_144;
    private static final String ZERO_HASH = "0".repeat(64);

    @Test
    void exactHistoryIncludesNoopsRetractionsOriginalOperationOrderAndDistinctUnicodeIdentities() throws IOException {
        String exact = "A\u0000é" + (char) 0xd800 + (char) 0xdc00;
        var history = List.of(
                record(1, "é", 100),
                new JournalRecord(2, new Transaction(new RequestId("e" + (char) 0x0301), List.of(
                        new WriteOperation.Put(ENTITY, new ValidTimeInterval(10L, 20L), 120)))),
                new JournalRecord(3, new Transaction(new RequestId("retract"), List.of(
                        new WriteOperation.Retract(ENTITY, new ValidTimeInterval(12L, 14L))))),
                new JournalRecord(4, new Transaction(new RequestId("same-value"), List.of(
                        new WriteOperation.Put(ENTITY, new ValidTimeInterval(10L, 12L), 120)))),
                new JournalRecord(5, new Transaction(new RequestId("absent-no-op"), List.of(
                        new WriteOperation.Retract(new EntityId("missing"), new ValidTimeInterval(null, null))))),
                new JournalRecord(6, new Transaction(new RequestId(exact), List.of(
                        new WriteOperation.Put(new EntityId(exact), new ValidTimeInterval(null, null), Long.MIN_VALUE),
                        new WriteOperation.Retract(new EntityId(exact), new ValidTimeInterval(Long.MIN_VALUE, Long.MAX_VALUE)),
                        new WriteOperation.Put(new EntityId(exact), new ValidTimeInterval(Long.MAX_VALUE, null), Long.MAX_VALUE)))));
        var input = bundle(DATABASE, 7, List.of(history.subList(0, 2), history.subList(2, 3), history.subList(3, 6)));
        var calls = new ArrayList<SegmentId>();
        var plan = HistoryCompactor.compact(input.manifest(), id -> {
            calls.add(id);
            return input.images().get(id);
        }, HistoryCompactor.Limits.DEFAULT);
        assertEquals(input.manifest().segments().stream().map(SegmentReference::id).toList(), calls);
        assertEquals(1, plan.segments().size());
        assertEquals(history, flattened(plan));
        assertEquals(input.manifest(), plan.sourceManifest());
        assertEquals(ManifestCodec.decode(ManifestCodec.encode(input.manifest())).id(), plan.sourceManifestId());
        assertEquals(8, plan.replacementManifest().generation());
        assertEquals(DATABASE, plan.replacementManifest().databaseId());
        assertEquals(input.manifest().journalPrefix(), plan.replacementManifest().journalPrefix());
        for (int index = 0; index < history.size(); index++) {
            assertArrayEquals(JournalCodec.encodeRecord(history.get(index)),
                    JournalCodec.encodeRecord(flattened(plan).get(index)));
        }
        assertPlanEncoding(plan);
    }

    @Test
    void knownV1ByteSizeIncludesLineageHeadersAndIntegrityFooter() {
        var one = new ImmutableSegment(DATABASE, List.of(record(1, "r1", 1)));
        var two = new ImmutableSegment(DATABASE, List.of(record(1, "r1", 1), record(2, "r2", 2)));
        // V1 contributes 120 segment/header/footer bytes and 56 bytes per small transaction.
        assertEquals(176, SegmentCodec.encodedBytes(one));
        assertEquals(232, SegmentCodec.encodedBytes(two));
        assertEquals(176, SegmentCodec.encode(one).length);
        assertEquals(232, SegmentCodec.encode(two).length);
    }

    @Test
    void exactPreflightCountsUtf16LineageCodeUnitsWithoutUnicodeNormalization() {
        var lineage = new DatabaseId("A\u0000é" + (char) 0xd800 + (char) 0x0301);
        assertEquals(5, lineage.value().length());
        var one = new ImmutableSegment(lineage, List.of(record(1, "r1", 1)));
        var two = new ImmutableSegment(lineage, List.of(record(1, "r1", 1), record(2, "r2", 2)));
        // Five UTF-16 code units give a 30-byte journal header, hence 110 bytes of segment overhead.
        assertEquals(166, SegmentCodec.encodedBytes(one));
        assertEquals(222, SegmentCodec.encodedBytes(two));
        assertEquals(166, SegmentCodec.encode(one).length);
        assertEquals(222, SegmentCodec.encode(two).length);
    }

    @ParameterizedTest
    @ValueSource(ints = {231, 232})
    void wholeEncodedByteBoundaryGivesHandKnownMaximalGroups(int target) throws IOException {
        var input = smallSingles(5);
        var plan = HistoryCompactor.compact(input.manifest(), input.images()::get, limits(target));
        var expected = target == 232 ? List.of(List.of(1L, 2L), List.of(3L, 4L), List.of(5L))
                : List.of(List.of(1L), List.of(2L), List.of(3L), List.of(4L), List.of(5L));
        assertEquals(expected, plan.segments().stream().map(segment -> segment.records().stream()
                .map(JournalRecord::sequence).toList()).toList());
        assertEquals(880, plan.inputBytes());
        assertEquals(target == 232 ? 640 : 880, plan.outputBytes());
        assertTrue(plan.segments().stream().allMatch(segment -> SegmentCodec.encodedBytes(segment) <= target));
        assertPlanEncoding(plan);
    }

    @Test
    void recordCountCapSplitsEvenWhenAllRecordsFitTheByteTarget() throws IOException {
        var records = new ArrayList<JournalRecord>();
        for (int sequence = 1; sequence <= SegmentCodec.MAX_RECORDS + 1; sequence++) {
            records.add(record(sequence, "request-" + sequence, sequence));
        }
        var input = bundle(DATABASE, 1, List.of(records.subList(0, 32_768), records.subList(32_768, records.size())));
        var plan = HistoryCompactor.compact(input.manifest(), input.images()::get, HistoryCompactor.Limits.DEFAULT);
        assertEquals(List.of(65_536, 1), plan.segments().stream().map(segment -> segment.records().size()).toList());
        assertEquals(1, plan.segments().getFirst().firstSequence());
        assertEquals(65_536, plan.segments().getFirst().lastSequence());
        assertEquals(65_537, plan.segments().getLast().firstSequence());
        assertEquals(records, flattened(plan));
        assertPlanEncoding(plan);
    }

    @Test
    void validGenesisAdvancesGenerationWithoutReadingOrProducingSegments() throws IOException {
        var input = bundle(DATABASE, 41, List.of());
        var plan = HistoryCompactor.compact(input.manifest(), ignored -> {
            throw new AssertionError("Genesis contains no segment references");
        }, new HistoryCompactor.Limits(1, 0, 0, 0));
        assertEquals(42, plan.replacementManifest().generation());
        assertEquals(input.manifest().journalPrefix(), plan.replacementManifest().journalPrefix());
        assertEquals(List.of(), plan.segments());
        assertEquals(List.of(), plan.replacementManifest().segments());
        assertEquals(0, plan.inputBytes());
        assertEquals(0, plan.outputBytes());
        assertPlanEncoding(plan);
    }

    @Test
    void limitsAcceptTheirExactEdgesAndRejectNegativeOrAboveHardCapValues() {
        var defaults = HistoryCompactor.Limits.DEFAULT;
        assertEquals(SegmentCodec.MAX_SEGMENT_BYTES, defaults.targetSegmentBytes());
        assertEquals(TOTAL_BYTES, defaults.maxInputBytes());
        assertEquals(TOTAL_BYTES, defaults.maxOutputBytes());
        assertEquals(TOTAL_RECORDS, defaults.maxRecords());
        new HistoryCompactor.Limits(1, 0, 0, 0);
        new HistoryCompactor.Limits(SegmentCodec.MAX_SEGMENT_BYTES, TOTAL_BYTES, TOTAL_BYTES, TOTAL_RECORDS);
        for (int target : new int[] {-1, 0, SegmentCodec.MAX_SEGMENT_BYTES + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new HistoryCompactor.Limits(target, 0, 0, 0));
        }
        for (long invalid : new long[] {-1, TOTAL_BYTES + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new HistoryCompactor.Limits(1, invalid, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new HistoryCompactor.Limits(1, 0, invalid, 0));
        }
        for (long invalid : new long[] {-1, TOTAL_RECORDS + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new HistoryCompactor.Limits(1, 0, 0, invalid));
        }
    }

    @Test
    void nullArgumentsAndExhaustedGenerationFailBeforeResolvingAnyObject() {
        var input = smallSingles(1);
        var calls = new AtomicInteger();
        CheckpointVerifier.SegmentSource untouched = ignored -> { calls.incrementAndGet(); return null; };
        assertThrows(NullPointerException.class, () -> HistoryCompactor.compact(null, untouched, HistoryCompactor.Limits.DEFAULT));
        assertThrows(NullPointerException.class, () -> HistoryCompactor.compact(input.manifest(), null, HistoryCompactor.Limits.DEFAULT));
        assertThrows(NullPointerException.class, () -> HistoryCompactor.compact(input.manifest(), untouched, null));
        var exhausted = new CheckpointManifest(DATABASE, Long.MAX_VALUE,
                input.manifest().journalPrefix(), input.manifest().segments());
        assertLimit(() -> HistoryCompactor.compact(exhausted, untouched, HistoryCompactor.Limits.DEFAULT));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"input", "records"})
    void declaredAggregateBudgetRejectsBeforeAnySourceCall(String budget) {
        var input = smallSingles(3);
        long bytes = input.images().values().stream().mapToLong(image -> image.length).sum();
        var limits = new HistoryCompactor.Limits(SegmentCodec.MAX_SEGMENT_BYTES,
                budget.equals("input") ? bytes - 1 : TOTAL_BYTES, TOTAL_BYTES,
                budget.equals("records") ? 2 : TOTAL_RECORDS);
        var calls = new AtomicInteger();
        assertLimit(() -> HistoryCompactor.compact(input.manifest(), ignored -> {
            calls.incrementAndGet();
            throw new AssertionError("Declared budget failure must precede retrieval");
        }, limits));
        assertEquals(0, calls.get());
    }

    @Test
    void defaultHardCapsRejectOversizedDeclaredTotalsWithoutAllocatingTheirObjects() {
        var bytes = new ArrayList<SegmentReference>();
        for (int index = 1; index <= 3; index++) {
            bytes.add(new SegmentReference(new SegmentId(Integer.toString(index).repeat(64)),
                    SegmentCodec.MAX_SEGMENT_BYTES, index, index));
        }
        var records = new ArrayList<SegmentReference>();
        for (int index = 1; index <= 5; index++) {
            records.add(new SegmentReference(new SegmentId(Integer.toString(index).repeat(64)),
                    100, (index - 1L) * SegmentCodec.MAX_RECORDS + 1, (long) index * SegmentCodec.MAX_RECORDS));
        }
        for (var manifest : List.of(
                new CheckpointManifest(DATABASE, 1, new JournalPrefix(3, 1000, ZERO_HASH), bytes),
                new CheckpointManifest(DATABASE, 1, new JournalPrefix(327_680, 1000, ZERO_HASH), records))) {
            var calls = new AtomicInteger();
            assertLimit(() -> HistoryCompactor.compact(manifest, ignored -> {
                calls.incrementAndGet();
                throw new AssertionError("Hard-cap declarations must fail before retrieval");
            }, HistoryCompactor.Limits.DEFAULT));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void exactInputOutputAndRecordBudgetsSucceedAndOneLessOutputByteFails() throws IOException {
        var input = smallSingles(3);
        var exact = new HistoryCompactor.Limits(SegmentCodec.MAX_SEGMENT_BYTES, 528, 288, 3);
        var plan = HistoryCompactor.compact(input.manifest(), input.images()::get, exact);
        assertEquals(528, plan.inputBytes());
        assertEquals(288, plan.outputBytes());
        assertEquals(3, flattened(plan).size());
        assertLimit(() -> HistoryCompactor.compact(input.manifest(), input.images()::get,
                new HistoryCompactor.Limits(SegmentCodec.MAX_SEGMENT_BYTES, 528, 287, 3)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 175})
    void indivisibleRecordCannotFitAnUndersizedTotalEncodedTarget(int target) {
        var input = smallSingles(1);
        assertLimit(() -> HistoryCompactor.compact(input.manifest(), input.images()::get, limits(target)));
    }

    @Test
    void outputManifestReferenceLimitRejectsAPlanThatWouldNeedTooManySmallGroups() {
        var records = new ArrayList<JournalRecord>();
        for (int sequence = 1; sequence <= ManifestCodec.MAX_SEGMENTS + 1; sequence++) {
            records.add(record(sequence, "r" + sequence, sequence));
        }
        var input = bundle(DATABASE, 1, List.of(records));
        var calls = new AtomicInteger();
        // Every complete record fits 200 bytes, while any pair exceeds it.
        assertLimit(() -> HistoryCompactor.compact(input.manifest(), id -> {
            calls.incrementAndGet();
            return input.images().get(id);
        }, limits(200)));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void missingReferencedObjectStopsWithoutReturningAnAcceptedPrefix(int missing) {
        var input = smallSingles(3);
        var calls = new ArrayList<SegmentId>();
        var missingId = input.manifest().segments().get(missing).id();
        assertInvalid(() -> HistoryCompactor.compact(input.manifest(), id -> {
            calls.add(id);
            return id.equals(missingId) ? null : input.images().get(id);
        }, HistoryCompactor.Limits.DEFAULT));
        assertEquals(input.manifest().segments().subList(0, missing + 1).stream().map(SegmentReference::id).toList(), calls);
    }

    @Test
    void sourceIoFailurePropagatesUnchangedAndStopsBeforeLaterReferences() {
        var input = smallSingles(3);
        var expected = new IOException("second segment unavailable");
        var calls = new ArrayList<SegmentId>();
        var second = input.manifest().segments().get(1).id();
        assertSame(expected, assertThrows(IOException.class, () -> HistoryCompactor.compact(input.manifest(), id -> {
            calls.add(id);
            if (id.equals(second)) { throw expected; }
            return input.images().get(id);
        }, HistoryCompactor.Limits.DEFAULT)));
        assertEquals(input.manifest().segments().subList(0, 2).stream().map(SegmentReference::id).toList(), calls);
    }

    @Test
    void corruptObjectFailsBeforeRetrievingLaterObjects() {
        var input = smallSingles(3);
        var damaged = input.images().get(input.manifest().segments().getFirst().id()).clone();
        damaged[damaged.length - 1] ^= 1;
        var calls = new AtomicInteger();
        var failure = assertThrows(SegmentFormatException.class, () -> HistoryCompactor.compact(input.manifest(), ignored -> {
            calls.incrementAndGet();
            return damaged;
        }, HistoryCompactor.Limits.DEFAULT));
        assertEquals(SegmentFormatException.Code.CORRUPT, failure.code());
        assertEquals(1, calls.get());
    }

    @Test
    void differentValidSameLengthObjectCannotSatisfyTheExpectedIdentifier() {
        var input = smallSingles(1);
        byte[] different = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(record(1, "r1", 2))));
        assertEquals(176, different.length);
        var failure = assertThrows(SegmentFormatException.class,
                () -> HistoryCompactor.compact(input.manifest(), ignored -> different, HistoryCompactor.Limits.DEFAULT));
        assertEquals(SegmentFormatException.Code.IDENTIFIER_MISMATCH, failure.code());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void declaredReferenceLengthMustMatchTheActualObject(int difference) {
        var input = smallSingles(1);
        var ref = input.manifest().segments().getFirst();
        var changed = new CheckpointManifest(DATABASE, 1, input.manifest().journalPrefix(),
                List.of(new SegmentReference(ref.id(), ref.byteLength() + difference, 1, 1)));
        assertInvalid(() -> HistoryCompactor.compact(changed, input.images()::get, HistoryCompactor.Limits.DEFAULT));
    }

    @Test
    void validObjectFromAnotherLineageCannotBeRepackedUnderThisManifest() {
        var original = smallSingles(1);
        var foreign = bundle(new DatabaseId("foreign-db"), 1, List.of(original.records()));
        var changed = new CheckpointManifest(DATABASE, 1, original.manifest().journalPrefix(), foreign.manifest().segments());
        assertInvalid(() -> HistoryCompactor.compact(changed, foreign.images()::get, HistoryCompactor.Limits.DEFAULT));
    }

    @Test
    void validObjectRangeMustEqualTheDeclaredCoverage() {
        var record = record(2, "r2", 2);
        byte[] image = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(record)));
        var id = new SegmentId(hash(image));
        var prefix = prefix(DATABASE, List.of(record));
        var declared = new CheckpointManifest(DATABASE, 1,
                new JournalPrefix(1, prefix.byteLength(), prefix.sha256()),
                List.of(new SegmentReference(id, image.length, 1, 1)));
        assertInvalid(() -> HistoryCompactor.compact(declared, ignored -> image, HistoryCompactor.Limits.DEFAULT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "long", "digest"})
    void reconstructedJournalPrefixMustMatchAfterAllPrivatePacking(String damage) {
        var input = smallSingles(3);
        var original = input.manifest().journalPrefix();
        var altered = new JournalPrefix(original.sequence(), original.byteLength()
                + (damage.equals("short") ? -1 : damage.equals("long") ? 1 : 0),
                damage.equals("digest") ? ZERO_HASH : original.sha256());
        var manifest = new CheckpointManifest(DATABASE, 1, altered, input.manifest().segments());
        assertInvalid(() -> HistoryCompactor.compact(manifest, input.images()::get, limits(232)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedRequestAcrossSegmentsRejectsBothIdenticalAndConflictingPayloads(boolean changed) {
        var first = record(1, "retry", 7);
        var second = changed ? record(2, "retry", 8) : new JournalRecord(2, first.transaction());
        var input = bundle(DATABASE, 1, List.of(List.of(first), List.of(second)));
        assertInvalid(() -> HistoryCompactor.compact(input.manifest(), input.images()::get, HistoryCompactor.Limits.DEFAULT));
    }

    @Test
    void deterministicRepackingCanReuseTheSameContentIdAcrossPhysicalGenerations() throws IOException {
        var input = bundle(DATABASE, 1, List.of(List.of(record(1, "r1", 1), record(2, "r2", 2), record(3, "r3", 3))));
        var first = HistoryCompactor.compact(input.manifest(), input.images()::get, HistoryCompactor.Limits.DEFAULT);
        var again = HistoryCompactor.compact(input.manifest(), input.images()::get, HistoryCompactor.Limits.DEFAULT);
        assertEquals(first.replacementManifest(), again.replacementManifest());
        assertEquals(first.segments(), again.segments());
        assertEquals(first.inputBytes(), again.inputBytes());
        assertEquals(first.outputBytes(), again.outputBytes());
        assertEquals(input.manifest().segments().getFirst().id(), first.replacementManifest().segments().getFirst().id());
        var next = HistoryCompactor.compact(first.replacementManifest(), images(first)::get, HistoryCompactor.Limits.DEFAULT);
        assertEquals(3, next.replacementManifest().generation());
        assertEquals(first.segments(), next.segments());
        assertEquals(first.replacementManifest().segments(), next.replacementManifest().segments());
        assertNotEquals(first.sourceManifestId(), next.sourceManifestId());
        assertEquals(first.replacementManifest().journalPrefix(), next.replacementManifest().journalPrefix());
    }

    @Test
    void planOwnsImmutableDecodedValuesAndDoesNotModifyOrRetainSourceArrays() throws IOException {
        var input = smallSingles(3);
        var originalImages = new LinkedHashMap<SegmentId, byte[]>();
        input.images().forEach((id, image) -> originalImages.put(id, image.clone()));
        var plan = HistoryCompactor.compact(input.manifest(), input.images()::get, limits(232));
        originalImages.forEach((id, image) -> assertArrayEquals(image, input.images().get(id)));
        assertThrows(UnsupportedOperationException.class, () -> plan.segments().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.sourceManifest().segments().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.replacementManifest().segments().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.segments().getFirst().records().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.segments().getFirst().records().getFirst().transaction().operations().clear());
        input.images().values().forEach(image -> Arrays.fill(image, (byte) 0));
        assertEquals(input.records(), flattened(plan));
        assertPlanEncoding(plan);
    }

    @Test
    void sourceMayReuseAnEarlierBufferAfterThatReferenceHasBeenConsumed() throws IOException {
        var input = smallSingles(3);
        var refs = input.manifest().segments();
        var calls = new ArrayList<SegmentId>();
        var plan = HistoryCompactor.compact(input.manifest(), id -> {
            int index = calls.size();
            if (index > 0) { Arrays.fill(input.images().get(refs.get(index - 1).id()), (byte) 0); }
            calls.add(id);
            return input.images().get(id);
        }, limits(232));
        assertEquals(refs.stream().map(SegmentReference::id).toList(), calls);
        assertEquals(input.records(), flattened(plan));
        assertPlanEncoding(plan);
    }

    private static HistoryCompactor.Limits limits(int target) {
        return new HistoryCompactor.Limits(target, TOTAL_BYTES, TOTAL_BYTES, TOTAL_RECORDS);
    }

    private static List<JournalRecord> flattened(HistoryCompactor.Plan plan) {
        return plan.segments().stream().flatMap(segment -> segment.records().stream()).toList();
    }

    private static void assertPlanEncoding(HistoryCompactor.Plan plan) throws IOException {
        assertEquals(plan.segments().size(), plan.replacementManifest().segments().size());
        long total = 0;
        for (int index = 0; index < plan.segments().size(); index++) {
            var segment = plan.segments().get(index);
            var reference = plan.replacementManifest().segments().get(index);
            byte[] encoded = SegmentCodec.encode(segment);
            total += encoded.length;
            assertEquals(new SegmentId(hash(encoded)), reference.id());
            assertEquals(encoded.length, reference.byteLength());
            assertEquals(segment.firstSequence(), reference.firstSequence());
            assertEquals(segment.lastSequence(), reference.lastSequence());
        }
        assertEquals(total, plan.outputBytes());
        CheckpointVerifier.verify(plan.replacementManifest(), images(plan)::get);
    }

    private static Map<SegmentId, byte[]> images(HistoryCompactor.Plan plan) {
        var images = new LinkedHashMap<SegmentId, byte[]>();
        for (var segment : plan.segments()) {
            byte[] image = SegmentCodec.encode(segment);
            images.put(new SegmentId(hash(image)), image);
        }
        return images;
    }

    private static void assertLimit(Action action) {
        assertEquals(ManifestFormatException.Code.LIMIT_EXCEEDED,
                assertThrows(ManifestFormatException.class, action::run).code());
    }

    private static void assertInvalid(Action action) {
        assertEquals(ManifestFormatException.Code.INVALID_CHECKPOINT,
                assertThrows(ManifestFormatException.class, action::run).code());
    }

    private static Bundle smallSingles(int count) {
        var partitions = new ArrayList<List<JournalRecord>>();
        for (int sequence = 1; sequence <= count; sequence++) {
            partitions.add(List.of(record(sequence, "r" + sequence, sequence)));
        }
        return bundle(DATABASE, 1, partitions);
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
        return new Bundle(new CheckpointManifest(database, generation, prefix(database, records), references), images, List.copyOf(records));
    }

    private static JournalPrefix prefix(DatabaseId database, List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(database));
        for (var record : records) { bytes.writeBytes(JournalCodec.encodeRecord(record)); }
        byte[] image = bytes.toByteArray();
        return new JournalPrefix(records.isEmpty() ? 0 : records.getLast().sequence(), image.length, hash(image));
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), value))));
    }

    private static String hash(byte[] image) { return HexFormat.of().formatHex(ManifestCodec.sha256().digest(image)); }

    private record Bundle(CheckpointManifest manifest, Map<SegmentId, byte[]> images, List<JournalRecord> records) { }

    @FunctionalInterface
    private interface Action { void run() throws IOException; }
}
