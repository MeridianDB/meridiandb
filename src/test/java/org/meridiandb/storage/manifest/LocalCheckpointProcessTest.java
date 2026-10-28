// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Process termination verifies reopen behavior; it does not emulate device power loss. */
@Timeout(60)
class LocalCheckpointProcessTest {
    private static final int HALT_STATUS = 74;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);
    private static final DatabaseId DATABASE = new DatabaseId("process-checkpoint");

    @TempDir
    Path directory;

    @Test
    void separateJvmOwnsStoreUntilRelease() throws Exception {
        requireDirectorySync();
        try (var child = start("hold")) {
            child.awaitOutput("HELD");
            assertStoreOpenRejected();
            child.release();
            assertEquals(0, child.awaitExit(), child.diagnostics());
        }
        try (var reopened = LocalCheckpointStore.open(directory)) {
            assertEquals(directory.toRealPath(), reopened.journalPath().getParent());
        }
    }

    @Test
    void rejectedSameJvmOpenLeavesOriginalOwnersCrossProcessLockIntact() throws Exception {
        requireDirectorySync();
        try (var owner = LocalCheckpointStore.open(directory)) {
            assertStoreOpenRejected();
            try (var contender = start("contend")) {
                assertEquals(0, contender.awaitExit(), contender.diagnostics());
                assertTrue(contender.diagnostics().lines().anyMatch("LOCKED"::equals), contender.diagnostics());
            }
            try (var journal = LocalJournal.create(owner.journalPath(), DATABASE)) {
                assertTrue(owner.readLatest(journal).isEmpty());
            }
        }
    }

    @Test
    void preexistingStoreLockHardLinkToOwnedJournalIsRejectedWithoutReleasingJournalLock() throws Exception {
        requireDirectorySync();
        var home = Files.createDirectory(directory.resolve("lock-aliases-journal"));
        var journalPath = home.resolve("transactions.journal");
        try (var journal = LocalJournal.create(journalPath, DATABASE)) {
            for (var record : history().subList(0, 2)) { journal.append(record); }
            var prefix = journal.prefix(2);
            try { Files.createLink(home.resolve(".meridiandb-checkpoints.lock"), journalPath); }
            catch (UnsupportedOperationException | FileSystemException unsupported) {
                assumeTrue(false, "Hard links unavailable: " + unsupported);
            }
            assertThrows(IOException.class, () -> {
                try (var unexpected = LocalCheckpointStore.open(home)) { unexpected.journalPath(); }
            });
            try (var contender = start("contend-journal", home)) {
                assertEquals(0, contender.awaitExit(), contender.diagnostics());
                assertTrue(contender.diagnostics().lines().anyMatch("JOURNAL LOCKED"::equals), contender.diagnostics());
            }
            assertEquals(prefix, journal.prefix(2));
            assertEquals(2, journal.force());
        }
    }

    @Test
    void rejectedDependencyHardLinksCannotReleaseJournalOrStoreProcessLocks() throws Exception {
        requireDirectorySync();
        for (boolean linkToJournal : new boolean[] {true, false}) {
            var home = Files.createDirectory(directory.resolve(linkToJournal ? "journal-alias" : "store-lock-alias"));
            try (var store = LocalCheckpointStore.open(home);
                 var journal = LocalJournal.create(store.journalPath(), DATABASE)) {
                var records = history().subList(0, 2);
                for (var record : records) { journal.append(record); }
                var segment = new ImmutableSegment(DATABASE, records);
                byte[] encoded = SegmentCodec.encode(segment);
                var id = SegmentCodec.decode(encoded).id();
                var alias = home.resolve(LocalCheckpointStore.segmentName(id));
                var target = linkToJournal ? store.journalPath() : home.resolve(".meridiandb-checkpoints.lock");
                try { Files.createLink(alias, target); }
                catch (UnsupportedOperationException | FileSystemException unsupported) {
                    assumeTrue(false, "Hard links unavailable: " + unsupported);
                }
                var prefix = journal.prefix(2);
                var manifest = new CheckpointManifest(DATABASE, 1,
                        new JournalPrefix(prefix.sequence(), prefix.byteLength(), prefix.sha256()),
                        List.of(new SegmentReference(id, encoded.length, 1, 2)));
                assertThrows(IOException.class, () -> store.publish(manifest, journal));
                // Reading and then closing a second POSIX same-file channel can release
                // the owner's process lock. A process contender proves both locks remain.
                try (var contender = start("contend", home)) {
                    assertEquals(0, contender.awaitExit(), contender.diagnostics());
                    assertTrue(contender.diagnostics().lines().anyMatch("LOCKED"::equals), contender.diagnostics());
                }
                try (var contender = start("contend-journal", home)) {
                    assertEquals(0, contender.awaitExit(), contender.diagnostics());
                    assertTrue(contender.diagnostics().lines().anyMatch("JOURNAL LOCKED"::equals), contender.diagnostics());
                }
                assertEquals(prefix, journal.prefix(2));
                assertEquals(2, journal.force());
            }
        }
    }

    @Test
    void abruptHaltAfterSuccessfulPublicationReopensExactManifestPrefixAndRecords() throws Exception {
        requireDirectorySync();
        String publication;
        try (var child = start("publish-halt")) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            publication = child.diagnostics().lines().filter(line -> line.startsWith("PUBLISHED ")).findFirst().orElseThrow();
        }
        var expectedId = new ManifestId(publication.substring("PUBLISHED ".length()));
        try (var store = LocalCheckpointStore.open(directory);
             var journal = LocalJournal.open(store.journalPath());
             var reader = store.readLatest(journal).orElseThrow()) {
            assertEquals(expectedId, reader.manifestId());
            assertEquals(1, reader.manifest().generation());
            assertEquals(2, reader.manifest().journalPrefix().sequence());
            var prefix = journal.prefix(2);
            assertEquals(prefix.byteLength(), reader.manifest().journalPrefix().byteLength());
            assertEquals(prefix.sha256(), reader.manifest().journalPrefix().sha256());
            try (var segment = reader.openSegment(0)) {
                assertEquals(history().subList(0, 2), segment.segment().records());
            }
            var replayed = new ArrayList<JournalRecord>();
            journal.replay(replayed::add);
            assertEquals(history().subList(0, 2), replayed);
            journal.append(history().get(2));
            journal.force();
            try (var unchanged = store.readLatest(journal).orElseThrow()) {
                assertEquals(expectedId, unchanged.manifestId());
                assertEquals(2, unchanged.manifest().journalPrefix().sequence());
            }
        }
    }

    private void requireDirectorySync() throws IOException {
        try (var store = LocalCheckpointStore.open(directory)) {
            assertEquals(directory.toRealPath(), store.journalPath().getParent());
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
        }
    }

    private void assertStoreOpenRejected() {
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalCheckpointStore.open(directory)) {
                unexpected.journalPath();
            }
        });
    }

    private Child start(String mode) throws IOException {
        return start(mode, directory);
    }

    private Child start(String mode, Path home) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        var output = home.resolve(mode + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath,
                CheckpointProcess.class.getName(), mode, home.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private static List<JournalRecord> history() {
        var entity = new EntityId("salary");
        return List.of(
                new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(null, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(0L, 10L), 120),
                        new WriteOperation.Retract(new EntityId("absent"), new ValidTimeInterval(null, null))))),
                new JournalRecord(3, new Transaction(new RequestId("after-reopen"), List.of(
                        new WriteOperation.Retract(entity, new ValidTimeInterval(2L, 4L))))));
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Path output;
        Child(Process process, Path output) { this.process = process; this.output = output; }

        String diagnostics() {
            try { return Files.readString(output, StandardCharsets.UTF_8); }
            catch (IOException failure) { return "Cannot read child output: " + failure; }
        }

        void awaitOutput(String marker) throws InterruptedException {
            long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (diagnostics().lines().anyMatch(marker::equals)) { return; }
                if (!process.isAlive()) { fail("Child exited before " + marker + ": " + diagnostics()); }
                Thread.sleep(10);
            }
            fail("Child did not report " + marker + ": " + diagnostics());
        }

        void release() throws IOException {
            var input = process.getOutputStream();
            input.write('R');
            input.flush();
            input.close();
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

    public static final class CheckpointProcess {
        private CheckpointProcess() { }
        public static void main(String[] args) throws Exception {
            var path = Path.of(args[1]);
            switch (args[0]) {
                case "hold" -> {
                    try (var store = LocalCheckpointStore.open(path)) {
                        store.journalPath();
                        System.out.println("HELD");
                        System.out.flush();
                        if (System.in.read() != 'R') { throw new IOException("Parent did not release owner"); }
                    }
                }
                case "contend" -> {
                    LocalCheckpointStore opened;
                    try { opened = LocalCheckpointStore.open(path); }
                    catch (IOException locked) { System.out.println("LOCKED"); return; }
                    try (opened) { opened.journalPath(); System.out.println("UNEXPECTEDLY OPENED"); }
                    System.exit(2);
                }
                case "contend-journal" -> {
                    LocalJournal opened;
                    try { opened = LocalJournal.open(path.resolve("transactions.journal")); }
                    catch (IOException locked) { System.out.println("JOURNAL LOCKED"); return; }
                    try (opened) { opened.prefix(0); System.out.println("UNEXPECTEDLY OPENED JOURNAL"); }
                    System.exit(2);
                }
                case "publish-halt" -> {
                    var store = LocalCheckpointStore.open(path);
                    var journal = LocalJournal.create(store.journalPath(), DATABASE);
                    var records = history().subList(0, 2);
                    for (var record : records) { journal.append(record); }
                    var segment = new ImmutableSegment(DATABASE, records);
                    var id = store.writeSegment(segment);
                    var prefix = journal.prefix(2);
                    var manifest = new CheckpointManifest(DATABASE, 1,
                            new JournalPrefix(prefix.sequence(), prefix.byteLength(), prefix.sha256()),
                            List.of(new SegmentReference(id, SegmentCodec.encode(segment).length, 1, 2)));
                    var published = store.publish(manifest, journal);
                    System.out.println("PUBLISHED " + published.value());
                    System.out.flush();
                    Runtime.getRuntime().halt(HALT_STATUS);
                }
                default -> throw new IllegalArgumentException("Unknown child mode: " + args[0]);
            }
        }
    }
}
