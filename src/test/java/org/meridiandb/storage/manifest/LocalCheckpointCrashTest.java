// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.LocalCheckpointStore.PublicationBoundary;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Real process termination at publication boundaries; no device power-loss simulation. */
@Timeout(60)
class LocalCheckpointCrashTest {
    private static final int HALT_STATUS = 76;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);
    private static final DatabaseId DATABASE = new DatabaseId("checkpoint-crash-db");
    private static final EntityId SALARY = new EntityId("salary");
    private static final EntityId BONUS = new EntityId("bonus");

    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(PublicationBoundary.class)
    void initializationHaltRetainsOnlyTheGenerationActuallySelected(PublicationBoundary boundary) throws Exception {
        var home = Files.createDirectory(directory.resolve("database"));
        requireDirectorySync(home);
        haltAt("initialize", boundary, home);

        boolean renamed = afterRename(boundary);
        assertNamespace(home, renamed ? 1 : 0, boundary == PublicationBoundary.CANDIDATE_SYNCED ? 1 : 0, 0, false);
        var original = snapshot(home);
        assertCreateRejectedWithoutChanges(home, original);
        assertStoredHistory(home, renamed ? 1 : 0, 0, List.of());
        if (renamed) { original.put(LocalReaderBarrier.NAME, new byte[0]); }
        try (var database = LocalDatabase.openWritable(home);
                var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(position(0), database.head());
            assertEquals(renamed ? Optional.of(position(0)) : Optional.empty(), database.checkpointPosition());
            assertEquals(absent(), view.query(new TemporalQuery.Get(SALARY, 15)));
            assertEquals(Optional.empty(), database.lookupRequest(new RequestId("initial")));
        }
        assertFilesEqual(original, snapshot(home));
        assertCreateRejectedWithoutChanges(home, original);
    }

    @ParameterizedTest
    @EnumSource(value = PublicationBoundary.class, names = "JOURNAL_CREATED", mode = EnumSource.Mode.EXCLUDE)
    void checkpointHaltPreservesAcknowledgedHistoryAndTheUncheckpointedTail(PublicationBoundary boundary) throws Exception {
        var home = Files.createDirectory(directory.resolve("database"));
        requireDirectorySync(home);
        haltAt("checkpoint", boundary, home);

        boolean renamed = afterRename(boundary);
        assertNamespace(home, renamed ? 2 : 1, boundary == PublicationBoundary.CANDIDATE_SYNCED ? 1 : 0, 1, true);
        var original = snapshot(home);
        assertCreateRejectedWithoutChanges(home, original);
        assertStoredHistory(home, renamed ? 2 : 1, renamed ? 1 : 0, history());
        try (var database = LocalDatabase.openWritable(home)) {
            assertEquals(position(2), database.head());
            assertEquals(Optional.of(position(renamed ? 1 : 0)), database.checkpointPosition());
            assertHistoricalAnswers(database);
            for (var record : history()) {
                assertEquals(Optional.of(position(record.sequence())), database.lookupRequest(record.transaction().requestId()));
                var retry = database.transact(record.transaction());
                assertTrue(retry.replayed());
                assertEquals(position(record.sequence()), retry.position());
            }
            var conflicting = new Transaction(new RequestId("initial"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 999)));
            var rejected = assertThrows(EngineException.class, () -> database.transact(conflicting));
            assertEquals(EngineException.Code.REQUEST_ID_REUSED, rejected.code());
            assertEquals(position(2), database.head());
        }
        assertFilesEqual(original, snapshot(home));
        assertCreateRejectedWithoutChanges(home, original);
        assertStoredHistory(home, renamed ? 2 : 1, renamed ? 1 : 0, history());
    }

    private void haltAt(String mode, PublicationBoundary boundary, Path home) throws Exception {
        try (var child = start(mode, boundary, home)) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.diagnostics().lines().anyMatch(("REACHED " + boundary)::equals), child.diagnostics());
            if (mode.equals("checkpoint")) {
                assertTrue(child.diagnostics().lines().anyMatch("ACKNOWLEDGED 1 2"::equals), child.diagnostics());
            }
        }
    }

    private static boolean afterRename(PublicationBoundary boundary) {
        return boundary == PublicationBoundary.MANIFEST_RENAMED || boundary == PublicationBoundary.DIRECTORY_SYNCED;
    }

    private static void requireDirectorySync(Path home) throws IOException {
        try (var store = LocalCheckpointStore.open(home)) {
            assertEquals(home.toRealPath().resolve("transactions.journal"), store.journalPath());
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unavailable) {
            assumeTrue(false, "Directory open/force unavailable: " + unavailable);
        }
    }

    private static void assertNamespace(Path home, int selected, int pending, int segments, boolean readerGuard) throws IOException {
        try (var entries = Files.list(home)) {
            var paths = entries.toList();
            var names = paths.stream().map(path -> path.getFileName().toString()).toList();
            assertEquals(selected, names.stream().filter(name -> name.startsWith("checkpoint-")).count());
            assertEquals(pending, names.stream().filter(name -> name.startsWith("pending-")).count());
            assertEquals(segments, names.stream().filter(name -> name.endsWith(".segment")).count());
            assertTrue(names.contains("transactions.journal"));
            assertTrue(names.contains(".meridiandb-checkpoints.lock"));
            assertEquals(readerGuard, names.contains(LocalReaderBarrier.NAME));
            if (readerGuard) { assertEquals(0, Files.size(home.resolve(LocalReaderBarrier.NAME))); }
            assertEquals(2 + selected + pending + segments + (readerGuard ? 1 : 0), names.size(), "No child diagnostics or unexpected store files");
            for (var candidate : paths.stream().filter(path -> path.getFileName().toString().startsWith("pending-")).toList()) {
                var manifest = ManifestCodec.decode(Files.readAllBytes(candidate)).manifest();
                assertEquals(selected + 1L, manifest.generation());
                assertEquals(segments == 0 ? 0 : 1, manifest.journalPrefix().sequence());
            }
        }
    }

    private static void assertStoredHistory(Path home, long generation, long cutoff,
                                            List<JournalRecord> expected) throws IOException {
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(DATABASE, journal.databaseId());
            assertEquals(expected.size(), journal.appendedSequence());
            var replayed = new ArrayList<JournalRecord>();
            journal.replay(replayed::add);
            assertEquals(expected, replayed);
            var selected = store.readLatest(journal);
            if (generation == 0) {
                assertTrue(selected.isEmpty(), "A complete pending candidate is never selected");
            } else {
                try (var checkpoint = selected.orElseThrow()) {
                    assertEquals(generation, checkpoint.manifest().generation());
                    assertEquals(cutoff, checkpoint.manifest().journalPrefix().sequence());
                    assertEquals(DATABASE, checkpoint.manifest().databaseId());
                    var prefix = journal.prefix(cutoff);
                    assertEquals(prefix.byteLength(), checkpoint.manifest().journalPrefix().byteLength());
                    assertEquals(prefix.sha256(), checkpoint.manifest().journalPrefix().sha256());
                    var records = new ArrayList<JournalRecord>();
                    for (int index = 0; index < checkpoint.manifest().segments().size(); index++) {
                        try (var segment = checkpoint.openSegment(index)) { records.addAll(segment.segment().records()); }
                    }
                    assertEquals(expected.subList(0, Math.toIntExact(cutoff)), records);
                }
            }
        }
    }

    private static void assertHistoricalAnswers(LocalDatabase database) {
        try (var genesis = database.openView(new ReadCutoff.At(position(0)));
                var first = database.openView(new ReadCutoff.At(position(1)));
                var head = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(absent(), genesis.query(new TemporalQuery.Get(SALARY, 15)));
            assertEquals(present(100), first.query(new TemporalQuery.Get(SALARY, 15)));
            assertEquals(absent(), first.query(new TemporalQuery.Get(BONUS, 15)));
            assertEquals(present(120), head.query(new TemporalQuery.Get(SALARY, 15)));
            assertEquals(present(100), head.query(new TemporalQuery.Get(SALARY, 5)));
            assertEquals(new TemporalResult.Segments(List.of(
                    new TemporalSegment(new ValidTimeInterval(8L, 10L), 100),
                    new TemporalSegment(new ValidTimeInterval(10L, 20L), 120),
                    new TemporalSegment(new ValidTimeInterval(20L, 22L), 100))),
                    head.query(new TemporalQuery.Scan(SALARY, new ValidTimeInterval(8L, 22L))));
            assertEquals(new TemporalResult.Aggregate(2, BigInteger.valueOf(127)),
                    head.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 15)));
        }
    }

    private static void assertCreateRejectedWithoutChanges(Path home, Map<String, byte[]> original) throws IOException {
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalDatabase.create(home, DATABASE)) { unexpected.head(); }
        });
        assertFilesEqual(original, snapshot(home));
    }

    private static Map<String, byte[]> snapshot(Path home) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        // All owners, including the halted child, are closed before raw byte inspection.
        try (var entries = Files.list(home)) {
            for (var path : entries.toList()) {
                assertTrue(Files.isRegularFile(path), "Unexpected non-regular store object: " + path);
                result.put(path.getFileName().toString(), Files.readAllBytes(path));
            }
        }
        return result;
    }

    private static void assertFilesEqual(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, bytes) -> assertArrayEquals(bytes, actual.get(name), name));
    }

    private Child start(String mode, PublicationBoundary boundary, Path home) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        var output = directory.resolve(mode + "-" + boundary + ".log");
        assertFalse(output.startsWith(home), "Child output must remain outside the database directory");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath,
                CrashProcess.class.getName(), mode, boundary.name(), home.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private static List<JournalRecord> history() {
        return List.of(
                new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                        new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 20L), 120),
                        new WriteOperation.Put(BONUS, new ValidTimeInterval(null, null), 7)))));
    }

    private static CommitPosition position(long sequence) { return new CommitPosition(DATABASE, sequence); }
    private static TemporalResult.Point present(long value) { return new TemporalResult.Point(OptionalLong.of(value)); }
    private static TemporalResult.Point absent() { return new TemporalResult.Point(OptionalLong.empty()); }

    private record Child(Process process, Path output) implements AutoCloseable {
        String diagnostics() {
            try { return Files.readString(output, StandardCharsets.UTF_8); }
            catch (IOException failure) { return "Cannot read child output: " + failure; }
        }
        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    () -> "Child did not exit: " + diagnostics());
            return process.exitValue();
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Child did not terminate: " + diagnostics());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating child", interrupted);
            } finally {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    public static final class CrashProcess {
        private CrashProcess() { }
        public static void main(String[] args) throws Exception {
            if (args.length != 3) { throw new IllegalArgumentException("Expected mode, boundary and directory"); }
            String mode = args[0];
            var boundary = PublicationBoundary.valueOf(args[1]);
            var home = Path.of(args[2]);
            if (mode.equals("initialize")) {
                try (var store = LocalCheckpointStore.openObserved(home, reached -> haltAt(reached, boundary));
                        var journal = store.initialize(DATABASE)) {
                    throw new AssertionError("Initialization returned before requested halt: " + journal.appendedSequence());
                }
            } else if (mode.equals("checkpoint")) {
                var records = history();
                try (var database = LocalDatabase.create(home, DATABASE)) {
                    var first = database.transact(records.get(0).transaction());
                    var second = database.transact(records.get(1).transaction());
                    if (first.position().sequence() != 1 || second.position().sequence() != 2) {
                        throw new AssertionError("Unexpected acknowledged positions");
                    }
                    System.out.println("ACKNOWLEDGED 1 2");
                    System.out.flush();
                }
                try (var store = LocalCheckpointStore.openObserved(home, reached -> haltAt(reached, boundary));
                        var journal = LocalJournal.open(store.journalPath())) {
                    var segment = new ImmutableSegment(DATABASE, records.subList(0, 1));
                    var id = store.writeSegment(segment);
                    var prefix = journal.prefix(1);
                    var manifest = new CheckpointManifest(DATABASE, 2,
                            new CheckpointManifest.JournalPrefix(1, prefix.byteLength(), prefix.sha256()),
                            List.of(new CheckpointManifest.SegmentReference(id, SegmentCodec.encode(segment).length, 1, 1)));
                    store.publish(manifest, journal);
                    throw new AssertionError("Checkpoint publication returned before requested halt");
                }
            } else {
                throw new IllegalArgumentException("Unknown child mode: " + mode);
            }
        }
        private static void haltAt(PublicationBoundary reached, PublicationBoundary requested) {
            if (reached == requested) {
                System.out.println("REACHED " + reached);
                System.out.flush();
                Runtime.getRuntime().halt(HALT_STATUS);
            }
        }
    }
}
