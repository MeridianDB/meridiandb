// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.internal.LocalFileOwnership;

/** Existing test files establish native locking, without fresh-enrollment or directory-durability claims. */
@Timeout(45)
class LocalS3CoordinationGuardNativeTest {
    private static final int HALTED = 74, UNSUPPORTED = 77;
    private static final Duration DEADLINE = Duration.ofSeconds(15);
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void soleNativeOwnerCanForceAndReleaseWithoutReplacingOrRemovingItsName(boolean shared) throws Exception {
        Path home = home("identity"); Object before = key(home);
        try (var lease = acquire(home, shared)) { lease.checkValid(); lease.force(); assertEquals(before, key(home)); }
        assertEquals(before, key(home)); assertEquals(0, Files.size(home.resolve(LocalS3CoordinationGuard.NAME)));
        assertEquals(List.of(LocalS3CoordinationGuard.NAME), names(home));
        try (var reopened = acquire(home, false)) { reopened.checkValid(); }
    }

    @ParameterizedTest @CsvSource({"true,true,ACQUIRED", "true,false,BUSY", "false,true,BUSY", "false,false,BUSY"})
    void actualNativeModesAdmitOnlyCompatibleProcesses(boolean heldShared, boolean requestedShared, String expected) throws Exception {
        Path home = home("modes");
        try (var lease = acquire(home, heldShared)) {
            try (var child = start("probe", home, requestedShared)) {
                assertEquals(0, child.exit(), child.log()); assertTrue(child.log().lines().anyMatch(expected::equals), child.log());
            }
            lease.checkValid();
        }
        try (var child = start("probe", home, false)) {
            assertEquals(0, child.exit(), child.log()); assertTrue(child.log().lines().anyMatch("ACQUIRED"::equals), child.log());
        }
    }

    @Test void rejectedSameJvmAndCrossAdapterAliasesPreserveTheOriginalProcessLock() throws Exception {
        Path home = home("original"), aliasHome = Files.createDirectory(directory.resolve("alias"));
        Path path = home.resolve(LocalS3CoordinationGuard.NAME), alias = aliasHome.resolve(LocalS3CoordinationGuard.NAME);
        link(alias, path);
        var first = acquire(home, true);
        try (var second = acquire(home.resolve("."), true)) {
            assertThrows(IOException.class, () -> LocalS3CoordinationGuard.acquire(aliasHome, true));
            assertThrows(IOException.class, () -> LocalFileOwnership.openControl(alias, StandardOpenOption.READ, StandardOpenOption.WRITE));
            assertThrows(IOException.class, () -> LocalFileOwnership.openData(path, StandardOpenOption.READ));
            assertTrue(LocalS3CoordinationGuard.acquire(home, false).isEmpty()); first.close();
            second.checkValid();
            try (var child = start("probe", home, false)) {
                assertEquals(0, child.exit(), child.log()); assertTrue(child.log().lines().anyMatch("BUSY"::equals), child.log());
            }
        } finally { first.close(); }
        try (var child = start("probe", home, false)) {
            assertEquals(0, child.exit(), child.log()); assertTrue(child.log().lines().anyMatch("ACQUIRED"::equals), child.log());
        }
    }

