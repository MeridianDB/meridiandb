// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.LocalSegmentReader;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

@Timeout(30)
@SuppressWarnings("try") // Explicit child close is part of lifetime verification.
class LocalCheckpointReaderProtectionTest {
    private static final DatabaseId DATABASE = new DatabaseId("reader-protection-db");
    private static final JournalRecord RECORD = new JournalRecord(1, new Transaction(new RequestId("first"),
            List.of(new WriteOperation.Put(new EntityId("account"), new ValidTimeInterval(null, null), 7))));
    private static final ImmutableSegment SEGMENT = new ImmutableSegment(DATABASE, List.of(RECORD));
    private static final byte[] IMAGE = SegmentCodec.encode(SEGMENT);

    @TempDir
    Path directory;

    @Test
    void explicitCloseReleasesProtectionOnceAndLeavesHeapChildrenAndReturnedValuesUsable() throws IOException {
        var protection = new TestProtection();
        var decoded = metadata();
        installSegment(decoded);
        var reader = new LocalCheckpointReader(directory, decoded, protection);
        try (var child = reader.openSegment(0)) {
            var returned = child.segment();
            var manifest = reader.manifest();
            var id = reader.manifestId();
            assertEquals(1, protection.checks.get());
            assertEquals(0, protection.closes.get());
            reader.close();
            reader.close();
            assertEquals(1, protection.closes.get());
            assertClosed(reader);
            assertEquals(decoded.manifest(), manifest);
            assertEquals(decoded.id(), id);
            assertEquals(SEGMENT, child.segment());
            assertEquals(SEGMENT, returned);
            child.close();
            assertThrows(IllegalStateException.class, child::segment);
            assertEquals(List.of(RECORD), returned.records());
        } finally { reader.close(); }
    }

    @Test
    void failedReleaseClosesTheReaderBeforeCallbacksAndRetainsProtectionForCloseRetry() throws IOException {
        var protection = new TestProtection();
        var decoded = metadata();
        installSegment(decoded);
        var reader = new LocalCheckpointReader(directory, decoded, protection);
        var failure = new IOException("Protection release failed");
        protection.closeAction = () -> assertClosed(reader);
        protection.closeFailure = failure;
        try (var child = reader.openSegment(0)) {
            assertSame(failure, assertThrows(IOException.class, reader::close));
            assertClosed(reader);
            assertEquals(1, protection.closes.get());
            assertEquals(SEGMENT, child.segment());
            protection.closeFailure = null;
            reader.close();
            reader.close();
            assertEquals(2, protection.closes.get(), "Only the failed protection close is retried");
            assertEquals(SEGMENT, child.segment());
        } finally {
            protection.closeFailure = null;
            reader.close();
        }
    }

    @Test
    void invalidProtectionFailsBeforeAnyLazyFileAccessAndDoesNotReleaseTheReference() throws IOException {
        var protection = new TestProtection();
        var failure = new IOException("Reader lock lost");
        protection.checkFailure = failure;
        var decoded = metadata();
        // No segment exists: the protection error must be observed before a path read.
        try (var reader = new LocalCheckpointReader(directory, decoded, protection)) {
            assertSame(failure, assertThrows(IOException.class, () -> reader.openSegment(0)));
            assertEquals(decoded.manifest(), reader.manifest());
            assertEquals(1, protection.checks.get());
            assertEquals(0, protection.closes.get());
            protection.checkFailure = null;
            installSegment(decoded);
            try (var child = reader.openSegment(0)) { assertEquals(SEGMENT, child.segment()); }
        }
        assertEquals(1, protection.closes.get());
    }

    @Test
    void failedSegmentValidationRetainsProtectionUntilExplicitReaderClose() throws IOException {
        var protection = new TestProtection();
        var decoded = metadata();
        var path = installSegment(decoded);
        var corrupted = IMAGE.clone();
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(path, corrupted);
        try (var reader = new LocalCheckpointReader(directory, decoded, protection)) {
            assertThrows(SegmentFormatException.class, () -> reader.openSegment(0));
            assertEquals(0, protection.closes.get());
            assertEquals(decoded.id(), reader.manifestId());
            Files.write(path, IMAGE);
            try (var child = reader.openSegment(0)) { assertEquals(SEGMENT, child.segment()); }
            assertEquals(2, protection.checks.get());
        }
        assertEquals(1, protection.closes.get());
    }

