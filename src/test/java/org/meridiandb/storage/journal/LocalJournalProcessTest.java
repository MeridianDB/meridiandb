// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Real JVM boundaries establish process behavior, not device power-loss guarantees. */
@Timeout(60)
class LocalJournalProcessTest {
    private static final int FORCED_HALT_STATUS = 73;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path directory;

    @Test
    void anotherJvmHoldsExclusiveOwnershipUntilItReleasesTheJournal() throws Exception {
        var path = directory.resolve("held.journal");
        createEmpty(path);
        try (var child = start("hold", path)) {
            child.awaitOutput("HELD");
            assertOpenRejected(path);
            child.release();
            assertEquals(0, child.awaitExit(), child.diagnostics());
        }
        try (var reopened = LocalJournal.open(path)) {
            var records = new ArrayList<JournalRecord>();
            reopened.replay(records::add);
            assertEquals(List.of(), records);
        }
    }

    @Test
    void aRejectedSameJvmOpenDoesNotReleaseTheExistingWritersProcessLock() throws Exception {
        var path = directory.resolve("same-jvm.journal");
        try (var owner = LocalJournal.create(path, new DatabaseId("same-jvm"))) {
            assertOpenRejected(path);
            try (var contender = start("contend", path)) {
                assertEquals(0, contender.awaitExit(), contender.diagnostics());
                assertTrue(contender.diagnostics().lines().anyMatch("LOCKED"::equals), contender.diagnostics());
            }
            // The failed attempts must leave the original writer usable as well as locked.
            owner.append(history().getFirst());
            assertEquals(1L, owner.force());
        }
    }

    @Test
    void abruptJvmHaltAfterForcePreservesTheExactPrefixAndAllowsFurtherAppends() throws Exception {
        var path = directory.resolve("halted.journal");
        try (var child = start("force-halt", path)) {
            assertEquals(FORCED_HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.diagnostics().lines().anyMatch("FORCED 2"::equals), child.diagnostics());
        }
        var expected = history();
        try (var reopened = LocalJournal.open(path)) {
            var recovered = new ArrayList<JournalRecord>();
            reopened.replay(recovered::add);
            assertEquals(expected.subList(0, 2), recovered);
            reopened.append(expected.get(2));
            assertEquals(3L, reopened.force());
        }
        try (var reopened = LocalJournal.open(path)) {
            var recovered = new ArrayList<JournalRecord>();
            reopened.replay(recovered::add);
            assertEquals(expected, recovered);
        }
    }

    private static void createEmpty(Path path) throws IOException {
        try (var journal = LocalJournal.create(path, new DatabaseId("held"))) {
            assertEquals(0L, journal.force());
        }
    }

    private static void assertOpenRejected(Path path) {
        assertThrows(IOException.class, () -> {
            // Close an unexpectedly successful open so a failed test cannot leak its handle.
            try (var unexpected = LocalJournal.open(path)) {
                unexpected.replay(ignored -> { });
            }
        });
    }

    private Child start(String mode, Path journal) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        var output = directory.resolve(mode + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath,
                JournalProcess.class.getName(), mode, journal.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        return new Child(process, output);
    }

    private static List<JournalRecord> history() {
        var alice = new EntityId("alice");
        var bob = new EntityId("bob");
        return List.of(
                new JournalRecord(1, new Transaction(new RequestId("first"), List.of(
                        new WriteOperation.Put(alice, new ValidTimeInterval(null, null), 7)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(alice, new ValidTimeInterval(0L, 10L), 11),
                        new WriteOperation.Retract(bob, new ValidTimeInterval(null, null))))),
                new JournalRecord(3, new Transaction(new RequestId("after-reopen"), List.of(
                        new WriteOperation.Retract(alice, new ValidTimeInterval(2L, 4L))))));
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Path output;

        private Child(Process process, Path output) {
            this.process = process;
            this.output = output;
        }

        private String diagnostics() {
            try {
                return Files.readString(output, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                return "Cannot read child output: " + failure;
            }
        }

        private void awaitOutput(String marker) throws InterruptedException {
            long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (diagnostics().lines().anyMatch(marker::equals)) {
                    return;
                }
                if (!process.isAlive()) {
                    fail("Child exited before " + marker + ": " + diagnostics());
                }
                Thread.sleep(10);
            }
            fail("Child did not report " + marker + ": " + diagnostics());
        }

        private void release() throws IOException {
            var input = process.getOutputStream();
            input.write('R');
            input.flush();
            input.close();
        }

        private int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    () -> "Child did not exit: " + diagnostics());
            return process.exitValue();
        }

        @Override
        public void close() throws IOException {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Child did not terminate: " + diagnostics());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating child JVM", interrupted);
            } finally {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    /** Invoked only by the process tests, with the same compiled test runtime classpath. */
    public static final class JournalProcess {
        private JournalProcess() { }

        public static void main(String[] args) throws Exception {
            var path = Path.of(args[1]);
            switch (args[0]) {
                case "hold" -> {
                    try (var journal = LocalJournal.open(path)) {
                        journal.replay(ignored -> { });
                        System.out.println("HELD");
                        System.out.flush();
                        if (System.in.read() != 'R') {
                            throw new IOException("Parent did not release the held journal");
                        }
                    }
                }
                case "contend" -> {
                    LocalJournal opened;
                    try {
                        opened = LocalJournal.open(path);
                    } catch (IOException locked) {
                        System.out.println("LOCKED");
                        return;
                    }
                    try (opened) {
                        opened.replay(ignored -> { });
                        System.out.println("UNEXPECTEDLY OPENED");
                    }
                    System.exit(2);
                }
                case "force-halt" -> {
                    var journal = LocalJournal.create(path, new DatabaseId("child-lineage"));
                    var records = history();
                    journal.append(records.getFirst());
                    journal.append(records.get(1));
                    if (journal.force() != 2L) {
                        throw new AssertionError("Expected forced sequence 2");
                    }
                    System.out.println("FORCED 2");
                    System.out.flush();
                    // Deliberately bypass finally/close/shutdown hooks after force has returned.
                    Runtime.getRuntime().halt(FORCED_HALT_STATUS);
                }
                default -> throw new IllegalArgumentException("Unknown test process mode: " + args[0]);
            }
        }
    }
}
