// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
// The reservation scope is the behavior under test; tokens intentionally have no access API.
@SuppressWarnings("try")
class LocalFileOwnershipTest {
    @TempDir
    Path directory;

    @Test
    void controlReservationExcludesReadersAndOtherControllersUntilReleased() throws IOException {
        var path = Files.createFile(directory.resolve("owned"));
        try (var control = LocalFileOwnership.control(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
        }
        try (var read = LocalFileOwnership.read(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
        }
        try (var control = LocalFileOwnership.control(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
        }
    }

    @Test
    void readersCoexistAndEachReservationMustCloseBeforeControlCanEnter() throws IOException {
        var path = Files.createFile(directory.resolve("readers"));
        var first = LocalFileOwnership.read(path);
        try (var second = LocalFileOwnership.read(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
            first.close();
            first.close();
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
            try (var third = LocalFileOwnership.read(path)) {
                assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
            }
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
        } finally { first.close(); }
        try (var control = LocalFileOwnership.control(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedCloseOfAnOldReservationCannotRemoveANewerOwner(boolean originalControl) throws IOException {
        var path = Files.createFile(directory.resolve("new-owner"));
        var original = originalControl ? LocalFileOwnership.control(path) : LocalFileOwnership.read(path);
        original.close();
        try (var replacement = LocalFileOwnership.control(path)) {
            original.close();
            original.close();
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
        } finally { original.close(); }
        try (var read = LocalFileOwnership.read(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
        }
    }

    @Test
    void distinctFilesHaveIndependentOwnershipAndRelease() throws IOException {
        var firstPath = Files.createFile(directory.resolve("first"));
        var secondPath = Files.createFile(directory.resolve("second"));
        var thirdPath = Files.createFile(directory.resolve("third"));
        var first = LocalFileOwnership.control(firstPath);
        try (var second = LocalFileOwnership.control(secondPath); var third = LocalFileOwnership.read(thirdPath)) {
            first.close();
            try (var firstRead = LocalFileOwnership.read(firstPath)) {
                assertThrows(IOException.class, () -> LocalFileOwnership.control(firstPath));
                assertThrows(IOException.class, () -> LocalFileOwnership.read(secondPath));
                assertThrows(IOException.class, () -> LocalFileOwnership.control(thirdPath));
            }
        } finally { first.close(); }
        try (var secondRead = LocalFileOwnership.read(secondPath); var thirdControl = LocalFileOwnership.control(thirdPath)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.control(secondPath));
            assertThrows(IOException.class, () -> LocalFileOwnership.read(thirdPath));
        }
    }

    @Test
    void reservationPrecedesFileCreationAndDoesNotOpenOrCreateThePath() throws IOException {
        var path = directory.resolve("not-created-yet");
        try (var control = LocalFileOwnership.control(path)) {
            assertFalse(Files.exists(path), "Reservation does not perform a channel open or path creation");
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
            Files.createFile(path);
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
        }
        try (var read = LocalFileOwnership.read(path)) {
            assertTrue(Files.exists(path));
        }
    }

    @Test
    void dotAndDotDotParentSpellingsResolveToTheSameReservation() throws IOException {
        var parent = Files.createDirectory(directory.resolve("parent"));
        Files.createDirectory(parent.resolve("child"));
        var path = Files.createFile(parent.resolve("owned"));
        var alias = parent.resolve("child").resolve("..").resolve(".").resolve("owned");
        try (var control = LocalFileOwnership.control(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.read(alias));
            assertThrows(IOException.class, () -> LocalFileOwnership.control(alias));
        }
        try (var control = LocalFileOwnership.control(alias)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
        }
    }

    @Test
    void symbolicParentAliasIsCanonicalizedBeforeAdmission() throws IOException {
        var parent = Files.createDirectory(directory.resolve("parent"));
        var path = Files.createFile(parent.resolve("owned"));
        var aliasParent = symbolicLink(directory.resolve("parent-alias"), parent);
        var alias = aliasParent.resolve("owned");
        try (var control = LocalFileOwnership.control(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.control(alias));
            assertThrows(IOException.class, () -> LocalFileOwnership.read(alias));
        }
        try (var first = LocalFileOwnership.read(path); var second = LocalFileOwnership.read(alias)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.control(alias));
        }
    }

    @Test
    void dotDotAfterASymbolicParentResolvesThePhysicalParentBeforeReservation() throws IOException {
        var lexicalParent = Files.createDirectory(directory.resolve("lexical"));
        var physicalParent = Files.createDirectory(directory.resolve("physical"));
        var target = Files.createDirectory(physicalParent.resolve("child"));
        var link = symbolicLink(lexicalParent.resolve("link"), target);
        var actual = Files.createFile(physicalParent.resolve("owned"));
        var alias = link.resolve("..").resolve("owned");
        assertTrue(Files.isSameFile(actual, alias));
        try (var control = LocalFileOwnership.control(actual)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.read(alias));
            assertThrows(IOException.class, () -> LocalFileOwnership.control(alias));
        }
        try (var control = LocalFileOwnership.control(alias)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.read(actual));
        }
    }

    @Test
    void aControlReservationRejectsHardLinkAliasesWithoutAChannelOpen() throws IOException {
        var path = Files.createFile(directory.resolve("original"));
        var alias = hardLink(directory.resolve("alias"), path);
        try (var control = LocalFileOwnership.control(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.control(alias));
            assertThrows(IOException.class, () -> LocalFileOwnership.read(alias));
        }
        try (var control = LocalFileOwnership.control(alias)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
        }
    }

    @Test
    void hardLinkedReadReservationsCoexistAndEachPreventsControl() throws IOException {
        var path = Files.createFile(directory.resolve("original"));
        var alias = hardLink(directory.resolve("alias"), path);
        var first = LocalFileOwnership.read(path);
        try (var second = LocalFileOwnership.read(alias)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
            assertThrows(IOException.class, () -> LocalFileOwnership.control(alias));
            first.close();
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
        } finally { first.close(); }
        try (var control = LocalFileOwnership.control(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.read(alias));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentAdmissionsKeepExactlyOneControllerOrAllReaders(boolean control) throws Exception {
        var path = Files.createFile(directory.resolve("concurrent"));
        int callers = 8;
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(callers);
        var executor = Executors.newFixedThreadPool(callers);
        var futures = new ArrayList<Future<Optional<LocalFileOwnership.Reservation>>>();
        var accepted = new ArrayList<LocalFileOwnership.Reservation>();
        var owned = new ConcurrentLinkedQueue<LocalFileOwnership.Reservation>();
        try {
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "Ownership workers were not released");
                    try {
                        var reservation = control ? LocalFileOwnership.control(path) : LocalFileOwnership.read(path);
                        owned.add(reservation);
                        return Optional.of(reservation);
                    } catch (IOException conflict) {
                        return Optional.empty();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Ownership workers did not reach admission barrier");
            start.countDown();
            for (var future : futures) { future.get(10, TimeUnit.SECONDS).ifPresent(accepted::add); }
            assertEquals(control ? 1 : callers, accepted.size());
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
            if (control) { assertThrows(IOException.class, () -> LocalFileOwnership.read(path)); }
            accepted.forEach(LocalFileOwnership.Reservation::close);
            try (var replacement = LocalFileOwnership.control(path)) {
                assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Ownership workers did not terminate");
            // Include tokens returned by workers whose futures were not reached after a failure.
            owned.forEach(LocalFileOwnership.Reservation::close);
        }
    }

    @Test
    void failedParentResolutionAndNullArgumentsLeaveNoReservation() throws IOException {
        var parent = directory.resolve("missing");
        var path = parent.resolve("owned");
        assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
        assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
        assertFalse(Files.exists(parent));
        assertThrows(NullPointerException.class, () -> LocalFileOwnership.control(null));
        assertThrows(NullPointerException.class, () -> LocalFileOwnership.read(null));
        Files.createDirectory(parent);
        try (var control = LocalFileOwnership.control(path)) {
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
        }
    }

    @Test
    void knownSuccessfulChannelCloseReleasesItsReservationOnce() throws IOException {
        var path = Files.createFile(directory.resolve("closed-channel"));
        var reservation = LocalFileOwnership.control(path);
        var raw = new TestFileChannel(null);
        var owned = new LocalFileOwnership.OwnedChannel(raw, reservation);
        try {
            assertSame(raw, owned.channel());
            assertTrue(raw.isOpen());
            assertFalse(owned.isClosed());
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
            owned.close();
            assertFalse(raw.isOpen());
            assertTrue(owned.isClosed());
            assertEquals(1, raw.closeCalls);
            try (var replacement = LocalFileOwnership.control(path)) {
                owned.close();
                assertEquals(1, raw.closeCalls);
                assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
            }
        } finally {
            owned.close();
            reservation.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void channelCloseFailureRemainsUncertainDespiteTheRawClosedFlagAndCannotBeRetried(String kind) throws IOException {
        var path = Files.createFile(directory.resolve("uncertain-channel"));
        var reservation = LocalFileOwnership.control(path);
        Throwable expected = switch (kind) {
            case "io" -> new IOException("Native close failed");
            case "runtime" -> new IllegalStateException("Native close failed");
            case "error" -> new AssertionError("Native close failed");
            default -> throw new AssertionError(kind);
        };
        var raw = new TestFileChannel(expected);
        var owned = new LocalFileOwnership.OwnedChannel(raw, reservation);
        try {
            assertSame(expected, assertThrows(expected.getClass(), owned::close));
            assertFalse(raw.isOpen(), "AbstractInterruptibleChannel marks closed before native cleanup");
            assertFalse(owned.isClosed(), "A thrown native close does not prove cleanup");
            assertEquals(1, raw.closeCalls);
            assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
            assertThrows(IOException.class, () -> LocalFileOwnership.read(path));
            for (int attempt = 0; attempt < 2; attempt++) {
                var repeated = assertThrows(IOException.class, owned::close);
                assertSame(expected, repeated.getCause());
                assertFalse(owned.isClosed());
                assertEquals(1, raw.closeCalls, "Repeating raw close would silently return without native cleanup");
                assertThrows(IOException.class, () -> LocalFileOwnership.control(path));
            }
        } finally {
            // This injected channel owns no OS resource. Dispose only its test token so the
            // process-wide registry does not retain a deliberate fake cleanup failure.
            reservation.close();
        }
    }

    private static Path hardLink(Path link, Path target) throws IOException {
        try { return Files.createLink(link, target); }
        catch (UnsupportedOperationException unavailable) {
            assumeTrue(false, "Hard links explicitly unsupported: " + unavailable);
            throw new AssertionError("Assumption must abort", unavailable);
        }
    }

    private static final class TestFileChannel extends FileChannel {
        private final Throwable closeFailure;
        int closeCalls;
        TestFileChannel(Throwable closeFailure) { this.closeFailure = closeFailure; }
        @Override protected void implCloseChannel() throws IOException {
            closeCalls++;
            if (closeFailure instanceof IOException io) { throw io; }
            if (closeFailure instanceof RuntimeException runtime) { throw runtime; }
            if (closeFailure instanceof Error error) { throw error; }
        }
        @Override public int read(ByteBuffer destination) { throw new UnsupportedOperationException(); }
        @Override public long read(ByteBuffer[] destinations, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer source) { throw new UnsupportedOperationException(); }
        @Override public long write(ByteBuffer[] sources, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public long position() { throw new UnsupportedOperationException(); }
        @Override public FileChannel position(long position) { throw new UnsupportedOperationException(); }
        @Override public long size() { throw new UnsupportedOperationException(); }
        @Override public FileChannel truncate(long size) { throw new UnsupportedOperationException(); }
        @Override public void force(boolean metadata) { throw new UnsupportedOperationException(); }
        @Override public long transferTo(long position, long count, WritableByteChannel target) { throw new UnsupportedOperationException(); }
        @Override public long transferFrom(ReadableByteChannel source, long position, long count) { throw new UnsupportedOperationException(); }
        @Override public int read(ByteBuffer destination, long position) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer source, long position) { throw new UnsupportedOperationException(); }
        @Override public MappedByteBuffer map(MapMode mode, long position, long size) { throw new UnsupportedOperationException(); }
        @Override public FileLock lock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
        @Override public FileLock tryLock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
    }

    private static Path symbolicLink(Path link, Path target) throws IOException {
        try { return Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException unavailable) {
            assumeTrue(false, "Symbolic links explicitly unsupported: " + unavailable);
            throw new AssertionError("Assumption must abort", unavailable);
        }
    }
}