    @Test
    void closeWaitsForTheProtectedLazyOpenAndItsHeapChildSurvivesRelease() throws Exception {
        var protection = new TestProtection();
        var decoded = metadata();
        installSegment(decoded);
        var reader = new LocalCheckpointReader(directory, decoded, protection);
        var checkEntered = new CountDownLatch(1);
        var continueOpen = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        protection.checkAction = () -> {
            checkEntered.countDown();
            await(continueOpen);
            assertEquals(0, protection.closes.get(), "The reference remains held throughout lazy open");
        };
        var executor = Executors.newFixedThreadPool(2);
        LocalSegmentReader child = null;
        try {
            var opening = executor.submit(() -> reader.openSegment(0));
            assertTrue(checkEntered.await(10, TimeUnit.SECONDS), "Lazy open did not reach protection check");
            var closing = executor.submit(() -> {
                closeStarted.countDown();
                reader.close();
                return null;
            });
            assertTrue(closeStarted.await(10, TimeUnit.SECONDS), "Close worker did not start");
            assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS),
                    "Close must wait while lazy open owns the protection monitor");
            assertEquals(0, protection.closes.get());
            continueOpen.countDown();
            child = opening.get(10, TimeUnit.SECONDS);
            closing.get(10, TimeUnit.SECONDS);
            assertEquals(1, protection.closes.get());
            assertClosed(reader);
            assertEquals(SEGMENT, child.segment());
        } finally {
            continueOpen.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Reader workers did not terminate");
            if (child != null) { child.close(); }
            reader.close();
        }
    }

    @Test
    void invalidIndexDoesNotReleaseProtectionOrPreventAnotherLazyOpen() throws IOException {
        var protection = new TestProtection();
        var decoded = metadata();
        installSegment(decoded);
        try (var reader = new LocalCheckpointReader(directory, decoded, protection)) {
            assertThrows(IndexOutOfBoundsException.class, () -> reader.openSegment(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> reader.openSegment(1));
            assertEquals(0, protection.closes.get());
            try (var child = reader.openSegment(0)) { assertEquals(SEGMENT, child.segment()); }
        }
        assertEquals(1, protection.closes.get());
    }

    @Test
    void constructorRejectsNullRequiredInputs() throws IOException {
        var decoded = metadata();
        var protection = new TestProtection();
        assertThrows(NullPointerException.class, () -> new LocalCheckpointReader(null, decoded, protection));
        assertThrows(NullPointerException.class, () -> new LocalCheckpointReader(directory, null, protection));
        assertThrows(NullPointerException.class, () -> new LocalCheckpointReader(directory, decoded, null));
        assertEquals(0, protection.closes.get());
    }

    private static ManifestCodec.DecodedManifest metadata() throws IOException {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(DATABASE));
        bytes.writeBytes(JournalCodec.encodeRecord(RECORD));
        var prefix = bytes.toByteArray();
        var decoded = SegmentCodec.decode(IMAGE);
        var manifest = new CheckpointManifest(DATABASE, 1,
                new JournalPrefix(1, prefix.length, HexFormat.of().formatHex(ManifestCodec.sha256().digest(prefix))),
                List.of(new SegmentReference(decoded.id(), IMAGE.length, 1, 1)));
        return ManifestCodec.decode(ManifestCodec.encode(manifest));
    }

    private Path installSegment(ManifestCodec.DecodedManifest decoded) throws IOException {
        return Files.write(directory.resolve(LocalCheckpointStore.segmentName(decoded.manifest().segments().getFirst().id())), IMAGE);
    }

    private static void assertClosed(LocalCheckpointReader reader) {
        assertThrows(IllegalStateException.class, reader::manifest);
        assertThrows(IllegalStateException.class, reader::manifestId);
        assertThrows(IllegalStateException.class, () -> reader.openSegment(0));
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new IOException("Timed out waiting for reader coordination"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Reader coordination interrupted", interrupted);
        }
    }

    @FunctionalInterface
    private interface Action { void run() throws IOException; }

    private static final class TestProtection implements LocalReaderBarrier.Protection {
        final AtomicInteger checks = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        Action checkAction = () -> {};
        Action closeAction = () -> {};
        IOException checkFailure;
        IOException closeFailure;
        @Override public void checkValid() throws IOException {
            checks.incrementAndGet();
            if (checkFailure != null) { throw checkFailure; }
            checkAction.run();
        }
        @Override public void close() throws IOException {
            closes.incrementAndGet();
            closeAction.run();
            if (closeFailure != null) { throw closeFailure; }
        }
    }
}
