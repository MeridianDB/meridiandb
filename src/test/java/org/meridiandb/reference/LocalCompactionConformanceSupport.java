// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.HistoryCompactor;
import org.meridiandb.storage.manifest.LocalCheckpointReader;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestId;
import org.meridiandb.storage.manifest.ReclamationResult;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.LocalSegmentFile;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Test orchestration only: all storage operations use real production adapters and exact history. */
final class LocalCompactionConformanceSupport {
    private LocalCompactionConformanceSupport() { }

    @FunctionalInterface
    interface Verifier {
        void verify(LocalDatabase database, String stage, boolean writable) throws IOException;
    }

    record Cycle(long cutoff, int sourceSegments, int outputSegments, long removedBytes) { }

    /** Caller has closed every database owner before entering; this helper closes every acquired pin. */
    static Cycle cycle(Path path, long cutoff, int targetBytes, boolean exercisePins, Verifier verifier) throws IOException {
        Path journalPath = path.resolve("transactions.journal");
        byte[] originalJournal = Files.readAllBytes(journalPath); // No owner or old reader exists yet.
        LocalCheckpointReader pinned = null;
        CheckpointManifest oldRoot = null;
        HistoryCompactor.Plan plan;
        ManifestId sourceId;
        ManifestId replacementId;
        Map<SegmentId, byte[]> sourceImages = new HashMap<>();
        List<JournalRecord> records;
        try {
            try (var store = LocalCheckpointStore.open(path); var journal = LocalJournal.open(store.journalPath())) {
                var replayed = new ArrayList<JournalRecord>();
                journal.replay(replayed::add);
                records = List.copyOf(replayed);
                assertTrue(cutoff >= 0 && cutoff <= records.size());
                long generation;
                try (var latest = store.readLatest(journal).orElseThrow()) {
                    generation = Math.addExact(latest.manifest().generation(), 1);
                }
                var references = new ArrayList<CheckpointManifest.SegmentReference>();
                for (var record : records.subList(0, Math.toIntExact(cutoff))) {
                    var segment = new ImmutableSegment(journal.databaseId(), List.of(record));
                    byte[] image = SegmentCodec.encode(segment);
                    var id = SegmentCodec.decode(image).id();
                    Path object = path.resolve(id.value() + ".segment");
                    if (Files.exists(object)) {
                        // Existing paths are not reuse evidence. Validate the entire expected object.
                        try (var existing = LocalSegmentFile.open(object, id)) { assertEquals(segment, existing.segment()); }
                    } else { assertEquals(id, store.writeSegment(segment)); }
                    sourceImages.put(id, image);
                    references.add(new CheckpointManifest.SegmentReference(id, image.length, record.sequence(), record.sequence()));
                }
                var prefix = journal.prefix(cutoff);
                var source = new CheckpointManifest(journal.databaseId(), generation,
                        new CheckpointManifest.JournalPrefix(cutoff, prefix.byteLength(), prefix.sha256()), references);
                sourceId = store.publish(source, journal);
                if (exercisePins) {
                    pinned = store.readLatest(journal).orElseThrow();
                    oldRoot = pinned.manifest();
                    assertEquals(sourceId, pinned.manifestId());
                    assertTrue(!oldRoot.segments().isEmpty(), "Reader-deferral evidence must pin a nonempty source graph");
                }
                var defaults = HistoryCompactor.Limits.DEFAULT;
                plan = HistoryCompactor.compact(source, sourceImages::get,
                        new HistoryCompactor.Limits(targetBytes, defaults.maxInputBytes(), defaults.maxOutputBytes(), defaults.maxRecords()));
                assertEquals(sourceId, plan.sourceManifestId());
                assertEquals(records.subList(0, Math.toIntExact(cutoff)),
                        plan.segments().stream().flatMap(segment -> segment.records().stream()).toList(),
                        "Actual compactor output must contain every original prefix record exactly once");
                assertEquals(source.journalPrefix(), plan.replacementManifest().journalPrefix());
                replacementId = store.publishCompaction(plan, journal);
                if (cutoff > 1) {
                    assertTrue(plan.segments().size() < cutoff, "The source must actually be repacked");
                    assertTrue(plan.outputBytes() < plan.inputBytes(), "Merged envelopes must reduce encoded prefix size");
                }
                try (var selected = store.readLatest(journal).orElseThrow()) {
                    assertEquals(replacementId, selected.manifestId());
                    assertEquals(plan.replacementManifest(), selected.manifest());
                }
                if (pinned != null) {
                    var deferred = store.reclaim(Set.of(), journal);
                    assertEquals(ReclamationResult.Status.DEFERRED_READERS, deferred.status());
                    assertTrue(deferred.removedManifests().isEmpty());
                    assertTrue(deferred.removedSegments().isEmpty());
                }
            }
            // Published state is reopened and queried before any unprotected removal.
            try (var database = LocalDatabase.openWritable(path)) {
                assertEquals(Optional.of(new CommitPosition(database.head().databaseId(), cutoff)), database.checkpointPosition());
                verifier.verify(database, "published-writable", true);
            }
            try (var database = LocalDatabase.open(path)) {
                assertEquals(Optional.of(new CommitPosition(database.head().databaseId(), cutoff)), database.checkpointPosition());
                verifier.verify(database, "published-read-only", false);
            }
            if (pinned != null) {
                assertEquals(oldRoot, pinned.manifest());
                var oldRecords = new ArrayList<JournalRecord>();
                for (int index = 0; index < oldRoot.segments().size(); index++) {
                    try (var segment = pinned.openSegment(index)) { oldRecords.addAll(segment.segment().records()); }
                }
                assertEquals(records.subList(0, Math.toIntExact(oldRoot.journalPrefix().sequence())), oldRecords,
                        "The old lazy graph stays loadable across source/replacement publication and writable reopen");
                pinned.close();
                pinned = null;
            }
        } finally {
            if (pinned != null) { pinned.close(); }
        }
        assertArrayEquals(originalJournal, Files.readAllBytes(journalPath), "Publication/retry checks preserve exact full journal bytes");
        if (exercisePins) {
            try (var store = LocalCheckpointStore.open(path); var journal = LocalJournal.open(store.journalPath())) {
                var retained = store.reclaim(Set.of(sourceId), journal);
                assertEquals(ReclamationResult.Status.COMPLETED, retained.status());
                assertEquals(Set.of(sourceId, replacementId), retained.retainedManifestIds());
                for (var reference : plan.sourceManifest().segments()) {
                    var image = sourceImages.get(reference.id());
                    try (var reader = LocalSegmentFile.open(path.resolve(reference.id().value() + ".segment"), reference.id())) {
                        assertEquals(SegmentCodec.decode(image, reference.id()).segment(), reader.segment(),
                                "An explicit retained source protects every unique original dependency");
                    }
                }
            }
            assertArrayEquals(originalJournal, Files.readAllBytes(journalPath), "Explicit physical retention preserves the journal");
        }
        final ReclamationResult removed;
        try (var store = LocalCheckpointStore.open(path); var journal = LocalJournal.open(store.journalPath())) {
            removed = store.reclaim(Set.of(), journal);
            assertEquals(ReclamationResult.Status.COMPLETED, removed.status());
            assertEquals(Optional.of(replacementId), removed.selectedManifestId());
            assertEquals(Set.of(replacementId), removed.retainedManifestIds());
            assertTrue(removed.removedManifests().stream().anyMatch(file -> file.name().endsWith(sourceId.value() + ".manifest")),
                    "The source manifest must actually be retired");
            for (var file : removed.removedManifests()) { assertFalse(Files.exists(path.resolve(file.name()))); }
            for (var file : removed.removedSegments()) { assertFalse(Files.exists(path.resolve(file.name()))); }
        }
        assertArrayEquals(originalJournal, Files.readAllBytes(journalPath), "Physical removal retains every exact journal byte");
        var wantedSegments = new HashSet<String>();
        plan.replacementManifest().segments().forEach(reference -> wantedSegments.add(reference.id().value() + ".segment"));
        try (var entries = Files.list(path)) {
            var names = entries.map(entry -> entry.getFileName().toString()).toList();
            assertEquals(1, names.stream().filter(name -> name.startsWith("checkpoint-")).count());
            assertEquals(wantedSegments, names.stream().filter(name -> name.endsWith(".segment"))
                    .collect(java.util.stream.Collectors.toSet()), "Normal complete removal leaves only live segment objects");
        }
        try (var database = LocalDatabase.openWritable(path)) {
            assertEquals(Optional.of(new CommitPosition(database.head().databaseId(), cutoff)), database.checkpointPosition());
            verifier.verify(database, "removed-writable", true);
        }
        try (var database = LocalDatabase.open(path)) {
            assertEquals(Optional.of(new CommitPosition(database.head().databaseId(), cutoff)), database.checkpointPosition());
            verifier.verify(database, "removed-read-only", false);
        }
        assertArrayEquals(originalJournal, Files.readAllBytes(journalPath), "Post-removal writable retry checks append no bytes");
        return new Cycle(cutoff, plan.sourceManifest().segments().size(), plan.segments().size(), removed.removedBytes());
    }

    static void checkIdentity(LocalDatabase database, List<JournalRecord> records, String label, boolean writable) throws IOException {
        var head = database.head();
        assertEquals(records.size(), head.sequence(), label + "/head");
        for (var record : records) {
            var position = new CommitPosition(head.databaseId(), record.sequence());
            assertEquals(Optional.of(position), database.lookupRequest(record.transaction().requestId()), label + "/lookup");
            if (!writable) { continue; }
            var replayed = database.transact(record.transaction());
            assertTrue(replayed.replayed(), label + "/identical retry");
            assertEquals(position, replayed.position(), label + "/original retry position");
            var changed = new ArrayList<>(record.transaction().operations());
            changed.add(new WriteOperation.Retract(new EntityId("conflicting-retry"), new ValidTimeInterval(null, null)));
            var conflict = new Transaction(record.transaction().requestId(), changed);
            assertEquals(EngineException.Code.REQUEST_ID_REUSED,
                    assertThrows(EngineException.class, () -> database.transact(conflict)).code(), label + "/conflicting retry");
            assertEquals(head, database.head(), label + "/retry cannot advance head");
        }
        assertTrue(database.lookupRequest(new RequestId("never-submitted-conformance")).isEmpty());
    }
}
