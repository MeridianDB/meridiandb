// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.segment.LocalSegmentReader;
import org.meridiandb.storage.segment.SegmentCodec;

/** Real shared/exclusive process barriers; none of these checks delete production history. */
@Timeout(90)
class LocalReaderProtectionIntegrationTest {
    private static final int HALT_STATUS = 77;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);
    private static final String GUARD = ".meridiandb-readers.lock";

    @TempDir
    Path directory;

    @Test
    void sameJvmReadersBlockExclusiveAdmissionUntilTheLastReferenceCloses() throws Exception {
        var fixture = fixture("database");
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            var first = store.readLatest(journal).orElseThrow();
            var second = store.readLatest(journal).orElseThrow();
            try {
                assertTrue(store.tryReclamationBarrier().isEmpty());
                first.close();
                first.close();
                assertTrue(store.tryReclamationBarrier().isEmpty());
                try (var heap = second.openSegment(0)) { assertEquals(List.of(LocalCompactionIntegrationTest.HISTORY.getFirst()), heap.segment().records()); }
                second.close();
                try (var exclusive = eventuallyExclusive(store)) { exclusive.checkValid(); }
            } finally { first.close(); second.close(); }
        }
        assertTrue(Files.isRegularFile(fixture.home().resolve(GUARD), LinkOption.NOFOLLOW_LINKS));
        assertEquals(0, Files.size(fixture.home().resolve(GUARD)));
    }

    @Test
    void readerSurvivesStoreCloseAndLaterPublicationWhileReturnedHeapChildOutlivesReader() throws Exception {
        var fixture = fixture("database");
        LocalCheckpointReader old;
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            old = store.readLatest(journal).orElseThrow();
        }
        try {
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                assertTrue(store.tryReclamationBarrier().isEmpty());
                store.publishCompaction(fixture.plan(), journal);
                try (var latest = store.readLatest(journal).orElseThrow()) { assertEquals(3, latest.manifest().generation()); }
            }
            assertEquals(2, old.manifest().generation());
            var heap = old.openSegment(1);
            try {
                old.close();
                assertThrows(IllegalStateException.class, old::manifest);
                assertThrows(IllegalStateException.class, () -> old.openSegment(0));
                assertEquals(List.of(LocalCompactionIntegrationTest.HISTORY.get(1)), heap.segment().records());
                try (var store = LocalCheckpointStore.open(fixture.home()); var exclusive = eventuallyExclusive(store)) { exclusive.checkValid(); }
            } finally { heap.close(); }
        } finally { old.close(); }
        LocalCompactionIntegrationTest.assertOriginalObjectsRetained(fixture);
    }

    @Test
    void emptySelectionDoesNotCreateReaderMetadata() throws IOException {
        Path home = home("database");
        try (var store = LocalCheckpointStore.open(home);
             var journal = LocalJournal.create(store.journalPath(), LocalCompactionIntegrationTest.DATABASE)) {
            assertTrue(store.readLatest(journal).isEmpty());
            assertFalse(Files.exists(home.resolve(GUARD), LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Test
    void exclusiveGuardCreatesPermanentEmptyRegularMetadataAndReleasesIdempotently() throws IOException {
        Path home = home("database");
        try (var store = LocalCheckpointStore.open(home)) {
            assertFalse(Files.exists(home.resolve(GUARD), LinkOption.NOFOLLOW_LINKS));
            var exclusive = store.tryReclamationBarrier().orElseThrow();
            try {
                exclusive.checkValid();
                assertTrue(Files.isRegularFile(home.resolve(GUARD), LinkOption.NOFOLLOW_LINKS));
                assertEquals(0, Files.size(home.resolve(GUARD)));
                assertTrue(store.tryReclamationBarrier().isEmpty());
            } finally { exclusive.close(); }
            exclusive.close();
            assertThrows(IOException.class, exclusive::checkValid);
            try (var acquiredAgain = store.tryReclamationBarrier().orElseThrow()) { acquiredAgain.checkValid(); }
        }
        assertTrue(Files.exists(home.resolve(GUARD), LinkOption.NOFOLLOW_LINKS));
        assertEquals(0, Files.size(home.resolve(GUARD)));
    }

    @Test
    void failedGraphVerificationLeavesNoReaderLeaseOrStoreOwnershipBehind() throws Exception {
        var fixture = fixture("database");
        Path selected = fixture.home().resolve(LocalCheckpointStore.generationName(2, fixture.sourceId()));
        byte[] original = Files.readAllBytes(selected);
        byte[] corrupt = original.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(selected, corrupt);
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertThrows(ManifestFormatException.class, () -> store.readLatest(journal));
        }
        Files.write(selected, original);
        try (var store = LocalCheckpointStore.open(fixture.home()); var exclusive = eventuallyExclusive(store)) { exclusive.checkValid(); }
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath());
             var reader = store.readLatest(journal).orElseThrow()) { assertEquals(fixture.sourceId(), reader.manifestId()); }
    }

    @Test
    void nonemptyReaderGuardIsRejectedWithoutChangingItsEvidence() throws Exception {
        var fixture = fixture("database");
        byte[] evidence = {4, 2, 9};
        Files.write(fixture.home().resolve(GUARD), evidence); // No lease is held at this fixture mutation.
        assertAdmissionRejected(fixture.home());
        assertArrayEquals(evidence, Files.readAllBytes(fixture.home().resolve(GUARD)));
        Files.write(fixture.home().resolve(GUARD), new byte[0]);
        try (var store = LocalCheckpointStore.open(fixture.home()); var exclusive = eventuallyExclusive(store)) { exclusive.checkValid(); }
    }

    @Test
    void guardDirectoryAndSymlinkAreRejectedBeforeFollowingOrOpeningThem() throws Exception {
        var asDirectory = fixture("guard-directory");
        Files.delete(asDirectory.home().resolve(GUARD));
        Files.createDirectory(asDirectory.home().resolve(GUARD));
        assertAdmissionRejected(asDirectory.home());
        assertTrue(Files.isDirectory(asDirectory.home().resolve(GUARD), LinkOption.NOFOLLOW_LINKS));
        var asSymlink = fixture("guard-symlink");
        Path target = Files.createFile(directory.resolve("external-empty-guard"));
        Files.delete(asSymlink.home().resolve(GUARD));
        try { Files.createSymbolicLink(asSymlink.home().resolve(GUARD), target); }
        catch (UnsupportedOperationException unsupported) { assumeTrue(false, "Symbolic link operation unavailable: " + unsupported); }
        assertAdmissionRejected(asSymlink.home());
        assertTrue(Files.isSymbolicLink(asSymlink.home().resolve(GUARD)));
        assertEquals(0, Files.size(target));
    }

    @Test
    void guardAliasesToJournalOrStoreLockCannotDropTheirOriginalProcessLocks() throws Exception {
        for (boolean journalAlias : new boolean[] {true, false}) {
            var fixture = fixture(journalAlias ? "guard-journal" : "guard-lock");
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                Path guard = fixture.home().resolve(GUARD);
                Files.delete(guard); // There are no admitted readers yet.
                Path target = journalAlias ? store.journalPath() : fixture.home().resolve(".meridiandb-checkpoints.lock");
                createHardLink(guard, target);
                var prefix = journal.prefix(3);
                assertThrows(IOException.class, () -> store.readLatest(journal));
                // Remove only the deliberately invalid alias, without opening its inode.
                // The independent contender now tests the actual owner lock rather than
                // being rejected merely because malformed reader metadata still exists.
                Files.delete(guard);
                try (var contender = start(journalAlias ? "contend-journal" : "contend-store", fixture.home(), "owner-contender")) {
                    assertEquals(0, contender.awaitExit(), contender.diagnostics());
                    assertTrue(contender.hasLine("LOCKED"), contender.diagnostics());
                }
                assertEquals(prefix, journal.prefix(3));
                assertEquals(3, journal.force());
            }
        }
    }

    @Test
    void anotherStoreGuardCannotAliasAnActiveStoreLockOrQuarantineEitherStore() throws Exception {
        var ownerFixture = fixture("owner-store");
        var otherFixture = fixture("other-store");
        Path alias = otherFixture.home().resolve(GUARD);
        try (var owner = LocalCheckpointStore.open(ownerFixture.home()); var journal = LocalJournal.open(owner.journalPath())) {
            var prefix = journal.prefix(3);
            Files.delete(alias); // Neither fixture has an admitted reader at this point.
            createHardLink(alias, ownerFixture.home().resolve(".meridiandb-checkpoints.lock"));
            try { assertAdmissionRejected(otherFixture.home()); }
            finally { Files.deleteIfExists(alias); }
            // With the malformed alias removed, only the original OS owner lock can
            // reject this independent process; the process-local registry cannot help.
            try (var contender = start("contend-store", ownerFixture.home(), "cross-store-owner")) {
                assertEquals(0, contender.awaitExit(), contender.diagnostics());
                assertTrue(contender.hasLine("LOCKED"), contender.diagnostics());
            }
            assertEquals(prefix, journal.prefix(3));
            assertEquals(3, journal.force());
            try (var exclusive = eventuallyExclusive(owner)) { exclusive.checkValid(); }
            try (var other = LocalCheckpointStore.open(otherFixture.home()); var otherJournal = LocalJournal.open(other.journalPath());
                 var reader = other.readLatest(otherJournal).orElseThrow()) {
                assertEquals(otherFixture.sourceId(), reader.manifestId());
            }
            try (var reader = owner.readLatest(journal).orElseThrow()) { assertEquals(ownerFixture.sourceId(), reader.manifestId()); }
        }
        try (var owner = LocalCheckpointStore.open(ownerFixture.home()); var exclusive = eventuallyExclusive(owner)) { exclusive.checkValid(); }
    }

    @Test
    void journalOpenOfAnAliasToALiveReaderGuardCannotReleaseItsProcessLock() throws Exception {
        var fixture = fixture("database");
        LocalCheckpointReader reader;
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            reader = store.readLatest(journal).orElseThrow();
        }
        Path alias = directory.resolve("reader-guard-as-journal");
        try {
            createHardLink(alias, fixture.home().resolve(GUARD));
            assertThrows(IOException.class, () -> {
                try (var unexpected = LocalJournal.open(alias)) { unexpected.prefix(0); }
            });
            // Unlink without opening the guard inode, then prove the retained OS
            // lease survives the rejected acquisition through the other adapter.
            Files.delete(alias);
            try (var contender = start("probe-exclusive", fixture.home(), "reverse-journal-alias")) {
                assertEquals(0, contender.awaitExit(), contender.diagnostics());
                assertTrue(contender.hasLine("BLOCKED"), contender.diagnostics());
            }
            assertEquals(fixture.sourceId(), reader.manifestId());
            try (var heap = reader.openSegment(0)) { assertEquals(List.of(LocalCompactionIntegrationTest.HISTORY.getFirst()), heap.segment().records()); }
        } finally { Files.deleteIfExists(alias); reader.close(); }
        try (var store = LocalCheckpointStore.open(fixture.home()); var exclusive = eventuallyExclusive(store)) { exclusive.checkValid(); }
    }

    @Test
    void rejectedSegmentManifestAndLazyAliasesCannotReleaseAnAdmittedReaderProcessLock() throws Exception {
        for (String kind : List.of("segment", "manifest", "lazy")) {
            var fixture = fixture("object-alias-" + kind);
            LocalCheckpointReader reader = null;
            Path alias = null;
            byte[] restore = null;
            try {
                try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                    reader = store.readLatest(journal).orElseThrow();
                    if (kind.equals("manifest")) {
                        var id = ManifestCodec.decode(ManifestCodec.encode(fixture.plan().replacementManifest())).id();
                        alias = fixture.home().resolve(LocalCheckpointStore.generationName(3, id));
                    } else if (kind.equals("segment")) {
                        alias = fixture.home().resolve(LocalCheckpointStore.segmentName(fixture.plan().replacementManifest().segments().getFirst().id()));
                    } else {
                        alias = fixture.home().resolve(LocalCheckpointStore.segmentName(fixture.source().segments().getFirst().id()));
                        restore = SegmentCodec.encode(new org.meridiandb.storage.segment.ImmutableSegment(
                                LocalCompactionIntegrationTest.DATABASE, List.of(LocalCompactionIntegrationTest.HISTORY.getFirst())));
                        Files.delete(alias);
                    }
                    createHardLink(alias, fixture.home().resolve(GUARD));
                    if (kind.equals("manifest")) {
                        assertThrows(IOException.class, () -> store.readLatest(journal));
                    } else if (kind.equals("segment")) {
                        assertThrows(IOException.class, () -> store.publishCompaction(fixture.plan(), journal));
                    } else {
                        var admitted = reader;
                        assertThrows(IOException.class, () -> admitted.openSegment(0));
                    }
                }
                // Unlink only the malformed alias. Do not open any raw guard/alias handle
                // while the original shared lease is alive; a POSIX close could unlock it.
                Files.delete(alias);
                alias = null;
                if (restore != null) {
                    Files.write(fixture.home().resolve(LocalCheckpointStore.segmentName(fixture.source().segments().getFirst().id())), restore);
                }
                try (var contender = start("probe-exclusive", fixture.home(), "guard-contender")) {
                    assertEquals(0, contender.awaitExit(), contender.diagnostics());
                    assertTrue(contender.hasLine("BLOCKED"), contender.diagnostics());
                }
                try (var heap = reader.openSegment(0)) { assertEquals(List.of(LocalCompactionIntegrationTest.HISTORY.getFirst()), heap.segment().records()); }
            } finally {
                if (alias != null) { Files.deleteIfExists(alias); }
                if (reader != null) { reader.close(); }
            }
            try (var store = LocalCheckpointStore.open(fixture.home()); var exclusive = eventuallyExclusive(store)) { exclusive.checkValid(); }
        }
    }

    @Test
    void crossProcessReaderAllowsNewPublicationAndSharedAdmissionButBlocksExclusiveUntilFinalClose() throws Exception {
        var fixture = fixture("database");
        LocalCheckpointReader parentReader = null;
        try (var child = start("hold-reader", fixture.home(), "reader-owner")) {
            child.awaitOutput("HELD");
            try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
                assertTrue(store.tryReclamationBarrier().isEmpty());
                parentReader = store.readLatest(journal).orElseThrow();
                store.publishCompaction(fixture.plan(), journal);
                try (var newest = store.readLatest(journal).orElseThrow()) { assertEquals(3, newest.manifest().generation()); }
                assertTrue(store.tryReclamationBarrier().isEmpty());
            }
            child.command('R');
            assertEquals(0, child.awaitExit(), child.diagnostics());
            assertTrue(child.hasLine("LAZY OLD READER OK"), child.diagnostics());
            assertTrue(child.hasLine("HEAP CHILD OK"), child.diagnostics());
            try (var store = LocalCheckpointStore.open(fixture.home())) {
                assertTrue(store.tryReclamationBarrier().isEmpty(), "The parent shared reference remains live");
                try (var heap = parentReader.openSegment(1)) { assertEquals(List.of(LocalCompactionIntegrationTest.HISTORY.get(1)), heap.segment().records()); }
                parentReader.close();
                try (var exclusive = eventuallyExclusive(store)) { exclusive.checkValid(); }
            }
        } finally { if (parentReader != null) { parentReader.close(); } }
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
    }

    @Test
    void forcedReaderProcessExitEventuallyReleasesItsOsLeaseWithoutPidOrTimerInference() throws Exception {
        var fixture = fixture("database");
        try (var child = start("halt-reader", fixture.home(), "halting-owner")) {
            child.awaitOutput("HELD");
            try (var store = LocalCheckpointStore.open(fixture.home())) { assertTrue(store.tryReclamationBarrier().isEmpty()); }
            child.command('H');
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
        }
        try (var store = LocalCheckpointStore.open(fixture.home()); var exclusive = eventuallyExclusive(store)) { exclusive.checkValid(); }
        LocalCompactionIntegrationTest.verifyWritableHistory(fixture.home());
    }

    @Test
    void lazyOpenRacingWithCloseReturnsACompleteHeapChildOrRejectsBeforeOpening() throws Exception {
        var fixture = fixture("database");
        var executor = Executors.newFixedThreadPool(2);
        try (var store = LocalCheckpointStore.open(fixture.home()); var journal = LocalJournal.open(store.journalPath())) {
            for (int attempt = 0; attempt < 16; attempt++) {
                var reader = store.readLatest(journal).orElseThrow();
                var barrier = new CyclicBarrier(2);
                try {
                    var opening = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try { return reader.openSegment(0); }
                        catch (IllegalStateException alreadyClosed) { return null; }
                    });
                    var closing = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); reader.close(); return null; });
                    LocalSegmentReader heap = opening.get(5, TimeUnit.SECONDS);
                    closing.get(5, TimeUnit.SECONDS);
                    if (heap != null) {
                        try (heap) { assertEquals(List.of(LocalCompactionIntegrationTest.HISTORY.getFirst()), heap.segment().records()); }
                    }
                    assertThrows(IllegalStateException.class, reader::manifest);
                    try (var exclusive = eventuallyExclusive(store)) { exclusive.checkValid(); }
                } finally { reader.close(); }
            }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private void assertAdmissionRejected(Path home) {
        assertThrows(IOException.class, () -> {
            try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath());
                 var unexpected = store.readLatest(journal).orElseThrow()) { unexpected.manifestId(); }
        });
    }

    private LocalCompactionIntegrationTest.Fixture fixture(String name) throws IOException {
        try { return LocalCompactionIntegrationTest.seedFixture(home(name), true); }
        catch (LocalCheckpointStore.SharedReaderLockUnsupportedException unsupported) {
            assumeTrue(false, "Required shared reader locking is unavailable: " + unsupported);
            throw new AssertionError("Assumption did not abort", unsupported);
        }
    }

    private Path home(String name) throws IOException {
        Path capability = Files.createDirectory(directory.resolve("capability-" + name));
        try (var store = LocalCheckpointStore.open(capability)) { store.journalPath(); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Required directory force is unavailable: " + unsupported);
        }
        return Files.createDirectory(directory.resolve(name));
    }

    private static void createHardLink(Path link, Path target) throws IOException {
        try { Files.createLink(link, target); }
        catch (UnsupportedOperationException unsupported) { assumeTrue(false, "Hard-link operation unavailable: " + unsupported); }
    }

    private static LocalReaderBarrier.Protection eventuallyExclusive(LocalCheckpointStore store) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            var exclusive = store.tryReclamationBarrier();
            if (exclusive.isPresent()) { return exclusive.orElseThrow(); }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("OS reader protection was not released within the bounded retry window");
    }

    private Child start(String mode, Path home, String label) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java.exe"); }
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        Path output = directory.resolve(home.getFileName() + "-" + label + "-" + mode + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, ReaderProcess.class.getName(), mode,
                home.toAbsolutePath().toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
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
        void awaitOutput(String marker) throws InterruptedException {
            long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (hasLine(marker)) { return; }
                if (!process.isAlive()) { fail("Reader child exited before " + marker + ": " + diagnostics()); }
                Thread.sleep(10);
            }
            fail("Reader child did not report " + marker + ": " + diagnostics());
        }
        void command(char value) throws IOException {
            var input = process.getOutputStream();
            input.write(value);
            input.flush();
        }
        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    () -> "Reader child did not exit: " + diagnostics());
            return process.exitValue();
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) { throw new IOException("Reader child did not terminate: " + diagnostics()); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating reader child", interrupted);
            } finally {
                process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close();
            }
        }
    }

    public static final class ReaderProcess {
        private ReaderProcess() { }
        public static void main(String[] args) throws Exception {
            Path home = Path.of(args[1]);
            switch (args[0]) {
                case "hold-reader", "halt-reader" -> {
                    LocalCheckpointReader retained;
                    try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
                        retained = store.readLatest(journal).orElseThrow();
                    }
                    try {
                        System.out.println("HELD"); System.out.flush();
                        int command = System.in.read();
                        if (command == 'H' && args[0].equals("halt-reader")) { Runtime.getRuntime().halt(HALT_STATUS); }
                        if (command != 'R') { throw new IOException("Parent did not release the held reader"); }
                        try (var first = retained.openSegment(0)) {
                            if (!first.segment().records().equals(List.of(LocalCompactionIntegrationTest.HISTORY.getFirst()))) {
                                throw new AssertionError("Old lazy history differs after publication");
                            }
                        }
                        System.out.println("LAZY OLD READER OK"); System.out.flush();
                        var heap = retained.openSegment(1);
                        try {
                            retained.close();
                            if (!heap.segment().records().equals(List.of(LocalCompactionIntegrationTest.HISTORY.get(1)))) {
                                throw new AssertionError("Heap child did not survive reader close");
                            }
                            System.out.println("HEAP CHILD OK"); System.out.flush();
                        } finally { heap.close(); }
                    } finally { retained.close(); }
                }
                case "probe-exclusive" -> {
                    try (var store = LocalCheckpointStore.open(home)) {
                        var exclusive = store.tryReclamationBarrier();
                        if (exclusive.isEmpty()) { System.out.println("BLOCKED"); }
                        else { try (var protection = exclusive.orElseThrow()) { protection.checkValid(); System.out.println("AVAILABLE"); } }
                    }
                }
                case "contend-journal" -> {
                    LocalJournal opened;
                    try { opened = LocalJournal.open(home.resolve("transactions.journal")); }
                    catch (IOException locked) { System.out.println("LOCKED"); return; }
                    try (opened) { opened.prefix(0); }
                    System.exit(2);
                }
                case "contend-store" -> {
                    LocalCheckpointStore opened;
                    try { opened = LocalCheckpointStore.open(home); }
                    catch (IOException locked) { System.out.println("LOCKED"); return; }
                    try (opened) { opened.journalPath(); }
                    System.exit(2);
                }
                default -> throw new IllegalArgumentException("Unknown reader-process mode: " + args[0]);
            }
        }
    }
}
