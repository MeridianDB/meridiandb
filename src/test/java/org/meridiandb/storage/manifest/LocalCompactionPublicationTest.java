// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

class LocalCompactionPublicationTest {
    private static final DatabaseId DATABASE = new DatabaseId("compact-db");
    private static final String ZERO_HASH = "0".repeat(64);

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"new", "mixed", "all", "genesis"})
    void installsOnlyMissingExactObjectsAndAlwaysPublishesTheNextSameCutoffGeneration(String shape) throws IOException {
        var fixture = fixture(shape, 1);
        var before = fixture.namespace.snapshot();
        var missing = outputNames(fixture.plan).stream().filter(name -> !before.containsKey(name)).toList();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var id = store.publishCompaction(fixture.plan, fixture.journal);
            assertEquals(ManifestCodec.decode(ManifestCodec.encode(fixture.plan.replacementManifest())).id(), id);
            assertEquals(missing, fixture.namespace.events.stream()
                    .filter(event -> event.startsWith("write:") && event.endsWith(".segment"))
                    .map(event -> event.substring("write:".length())).toList());
            assertPreserved(before, fixture.namespace.files);
            assertArrayEquals(ManifestCodec.encode(fixture.plan.replacementManifest()),
                    fixture.namespace.files.get(LocalCheckpointStore.generationName(2, id)));
            assertTrue(fixture.namespace.files.keySet().stream().noneMatch(name -> name.startsWith("pending-")));
            assertPublicationOrder(fixture);
            try (var selected = store.readLatest(fixture.journal).orElseThrow()) {
                assertEquals(fixture.plan.replacementManifest(), selected.manifest());
                assertEquals(fixture.plan.sourceManifest().journalPrefix(), selected.manifest().journalPrefix());
                assertEquals(2, selected.manifest().generation());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"segment", "manifest", "compaction"})
    void creationReservesAnAbsentPermanentReaderBarrierBeforeFillingTheDirectory(String operation) throws IOException {
        var fixture = fixture("new", 1);
        fixture.namespace.files.remove(LocalReaderBarrier.NAME);
        fixture.namespace.fillTo(LocalCheckpointStore.MAX_DIRECTORY_ENTRIES - 1);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var failure = assertThrows(IOException.class, () -> {
                switch (operation) {
                    case "segment" -> store.writeSegment(fixture.plan.segments().getFirst());
                    case "manifest" -> store.publish(fixture.plan.replacementManifest(), fixture.journal);
                    case "compaction" -> store.publishCompaction(fixture.plan, fixture.journal);
                    default -> throw new AssertionError(operation);
                }
            });
            assertTrue(failure.getMessage().contains("capacity"));
            assertNoEffects(fixture);
        }
    }

    @Test
    void successfulCompactionLeavesOneSlotForItsAbsentPermanentReaderBarrier() throws IOException {
        var fixture = fixture("new", 1);
        fixture.namespace.files.remove(LocalReaderBarrier.NAME);
        fixture.namespace.fillTo(LocalCheckpointStore.MAX_DIRECTORY_ENTRIES - 3);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            store.publishCompaction(fixture.plan, fixture.journal);
            assertEquals(LocalCheckpointStore.MAX_DIRECTORY_ENTRIES - 1, fixture.namespace.entries().size());
            assertFalse(fixture.namespace.files.containsKey(LocalReaderBarrier.NAME));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "stale"})
    void selectedSourceMustExistAndMatchThePlansExactManifestIdentifier(String change) throws IOException {
        var fixture = fixture("new", 1);
        if (change.equals("missing")) {
            fixture.namespace.files.remove(selectedName(fixture.plan.sourceManifest()));
        } else {
            var original = fixture.plan.sourceManifest();
            fixture.namespace.select(new CheckpointManifest(DATABASE, 2, original.journalPrefix(), original.segments()));
        }
        var before = fixture.namespace.snapshot();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan, fixture.journal));
            assertPreserved(before, fixture.namespace.files);
            assertNoEffects(fixture);
            assertPoisoned(store, fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lineage", "prefix"})
    void sourceGraphMustStillMatchTheActualOwnerBeforeInstallingAnyReplacement(String change) throws IOException {
        var fixture = fixture("new", 1);
        if (change.equals("lineage")) { fixture.journal.database = new DatabaseId("foreign"); }
        else { fixture.journal.wrongHash = true; }
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan, fixture.journal));
            assertNoEffects(fixture);
            assertPoisoned(store, fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "corrupt"})
    void missingOrCorruptSourceDependencyFailsBeforeInstallation(String damage) throws IOException {
        var fixture = fixture("new", 1);
        String dependency = LocalCheckpointStore.segmentName(fixture.plan.sourceManifest().segments().getFirst().id());
        if (damage.equals("missing")) { fixture.namespace.files.remove(dependency); }
        else { fixture.namespace.files.get(dependency)[0] ^= 1; }
        Class<? extends Throwable> expected = damage.equals("missing") ? IOException.class : SegmentFormatException.class;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(expected, () -> store.publishCompaction(fixture.plan, fixture.journal));
            assertNoEffects(fixture);
            assertPoisoned(store, fixture);
        }
    }

    @Test
    void corruptExistingLaterOutputIsCheckedBeforeWritingAnEarlierMissingOutput() throws IOException {
        var fixture = fixture(5, 232, 1);
        var references = fixture.plan.replacementManifest().segments();
        String missingFirst = LocalCheckpointStore.segmentName(references.getFirst().id());
        String corruptSecond = LocalCheckpointStore.segmentName(references.get(1).id());
        byte[] damaged = SegmentCodec.encode(fixture.plan.segments().get(1));
        damaged[damaged.length - 1] ^= 1;
        fixture.namespace.files.put(corruptSecond, damaged.clone());
        assertFalse(fixture.namespace.files.containsKey(missingFirst));
        var before = fixture.namespace.snapshot();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan, fixture.journal));
            assertNoEffects(fixture);
            assertPreserved(before, fixture.namespace.files);
            assertArrayEquals(damaged, fixture.namespace.files.get(corruptSecond));
            assertPoisoned(store, fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"entries", "generations"})
    void capacityForAllMissingObjectsAndTheManifestIsReservedBeforeInstallation(String bound) throws IOException {
        var fixture = fixture("new", bound.equals("generations") ? LocalCheckpointStore.MAX_GENERATIONS : 1);
        if (bound.equals("entries")) {
            fixture.namespace.fillTo(LocalCheckpointStore.MAX_DIRECTORY_ENTRIES - 1);
        } else {
            var selected = fixture.plan.sourceManifest();
            for (int generation = 1; generation < LocalCheckpointStore.MAX_GENERATIONS; generation++) {
                fixture.namespace.select(new CheckpointManifest(DATABASE, generation, selected.journalPrefix(), selected.segments()));
            }
        }
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan, fixture.journal));
            assertNoEffects(fixture);
            assertPoisoned(store, fixture);
        }
    }

    @Test
    void exactRemainingCapacityAllowsOneMissingSegmentAndOneNewManifest() throws IOException {
        var fixture = fixture("new", 1);
        fixture.namespace.fillTo(LocalCheckpointStore.MAX_DIRECTORY_ENTRIES - 2);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            store.publishCompaction(fixture.plan, fixture.journal);
            assertEquals(LocalCheckpointStore.MAX_DIRECTORY_ENTRIES, fixture.namespace.entries().size());
            try (var selected = store.readLatest(fixture.journal).orElseThrow()) {
                assertEquals(2, selected.manifest().generation());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"install-before", "install-partial", "install-after", "install-runtime", "install-error",
            "verify", "journal-force", "segment-force", "dependency-directory", "candidate-partial", "candidate-after",
            "move-before", "move-after", "move-runtime-after", "move-error-after", "final-directory"})
    void failuresRetainOldObjectsAndCandidatesPoisonTheStoreAndResolveSelectionOnlyByReopen(String phase) throws IOException {
        var fixture = fixture("new", 1);
        fixture.namespace.fault = phase;
        var before = fixture.namespace.snapshot();
        Class<? extends Throwable> expected = phase.equals("verify") ? SegmentFormatException.class
                : phase.equals("install-runtime") ? IllegalStateException.class
                : phase.endsWith("error") || phase.equals("move-error-after") ? AssertionError.class
                : phase.startsWith("move-") || phase.equals("final-directory")
                    ? LocalCheckpointStore.UncertainPublicationException.class : IOException.class;
        boolean selectedReplacement = phase.equals("move-after") || phase.equals("move-runtime-after")
                || phase.equals("move-error-after") || phase.equals("final-directory");
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(expected, () -> store.publishCompaction(fixture.plan, fixture.journal));
            assertPreserved(before, fixture.namespace.files);
            String output = outputNames(fixture.plan).getFirst();
            assertEquals(!phase.equals("install-before"), fixture.namespace.files.containsKey(output));
            if (phase.equals("install-partial")) { assertEquals(7, fixture.namespace.files.get(output).length); }
            boolean pending = phase.equals("candidate-partial") || phase.equals("candidate-after") || phase.equals("move-before");
            assertEquals(pending, fixture.namespace.files.keySet().stream().anyMatch(name -> name.startsWith("pending-")));
            assertEquals(selectedReplacement,
                    fixture.namespace.files.containsKey(selectedName(fixture.plan.replacementManifest())));
            assertPoisoned(store, fixture);
        }
        var reopened = fixture.namespace.reopen();
        try (var store = new LocalCheckpointStore(reopened);
                var selected = store.readLatest(new MemoryJournal(reopened, fixture.journal.prefix)).orElseThrow()) {
            assertEquals(selectedReplacement ? 2 : 1, selected.manifest().generation());
        }
    }

    @Test
    void reentrantMutationReadPublicationAndCloseAreRejectedWhileTheOuterCallSucceeds() throws IOException {
        var fixture = fixture("mixed", 1);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            fixture.namespace.readHook = () -> {
                assertThrows(IOException.class, store::close);
                assertThrows(IOException.class, store::journalPath);
                assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan, fixture.journal));
                assertThrows(IOException.class, () -> store.readLatest(fixture.journal));
                assertThrows(IOException.class, () -> store.writeSegment(fixture.plan.segments().getFirst()));
            };
            store.publishCompaction(fixture.plan, fixture.journal);
            fixture.namespace.readHook = null;
            try (var selected = store.readLatest(fixture.journal).orElseThrow()) {
                assertEquals(2, selected.manifest().generation());
            }
        }
    }

    @Test
    void nullMetadataIsRejectedBeforeEffectsAndTheStoreCanStillPublish() throws IOException {
        var fixture = fixture("new", 1);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(NullPointerException.class, () -> store.publishCompaction(null, fixture.journal));
            assertThrows(NullPointerException.class,
                    () -> store.publishCompaction(fixture.plan, (LocalCheckpointStore.JournalAccess) null));
            assertNoEffects(fixture);
            store.publishCompaction(fixture.plan, fixture.journal);
        }
    }

    @Test
    void publicEntryRejectsAnOwnerAtAnotherPathBeforeStorageEffectsAndWithoutPoisoning() throws IOException {
        var fixture = fixture("new", 1);
        try (var store = new LocalCheckpointStore(fixture.namespace);
                var other = LocalJournal.create(directory.resolve("other.journal"), DATABASE)) {
            assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan, other));
            assertNoEffects(fixture);
            store.publishCompaction(fixture.plan, fixture.journal);
        }
    }

    @Test
    void closeFailureCanBeRetriedAndTheClosedStoreRejectsFurtherCompaction() throws IOException {
        var fixture = fixture("new", 1);
        fixture.namespace.failCloseOnce = true;
        var store = new LocalCheckpointStore(fixture.namespace);
        assertThrows(IOException.class, store::close);
        assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan, fixture.journal));
        store.close();
        store.close();
        assertEquals(2, fixture.namespace.closes);
        assertNoEffects(fixture);
    }

    private static void assertPublicationOrder(Fixture fixture) {
        var events = fixture.namespace.events;
        int journalForce = events.indexOf("journal-force");
        assertTrue(journalForce >= 0);
        for (String name : outputNames(fixture.plan)) {
            int force = events.indexOf("force:" + name);
            assertTrue(force > journalForce, "Every reused or new output is forced after the owner journal");
            assertTrue(force < events.indexOf("directory-force"));
            int write = events.indexOf("write:" + name);
            if (write >= 0) { assertTrue(write < journalForce); }
        }
        int dependencySync = events.indexOf("directory-force");
        int candidateWrite = starting(events, "write:pending-");
        int move = starting(events, "move:");
        assertTrue(journalForce < dependencySync && dependencySync < candidateWrite);
        assertTrue(candidateWrite < move && move < events.lastIndexOf("directory-force"));
    }

    private static void assertNoEffects(Fixture fixture) {
        assertTrue(fixture.namespace.events.stream().noneMatch(event -> event.startsWith("write:")
                || event.startsWith("force:") || event.startsWith("move:") || event.equals("directory-force")
                || event.equals("journal-force")));
    }

    private static void assertPoisoned(LocalCheckpointStore store, Fixture fixture) {
        int events = fixture.namespace.events.size();
        assertThrows(IOException.class, store::journalPath);
        assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan, fixture.journal));
        assertThrows(IOException.class, () -> store.readLatest(fixture.journal));
        assertEquals(events, fixture.namespace.events.size());
    }

    private static void assertPreserved(Map<String, byte[]> before, Map<String, byte[]> after) {
        before.forEach((name, bytes) -> assertArrayEquals(bytes, after.get(name), name));
    }

    private static List<String> outputNames(HistoryCompactor.Plan plan) {
        return plan.replacementManifest().segments().stream().map(ref -> LocalCheckpointStore.segmentName(ref.id())).toList();
    }

    private static int starting(List<String> events, String prefix) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).startsWith(prefix)) { return index; }
        }
        return -1;
    }

    private Fixture fixture(String shape, long generation) throws IOException {
        if (shape.equals("genesis")) { return fixture(List.of(), SegmentCodec.MAX_SEGMENT_BYTES, generation); }
        var records = List.of(record(1), record(2), record(3));
        return fixture(shape.equals("all") ? List.of(records) : records.stream().map(List::of).toList(),
                shape.equals("mixed") ? 232 : SegmentCodec.MAX_SEGMENT_BYTES, generation);
    }

    private Fixture fixture(int records, int target, long generation) throws IOException {
        var partitions = new ArrayList<List<JournalRecord>>();
        for (int sequence = 1; sequence <= records; sequence++) { partitions.add(List.of(record(sequence))); }
        return fixture(partitions, target, generation);
    }

    private Fixture fixture(List<List<JournalRecord>> partitions, int target, long generation) throws IOException {
        var namespace = new MemoryNamespace(directory);
        namespace.files.put(LocalReaderBarrier.NAME, new byte[0]);
        var references = new ArrayList<SegmentReference>();
        var prefixImage = new ByteArrayOutputStream();
        prefixImage.writeBytes(JournalCodec.encodeHeader(DATABASE));
        long sequence = 0;
        for (var partition : partitions) {
            var segment = new ImmutableSegment(DATABASE, partition);
            byte[] image = SegmentCodec.encode(segment);
            var id = new SegmentId(hash(image));
            namespace.files.put(LocalCheckpointStore.segmentName(id), image);
            references.add(new SegmentReference(id, image.length, segment.firstSequence(), segment.lastSequence()));
            for (var record : partition) {
                prefixImage.writeBytes(JournalCodec.encodeRecord(record));
                sequence = record.sequence();
            }
        }
        byte[] prefixBytes = prefixImage.toByteArray();
        var prefix = new LocalJournal.Prefix(sequence, prefixBytes.length, hash(prefixBytes));
        var source = new CheckpointManifest(DATABASE, generation,
                new JournalPrefix(sequence, prefix.byteLength(), prefix.sha256()), references);
        namespace.select(source);
        var plan = HistoryCompactor.compact(source, id -> namespace.files.get(LocalCheckpointStore.segmentName(id)),
                new HistoryCompactor.Limits(target, HistoryCompactor.MAX_TOTAL_BYTES,
                        HistoryCompactor.MAX_TOTAL_BYTES, HistoryCompactor.MAX_TOTAL_RECORDS));
        namespace.events.clear();
        return new Fixture(namespace, new MemoryJournal(namespace, prefix), plan);
    }

    private static JournalRecord record(long sequence) {
        return new JournalRecord(sequence, new Transaction(new RequestId("r" + sequence), List.of(
                new WriteOperation.Put(new EntityId("e"), new ValidTimeInterval(null, null), sequence))));
    }

    private static String selectedName(CheckpointManifest manifest) {
        return LocalCheckpointStore.generationName(manifest.generation(), ManifestCodec.decode(ManifestCodec.encode(manifest)).id());
    }

    private static String hash(byte[] image) { return HexFormat.of().formatHex(ManifestCodec.sha256().digest(image)); }

    private record Fixture(MemoryNamespace namespace, MemoryJournal journal, HistoryCompactor.Plan plan) { }

    private static final class MemoryJournal implements LocalCheckpointStore.JournalAccess {
        private final MemoryNamespace namespace;
        final LocalJournal.Prefix prefix;
        DatabaseId database = DATABASE;
        boolean wrongHash;
        MemoryJournal(MemoryNamespace namespace, LocalJournal.Prefix prefix) { this.namespace = namespace; this.prefix = prefix; }
        @Override public DatabaseId databaseId() { return database; }
        @Override public LocalJournal.Prefix prefix(long sequence) {
            namespace.events.add("journal-prefix:" + sequence);
            assertEquals(prefix.sequence(), sequence);
            return wrongHash ? new LocalJournal.Prefix(sequence, prefix.byteLength(), ZERO_HASH) : prefix;
        }
        @Override public void force() throws IOException {
            namespace.events.add("journal-force");
            if (namespace.fault.equals("journal-force")) { throw new IOException("Injected journal force failure"); }
        }
    }

    private static final class MemoryNamespace implements LocalCheckpointStore.Namespace {
        private final Path directory;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final List<String> fillers = new ArrayList<>();
        String fault = "";
        Runnable readHook;
        int directoryForces;
        int closes;
        boolean failCloseOnce;
        MemoryNamespace(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
        @Override public Path directory() { return directory; }
        @Override public LocalReaderBarrier.Protection acquireReader() {
            return new LocalReaderBarrier.Protection() {
                @Override public void checkValid() { }
                @Override public void close() { }
            };
        }
        @Override public java.util.Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() {
            return java.util.Optional.of(acquireReader());
        }
        @Override public List<String> entries() {
            events.add("entries");
            var names = new LinkedHashSet<>(files.keySet());
            names.addAll(fillers);
            return List.copyOf(names);
        }
        @Override public byte[] read(String name, int maxBytes) throws IOException {
            events.add("read:" + name);
            if (readHook != null) { readHook.run(); }
            byte[] image = files.get(name);
            if (image == null || image.length > maxBytes) { throw new IOException("Missing or oversized " + name); }
            return image.clone();
        }
        @Override public void writeNew(String name, byte[] image) throws IOException {
            events.add("write:" + name);
            if (files.containsKey(name)) { throw new IOException("Create-only destination exists"); }
            boolean segment = name.endsWith(".segment");
            if (segment && fault.equals("install-before")) { throw new IOException("Install failed before creation"); }
            files.put(name, image.clone());
            if (segment && fault.equals("install-partial") || !segment && fault.equals("candidate-partial")) {
                files.put(name, Arrays.copyOf(image, 7));
                throw new IOException("Partial candidate retained");
            }
            if (segment && fault.equals("install-after") || !segment && fault.equals("candidate-after")) {
                throw new IOException("Complete candidate retained after failure");
            }
            if (segment && fault.equals("install-runtime")) { throw new IllegalStateException("Install runtime failure"); }
            if (segment && fault.equals("install-error")) { throw new AssertionError("Install error"); }
            if (segment && fault.equals("verify")) { files.get(name)[image.length - 1] ^= 1; }
        }
        @Override public void forceFile(String name) throws IOException {
            events.add("force:" + name);
            if (fault.equals("segment-force")) { throw new IOException("Segment force failed"); }
            if (!files.containsKey(name)) { throw new IOException("Missing dependency"); }
        }
        @Override public void atomicMove(String from, String to) throws IOException {
            events.add("move:" + from + "->" + to);
            if (fault.equals("move-before")) { throw new IOException("Rename failed before effect"); }
            if (!files.containsKey(from) || files.containsKey(to)) { throw new IOException("Invalid rename"); }
            files.put(to, files.remove(from));
            if (fault.equals("move-after")) { throw new IOException("Rename failed after effect"); }
            if (fault.equals("move-runtime-after")) { throw new IllegalStateException("Rename runtime failure after effect"); }
            if (fault.equals("move-error-after")) { throw new AssertionError("Rename error after effect"); }
        }
        @Override public void forceDirectory() throws IOException {
            events.add("directory-force");
            directoryForces++;
            if (fault.equals("dependency-directory") && directoryForces == 1
                    || fault.equals("final-directory") && directoryForces == 2) {
                throw new IOException("Directory force failed");
            }
        }
        @Override public void close() throws IOException {
            closes++;
            if (failCloseOnce) { failCloseOnce = false; throw new IOException("Close failed once"); }
        }
        void select(CheckpointManifest manifest) { files.put(selectedName(manifest), ManifestCodec.encode(manifest)); }
        void fillTo(int count) {
            while (files.size() + fillers.size() < count) { fillers.add("unrelated-" + fillers.size()); }
        }
        Map<String, byte[]> snapshot() {
            var result = new LinkedHashMap<String, byte[]>();
            files.forEach((name, image) -> result.put(name, image.clone()));
            return result;
        }
        MemoryNamespace reopen() {
            var reopened = new MemoryNamespace(directory);
            reopened.files.putAll(snapshot());
            reopened.fillers.addAll(fillers);
            return reopened;
        }
    }
}
