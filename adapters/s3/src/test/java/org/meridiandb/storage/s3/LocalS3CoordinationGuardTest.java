// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class LocalS3CoordinationGuardTest {
    @TempDir Path directory;

    @Test void sharedLeasesUseOneChannelAndOnlyFinalCloseReleasesNativeOwnership() throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("shared");
        var first = registry.acquire(path, true).orElseThrow();
        var second = registry.acquire(path.getParent().resolve(".").resolve(path.getFileName()), true).orElseThrow();
        assertEquals(1, opener.handles.size()); var handle = opener.last();
        assertEquals(List.of(true), handle.requests);
        assertTrue(registry.acquire(path, false).isEmpty());
        first.force(); assertEquals(1, handle.forces);
        first.close(); first.close();
        assertThrows(IOException.class, first::checkValid); assertThrows(IOException.class, first::force);
        assertEquals(0, handle.closes); second.checkValid();
        second.close(); second.close();
        assertEquals(1, handle.releases); assertEquals(1, handle.closes);
        assertEquals(0, Files.size(path));
        try (var exclusive = registry.acquire(path, false).orElseThrow()) { exclusive.checkValid(); }
        assertEquals(2, opener.handles.size());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void exclusiveLeaseDefersBothModesBeforeAnyAdditionalOpen(boolean requestedShared) throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("exclusive");
        try (var lease = registry.acquire(path, false).orElseThrow()) {
            assertTrue(registry.acquire(path, requestedShared).isEmpty()); assertEquals(1, opener.handles.size()); lease.checkValid();
        }
        try (var next = registry.acquire(path, requestedShared).orElseThrow()) { next.checkValid(); }
    }

    @Test void knownExternalContentionCleansTheUnusedChannelAndAllowsAnotherAttempt() throws Exception {
        var opener = new TestOpener(); opener.configure = h -> h.lockResult = false;
        var registry = registry(opener); Path path = guard("busy");
        assertTrue(registry.acquire(path, true).isEmpty());
        assertEquals(0, opener.last().releases); assertEquals(1, opener.last().closes);
        opener.configure = h -> { };
        try (var lease = registry.acquire(path, true).orElseThrow()) { lease.checkValid(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void wrongActualLockModeIsRejectedAndKnownCleanupReleasesTheSlot(boolean requestedShared) throws Exception {
        var opener = new TestOpener(); opener.configure = h -> h.convert = true;
        var registry = registry(opener); Path path = guard("converted");
        var failure = assertThrows(IOException.class, () -> registry.acquire(path, requestedShared));
        if (requestedShared) { assertInstanceOf(LocalS3CoordinationGuard.SharedLockUnsupportedException.class, failure); }
        assertEquals(1, opener.last().releases); assertEquals(1, opener.last().closes);
        opener.configure = h -> { };
        try (var lease = registry.acquire(path, requestedShared).orElseThrow()) { lease.checkValid(); }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "directory", "nonempty"})
    void invalidGuardNamesFailBeforeOpenAndAreNeverCreatedOrRepaired(String kind) throws Exception {
        var opener = new TestOpener(); var registry = registry(opener);
        Path path = directory.resolve(kind);
        if (kind.equals("directory")) { Files.createDirectory(path); }
        if (kind.equals("nonempty")) { Files.write(path, new byte[] {42}); }
        assertThrows(IOException.class, () -> registry.acquire(path, true)); assertTrue(opener.handles.isEmpty());
        if (kind.equals("missing")) { assertFalse(Files.exists(path)); }
        if (kind.equals("nonempty")) { assertArrayEquals(new byte[] {42}, Files.readAllBytes(path)); }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "nonempty", "replacement"})
    void changedNameInvalidatesAllLeasesAndNeverOpensAReplacement(String kind) throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard(kind);
        var lease = registry.acquire(path, true).orElseThrow();
        if (kind.equals("nonempty")) { Files.write(path, new byte[] {9}); }
        else { Files.move(path, path.resolveSibling("old")); if (kind.equals("replacement")) { Files.createFile(path); opener.identities.replace(path); } }
        assertThrows(IOException.class, lease::checkValid);
        assertThrows(IOException.class, () -> registry.acquire(path, true));
        assertThrows(IOException.class, lease::force); assertEquals(0, opener.last().forces);
        assertEquals(1, opener.handles.size()); lease.close();
        assertTrue(Files.exists(path.resolveSibling("old")) || kind.equals("nonempty"));
    }

    @Test void missingNameObservationRemainsPoisonedEvenIfTheOriginalNameReturns() throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("rename-back");
        var lease = registry.acquire(path, true).orElseThrow(); Path saved = path.resolveSibling("saved");
        Files.move(path, saved); assertThrows(IOException.class, () -> registry.acquire(path, true)); Files.move(saved, path);
        assertThrows(IOException.class, lease::checkValid); assertThrows(IOException.class, () -> registry.acquire(path, true));
        lease.close();
        try (var reopened = registry.acquire(path, true).orElseThrow()) { reopened.checkValid(); }
    }

    @Test void nameReplacementDuringNativeAcquisitionNeverExposesALease() throws Exception {
        var opener = new TestOpener(); Path path = guard("startup-race");
        opener.configure = h -> h.afterLock = () -> { Files.move(path, path.resolveSibling("original")); Files.createFile(path); opener.identities.replace(path); };
        var registry = registry(opener);
        assertThrows(IOException.class, () -> registry.acquire(path, true));
        assertEquals(1, opener.last().releases); assertEquals(1, opener.last().closes);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nativeInvalidityPoisonsTheEntryUntilEveryLeaseCloses(boolean closeRaw) throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("invalid");
        var lease = registry.acquire(path, true).orElseThrow(); var handle = opener.last();
        if (closeRaw) { handle.open = false; } else { handle.valid = false; }
        assertThrows(IOException.class, lease::checkValid); handle.open = true; handle.valid = true;
        assertThrows(IOException.class, lease::checkValid); assertThrows(IOException.class, () -> registry.acquire(path, true));
        lease.close();
    }

    @ParameterizedTest @ValueSource(strings = {"io", "runtime", "error"})
    void forceFailurePoisonsEverySharedLeaseWithoutReleasingTheirProtection(String type) throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("force");
        var first = registry.acquire(path, true).orElseThrow(); var second = registry.acquire(path, true).orElseThrow();
        var failure = failure(type); opener.last().forceFailure = failure;
        assertSame(failure, assertThrows(Throwable.class, first::force));
        assertThrows(IOException.class, second::checkValid); assertThrows(IOException.class, () -> registry.acquire(path, true));
        first.close(); assertEquals(0, opener.last().closes); second.close(); assertEquals(1, opener.last().closes);
    }

    @ParameterizedTest @CsvSource({"runtime,false", "runtime,true", "error,false", "error,true"})
    void uncertainNativeHealthInspectionCannotBecomeHealthyOnAnotherCheck(String type, boolean openAccessor) throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("health-throw");
        var lease = registry.acquire(path, true).orElseThrow(); var handle = opener.last();
        var failure = failure(type); handle.healthFailure = failure; handle.failOpenHealth = openAccessor;
        assertSame(failure, assertThrows(Throwable.class, lease::checkValid)); handle.healthFailure = null;
        assertThrows(IOException.class, lease::checkValid); assertThrows(IOException.class, () -> registry.acquire(path, true));
        assertThrows(IOException.class, lease::force); assertEquals(0, handle.forces); lease.close();
    }

    @ParameterizedTest @ValueSource(strings = {"io", "runtime", "error"})
    void releaseFailureStillAttemptsKnownCloseAndAllowsFreshAcquisition(String type) throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("release");
        var lease = registry.acquire(path, true).orElseThrow(); var handle = opener.last();
        var failure = failure(type); handle.releaseFailure = failure;
        assertSame(failure, assertThrows(Throwable.class, lease::close)); assertEquals(1, handle.closes);
        assertThrows(IOException.class, lease::checkValid); lease.close(); assertEquals(1, handle.closes);
        try (var next = registry.acquire(path, false).orElseThrow()) { next.checkValid(); }
    }

    @ParameterizedTest @CsvSource({"io,false", "io,true", "runtime,false", "runtime,true", "error,false", "error,true"})
    void thrownCloseIsQuarantinedEvenWhenTheHandleReportsClosed(String type, boolean closedAfterThrow) throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("close");
        var lease = registry.acquire(path, true).orElseThrow(); var handle = opener.last();
        var failure = failure(type); handle.closeFailure = failure; handle.closedAfterThrow = closedAfterThrow;
        assertSame(failure, assertThrows(Throwable.class, lease::close));
        assertThrows(IOException.class, lease::checkValid); assertThrows(IOException.class, lease::force);
        assertThrows(IOException.class, () -> registry.acquire(path, true));
        var retry = assertThrows(IOException.class, lease::close); assertSame(failure, retry.getCause());
        assertEquals(1, handle.closes); assertEquals(1, opener.handles.size());
    }

    @Test void failedReleaseIsPrimaryAndFailedCloseIsSuppressedWhileBothRemainEvidence() throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("both");
        var lease = registry.acquire(path, false).orElseThrow(); var handle = opener.last();
        var release = new IOException("release"); var close = new IllegalStateException("close");
        handle.releaseFailure = release; handle.closeFailure = close;
        assertSame(release, assertThrows(IOException.class, lease::close)); assertArrayEquals(new Throwable[] {close}, release.getSuppressed());
        assertEquals(1, handle.releases); assertEquals(1, handle.closes); assertThrows(IOException.class, () -> registry.acquire(path, true));
    }

    @Test void returnedCloseWithoutActuallyClosingAlsoQuarantinesTheEntry() throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("no-close");
        var lease = registry.acquire(path, false).orElseThrow(); opener.last().ignoreClose = true;
        assertThrows(IOException.class, lease::close); assertThrows(IOException.class, lease::close);
        assertEquals(1, opener.last().closes); assertThrows(IOException.class, () -> registry.acquire(path, true));
    }

    @ParameterizedTest @ValueSource(strings = {"io", "runtime", "error"})
    void lockFailureCleansItsSoleChannelAndPreservesTheOriginalFailure(String type) throws Exception {
        var opener = new TestOpener(); var original = failure(type); opener.configure = h -> h.lockFailure = original;
        var registry = registry(opener); Path path = guard("lock");
        assertSame(original, assertThrows(Throwable.class, () -> registry.acquire(path, true))); assertEquals(1, opener.last().closes);
        opener.configure = h -> { }; try (var next = registry.acquire(path, true).orElseThrow()) { next.checkValid(); }
    }

    @Test void failedConflictCleanupCannotBeReportedAsSafeDeferral() throws Exception {
        var opener = new TestOpener(); var failure = new IOException("unused close uncertain");
        opener.configure = h -> { h.lockResult = false; h.closeFailure = failure; h.closedAfterThrow = true; };
        var registry = registry(opener); Path path = guard("conflict-close");
        assertSame(failure, assertThrows(IOException.class, () -> registry.acquire(path, false)));
        assertThrows(IOException.class, () -> registry.acquire(path, false)); assertEquals(1, opener.last().closes);
    }

    @Test void failedOpenReturnsItsRegistrySlotWithoutClaimingCleanupOfAnUnreturnedHandle() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger(); var expected = new IOException("open failed");
        var registry = new LocalS3CoordinationGuard.Registry(path -> {
            if (calls.getAndIncrement() == 0) { throw expected; } return new TestHandle();
        }, 1, new TestIdentities());
        Path path = guard("open-failure");
        assertSame(expected, assertThrows(IOException.class, () -> registry.acquire(path, true)));
        try (var lease = registry.acquire(path, true).orElseThrow()) { lease.checkValid(); }
        assertEquals(2, calls.get());
    }

    @Test void forceRechecksTheOriginalNameBeforeReturning() throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("force-name");
        var lease = registry.acquire(path, true).orElseThrow();
        opener.last().onForce = () -> { Files.move(path, path.resolveSibling("old")); Files.createFile(path); opener.identities.replace(path); };
        assertThrows(IOException.class, lease::force); assertEquals(1, opener.last().forces);
        assertThrows(IOException.class, lease::checkValid); assertThrows(IOException.class, () -> registry.acquire(path, true)); lease.close();
    }

    @Test void cleanupInvalidatesBeforeBothNativeActionsAndRejectsReentrantRelease() throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("cleanup-order");
        var lease = registry.acquire(path, true).orElseThrow();
        Action check = () -> {
            assertThrows(IOException.class, lease::checkValid); assertThrows(IOException.class, lease::force);
            assertThrows(IOException.class, lease::close); assertThrows(IOException.class, () -> registry.acquire(path, true));
        };
        opener.last().onRelease = check; opener.last().onClose = check; lease.close();
        assertEquals(1, opener.last().releases); assertEquals(1, opener.last().closes); lease.close();
    }

    @Test void nativePlacementAndSharedReferenceCapacitiesAreReservedBeforeOpening() throws Exception {
        var opener = new TestOpener(); var registry = registry(opener, 2);
        Path firstPath = guard("one"), secondPath = guard("two"), thirdPath = guard("three");
        var first = registry.acquire(firstPath, true).orElseThrow(); var second = registry.acquire(secondPath, true).orElseThrow();
        assertThrows(IOException.class, () -> registry.acquire(thirdPath, false)); assertEquals(2, opener.handles.size());
        var leases = new ArrayList<LocalS3CoordinationGuard.Lease>(); leases.add(first);
        for (int i = 1; i < LocalS3CoordinationGuard.MAX_LEASES; i++) { leases.add(registry.acquire(firstPath, true).orElseThrow()); }
        assertThrows(IOException.class, () -> registry.acquire(firstPath, true)); assertEquals(2, opener.handles.size());
        for (var lease : leases) { lease.close(); } second.checkValid();
        try (var third = registry.acquire(thirdPath, false).orElseThrow()) { third.checkValid(); } second.close();
    }

    @Test void forceSerializesWithCloseAndRejectsReentrantOwnerOperations() throws Exception {
        var opener = new TestOpener(); var registry = registry(opener); Path path = guard("serial");
        var lease = registry.acquire(path, true).orElseThrow();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        opener.last().onForce = () -> {
            assertThrows(IOException.class, lease::close); assertThrows(IOException.class, lease::checkValid);
            assertThrows(IOException.class, lease::force); assertThrows(IOException.class, () -> registry.acquire(path, true));
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var forcing = executor.submit(() -> { lease.force(); return true; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closing = executor.submit(() -> { lease.close(); return true; });
            assertFalse(closing.isDone()); release.countDown();
            assertTrue(forcing.get(5, TimeUnit.SECONDS)); assertTrue(closing.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertEquals(1, opener.last().forces); assertEquals(1, opener.last().closes); assertThrows(IOException.class, lease::checkValid);
    }

    private static LocalS3CoordinationGuard.Registry registry(TestOpener opener) {
        return registry(opener, LocalS3CoordinationGuard.MAX_PLACEMENTS);
    }
    private static LocalS3CoordinationGuard.Registry registry(TestOpener opener, int limit) {
        return new LocalS3CoordinationGuard.Registry(opener, limit, opener.identities);
    }
    // Controlled identities keep registry/fault tests independent of native file-key capability.
    static final class TestIdentities implements LocalS3CoordinationGuard.IdentityReader {
        private final java.util.Map<Path, Object> values = new java.util.HashMap<>();
        @Override public Object read(Path path) throws IOException {
            var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() != 0) { throw new IOException("Invalid test guard name"); }
            return values.computeIfAbsent(path, ignored -> new Object());
        }
        void replace(Path path) throws IOException {
            Path absolute = path.toAbsolutePath();
            values.put(absolute.getParent().toRealPath().resolve(absolute.getFileName()), new Object());
        }
    }
    private Path guard(String name) throws IOException { return Files.createFile(Files.createDirectory(directory.resolve(name)).resolve(LocalS3CoordinationGuard.NAME)); }
    private static Throwable failure(String type) { return switch (type) {
        case "io" -> new IOException(type); case "runtime" -> new IllegalStateException(type); case "error" -> new AssertionError(type);
        default -> throw new AssertionError(type);
    }; }
    private static void raise(Throwable failure) throws IOException {
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }
    @FunctionalInterface interface Action { void run() throws IOException; }
    static final class TestOpener implements LocalS3CoordinationGuard.Opener {
        final List<TestHandle> handles = new ArrayList<>();
        final TestIdentities identities = new TestIdentities();
        Consumer<TestHandle> configure = handle -> { };
        @Override public LocalS3CoordinationGuard.Handle open(Path path) {
            var handle = new TestHandle(); handles.add(handle); configure.accept(handle); return handle;
        }
        TestHandle last() { return handles.getLast(); }
    }
    static final class TestHandle implements LocalS3CoordinationGuard.Handle {
        final List<Boolean> requests = new ArrayList<>();
        boolean lockResult = true, convert, shared, valid, open = true, closedAfterThrow, ignoreClose;
        int forces, releases, closes;
        Throwable lockFailure, forceFailure, releaseFailure, closeFailure, healthFailure;
        boolean failOpenHealth;
        Action afterLock = () -> { }, onForce = () -> { }, onRelease = () -> { }, onClose = () -> { };
        @Override public boolean tryLock(boolean requested) throws IOException {
            requests.add(requested); raise(lockFailure); shared = convert != requested; valid = lockResult; afterLock.run(); return lockResult;
        }
        @Override public boolean isShared() { return shared; }
        @Override public boolean isValid() { if (!failOpenHealth) { inspectHealth(); } return valid; }
        @Override public boolean isOpen() { if (failOpenHealth) { inspectHealth(); } return open; }
        private void inspectHealth() {
            if (healthFailure instanceof RuntimeException failure) { throw failure; }
            if (healthFailure instanceof Error failure) { throw failure; }
        }
        @Override public void force() throws IOException { forces++; onForce.run(); raise(forceFailure); }
        @Override public void release() throws IOException { releases++; onRelease.run(); raise(releaseFailure); valid = false; }
        @Override public void close() throws IOException {
            closes++; onClose.run(); if (closeFailure != null) { if (closedAfterThrow) { open = false; } raise(closeFailure); }
            if (!ignoreClose) { open = false; valid = false; }
        }
    }
}
