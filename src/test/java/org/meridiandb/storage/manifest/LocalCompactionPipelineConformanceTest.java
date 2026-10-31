// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.segment.LocalSegmentReader;
import org.meridiandb.storage.manifest.LocalCompactionPipelineHistory.Layout;
import org.meridiandb.storage.manifest.LocalCompactionPipelineHistory.Lengths;

/** Actual publication/removal with independent temporal answers and measured encoded-file accounting. */
@Timeout(90)
class LocalCompactionPipelineConformanceTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(Layout.class)
    void completePipelinePreservesAllHistoryRootsAndReadersWithExactRetainedFileDeltas(Layout layout) throws IOException {
        Path home = freshHome();
        var fixture = LocalCompactionPipelineHistory.seed(home, layout);
        var source = fixture.sourceLengths();
        var sourceImages = LocalCompactionPipelineHistory.images(home);
        LocalCompactionPipelineHistory.assertDiagnosticsAndJournal(sourceImages);
        LocalCompactionPipelineHistory.assertGraphs(home, sourceImages, fixture.sourceId());
        LocalCompactionPipelineHistory.assertAnswers(home);
        assertEquals(fixture.plan().inputBytes(), fixture.source().segments().stream().mapToLong(CheckpointManifest.SegmentReference::byteLength).sum());
        assertEquals(fixture.plan().outputBytes(), fixture.plan().replacementManifest().segments().stream()
                .mapToLong(CheckpointManifest.SegmentReference::byteLength).sum());
        assertEquals(fixture.plan().inputBytes() + LocalCompactionPipelineHistory.ORPHAN_BYTES.length, source.categoryBytes(".segment"));
        if (layout == Layout.COMBINE) {
            assertEquals(8, fixture.source().segments().size());
            assertEquals(1, fixture.plan().segments().size());
            assertTrue(fixture.plan().outputBytes() < fixture.plan().inputBytes());
        } else if (layout == Layout.SPLIT) {
            assertEquals(1, fixture.source().segments().size());
            assertTrue(fixture.plan().segments().size() > 1);
            assertTrue(fixture.plan().outputBytes() > fixture.plan().inputBytes());
        } else {
            assertEquals(fixture.source().segments(), fixture.plan().replacementManifest().segments());
            assertEquals(fixture.plan().inputBytes(), fixture.plan().outputBytes());
        }
        LocalCheckpointReader old = null;
        LocalSegmentReader heap = null;
        try {
            try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
                old = store.readLatest(journal).orElseThrow();
                assertEquals(fixture.sourceId(), old.manifestId());
            }
            // The source reader still holds independent shared protection after its publisher closes.
            try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
                assertEquals(LocalCompactionPipelineHistory.selectedId(fixture), store.publishCompaction(fixture.plan(), journal));
                assertEquals(ReclamationResult.Status.DEFERRED_READERS, store.reclaim(Set.of(fixture.sourceId()), journal).status());
                assertEquals(fixture.source().journalPrefix(), fixture.plan().replacementManifest().journalPrefix());
            }
            var published = LocalCompactionPipelineHistory.lengths(home); // stat only, no guard channel.
            assertPublishedInstallation(fixture, source, published);
            LocalCompactionPipelineHistory.assertAnswers(home);
            heap = old.openSegment(0);
            var heapHistory = heap.segment().records();
            assertEquals(LocalCompactionPipelineHistory.HISTORY.subList(0, Math.toIntExact(fixture.source().segments().getFirst().lastSequence())), heapHistory);
            old.close();
            old = null;
            // All OS-backed reader/publisher/journal owners are now closed before raw byte snapshots.
            var publishedImages = LocalCompactionPipelineHistory.images(home);
            LocalCompactionPipelineHistory.assertDiagnosticsAndJournal(publishedImages);
            sourceImages.forEach((name, image) -> assertArrayEquals(image, publishedImages.get(name), name));
            LocalCompactionPipelineHistory.assertGraphs(home, publishedImages, LocalCompactionPipelineHistory.selectedId(fixture));

            ReclamationResult backup;
            try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
                backup = store.reclaim(Set.of(fixture.sourceId()), journal);
                assertEquals(ReclamationResult.Status.COMPLETED, backup.status());
                assertEquals(Set.of(fixture.sourceId(), LocalCompactionPipelineHistory.selectedId(fixture)), backup.retainedManifestIds());
                assertEquals(1, backup.removedManifests().size()); // Only genesis, not the explicit backup root.
                assertTrue(backup.removedSegments().isEmpty());
            }
            var retainedBackup = LocalCompactionPipelineHistory.lengths(home);
            assertReceiptDelta(published, retainedBackup, backup);
            var retainedImages = LocalCompactionPipelineHistory.images(home);
            assertUnremovedBytes(publishedImages, retainedImages, backup);
            LocalCompactionPipelineHistory.assertDiagnosticsAndJournal(retainedImages);
            LocalCompactionPipelineHistory.assertGraphs(home, retainedImages, LocalCompactionPipelineHistory.selectedId(fixture));
            LocalCompactionPipelineHistory.assertAnswers(home);

            ReclamationResult removed;
            try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
                removed = store.reclaim(Set.of(), journal);
                assertEquals(ReclamationResult.Status.COMPLETED, removed.status());
                assertEquals(1, removed.removedManifests().size());
                assertEquals(layout == Layout.COMBINE ? 8 : layout == Layout.SPLIT ? 1 : 0, removed.removedSegments().size());
                var repeated = store.reclaim(Set.of(), journal);
                assertEquals(ReclamationResult.Status.COMPLETED, repeated.status());
                assertEquals(0, repeated.removedBytes());
                assertTrue(repeated.removedManifests().isEmpty());
                assertTrue(repeated.removedSegments().isEmpty());
            }
            var reclaimed = LocalCompactionPipelineHistory.lengths(home);
            assertReceiptDelta(retainedBackup, reclaimed, removed);
            var finalImages = LocalCompactionPipelineHistory.images(home);
            assertUnremovedBytes(retainedImages, finalImages, removed);
            LocalCompactionPipelineHistory.assertDiagnosticsAndJournal(finalImages);
            assertEquals(fixture.plan().outputBytes() + LocalCompactionPipelineHistory.ORPHAN_BYTES.length, reclaimed.categoryBytes(".segment"));
            assertEquals(1, reclaimed.manifestCount());
            assertEquals(fixture.plan().segments().size() + 1, reclaimed.segmentCount()); // Plus the preserved orphan evidence.
            assertEquals(heapHistory, heap.segment().records(), "Heap snapshot remains usable after physical source removal");
            LocalCompactionPipelineHistory.assertGraphs(home, finalImages, LocalCompactionPipelineHistory.selectedId(fixture));
            LocalCompactionPipelineHistory.assertAnswers(home);
            assertEquals(reclaimed, LocalCompactionPipelineHistory.lengths(home), "Identical retries/reopen create no storage object");
            emitSpaceAccounting(fixture, published, retainedBackup, reclaimed, backup, removed);
        } finally {
            if (old != null) { old.close(); }
            if (heap != null) { heap.close(); }
        }
    }

    private static void assertPublishedInstallation(LocalCompactionPipelineHistory.Fixture fixture, Lengths source, Lengths published) {
        var expected = new TreeMap<>(source.files());
        for (var reference : fixture.plan().replacementManifest().segments()) {
            expected.put(LocalCheckpointStore.segmentName(reference.id()), reference.byteLength());
        }
        expected.put(LocalCompactionPipelineHistory.selectedName(fixture), (long) ManifestCodec.encode(fixture.plan().replacementManifest()).length);
        assertEquals(expected, published.files());
        assertTrue(published.totalBytes() > source.totalBytes(), "Publication retains prior files; repacking alone reclaims no disk bytes");
    }

    private static void assertReceiptDelta(Lengths before, Lengths after, ReclamationResult result) {
        var expected = new TreeMap<>(before.files());
        for (var file : result.removedManifests()) { assertEquals(file.byteLength(), expected.remove(file.name()).longValue()); }
        for (var file : result.removedSegments()) { assertEquals(file.byteLength(), expected.remove(file.name()).longValue()); }
        assertEquals(expected, after.files());
        assertEquals(result.removedBytes(), before.totalBytes() - after.totalBytes());
        assertEquals(result.removedManifests().size() + result.removedSegments().size(), before.files().size() - after.files().size());
        assertEquals(before.journalBytes(), after.journalBytes());
    }

    private static void assertUnremovedBytes(Map<String, byte[]> before, Map<String, byte[]> after, ReclamationResult result) {
        var removed = new HashSet<String>();
        result.removedManifests().forEach(file -> removed.add(file.name()));
        result.removedSegments().forEach(file -> removed.add(file.name()));
        assertEquals(before.keySet().stream().filter(name -> !removed.contains(name)).collect(java.util.stream.Collectors.toSet()), after.keySet());
        after.forEach((name, image) -> assertArrayEquals(before.get(name), image, name));
    }

    private static void emitSpaceAccounting(LocalCompactionPipelineHistory.Fixture fixture, Lengths published,
                                            Lengths backup, Lengths reclaimed, ReclamationResult backupReceipt,
                                            ReclamationResult removed) {
        String stages = String.join(",", fixture.baseline().json("baseline"), fixture.sourceLengths().json("source"),
                published.json("published"), backup.json("backup-retained"), reclaimed.json("reclaimed"));
        int target = fixture.layout() == Layout.SPLIT ? 512 : org.meridiandb.storage.segment.SegmentCodec.MAX_SEGMENT_BYTES;
        System.out.println("SPACE_ACCOUNTING_JSON {\"dataset\":\"" + LocalCompactionPipelineHistory.DATASET
                + "\",\"layout\":\"" + fixture.layout().name().toLowerCase(java.util.Locale.ROOT)
                + "\",\"transactions\":12,\"checkpointCutoff\":8,\"sourceSegments\":" + fixture.source().segments().size()
                + ",\"outputSegments\":" + fixture.plan().segments().size() + ",\"targetSegmentBytes\":" + target
                + ",\"inputSegmentBytes\":" + fixture.plan().inputBytes() + ",\"outputSegmentBytes\":" + fixture.plan().outputBytes()
                + ",\"backupRemovedBytes\":" + backupReceipt.removedBytes() + ",\"finalRemovedBytes\":" + removed.removedBytes()
                + ",\"queryCasesPerCutoff\":" + LocalCompactionPipelineHistory.QUERIES.size() + ",\"stages\":[" + stages + "]}");
    }

    private Path freshHome() throws IOException {
        Path capability = Files.createDirectory(directory.resolve("capability"));
        try (var store = LocalCheckpointStore.open(capability)) { store.journalPath(); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Required directory force is unavailable: " + unsupported);
        }
        return Files.createDirectory(directory.resolve("database"));
    }
}
