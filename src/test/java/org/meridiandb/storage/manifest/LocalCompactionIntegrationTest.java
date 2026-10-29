// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.LocalSegmentFile;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.EntityValue;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.ValuePredicate;
import org.meridiandb.temporal.WriteOperation;

@Timeout(60)
class LocalCompactionIntegrationTest {
    static final DatabaseId DATABASE = new DatabaseId("local-compaction");
    static final EntityId SALARY = new EntityId("salary");
    static final EntityId BONUS = new EntityId("bonus");
    static final List<JournalRecord> HISTORY = List.of(
            new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 100),
                    new WriteOperation.Put(BONUS, new ValidTimeInterval(null, null), 5)))),
            new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 20L), 120),
                    new WriteOperation.Put(BONUS, new ValidTimeInterval(10L, 20L), 7)))),
            new JournalRecord(3, new Transaction(new RequestId("journal-tail"), List.of(
                    new WriteOperation.Retract(SALARY, new ValidTimeInterval(12L, 14L))))));

    @TempDir
    Path directory;

    @Test
    void twoSegmentsPublishAsOneAndOldLazyReaderSurvivesStoreCloseWithoutReclaimingAnything() throws IOException {
        var fixture = fixture("database", true);
        LocalCheckpointReader old = null;
        try {
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                old = store.readLatest(journal).orElseThrow();
                assertEquals(2, old.manifest().segments().size());
                var id = store.publishCompaction(fixture.plan(), journal);
                assertEquals(ManifestCodec.decode(ManifestCodec.encode(fixture.plan().replacementManifest())).id(), id);
                try (var latest = store.readLatest(journal).orElseThrow(); var segment = latest.openSegment(0)) {
                    assertEquals(3, latest.manifest().generation());
                    assertEquals(1, latest.manifest().segments().size());
                    assertEquals(HISTORY.subList(0, 2), segment.segment().records());
                }
            }
            // Store/journal owners are gone, but both old lazily opened objects remain valid.
            try (var first = old.openSegment(0); var second = old.openSegment(1)) {
                assertEquals(List.of(HISTORY.getFirst()), first.segment().records());
                assertEquals(List.of(HISTORY.get(1)), second.segment().records());
            }
            assertEquals(fixture.source(), old.manifest());
            assertOriginalObjectsRetained(fixture);
            assertArrayEquals(fixture.journalBytes(), Files.readAllBytes(fixture.home().resolve("transactions.journal")));
            verifyWritableHistory(fixture.home());
        } finally { if (old != null) { old.close(); } }
    }

    @Test
    void unchangedLayoutVerifiesAndReusesItsExistingContentPathWhileAdvancingGeneration() throws IOException {
        var fixture = fixture("database", false);
        assertEquals(fixture.source().segments(), fixture.plan().replacementManifest().segments());
        var before = segmentImages(fixture.home());
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            store.publishCompaction(fixture.plan(), journal);
            try (var latest = store.readLatest(journal).orElseThrow()) {
                assertEquals(3, latest.manifest().generation());
                assertEquals(fixture.source().journalPrefix(), latest.manifest().journalPrefix());
            }
        }
        assertImagesEqual(before, segmentImages(fixture.home()));
        verifyWritableHistory(fixture.home());
    }

    @Test
    void independentlyPreinstalledIdenticalOutputIsReusedWithoutReplacement() throws IOException {
        var fixture = fixture("database", true);
        var replacement = fixture.plan().segments().getFirst();
        var reference = fixture.plan().replacementManifest().segments().getFirst();
        Path path = fixture.home().resolve(LocalCheckpointStore.segmentName(reference.id()));
        assertFalse(Files.exists(path));
        assertEquals(reference.id(), LocalSegmentFile.writeNew(path, replacement));
        byte[] original = Files.readAllBytes(path);
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            store.publishCompaction(fixture.plan(), journal);
        }
        assertArrayEquals(original, Files.readAllBytes(path));
        assertOriginalObjectsRetained(fixture);
    }

    @Test
    void emptyGenesisCompactionAdvancesGenerationWithNoSegmentWritesOrHistoryChange() throws IOException {
        Path home = home("database");
        try (var database = LocalDatabase.create(home, DATABASE)) { assertEquals(0, database.head().sequence()); }
        byte[] before = Files.readAllBytes(home.resolve("transactions.journal"));
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath());
             var current = store.readLatest(journal).orElseThrow()) {
            var plan = HistoryCompactor.compact(current.manifest(), ignored -> { throw new AssertionError("Genesis has no segments"); },
                    HistoryCompactor.Limits.DEFAULT);
            store.publishCompaction(plan, journal);
            try (var latest = store.readLatest(journal).orElseThrow()) {
                assertEquals(2, latest.manifest().generation());
                assertEquals(0, latest.manifest().journalPrefix().sequence());
                assertTrue(latest.manifest().segments().isEmpty());
            }
        }
        assertTrue(segmentImages(home).isEmpty());
        assertArrayEquals(before, Files.readAllBytes(home.resolve("transactions.journal")));
        try (var database = LocalDatabase.openWritable(home)) { assertEquals(0, database.head().sequence()); }
    }

    @Test
    void stalePlanRejectsNewerSelectedGenerationBeforeInstallingItsMissingOutput() throws IOException {
        var fixture = fixture("database", true);
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            var advanced = new CheckpointManifest(DATABASE, 3, fixture.source().journalPrefix(), fixture.source().segments());
            store.publish(advanced, journal);
            assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan(), journal));
        }
        assertFalse(Files.exists(outputPath(fixture)));
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath());
             var latest = store.readLatest(journal).orElseThrow()) { assertEquals(3, latest.manifest().generation()); }
    }

    @Test
    void matchingGenerationLineageAndPrefixCannotSubstituteADifferentSelectedManifestId() throws IOException {
        var split = fixture("split-source", true);
        var combined = fixture("combined-source", false);
        assertEquals(split.source().generation(), combined.source().generation());
        assertEquals(split.source().journalPrefix(), combined.source().journalPrefix());
        assertFalse(split.sourceId().equals(combined.sourceId()));
        var before = snapshot(combined.home());
        try (var store = LocalCheckpointStore.open(combined.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(IOException.class, () -> store.publishCompaction(split.plan(), journal));
        }
        assertImagesEqual(before, snapshot(combined.home()));
    }

    @Test
    void journalFromAnotherPathIsRejectedEvenWhenItsExactHistoryAndLineageMatch() throws IOException {
        var fixture = fixture("database", true);
        try (var other = LocalJournal.create(directory.resolve("other.journal"), DATABASE)) {
            for (var record : HISTORY) { other.append(record); }
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan(), other));
                assertEquals(3, journal.appendedSequence());
            }
        }
        assertFalse(Files.exists(outputPath(fixture)));
    }

    @Test
    void changedActualJournalPrefixRejectsThePlanWithoutInstallingAnyReplacement() throws IOException {
        var fixture = fixture("database", true);
        var changed = new ArrayList<>(HISTORY);
        changed.set(0, new JournalRecord(1, new Transaction(HISTORY.getFirst().transaction().requestId(), List.of(
                new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 999),
                new WriteOperation.Put(BONUS, new ValidTimeInterval(null, null), 5)))));
        byte[] altered = journalImage(changed);
        Files.write(fixture.home().resolve("transactions.journal"), altered);
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan(), journal));
        }
        assertArrayEquals(altered, Files.readAllBytes(fixture.home().resolve("transactions.journal")));
        assertFalse(Files.exists(outputPath(fixture)));
    }

    @Test
    void activeEngineWriterExcludesCompactionOwnerUntilItCloses() throws IOException {
        var fixture = fixture("database", true);
        try (var writer = LocalDatabase.openWritable(fixture.home())) {
            assertEquals(position(3), writer.head());
            assertThrows(IOException.class, () -> {
                try (var unexpected = LocalCheckpointStore.open(fixture.home())) { unexpected.journalPath(); }
            });
        }
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            store.publishCompaction(fixture.plan(), journal);
        }
        verifyWritableHistory(fixture.home());
    }

    @Test
    void corruptExistingOutputIsPreservedAndNeverOverwrittenOrSelected() throws IOException {
        var fixture = fixture("database", true);
        byte[] evidence = {9, 4, 2};
        Files.write(outputPath(fixture), evidence);
        assertCandidateRejected(fixture);
        assertArrayEquals(evidence, Files.readAllBytes(outputPath(fixture)));
        assertOriginalSelection(fixture);
    }

    @Test
    void existingDirectoryAtOutputNameIsRejectedAndPreserved() throws IOException {
        var fixture = fixture("database", true);
        Files.createDirectory(outputPath(fixture));
        assertCandidateRejected(fixture);
        assertTrue(Files.isDirectory(outputPath(fixture), LinkOption.NOFOLLOW_LINKS));
        assertOriginalSelection(fixture);
    }

    @Test
    void existingSymlinkToValidReplacementBytesIsRejectedWithoutFollowingOrReplacingIt() throws IOException {
        var fixture = fixture("database", true);
        Path target = directory.resolve("external-valid.segment");
        Files.write(target, SegmentCodec.encode(fixture.plan().segments().getFirst()));
        try { Files.createSymbolicLink(outputPath(fixture), target); }
        catch (UnsupportedOperationException unsupported) {
            assumeTrue(false, "Symbolic links unavailable: " + unsupported);
        }
        assertCandidateRejected(fixture);
        assertTrue(Files.isSymbolicLink(outputPath(fixture)));
        assertArrayEquals(SegmentCodec.encode(fixture.plan().segments().getFirst()), Files.readAllBytes(target));
        assertOriginalSelection(fixture);
    }

    @Test
    void outputHardLinksToOwnedJournalOrLockAreRejectedWithoutReleasingEitherProcessLock() throws Exception {
        for (boolean toJournal : new boolean[] {true, false}) {
            var fixture = fixture(toJournal ? "journal-alias" : "lock-alias", true);
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                var prefix = journal.prefix(3);
                Path target = toJournal ? store.journalPath() : fixture.home().resolve(".meridiandb-checkpoints.lock");
                try { Files.createLink(outputPath(fixture), target); }
                catch (UnsupportedOperationException unsupported) {
                    assumeTrue(false, "Hard links unavailable: " + unsupported);
                }
                assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan(), journal));
                assertOwnershipContenderRejected(fixture.home(), "store");
                assertOwnershipContenderRejected(fixture.home(), "journal");
                assertEquals(prefix, journal.prefix(3));
                assertEquals(3, journal.force());
            }
        }
    }

    private void assertOwnershipContenderRejected(Path home, String kind) throws Exception {
        Path output = directory.resolve(home.getFileName() + "-contend-" + kind + ".log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java.exe"); }
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, OwnershipProcess.class.getName(), kind,
                home.toAbsolutePath().toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS), "Contender must terminate");
            String diagnostics = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), diagnostics);
            assertTrue(diagnostics.lines().anyMatch("LOCKED"::equals), diagnostics);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        }
    }

    private static void assertCandidateRejected(Fixture fixture) throws IOException {
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            var failure = assertThrows(Exception.class, () -> store.publishCompaction(fixture.plan(), journal));
            assertTrue(failure instanceof IOException || failure instanceof SegmentFormatException,
                    "Candidate validation must fail with an I/O or format failure: " + failure);
        }
    }

    private static void assertOriginalSelection(Fixture fixture) throws IOException {
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath());
             var latest = store.readLatest(journal).orElseThrow()) { assertEquals(fixture.sourceId(), latest.manifestId()); }
    }

    private Fixture fixture(String name, boolean split) throws IOException { return seedFixture(home(name), split); }

    private Path home(String name) throws IOException {
        Path capability = Files.createDirectory(directory.resolve("capability-" + name));
        try (var store = LocalCheckpointStore.open(capability)) { store.journalPath(); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
        }
        return Files.createDirectory(directory.resolve(name));
    }

    static Fixture seedFixture(Path home, boolean split) throws IOException {
        try (var database = LocalDatabase.create(home, DATABASE)) {
            for (var record : HISTORY) { assertEquals(record.sequence(), database.transact(record.transaction()).position().sequence()); }
        }
        CheckpointManifest source;
        ManifestId sourceId;
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
            var references = new ArrayList<CheckpointManifest.SegmentReference>();
            for (var records : split ? List.of(HISTORY.subList(0, 1), HISTORY.subList(1, 2)) : List.of(HISTORY.subList(0, 2))) {
                var segment = new ImmutableSegment(DATABASE, records);
                var id = store.writeSegment(segment);
                references.add(new CheckpointManifest.SegmentReference(id, SegmentCodec.encode(segment).length,
                        records.getFirst().sequence(), records.getLast().sequence()));
            }
            var prefix = journal.prefix(2);
            source = new CheckpointManifest(DATABASE, 2,
                    new CheckpointManifest.JournalPrefix(2, prefix.byteLength(), prefix.sha256()), references);
            sourceId = store.publish(source, journal);
        }
        var plan = HistoryCompactor.compact(source,
                id -> Files.readAllBytes(home.resolve(LocalCheckpointStore.segmentName(id))), HistoryCompactor.Limits.DEFAULT);
        return new Fixture(home, source, sourceId, plan, Files.readAllBytes(home.resolve("transactions.journal")));
    }

    record Fixture(Path home, CheckpointManifest source, ManifestId sourceId, HistoryCompactor.Plan plan, byte[] journalBytes) { }

    static void verifyWritableHistory(Path home) throws IOException {
        try (var database = LocalDatabase.openWritable(home)) {
            assertEquals(position(3), database.head());
            assertEquals(Optional.of(position(2)), database.checkpointPosition());
            for (var record : HISTORY) {
                assertEquals(Optional.of(position(record.sequence())), database.lookupRequest(record.transaction().requestId()));
            }
            try (var view = database.openView(ReadCutoff.Head.INSTANCE);
                 var old = database.openView(new ReadCutoff.At(position(1)))) {
                assertEquals(new TemporalResult.Point(OptionalLong.empty()), view.query(new TemporalQuery.Get(SALARY, 13)));
                assertEquals(new TemporalResult.Point(OptionalLong.of(7)), view.query(new TemporalQuery.Get(BONUS, 13)));
                assertEquals(new TemporalResult.Aggregate(1, BigInteger.valueOf(7)),
                        view.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
                assertEquals(new TemporalResult.Rows(List.of(new EntityValue(BONUS, 7))), view.query(new TemporalQuery.Select(
                        List.of(SALARY, BONUS), 13, new ValuePredicate(ValuePredicate.Operator.NE, 0))));
                assertEquals(new TemporalResult.Point(OptionalLong.of(100)), old.query(new TemporalQuery.Get(SALARY, 13)));
            }
            assertEquals(new CommitResult(position(3), true), database.transact(HISTORY.get(2).transaction()));
            var conflict = new Transaction(HISTORY.get(1).transaction().requestId(), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 999)));
            assertEquals(EngineException.Code.REQUEST_ID_REUSED,
                    assertThrows(EngineException.class, () -> database.transact(conflict)).code());
            assertEquals(position(3), database.head());
        }
        assertArrayEquals(journalImage(HISTORY), Files.readAllBytes(home.resolve("transactions.journal")));
    }

    static byte[] journalImage(List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(DATABASE));
        for (var record : records) { bytes.writeBytes(JournalCodec.encodeRecord(record)); }
        return bytes.toByteArray();
    }

    static void assertOriginalObjectsRetained(Fixture fixture) {
        for (var reference : fixture.source().segments()) {
            assertTrue(Files.exists(fixture.home().resolve(LocalCheckpointStore.segmentName(reference.id()))));
        }
        assertTrue(Files.exists(fixture.home().resolve(LocalCheckpointStore.generationName(2, fixture.sourceId()))));
    }

    private static Path outputPath(Fixture fixture) {
        return fixture.home().resolve(LocalCheckpointStore.segmentName(fixture.plan().replacementManifest().segments().getFirst().id()));
    }

    private static Map<String, byte[]> segmentImages(Path home) throws IOException {
        var result = snapshot(home);
        result.keySet().removeIf(name -> !name.endsWith(".segment"));
        return result;
    }

    private static Map<String, byte[]> snapshot(Path home) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var paths = Files.list(home)) {
            for (var path : paths.toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { result.put(path.getFileName().toString(), Files.readAllBytes(path)); }
            }
        }
        return result;
    }

    private static void assertImagesEqual(Map<String, byte[]> before, Map<String, byte[]> after) {
        assertEquals(before.keySet(), after.keySet());
        before.forEach((name, bytes) -> assertArrayEquals(bytes, after.get(name), name));
    }

    private static CommitPosition position(long sequence) { return new CommitPosition(DATABASE, sequence); }

    public static final class OwnershipProcess {
        private OwnershipProcess() { }
        public static void main(String[] args) throws Exception {
            Path home = Path.of(args[1]);
            if (args[0].equals("store")) {
                LocalCheckpointStore opened;
                try { opened = LocalCheckpointStore.open(home); }
                catch (IOException locked) { System.out.println("LOCKED"); return; }
                try (opened) { opened.journalPath(); }
            } else {
                LocalJournal opened;
                try { opened = LocalJournal.open(home.resolve("transactions.journal")); }
                catch (IOException locked) { System.out.println("LOCKED"); return; }
                try (opened) { opened.prefix(0); }
            }
            System.exit(2);
        }
    }
}
