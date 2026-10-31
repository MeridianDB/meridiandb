// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.internal.LocalFileOwnership;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.temporal.DatabaseId;

class LocalObjectStoreConformanceTest extends AbstractImmutableObjectStoreConformance {
    @TempDir Path directory;

    @Override protected ImmutableObjectStore store() throws IOException { return new LocalObjectStore(directory); }

    @Test
    void namespaceMustAlreadyBeAnExistingDirectory() throws IOException {
        Path missing = directory.resolve("missing");
        assertThrows(NoSuchFileException.class, () -> new LocalObjectStore(missing));
        assertFalse(Files.exists(missing));
        Path regular = directory.resolve("regular");
        Files.write(regular, ABC);
        assertThrows(IOException.class, () -> new LocalObjectStore(regular));
        assertThrows(NullPointerException.class, () -> new LocalObjectStore(null));
    }

    @Test
    void missingFilesAndDirectoryObjectsAreDistinctFromValidImages() throws IOException {
        var backend = store();
        var missing = new ObjectKey("absent.bin");
        assertThrows(NoSuchFileException.class, () -> backend.read(missing, ABC_REFERENCE));
        assertFalse(Files.exists(directory.resolve(missing.value())));
        var folder = new ObjectKey("folder");
        Files.createDirectory(directory.resolve(folder.value()));
        assertThrows(IOException.class, () -> backend.read(folder, ABC_REFERENCE));
        assertThrows(ObjectAlreadyExistsException.class, () -> backend.createNew(folder, ABC));
        assertThrows(IOException.class, () -> backend.createOrVerify(folder, ABC));
        assertTrue(Files.isDirectory(directory.resolve(folder.value())));
    }

    @Test
    void corruptAndIncompleteEvidenceIsRetainedWithoutRepair() throws IOException {
        var backend = store();
        for (byte[] damaged : new byte[][] {new byte[0], {97, 98}, {97, 98, 100}, {97, 98, 99, 0}}) {
            var key = new ObjectKey("damage-" + damaged.length + ".bin");
            Path path = directory.resolve(key.value());
            Files.write(path, damaged);
            assertThrows(ObjectIntegrityException.class, () -> backend.read(key, ABC_REFERENCE));
            assertThrows(ObjectIntegrityException.class, () -> backend.createOrVerify(key, ABC));
            assertArrayEquals(damaged, Files.readAllBytes(path));
        }
    }

    @Test
    void heapSnapshotSurvivesDiskMutationAndDeletionButNewReadsFail() throws IOException {
        var key = new ObjectKey("snapshot.bin");
        var backend = store();
        backend.createNew(key, ABC);
        try (var reader = backend.read(key, ABC_REFERENCE)) {
            Path path = directory.resolve(key.value());
            Files.write(path, new byte[] {0, 0, 0}, StandardOpenOption.TRUNCATE_EXISTING);
            assertThrows(ObjectIntegrityException.class, () -> backend.read(key, ABC_REFERENCE));
            assertArrayEquals(ABC, reader.bytes());
            Files.delete(path);
            assertArrayEquals(ABC, reader.bytes());
            assertThrows(NoSuchFileException.class, () -> backend.read(key, ABC_REFERENCE));
        }
    }

