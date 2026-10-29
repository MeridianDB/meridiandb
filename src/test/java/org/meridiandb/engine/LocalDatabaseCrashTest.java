// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.LocalDatabase.Boundary;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
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

/**
 * Real JVM exits leave the OS running. They verify process recovery and retry boundaries,
 * without simulating power loss, volatile device caches or an interrupted kernel write.
 */
@Timeout(45)
class LocalDatabaseCrashTest {
    private static final int HALT_STATUS = 75;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);
    private static final DatabaseId DATABASE = new DatabaseId("crash-recovery-db");
    private static final EntityId SALARY = new EntityId("salary");
    private static final EntityId BONUS = new EntityId("bonus");
    private static final Transaction FIRST = new Transaction(new RequestId("acknowledged-one"), List.of(
            new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 100),
            new WriteOperation.Put(BONUS, new ValidTimeInterval(null, null), 5)));
    private static final Transaction SECOND = new Transaction(new RequestId("candidate-two"), List.of(
            new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 20L), 120),
            new WriteOperation.Put(BONUS, new ValidTimeInterval(10L, 20L), 7)));
    private static final List<JournalRecord> HISTORY = List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND));

    @TempDir
    Path directory;

    @Test
    void haltBeforeAppendKeepsAcknowledgedPrefixAndAllowsExactlyOneCandidateCommit() throws Exception {
        exerciseCommitBoundary(Boundary.BEFORE_APPEND, false);
    }

    @Test
    void haltAfterCompleteAppendRecoversCandidateWithoutClaimingPowerLossDurability() throws Exception {
        exerciseCommitBoundary(Boundary.AFTER_APPEND, true);
    }

    @Test
    void haltAfterForceRecoversCandidateAndResolvesItsRetryAtOriginalPosition() throws Exception {
        exerciseCommitBoundary(Boundary.AFTER_FORCE, true);
    }

    @Test
    void haltAfterInMemoryPublicationBeforeReturnRecoversTheWholeCandidate() throws Exception {
        exerciseCommitBoundary(Boundary.AFTER_PUBLICATION, true);
    }

    @Test
    void haltAfterFlushedSuccessfulReturnPreservesBothAcknowledgedTransactions() throws Exception {
        Path home = freshHome();
        try (var child = start("acknowledged-return", home, Boundary.AFTER_PUBLICATION)) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.hasLine("ACK 1"), child.diagnostics());
            assertTrue(child.hasLine("ACK 2"), child.diagnostics());
        }
        assertStoredHistory(home, 2);
        verifyRecoveredCandidateAndRetries(home, true);
    }

    @Test
    void haltAfterHiddenWritableRecoveryBeforeStartupSynchronizationPreservesAcknowledgedHistory() throws Exception {
        exerciseStartupBoundary(Boundary.RECOVERED, false);
    }

    @Test
    void haltAfterWritableStartupSynchronizationPreservesAcknowledgedHistory() throws Exception {
        exerciseStartupBoundary(Boundary.SYNCHRONIZED, false);
    }

    @Test
    void haltAfterHiddenReadOnlyRecoveryReleasesOwnershipForWritableReopen() throws Exception {
        exerciseStartupBoundary(Boundary.RECOVERED, true);
    }

    @Test
    void haltDuringCreationAfterHiddenGenesisRecoveryLeavesAnOpenableGenesis() throws Exception {
        exerciseCreationBoundary(Boundary.RECOVERED);
    }

    @Test
    void haltDuringCreationAfterStartupSynchronizationLeavesAnOpenableGenesis() throws Exception {
        exerciseCreationBoundary(Boundary.SYNCHRONIZED);
    }

    @Test
    void externallyInjectedPartialFrameAfterOwnerCloseIsRejectedWithoutRepairOrEvidenceLoss() throws IOException {
        Path home = freshHome();
        seedAcknowledgedFirst(home);
        Path journal = home.resolve("transactions.journal");
        byte[] acknowledged = Files.readAllBytes(journal);
        byte[] candidate = JournalCodec.encodeRecord(HISTORY.get(1));
        byte[] partial = Arrays.copyOf(candidate, candidate.length / 2);
        // This is a deliberate malformed-image fixture, not a claim that a real
        // interrupted append wrote exactly this prefix. No owning handle is open.
        Files.write(journal, partial, StandardOpenOption.APPEND);
        byte[] evidence = concat(acknowledged, partial);
        assertArrayEquals(evidence, Files.readAllBytes(journal));
        assertThrows(LocalJournal.IncompleteTailException.class, () -> LocalDatabase.openWritable(home));
        assertArrayEquals(evidence, Files.readAllBytes(journal));
        assertThrows(LocalJournal.IncompleteTailException.class, () -> LocalDatabase.open(home));
        assertArrayEquals(evidence, Files.readAllBytes(journal));
        try (var store = LocalCheckpointStore.open(home)) {
            assertEquals(home.toRealPath().resolve("transactions.journal"), store.journalPath());
        }
        // Explicit fixture restoration is separate from the production API's
        // strict open behavior and also verifies both failed opens released owners.
        Files.write(journal, acknowledged);
        try (var database = LocalDatabase.openWritable(home)) {
            assertEquals(position(1), database.head());
            assertEquals(Optional.empty(), database.lookupRequest(SECOND.requestId()));
            assertEquals(new CommitResult(position(1), true), database.transact(FIRST));
        }
        assertArrayEquals(acknowledged, Files.readAllBytes(journal));
    }

    private void exerciseCommitBoundary(Boundary boundary, boolean candidatePresent) throws Exception {
        Path home = freshHome();
        try (var child = start("commit-boundary", home, boundary)) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.hasLine("ACK 1"), child.diagnostics());
            assertTrue(child.hasLine("HALT " + boundary), child.diagnostics());
            assertFalse(child.hasLine("ACK 2"), child.diagnostics());
        }
        assertStoredHistory(home, candidatePresent ? 2 : 1);
        verifyRecoveredCandidateAndRetries(home, candidatePresent);
    }

    private void exerciseStartupBoundary(Boundary boundary, boolean readOnly) throws Exception {
        Path home = freshHome();
        seedAcknowledgedFirst(home);
        byte[] before = Files.readAllBytes(home.resolve("transactions.journal"));
        try (var child = start(readOnly ? "open-readonly" : "open-writable", home, boundary)) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.hasLine("HALT " + boundary), child.diagnostics());
        }
        assertArrayEquals(before, Files.readAllBytes(home.resolve("transactions.journal")));
        assertStoredHistory(home, 1);
        try (var database = LocalDatabase.openWritable(home)) {
            assertEquals(position(1), database.head());
            assertEquals(Optional.of(position(0)), database.checkpointPosition());
            assertEquals(Optional.of(position(1)), database.lookupRequest(FIRST.requestId()));
            assertEquals(Optional.empty(), database.lookupRequest(SECOND.requestId()));
            assertEquals(new CommitResult(position(1), true), database.transact(FIRST));
            assertSnapshot(database, 1);
        }
        assertArrayEquals(before, Files.readAllBytes(home.resolve("transactions.journal")));
    }

    private void exerciseCreationBoundary(Boundary boundary) throws Exception {
        Path home = freshHome();
        try (var child = start("create-boundary", home, boundary)) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.hasLine("HALT " + boundary), child.diagnostics());
            assertFalse(child.hasLine("ACK 1"), child.diagnostics());
        }
        assertStoredHistory(home, 0);
        try (var database = LocalDatabase.openWritable(home); var empty = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(position(0), database.head());
            assertEquals(Optional.of(position(0)), database.checkpointPosition());
            assertEquals(Optional.empty(), database.lookupRequest(FIRST.requestId()));
            assertEquals(Optional.empty(), database.lookupRequest(SECOND.requestId()));
            assertEquals(new TemporalResult.Aggregate(0, BigInteger.ZERO),
                    empty.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
            assertEquals(new CommitResult(position(1), false), database.transact(FIRST));
            assertEquals(new CommitResult(position(1), true), database.transact(FIRST));
            assertEquals(position(0), empty.position());
        }
        assertStoredHistory(home, 1);
    }

    private void verifyRecoveredCandidateAndRetries(Path home, boolean candidatePresent) throws IOException {
        try (var database = LocalDatabase.openWritable(home)) {
            assertEquals(position(candidatePresent ? 2 : 1), database.head());
            assertEquals(Optional.of(position(0)), database.checkpointPosition());
            assertEquals(Optional.of(position(1)), database.lookupRequest(FIRST.requestId()));
            assertEquals(candidatePresent ? Optional.of(position(2)) : Optional.empty(), database.lookupRequest(SECOND.requestId()));
            assertSnapshot(database, candidatePresent ? 2 : 1);
            assertEquals(new CommitResult(position(1), true), database.transact(FIRST));
            assertEquals(new CommitResult(position(2), candidatePresent), database.transact(SECOND));
            assertEquals(new CommitResult(position(2), true), database.transact(SECOND));
            var conflict = new Transaction(SECOND.requestId(), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 999)));
            var failure = assertThrows(EngineException.class, () -> database.transact(conflict));
            assertEquals(EngineException.Code.REQUEST_ID_REUSED, failure.code());
            assertEquals(position(2), database.head());
            assertSnapshot(database, 2);
            try (var historical = database.openView(new ReadCutoff.At(position(1)))) {
                assertEquals(present(100), historical.query(new TemporalQuery.Get(SALARY, 13)));
                assertEquals(present(5), historical.query(new TemporalQuery.Get(BONUS, 13)));
                assertEquals(new TemporalResult.Aggregate(2, BigInteger.valueOf(105)),
                        historical.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
            }
        }
        assertStoredHistory(home, 2);
    }

    private static void assertSnapshot(LocalDatabase database, int head) {
        try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(position(head), view.position());
            assertEquals(present(head == 1 ? 100 : 120), view.query(new TemporalQuery.Get(SALARY, 13)));
            assertEquals(present(head == 1 ? 5 : 7), view.query(new TemporalQuery.Get(BONUS, 13)));
            assertEquals(present(100), view.query(new TemporalQuery.Get(SALARY, 0)));
            assertEquals(new TemporalResult.Aggregate(2, BigInteger.valueOf(head == 1 ? 105 : 127)),
                    view.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
            var segments = head == 1 ? List.of(new TemporalSegment(new ValidTimeInterval(8L, 22L), 100))
                    : List.of(new TemporalSegment(new ValidTimeInterval(8L, 10L), 100),
                            new TemporalSegment(new ValidTimeInterval(10L, 20L), 120),
                            new TemporalSegment(new ValidTimeInterval(20L, 22L), 100));
            assertEquals(new TemporalResult.Segments(segments),
                    view.query(new TemporalQuery.Scan(SALARY, new ValidTimeInterval(8L, 22L))));
        }
    }

    private static void assertStoredHistory(Path home, int count) throws IOException {
        byte[] image = Files.readAllBytes(home.resolve("transactions.journal"));
        var scanned = JournalCodec.scan(image);
        assertEquals(DATABASE, scanned.databaseId());
        assertEquals(HISTORY.subList(0, count), scanned.records());
        var expected = new ByteArrayOutputStream();
        expected.writeBytes(JournalCodec.encodeHeader(DATABASE));
        for (var record : HISTORY.subList(0, count)) { expected.writeBytes(JournalCodec.encodeRecord(record)); }
        assertArrayEquals(expected.toByteArray(), image);
    }

    private Path freshHome() throws IOException {
        // Probe in a separate directory: creating a lock or process log inside the
        // database directory would violate the initialization freshness contract.
        Path capability = Files.createDirectory(directory.resolve("capability"));
        try (var store = LocalCheckpointStore.open(capability)) { store.journalPath(); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
        }
        return Files.createDirectory(directory.resolve("database"));
    }

    private static void seedAcknowledgedFirst(Path home) throws IOException {
        try (var database = LocalDatabase.create(home, DATABASE)) {
            assertEquals(new CommitResult(position(1), false), database.transact(FIRST));
        }
    }

    private Child start(String mode, Path home, Boundary boundary) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java.exe"); }
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        Path output = directory.resolve("child-" + mode + "-" + boundary + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath,
                CrashProcess.class.getName(), mode, home.toAbsolutePath().toString(), boundary.name())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private static CommitPosition position(long sequence) { return new CommitPosition(DATABASE, sequence); }
    private static TemporalResult.Point present(long value) { return new TemporalResult.Point(OptionalLong.of(value)); }
    private static byte[] concat(byte[] prefix, byte[] suffix) {
        byte[] result = Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Path output;
        Child(Process process, Path output) { this.process = process; this.output = output; }
        String diagnostics() {
            try { return Files.readString(output, StandardCharsets.UTF_8); }
            catch (IOException failure) { return "Cannot read child output: " + failure; }
        }
        boolean hasLine(String expected) { return diagnostics().lines().anyMatch(expected::equals); }
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

    /** Invoked as a separate JVM using exactly the compiled production factories and write path. */
    public static final class CrashProcess {
        private CrashProcess() { }
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            Path home = Path.of(args[1]);
            Boundary target = Boundary.valueOf(args[2]);
            switch (mode) {
                case "commit-boundary" -> {
                    var armed = new AtomicBoolean();
                    var database = LocalDatabase.create(home, DATABASE, boundary -> {
                        if (armed.get() && boundary == target) { haltAt(boundary); }
                    });
                    acknowledge(database.transact(FIRST), 1);
                    armed.set(true);
                    acknowledge(database.transact(SECOND), 2);
                    throw new AssertionError("Requested commit boundary was not reached");
                }
                case "acknowledged-return" -> {
                    var database = LocalDatabase.create(home, DATABASE);
                    acknowledge(database.transact(FIRST), 1);
                    acknowledge(database.transact(SECOND), 2);
                    Runtime.getRuntime().halt(HALT_STATUS);
                }
                case "open-writable" -> {
                    var database = LocalDatabase.openWritable(home, boundary -> {
                        if (boundary == target) { haltAt(boundary); }
                    });
                    database.close();
                    throw new AssertionError("Requested writable startup boundary was not reached");
                }
                case "open-readonly" -> {
                    var database = LocalDatabase.open(home, boundary -> {
                        if (boundary == target) { haltAt(boundary); }
                    });
                    database.close();
                    throw new AssertionError("Requested read-only startup boundary was not reached");
                }
                case "create-boundary" -> {
                    var database = LocalDatabase.create(home, DATABASE, boundary -> {
                        if (boundary == target) { haltAt(boundary); }
                    });
                    database.close();
                    throw new AssertionError("Requested creation boundary was not reached");
                }
                default -> throw new IllegalArgumentException("Unknown crash test mode: " + mode);
            }
        }
        private static void acknowledge(CommitResult result, long sequence) {
            if (!result.equals(new CommitResult(position(sequence), false))) {
                throw new AssertionError("Unexpected committed result: " + result);
            }
            System.out.println("ACK " + sequence);
            System.out.flush();
        }
        private static void haltAt(Boundary boundary) {
            System.out.println("HALT " + boundary);
            System.out.flush();
            Runtime.getRuntime().halt(HALT_STATUS);
        }
    }
}