    @Test void aGuardAlreadyReservedByAnotherAdapterIsRejectedBeforeNativeOpen() throws Exception {
        Path home = home("cross-owner");
        try (var supported = acquire(home, false)) { supported.checkValid(); }
        try (var owned = LocalFileOwnership.openControl(home.resolve(LocalS3CoordinationGuard.NAME), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var failure = assertThrows(IOException.class, () -> LocalS3CoordinationGuard.acquire(home, true));
            assertTrue(failure.getMessage().startsWith("Path is already owned")); assertTrue(owned.channel().isOpen());
        }
        try (var lease = acquire(home, true)) { lease.checkValid(); }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void actualChildOwnershipSurvivesUntilGracefulOrAbruptProcessExit(boolean shared, boolean abrupt) throws Exception {
        Path home = home("child"); Object before = key(home);
        try (var child = start("hold", home, shared)) {
            child.line("HELD");
            assertTrue(LocalS3CoordinationGuard.acquire(home, false).isEmpty());
            if (shared) { try (var reader = acquire(home, true)) { reader.checkValid(); } }
            else { assertTrue(LocalS3CoordinationGuard.acquire(home, true).isEmpty()); }
            child.send(abrupt ? 'H' : 'R'); assertEquals(abrupt ? HALTED : 0, child.exit(), child.log());
        }
        try (var lease = acquire(home, false)) { lease.checkValid(); }
        assertEquals(before, key(home)); assertEquals(List.of(LocalS3CoordinationGuard.NAME), names(home));
    }

    @Test void anUnexpectedUnregisteredOverlapRetainsItsChannelAndQuarantineUntilProcessExit() throws Exception {
        Path home = home("overlap");
        try (var supported = acquire(home, false)) { supported.checkValid(); }
        try (var child = start("overlap", home, false)) {
            child.line("QUARANTINED");
            assertTrue(LocalS3CoordinationGuard.acquire(home, false).isEmpty(), "Rejected duplicate channel must not release the raw owner's POSIX lock");
            child.send('R'); assertEquals(0, child.exit(), child.log());
            assertTrue(child.log().lines().anyMatch("QUARANTINED AFTER RAW CLOSE"::equals), child.log());
        }
        try (var lease = acquire(home, false)) { lease.checkValid(); }
    }

    @Test void aFinalSymlinkIsRejectedWithoutTouchingItsTarget() throws Exception {
        Path target = Files.createFile(directory.resolve("target")), home = Files.createDirectory(directory.resolve("linked"));
        try { Files.createSymbolicLink(home.resolve(LocalS3CoordinationGuard.NAME), target); }
        catch (UnsupportedOperationException unavailable) { assumeTrue(false, "Symbolic links explicitly unsupported: " + unavailable); }
        assertThrows(IOException.class, () -> LocalS3CoordinationGuard.acquire(home, true));
        assertEquals(0, Files.size(target));
        try (var raw = LocalFileOwnership.openControl(target, StandardOpenOption.READ, StandardOpenOption.WRITE)) { assertTrue(raw.channel().isOpen()); }
    }

    @Test void missingNativeGuardIsNeverImplicitlyCreated() throws Exception {
        Path home = Files.createDirectory(directory.resolve("missing"));
        assertThrows(IOException.class, () -> LocalS3CoordinationGuard.acquire(home, true)); assertTrue(names(home).isEmpty());
    }

    private Path home(String name) throws IOException {
        Path home = Files.createDirectory(directory.resolve(name)); Files.createFile(home.resolve(LocalS3CoordinationGuard.NAME)); return home;
    }
    private static Object key(Path home) throws IOException { return Files.readAttributes(home.resolve(LocalS3CoordinationGuard.NAME), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey(); }
    private static List<String> names(Path home) throws IOException { try (var paths = Files.list(home)) { return paths.map(p -> p.getFileName().toString()).sorted().toList(); } }
    private static LocalS3CoordinationGuard.Lease acquire(Path home, boolean shared) throws IOException {
        try { return LocalS3CoordinationGuard.acquire(home, shared).orElseThrow(() -> new IOException("Unexpected native contention")); }
        catch (LocalS3CoordinationGuard.SharedLockUnsupportedException | LocalS3CoordinationGuard.IdentityUnsupportedException unavailable) {
            assumeTrue(false, "Explicit native coordination capability unavailable: " + unavailable); throw new AssertionError(unavailable);
        }
    }
    private static void link(Path link, Path target) throws IOException {
        try { Files.createLink(link, target); }
        catch (UnsupportedOperationException unavailable) { assumeTrue(false, "Hard links explicitly unsupported: " + unavailable); }
    }
    private Child start(String mode, Path home, boolean shared) throws IOException {
        Path output = directory.resolve("child-" + UUID.randomUUID() + ".log");
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "--class-path",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), ChildMain.class.getName(), mode, home.toString(), Boolean.toString(shared)));
        return new Child(new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start(), output);
    }
    private record Child(Process process, Path output) implements AutoCloseable {
        String log() throws IOException { return Files.readString(output, StandardCharsets.UTF_8); }
        void line(String expected) throws Exception {
            long end = System.nanoTime() + DEADLINE.toNanos();
            while (System.nanoTime() < end) {
                if (log().lines().anyMatch(expected::equals)) { return; }
                if (!process.isAlive()) { int code = process.exitValue(); assumeTrue(code != UNSUPPORTED, log()); fail("Child exited before " + expected + ": " + log()); }
                Thread.sleep(10);
            }
            fail("Child did not report " + expected + ": " + log());
        }
        void send(char value) throws IOException { process.getOutputStream().write(value); process.getOutputStream().flush(); }
        int exit() throws Exception {
            assertTrue(process.waitFor(DEADLINE.toMillis(), TimeUnit.MILLISECONDS), "Child timeout: " + log());
            int result = process.exitValue(); assumeTrue(result != UNSUPPORTED, log()); return result;
        }
        @Override public void close() throws IOException {
            try {
                if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)); }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted child cleanup", interrupted); }
            finally { process.getOutputStream().close(); }
        }
    }
    public static final class ChildMain {
        private ChildMain() { }
        public static void main(String[] args) throws Exception {
            Path home = Path.of(args[1]); boolean shared = Boolean.parseBoolean(args[2]);
            try {
                if (args[0].equals("overlap")) {
                    Path path = home.resolve(LocalS3CoordinationGuard.NAME);
                    try (var raw = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                        var lock = raw.tryLock(); assertNotNull(lock);
                        assertThrows(IOException.class, () -> LocalS3CoordinationGuard.acquire(home, false));
                        System.out.println("QUARANTINED"); System.out.flush(); assertEquals('R', System.in.read());
                        lock.release();
                    }
                    assertThrows(IOException.class, () -> LocalS3CoordinationGuard.acquire(home, false));
                    assertThrows(IOException.class, () -> LocalFileOwnership.openControl(path, StandardOpenOption.READ, StandardOpenOption.WRITE));
                    System.out.println("QUARANTINED AFTER RAW CLOSE"); return;
                }
                var acquired = LocalS3CoordinationGuard.acquire(home, shared);
                if (acquired.isEmpty()) { System.out.println("BUSY"); return; }
                try (var lease = acquired.orElseThrow()) {
                    lease.checkValid();
                    if (args[0].equals("probe")) { System.out.println("ACQUIRED"); return; }
                    System.out.println("HELD"); System.out.flush(); int command = System.in.read();
                    if (command == 'H') { Runtime.getRuntime().halt(HALTED); }
                    assertEquals('R', command);
                }
            } catch (LocalS3CoordinationGuard.SharedLockUnsupportedException | LocalS3CoordinationGuard.IdentityUnsupportedException unavailable) {
                System.out.println("UNSUPPORTED " + unavailable); System.exit(UNSUPPORTED);
            }
        }
    }
}