    @Test
    void nullsAndOversizedInputFailBeforeCreatingAnyPath() throws IOException {
        var backend = store();
        var key = new ObjectKey("preflight.bin");
        assertThrows(NullPointerException.class, () -> backend.createNew(null, ABC));
        assertThrows(NullPointerException.class, () -> backend.createNew(key, null));
        assertThrows(NullPointerException.class, () -> backend.createOrVerify(null, ABC));
        assertThrows(NullPointerException.class, () -> backend.createOrVerify(key, null));
        assertThrows(NullPointerException.class, () -> backend.read(null, ABC_REFERENCE));
        assertThrows(NullPointerException.class, () -> backend.read(key, null));
        byte[] tooLarge = new byte[32 * 1024 * 1024 + 1];
        assertThrows(IllegalArgumentException.class, () -> backend.createNew(key, tooLarge));
        assertThrows(IllegalArgumentException.class, () -> backend.createOrVerify(key, tooLarge));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test
    void oversizedSparseFileIsRejectedBeforeReadingItsContents() throws IOException {
        var key = new ObjectKey("oversized.bin");
        Path path = directory.resolve(key.value());
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {1}), 32L * 1024 * 1024);
        }
        var failure = assertThrows(ObjectIntegrityException.class, () -> store().read(key, ABC_REFERENCE));
        assertEquals(ObjectIntegrityException.Code.LENGTH_MISMATCH, failure.code());
        assertEquals(32L * 1024 * 1024 + 1, Files.size(path));
    }

    @Test
    void symbolicLinkIsNotFollowedForReadCreateOrReuse() throws IOException {
        Path target = directory.resolve("target.bin");
        Files.write(target, ABC);
        var key = new ObjectKey("alias.bin");
        createLink(directory.resolve(key.value()), target, true);
        var backend = store();
        assertThrows(IOException.class, () -> backend.read(key, ABC_REFERENCE));
        assertThrows(ObjectAlreadyExistsException.class, () -> backend.createNew(key, ABC));
        assertThrows(IOException.class, () -> backend.createOrVerify(key, ABC));
        assertTrue(Files.isSymbolicLink(directory.resolve(key.value())));
        assertArrayEquals(ABC, Files.readAllBytes(target));
    }

    @Test
    void ownedControlPathIsRejectedBeforeAnySameFileChannelCanReleaseItsLock() throws Exception {
        Path path = directory.resolve("control.bin");
        Files.write(path, ABC);
        try (var owner = LocalFileOwnership.openControl(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = owner.channel().tryLock()) {
            assertNotNull(lock);
            assertOwnerPreserved(new ObjectKey("control.bin"), path, lock);
        }
        assertArrayEquals(ABC, Files.readAllBytes(path));
        try (var reader = store().read(new ObjectKey("control.bin"), ABC_REFERENCE)) {
            assertArrayEquals(ABC, reader.bytes());
        }
    }

    @Test
    void hardLinkToOwnedControlCannotBypassSameFileReservation() throws Exception {
        Path path = directory.resolve("control.bin");
        Files.write(path, ABC);
        var key = new ObjectKey("hard-alias.bin");
        createLink(directory.resolve(key.value()), path, false);
        try (var owner = LocalFileOwnership.openControl(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = owner.channel().tryLock()) {
            assertNotNull(lock);
            assertOwnerPreserved(key, path, lock);
        }
        assertArrayEquals(ABC, Files.readAllBytes(path));
    }

    @Test
    void liveJournalIdentityAndNativeWriterLockSurviveRejectedObjectOperations() throws Exception {
        Path path = directory.resolve("journal.bin");
        var lineage = new DatabaseId("object-alias-journal");
        var backend = store();
        var key = new ObjectKey("journal.bin");
        try (var journal = LocalJournal.create(path, lineage)) {
            for (var failure : new IOException[] {
                    assertThrows(IOException.class, () -> backend.read(key, ABC_REFERENCE)),
                    assertThrows(IOException.class, () -> backend.createNew(key, ABC)),
                    assertThrows(IOException.class, () -> backend.createOrVerify(key, ABC))}) {
                assertTrue(failure.getMessage().contains("already owned"), failure.toString());
            }
            assertLockedFromAnotherProcess(path);
            assertEquals(0, journal.force());
            assertEquals(lineage, journal.databaseId());
        }
        try (var reopened = LocalJournal.open(path)) { assertEquals(lineage, reopened.databaseId()); }
    }

    private void assertOwnerPreserved(ObjectKey key, Path path, FileLock lock) throws Exception {
        var backend = store();
        for (var failure : new IOException[] {
                assertThrows(IOException.class, () -> backend.read(key, ABC_REFERENCE)),
                assertThrows(IOException.class, () -> backend.createNew(key, ABC)),
                assertThrows(IOException.class, () -> backend.createOrVerify(key, ABC))}) {
            assertTrue(failure.getMessage().contains("already owned"), failure.toString());
        }
        assertTrue(lock.isValid());
        assertLockedFromAnotherProcess(path);
    }

    private static void createLink(Path link, Path target, boolean symbolic) throws IOException {
        try {
            if (symbolic) { Files.createSymbolicLink(link, target.getFileName()); }
            else { Files.createLink(link, target); }
        } catch (UnsupportedOperationException unsupported) {
            assumeTrue(false, "Link capability unavailable: " + unsupported);
        } catch (FileSystemException failure) {
            String reason = String.valueOf(failure.getReason()).toLowerCase(Locale.ROOT);
            if (reason.contains("not supported") || reason.contains("privilege")) {
                assumeTrue(false, "Link capability unavailable: " + failure);
            }
            throw failure;
        }
    }

    private static void assertLockedFromAnotherProcess(Path path) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var child = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                LocalObjectStoreConformanceTest.class.getName(), "probe-lock", path.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Lock probe timed out");
            String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertEquals("LOCKED", output.strip());
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(10, TimeUnit.SECONDS)); }
        }
    }

    /** A separate JVM verifies native ownership, because FileLock.isValid alone cannot detect POSIX release. */
    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 2 || !arguments[0].equals("probe-lock")) {
            throw new IllegalArgumentException("Expected probe-lock and a path");
        }
        try (var channel = FileChannel.open(Path.of(arguments[1]), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            try (var lock = channel.tryLock()) {
                if (lock != null) { throw new IOException("Parent native lock was released"); }
                System.out.println("LOCKED");
            }
        }
    }
}
