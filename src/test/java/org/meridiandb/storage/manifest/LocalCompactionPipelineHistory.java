// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.reference.ReferenceModel;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.ValuePredicate;
import org.meridiandb.temporal.WriteOperation;

/** Independent deterministic history and expected answers shared by integrated storage checks. */
final class LocalCompactionPipelineHistory {
    static final String DATASET = "pipeline-12-v1";
    static final DatabaseId DATABASE = new DatabaseId(DATASET);
    static final int CHECKPOINT = 8;
    static final EntityId ACCOUNT = new EntityId("account");
    static final EntityId BONUS = new EntityId("bonus");
    static final EntityId EMOJI = new EntityId("\uD83D\uDE00");
    static final EntityId MISSING = new EntityId("missing");
    static final List<EntityId> ENTITIES = List.of(EMOJI, MISSING, BONUS, ACCOUNT);
    static final String ORPHAN = "e".repeat(64) + ".segment";
    static final String NOTES = "operator-evidence.txt";
    static final byte[] ORPHAN_BYTES = {7, 0, 9};
    static final byte[] NOTE_BYTES = "pipeline diagnostic: preserve me\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static final List<JournalRecord> HISTORY = List.of(
            record(1, put(ACCOUNT, null, null, 100), put(BONUS, null, null, 10), put(EMOJI, null, null, Long.MAX_VALUE)),
            record(2, put(ACCOUNT, 10L, 30L, 120), put(BONUS, 0L, 40L, 20)),
            record(3, retract(ACCOUNT, 15L, 18L), retract(MISSING, null, null)),
            record(4, put(ACCOUNT, null, 0L, 90), put(EMOJI, Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE)),
            record(5, put(BONUS, 0L, 40L, 10)),
            record(6, retract(EMOJI, 5L, 25L), put(ACCOUNT, 40L, 50L, 100)),
            record(7, retract(ACCOUNT, 30L, 40L), put(ACCOUNT, 35L, 38L, 130)),
            record(8, retract(MISSING, 5L, 10L), put(ACCOUNT, 50L, 60L, 100)),
            record(9, put(ACCOUNT, 12L, 22L, 140)),
            record(10, retract(BONUS, -5L, 5L), put(EMOJI, Long.MAX_VALUE, null, Long.MIN_VALUE)),
            record(11, retract(ACCOUNT, 20L, 25L), put(BONUS, 20L, 25L, Long.MAX_VALUE)),
            record(12, put(ACCOUNT, 12L, 22L, 140), retract(MISSING, null, null)));
    static final List<TemporalQuery> QUERIES = queries();

    enum Layout { COMBINE, SPLIT, UNCHANGED }

    record Lengths(Map<String, Long> files) {
        Lengths { files = java.util.Collections.unmodifiableMap(new TreeMap<>(files)); }
        long totalBytes() { return files.values().stream().mapToLong(Long::longValue).sum(); }
        long categoryBytes(String suffix) {
            return files.entrySet().stream().filter(entry -> entry.getKey().endsWith(suffix)).mapToLong(Map.Entry::getValue).sum();
        }
        long manifestBytes() {
            return files.entrySet().stream().filter(entry -> entry.getKey().startsWith("checkpoint-")).mapToLong(Map.Entry::getValue).sum();
        }
        long manifestCount() { return files.keySet().stream().filter(name -> name.startsWith("checkpoint-")).count(); }
        long segmentCount() { return files.keySet().stream().filter(name -> name.endsWith(".segment")).count(); }
        long journalBytes() { return files.get(LocalCheckpointStore.JOURNAL); }
        String json(String stage) {
            return "{\"stage\":\"" + stage + "\",\"objects\":" + files.size() + ",\"totalBytes\":" + totalBytes()
                    + ",\"manifests\":" + manifestCount() + ",\"manifestBytes\":" + manifestBytes()
                    + ",\"segments\":" + segmentCount() + ",\"segmentBytes\":" + categoryBytes(".segment")
                    + ",\"journalBytes\":" + journalBytes() + ",\"controlBytes\":" + categoryBytes(".lock")
                    + ",\"otherBytes\":" + files.getOrDefault(NOTES, 0L) + ",\"diagnosticSegmentBytes\":" + ORPHAN_BYTES.length + "}";
        }
    }

    record Fixture(Path home, Layout layout, CheckpointManifest source, ManifestId sourceId,
                   HistoryCompactor.Plan plan, Lengths baseline, Lengths sourceLengths) { }

    private LocalCompactionPipelineHistory() { }

    static Fixture seed(Path home, Layout layout) throws IOException {
        try (var database = LocalDatabase.create(home, DATABASE)) {
            for (var record : HISTORY) {
                assertEquals(new CommitResult(position(record.sequence()), false), database.transact(record.transaction()));
            }
            // A retry acknowledges an earlier commit without adding another journal transaction.
            assertEquals(new CommitResult(position(3), true), database.transact(HISTORY.get(2).transaction()));
            assertEquals(position(12), database.head());
        }
        Files.write(home.resolve(ORPHAN), ORPHAN_BYTES);
        Files.write(home.resolve(NOTES), NOTE_BYTES);
        var baseline = lengths(home);
        CheckpointManifest source;
        ManifestId sourceId;
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
            var references = new ArrayList<CheckpointManifest.SegmentReference>();
            var groups = new ArrayList<List<JournalRecord>>();
            if (layout == Layout.COMBINE) {
                for (var record : HISTORY.subList(0, CHECKPOINT)) { groups.add(List.of(record)); }
            } else { groups.add(HISTORY.subList(0, CHECKPOINT)); }
            for (var group : groups) {
                var segment = new ImmutableSegment(DATABASE, group);
                var id = store.writeSegment(segment);
                references.add(new CheckpointManifest.SegmentReference(id, SegmentCodec.encode(segment).length,
                        group.getFirst().sequence(), group.getLast().sequence()));
            }
            var prefix = journal.prefix(CHECKPOINT);
            source = new CheckpointManifest(DATABASE, 2,
                    new CheckpointManifest.JournalPrefix(CHECKPOINT, prefix.byteLength(), prefix.sha256()), references);
            sourceId = store.publish(source, journal);
        }
        var plan = plan(home, source, layout);
        return new Fixture(home, layout, source, sourceId, plan, baseline, lengths(home));
    }

    static HistoryCompactor.Plan plan(Path home, CheckpointManifest source, Layout layout) throws IOException {
        var limits = layout == Layout.SPLIT ? new HistoryCompactor.Limits(512,
                HistoryCompactor.MAX_TOTAL_BYTES, HistoryCompactor.MAX_TOTAL_BYTES, HistoryCompactor.MAX_TOTAL_RECORDS)
                : HistoryCompactor.Limits.DEFAULT;
        return HistoryCompactor.compact(source,
                id -> Files.readAllBytes(home.resolve(LocalCheckpointStore.segmentName(id))), limits);
    }

    static ManifestId selectedId(Fixture fixture) { return ManifestCodec.decode(ManifestCodec.encode(fixture.plan().replacementManifest())).id(); }
    static String selectedName(Fixture fixture) { return LocalCheckpointStore.generationName(3, selectedId(fixture)); }
    static CommitPosition position(long sequence) { return new CommitPosition(DATABASE, sequence); }

    static byte[] journalImage() {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(DATABASE));
        HISTORY.forEach(record -> bytes.writeBytes(JournalCodec.encodeRecord(record)));
        return bytes.toByteArray();
    }

    /** Metadata-only: safe while a cooperating old reader still owns its guard channel. */
    static Lengths lengths(Path home) throws IOException {
        var files = new TreeMap<String, Long>();
        try (var entries = Files.list(home)) {
            for (var path : entries.toList()) {
                var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                assertTrue(attrs.isRegularFile(), "The controlled fixture contains only regular files: " + path);
                files.put(path.getFileName().toString(), attrs.size());
            }
        }
        return new Lengths(files);
    }

    /** Call only after ALL owners, including independently retained lazy readers, close. */
    static Map<String, byte[]> images(Path home) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var entries = Files.list(home)) {
            for (var path : entries.sorted().toList()) {
                assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
                result.put(path.getFileName().toString(), Files.readAllBytes(path));
            }
        }
        return result;
    }

    static void assertDiagnosticsAndJournal(Map<String, byte[]> snapshot) {
        assertArrayEquals(journalImage(), snapshot.get(LocalCheckpointStore.JOURNAL));
        assertArrayEquals(ORPHAN_BYTES, snapshot.get(ORPHAN));
        assertArrayEquals(NOTE_BYTES, snapshot.get(NOTES));
        assertArrayEquals(new byte[0], snapshot.get(LocalCheckpointStore.LOCK));
        assertArrayEquals(new byte[0], snapshot.get(LocalReaderBarrier.NAME));
    }

    static void assertGraphs(Path home, Map<String, byte[]> snapshot, ManifestId expectedSelected) throws IOException {
        var manifests = snapshot.entrySet().stream().filter(entry -> entry.getKey().startsWith("checkpoint-"))
                .map(entry -> {
                    String name = entry.getKey();
                    var expectedId = new ManifestId(name.substring(name.length() - 73, name.length() - 9));
                    return ManifestCodec.decode(entry.getValue(), expectedId).manifest();
                }).toList();
        for (var manifest : manifests) {
            CheckpointVerifier.verify(manifest, id -> snapshot.get(LocalCheckpointStore.segmentName(id)));
        }
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath());
             var selected = store.readLatest(journal).orElseThrow()) {
            assertEquals(expectedSelected, selected.manifestId());
            assertEquals(CHECKPOINT, selected.manifest().journalPrefix().sequence());
            for (var manifest : manifests) {
                var actual = journal.prefix(manifest.journalPrefix().sequence());
                assertEquals(manifest.journalPrefix().byteLength(), actual.byteLength());
                assertEquals(manifest.journalPrefix().sha256(), actual.sha256());
            }
            var history = new ArrayList<JournalRecord>();
            for (int index = 0; index < selected.manifest().segments().size(); index++) {
                try (var heap = selected.openSegment(index)) { history.addAll(heap.segment().records()); }
            }
            assertEquals(HISTORY.subList(0, CHECKPOINT), history);
        }
    }

    static void assertAnswers(Path home) throws IOException {
        var oracle = new ReferenceModel(DATABASE);
        HISTORY.forEach(record -> assertEquals(new CommitResult(position(record.sequence()), false), oracle.transact(record.transaction())));
        try (var database = LocalDatabase.openWritable(home)) {
            assertEquals(position(12), database.head());
            assertEquals(Optional.of(position(CHECKPOINT)), database.checkpointPosition());
            for (int cutoff = 0; cutoff <= HISTORY.size(); cutoff++) {
                var position = position(cutoff);
                try (var view = database.openView(new ReadCutoff.At(position))) {
                    for (var query : QUERIES) { assertEquals(oracle.query(position, query), view.query(query), "cutoff=" + cutoff + "/" + query); }
                }
            }
            // Literal anchors supplement the independent algorithm comparisons.
            anchor(database, 1, new TemporalQuery.Get(ACCOUNT, 13), OptionalLong.of(100));
            anchor(database, 3, new TemporalQuery.Get(ACCOUNT, 16), OptionalLong.empty());
            anchor(database, 7, new TemporalQuery.Get(ACCOUNT, 36), OptionalLong.of(130));
            anchor(database, 12, new TemporalQuery.Get(ACCOUNT, 21), OptionalLong.of(140));
            anchor(database, 12, new TemporalQuery.Get(BONUS, 22), OptionalLong.of(Long.MAX_VALUE));
            try (var pinned = database.openView(new ReadCutoff.At(position(CHECKPOINT)))) {
                var before = pinned.query(new TemporalQuery.Scan(ACCOUNT, new ValidTimeInterval(null, null)));
                for (var record : HISTORY) {
                    assertEquals(oracle.lookupRequest(record.transaction().requestId()), database.lookupRequest(record.transaction().requestId()));
                    assertEquals(new CommitResult(position(record.sequence()), true), database.transact(record.transaction()));
                }
                assertEquals(Optional.empty(), database.lookupRequest(new RequestId("not-committed")));
                var conflict = new Transaction(HISTORY.getFirst().transaction().requestId(), List.of(retract(ACCOUNT, null, null)));
                assertEquals(EngineException.Code.REQUEST_ID_REUSED, assertThrows(EngineException.class, () -> database.transact(conflict)).code());
                assertEquals(position(CHECKPOINT), pinned.position());
                assertEquals(before, pinned.query(new TemporalQuery.Scan(ACCOUNT, new ValidTimeInterval(null, null))));
                assertEquals(position(12), database.head());
            }
        }
    }

    private static void anchor(LocalDatabase database, int cutoff, TemporalQuery query, OptionalLong expected) {
        try (var view = database.openView(new ReadCutoff.At(position(cutoff)))) { assertEquals(new TemporalResult.Point(expected), view.query(query)); }
    }

    private static List<TemporalQuery> queries() {
        var queries = new ArrayList<TemporalQuery>();
        var points = List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 5L, 9L, 10L, 14L, 15L, 17L, 18L,
                19L, 20L, 21L, 22L, 24L, 25L, 29L, 30L, 35L, 38L, 40L, 50L, 60L, Long.MAX_VALUE);
        var windows = List.of(new ValidTimeInterval(null, null), new ValidTimeInterval(-10L, 50L),
                new ValidTimeInterval(10L, 30L), new ValidTimeInterval(15L, 25L),
                new ValidTimeInterval(Long.MIN_VALUE, Long.MIN_VALUE + 1), new ValidTimeInterval(Long.MAX_VALUE, null));
        for (var entity : ENTITIES) {
            points.forEach(point -> queries.add(new TemporalQuery.Get(entity, point)));
            windows.forEach(window -> queries.add(new TemporalQuery.Scan(entity, window)));
        }
        var aggregatePoints = List.of(-1L, 0L, 10L, 15L, 18L, 20L, 25L, 40L, Long.MAX_VALUE);
        aggregatePoints.forEach(point -> queries.add(new TemporalQuery.Sum(ENTITIES, point)));
        for (var operator : ValuePredicate.Operator.values()) {
            var predicate = new ValuePredicate(operator, 100);
            aggregatePoints.forEach(point -> queries.add(new TemporalQuery.Select(ENTITIES, point, predicate)));
            for (var entity : ENTITIES) {
                windows.subList(0, 3).forEach(window -> queries.add(new TemporalQuery.FilterScan(entity, window, predicate)));
            }
        }
        return List.copyOf(queries);
    }

    private static JournalRecord record(long sequence, WriteOperation... operations) {
        return new JournalRecord(sequence, new Transaction(new RequestId("pipeline-" + sequence), List.of(operations)));
    }
    private static WriteOperation.Put put(EntityId entity, Long from, Long until, long value) {
        return new WriteOperation.Put(entity, new ValidTimeInterval(from, until), value);
    }
    private static WriteOperation.Retract retract(EntityId entity, Long from, Long until) {
        return new WriteOperation.Retract(entity, new ValidTimeInterval(from, until));
    }
}
