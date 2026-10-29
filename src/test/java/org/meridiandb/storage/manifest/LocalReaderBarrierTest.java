// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalReaderBarrierTest {
    @TempDir
    Path directory;

    @Test
    void sharedReferencesReuseOneChannelAndOnlyTheLastCloseReleasesIt() throws IOException {
        var opener = new TestOpener();
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("shared");
        var first = registry.acquire(path, true).orElseThrow();
        var second = registry.acquire(path.getParent().resolve(".").resolve(LocalReaderBarrier.NAME), true).orElseThrow();
        var handle = opener.handles.getFirst();
        assertEquals(1, opener.opens);
        assertEquals(List.of(true), handle.lockRequests);
        assertEquals(Optional.empty(), registry.acquire(path, false));
        assertEquals(1, opener.opens, "Known local reader contention opens no second channel");
        first.close();
        first.close();
        assertThrows(IOException.class, first::checkValid);
        assertEquals(0, handle.releases);
        assertEquals(0, handle.closes);
        second.checkValid();
        second.close();
        second.close();
        assertEquals(1, handle.releases);
        assertEquals(1, handle.closes);
        assertThrows(IOException.class, second::checkValid);
        try (var exclusive = registry.acquire(path, false).orElseThrow()) {
            exclusive.checkValid();
            assertEquals(2, opener.opens);
            assertEquals(List.of(false), opener.handles.getLast().lockRequests);
        }
    }

    @Test
    void anExclusiveReferenceDefersBothSharedAndExclusiveLocalAdmissionWithoutOpening() throws IOException {
        var opener = new TestOpener();
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("exclusive");
        try (var exclusive = registry.acquire(path, false).orElseThrow()) {
            assertEquals(Optional.empty(), registry.acquire(path, true));
            assertEquals(Optional.empty(), registry.acquire(path, false));
            assertEquals(1, opener.opens);
            exclusive.checkValid();
        }
        try (var shared = registry.acquire(path, true).orElseThrow()) {
            shared.checkValid();
            assertEquals(2, opener.opens);
        }
    }

    @Test
    void differentGuardFilesHaveIndependentChannelsAndLifetimes() throws IOException {
        var opener = new TestOpener();
        var registry = new LocalReaderBarrier.Registry(opener);
        var first = registry.acquire(guard("first"), true).orElseThrow();
        var second = registry.acquire(guard("second"), true).orElseThrow();
        assertEquals(2, opener.opens);
        first.close();
        assertFalse(opener.handles.getFirst().open);
        assertTrue(opener.handles.getLast().open);
        second.checkValid();
        second.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hardLinkAliasesAreRejectedBeforeOpeningOrClosingAnotherChannel(boolean shared) throws IOException {
        var opener = new TestOpener();
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("original");
        var alias = Files.createDirectory(directory.resolve("alias")).resolve(LocalReaderBarrier.NAME);
        try { Files.createLink(alias, path); }
        catch (UnsupportedOperationException unavailable) { assumeTrue(false, "Hard links explicitly unsupported: " + unavailable); }
        try (var original = registry.acquire(path, true).orElseThrow()) {
            assertThrows(IOException.class, () -> registry.acquire(alias, shared));
            assertEquals(1, opener.opens);
            assertEquals(0, opener.handles.getFirst().closes);
            original.checkValid();
        }
    }

    @Test
    void aKnownExternalConflictClosesTheUnusedChannelAndPermitsAnotherAttempt() throws IOException {
        var opener = new TestOpener();
        opener.configure = handle -> handle.lockResult = opener.opens != 1;
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("busy");
        assertEquals(Optional.empty(), registry.acquire(path, false));
        assertEquals(0, opener.handles.getFirst().releases);
        assertEquals(1, opener.handles.getFirst().closes);
        try (var acquired = registry.acquire(path, false).orElseThrow()) {
            assertEquals(2, opener.opens);
            acquired.checkValid();
        }
    }

    @Test
    void convertedExclusiveLockIsExplicitlyUnsupportedAndItsResourcesAreClosed() throws IOException {
        var opener = new TestOpener();
        opener.configure = handle -> handle.convertShared = opener.opens == 1;
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("converted");
        assertThrows(LocalCheckpointStore.SharedReaderLockUnsupportedException.class, () -> registry.acquire(path, true));
        assertEquals(1, opener.handles.getFirst().releases);
        assertEquals(1, opener.handles.getFirst().closes);
        try (var valid = registry.acquire(path, true).orElseThrow()) {
            valid.checkValid();
            assertEquals(2, opener.opens);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidOrClosedUnderlyingHandleRejectsValidationAndFurtherAcquisition(boolean closed) throws IOException {
        var opener = new TestOpener();
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("invalid");
        var protection = registry.acquire(path, true).orElseThrow();
        var handle = opener.handles.getFirst();
        if (closed) { handle.open = false; }
        else { handle.valid = false; }
        assertThrows(IOException.class, protection::checkValid);
        assertThrows(IOException.class, () -> registry.acquire(path, true));
        assertEquals(1, opener.opens);
        protection.close();
        try (var fresh = registry.acquire(path, true).orElseThrow()) { fresh.checkValid(); }
    }

    @Test
    void releaseAndCloseAreBothAttemptedAndClosedResourcesAreRemovedDespiteAReleaseFailure() throws IOException {
        var opener = new TestOpener();
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("release-failure");
        var protection = registry.acquire(path, true).orElseThrow();
        var handle = opener.handles.getFirst();
        var failure = new IOException("Release failed");
        handle.releaseFailure = failure;
        assertSame(failure, assertThrows(IOException.class, protection::close));
        assertEquals(1, handle.releases);
        assertEquals(1, handle.closes);
        assertFalse(handle.open);
        protection.close();
        assertEquals(1, handle.closes, "Already closed resources are not closed again");
        assertThrows(IOException.class, protection::checkValid);
        try (var next = registry.acquire(path, true).orElseThrow()) { next.checkValid(); }
    }

    @Test
    void stillOpenCloseFailurePoisonsAdmissionAndRetainsAReferenceThatCanRetryCleanup() throws IOException {
        var opener = new TestOpener();
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("retry-close");
        var protection = registry.acquire(path, true).orElseThrow();
        var handle = opener.handles.getFirst();
        var closeFailure = new IOException("Close failed before closing");
        handle.closeFailure = closeFailure;
        assertSame(closeFailure, assertThrows(IOException.class, protection::close));
        assertTrue(handle.open);
        assertThrows(IOException.class, protection::checkValid);
        assertThrows(IOException.class, () -> registry.acquire(path, false));
        assertThrows(IOException.class, () -> registry.acquire(path, true));
        assertEquals(1, opener.opens);
        handle.closeFailure = null;
        protection.close();
        protection.close();
        assertFalse(handle.open);
        assertEquals(1, handle.releases, "Successful release is not repeated during a close retry");
        assertEquals(2, handle.closes);
        try (var next = registry.acquire(path, false).orElseThrow()) { next.checkValid(); }
    }

    @Test
    void closeThatTakesEffectThenThrowsRemovesTheReservationAndIsIdempotent() throws IOException {
        var opener = new TestOpener();
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("closed-despite-error");
        var protection = registry.acquire(path, true).orElseThrow();
        var handle = opener.handles.getFirst();
        handle.closeFailure = new IOException("Closed before failure");
        handle.closeTakesEffect = true;
        assertThrows(IOException.class, protection::close);
        assertFalse(handle.open);
        protection.close();
        assertEquals(1, handle.closes);
        try (var next = registry.acquire(path, true).orElseThrow()) { next.checkValid(); }
    }

    @Test
    void releaseAndCloseFailuresKeepTheirPrimarySuppressionAndBothCanBeRetried() throws IOException {
        var opener = new TestOpener();
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("both-fail");
        var protection = registry.acquire(path, true).orElseThrow();
        var handle = opener.handles.getFirst();
        var releaseFailure = new IOException("Release failed");
        var closeFailure = new IOException("Close failed");
        handle.releaseFailure = releaseFailure;
        handle.closeFailure = closeFailure;
        assertSame(releaseFailure, assertThrows(IOException.class, protection::close));
        assertArrayEquals(new Throwable[] {closeFailure}, releaseFailure.getSuppressed());
        assertEquals(1, handle.releases);
        assertEquals(1, handle.closes);
        assertThrows(IOException.class, () -> registry.acquire(path, true));
        handle.releaseFailure = null;
        handle.closeFailure = null;
        protection.close();
        assertEquals(2, handle.releases);
        assertEquals(2, handle.closes);
        try (var next = registry.acquire(path, false).orElseThrow()) { next.checkValid(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void cleanupPropagatesTheReleaseFailureWithCloseSuppressedAfterBothEffects(String kind) throws IOException {
        var opener = new TestOpener();
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("cleanup-failure");
        var protection = registry.acquire(path, true).orElseThrow();
        var handle = opener.handles.getFirst();
        var expected = failure(kind, "Release failed");
        var closeFailure = new IOException("Close took effect before failure");
        handle.releaseFailure = expected;
        handle.closeFailure = closeFailure;
        handle.closeTakesEffect = true;
        assertSame(expected, assertThrows(expected.getClass(), protection::close));
        assertArrayEquals(new Throwable[] {closeFailure}, expected.getSuppressed());
        assertEquals(1, handle.releases);
        assertEquals(1, handle.closes);
        assertFalse(handle.open);
        assertThrows(IOException.class, protection::checkValid);
        protection.close();
        assertEquals(1, handle.closes);
        try (var next = registry.acquire(path, true).orElseThrow()) { next.checkValid(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void failedPartialAcquisitionCleansUpAndPropagatesTheOriginalFailure(String kind) throws IOException {
        var opener = new TestOpener();
        var expected = failure(kind, "Lock failed");
        opener.configure = handle -> {
            if (opener.opens == 1) {
                handle.lockFailure = expected;
                handle.lockFailureAfterEffect = true;
            }
        };
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("acquire-failure");
        assertSame(expected, assertThrows(expected.getClass(), () -> registry.acquire(path, true)));
        assertEquals(1, opener.handles.getFirst().releases);
        assertEquals(1, opener.handles.getFirst().closes);
        try (var next = registry.acquire(path, true).orElseThrow()) { next.checkValid(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void failedPartialAcquireWithFailedCleanupIsQuarantinedWithoutOpeningAnotherHandle(String kind) throws IOException {
        var opener = new TestOpener();
        var expected = failure(kind, "Lock failed");
        var cleanup = new IOException("Cleanup failed and remains open");
        opener.configure = handle -> {
            handle.lockFailure = expected;
            handle.closeFailure = cleanup;
        };
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("quarantine");
        assertSame(expected, assertThrows(expected.getClass(), () -> registry.acquire(path, true)));
        assertArrayEquals(new Throwable[] {cleanup}, expected.getSuppressed());
        assertTrue(opener.handles.getFirst().open);
        assertThrows(IOException.class, () -> registry.acquire(path, true));
        assertThrows(IOException.class, () -> registry.acquire(path, false));
        assertEquals(1, opener.opens);
        assertEquals(1, opener.handles.getFirst().closes);
    }

    @Test
    void conflictWithUncertainCleanupIsQuarantinedInsteadOfReportedAsSafeContention() throws IOException {
        var opener = new TestOpener();
        opener.configure = handle -> {
            handle.lockResult = false;
            handle.closeFailure = new IOException("Conflict cleanup failed");
        };
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("busy-cleanup");
        assertThrows(IOException.class, () -> registry.acquire(path, false));
        assertThrows(IOException.class, () -> registry.acquire(path, true));
        assertEquals(1, opener.opens);
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void openerFailureBeforeReturningAHandleLeavesNoReservation(String kind) throws IOException {
        var opener = new TestOpener();
        var expected = failure(kind, "Open failed");
        opener.openFailure = expected;
        var registry = new LocalReaderBarrier.Registry(opener);
        var path = guard("open-failure");
        assertSame(expected, assertThrows(expected.getClass(), () -> registry.acquire(path, true)));
        assertTrue(opener.handles.isEmpty());
        opener.openFailure = null;
        try (var next = registry.acquire(path, true).orElseThrow()) { next.checkValid(); }
        assertEquals(2, opener.opens);
    }

    @Test
    void nullRequiredArgumentsFailBeforeTheOpenerRuns() {
        var opener = new TestOpener();
        assertThrows(NullPointerException.class, () -> new LocalReaderBarrier.Registry(null));
        var registry = new LocalReaderBarrier.Registry(opener);
        assertThrows(NullPointerException.class, () -> registry.acquire(null, true));
        assertEquals(0, opener.opens);
    }

    private Path guard(String directoryName) throws IOException {
        return Files.createFile(Files.createDirectory(directory.resolve(directoryName)).resolve(LocalReaderBarrier.NAME));
    }

    private static Throwable failure(String kind, String message) {
        return switch (kind) {
            case "io" -> new IOException(message);
            case "runtime" -> new IllegalStateException(message);
            case "error" -> new AssertionError(message);
            default -> throw new AssertionError(kind);
        };
    }

    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }

    private static final class TestOpener implements LocalReaderBarrier.Opener {
        final List<TestHandle> handles = new ArrayList<>();
        Consumer<TestHandle> configure = ignored -> {};
        Throwable openFailure;
        int opens;
        @Override public LocalReaderBarrier.Handle open(Path path) throws IOException {
            opens++;
            if (openFailure != null) { throwFailure(openFailure); }
            var handle = new TestHandle();
            configure.accept(handle);
            handles.add(handle);
            return handle;
        }
    }

    private static final class TestHandle implements LocalReaderBarrier.Handle {
        final List<Boolean> lockRequests = new ArrayList<>();
        boolean lockResult = true;
        boolean convertShared;
        boolean valid;
        boolean shared;
        boolean open = true;
        boolean lockFailureAfterEffect;
        boolean closeTakesEffect;
        Throwable lockFailure;
        Throwable releaseFailure;
        Throwable closeFailure;
        int releases;
        int closes;
        @Override public boolean tryLock(boolean requestedShared) throws IOException {
            lockRequests.add(requestedShared);
            shared = requestedShared && !convertShared;
            if (lockFailureAfterEffect) { valid = true; }
            if (lockFailure != null) { throwFailure(lockFailure); }
            valid = lockResult;
            return lockResult;
        }
        @Override public boolean isShared() { return shared; }
        @Override public boolean isValid() { return valid; }
        @Override public boolean isOpen() { return open; }
        @Override public void release() throws IOException {
            releases++;
            if (releaseFailure != null) { throwFailure(releaseFailure); }
            valid = false;
        }
        @Override public void close() throws IOException {
            closes++;
            if (closeFailure == null || closeTakesEffect) {
                open = false;
                valid = false;
            }
            if (closeFailure != null) { throwFailure(closeFailure); }
        }
    }
}
