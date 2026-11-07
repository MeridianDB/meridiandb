// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.internal.LocalIo;
import org.meridiandb.storage.internal.LocalIoObservation;
import org.meridiandb.storage.internal.LocalIoObservation.Metric;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.workload.LocalWorkloadIo.Category;
import org.meridiandb.workload.WorkloadMeasurements.Kind;
import org.meridiandb.workload.WorkloadMeasurements.Phase;

class LocalWorkloadIoTest {
    @TempDir Path directory;

    @Test
    void retainsLiteralNativeWorkWithOrderedTypedOperationAndAuxiliaryScopes() throws IOException {
        var trace = new LocalWorkloadIo(3);
        trace.iteration(0);
        Path file = directory.resolve("bytes");
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertEquals(3, trace.call(Category.OPERATION, Phase.LOAD, Kind.COMMIT,
                    () -> LocalIo.write(LocalIo.Role.JOURNAL, channel, ByteBuffer.wrap(new byte[] {1, 2, 3}), 0)));
            trace.call(Category.VALIDATION, null, null,
                    () -> LocalIo.read(LocalIo.Role.JOURNAL, channel, ByteBuffer.allocate(2), 0));
        }
        var entries = trace.entries();
        assertEquals(List.of(0, 1), entries.stream().map(LocalWorkloadIo.Entry::ordinal).toList());
        assertEquals(3, entries.getFirst().counters().value(LocalIo.Role.JOURNAL, Metric.WRITE_BYTES));
        assertEquals(2, entries.getLast().counters().sum(Metric.READ_BYTES));
        assertEquals(Category.VALIDATION, entries.getLast().category());
        assertTrue(entries.stream().allMatch(LocalWorkloadIo.Entry::accepted));
        assertThrows(UnsupportedOperationException.class, entries::clear);
    }

    @Test
    void failedNativeCallKeepsOriginalExceptionAndIncompleteCounters() throws IOException {
        var trace = new LocalWorkloadIo(1);
        var channel = FileChannel.open(Files.write(directory.resolve("closed"), new byte[] {1}));
        channel.close();
        var thrown = assertThrows(IOException.class, () -> trace.call(Category.VALIDATION, null, null,
                () -> LocalIo.read(LocalIo.Role.JOURNAL, channel, ByteBuffer.allocate(1), 0)));
        var entry = trace.entries().getFirst();
        assertEquals(thrown.getClass().getName(), entry.operationFailureType());
        assertEquals(1, entry.counters().sum(Metric.READ_FAILURES));
        assertEquals(0, entry.counters().sum(Metric.READ_BYTES));
        assertNotNull(entry.observationFailureType());
        assertFalse(entry.callReturned());
        assertFalse(entry.accepted());
        assertEquals(1, thrown.getSuppressed().length);
        try (var next = LocalIoObservation.open()) { assertNotNull(next); }
    }

    @Test
    void runtimeAndErrorIdentitySurviveAndFailedCallsKeepEarlierCompletedBytes() throws IOException {
        for (Throwable failure : List.of(new IllegalStateException("runtime"), new AssertionError("error"))) {
            var trace = new LocalWorkloadIo(1);
            try (var channel = FileChannel.open(directory.resolve(failure.getClass().getSimpleName()),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var thrown = assertThrows(failure.getClass(), () -> trace.call(Category.OPERATION, Phase.MIXED, Kind.COMMIT, () -> {
                    LocalIo.write(LocalIo.Role.JOURNAL, channel, ByteBuffer.wrap(new byte[] {1, 2}), 0);
                    if (failure instanceof RuntimeException runtime) { throw runtime; }
                    throw (Error) failure;
                }));
                assertSame(failure, thrown);
            }
            var entry = trace.entries().getFirst();
            assertEquals(2, entry.counters().sum(Metric.WRITE_BYTES));
            assertFalse(entry.accepted());
            assertNull(entry.observationFailureType());
        }
    }

    @Test
    void postAcquisitionObservationFailureClosesReturnedOwnerAndSuppressesCleanupFailure() {
        var failure = new IllegalStateException("after observation");
        var cleanup = new IOException("cleanup");
        var closed = new AtomicInteger();
        var trace = new LocalWorkloadIo(1, ignored -> { throw failure; });
        AutoCloseable resource = () -> { closed.incrementAndGet(); throw cleanup; };
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> trace.call(Category.OWNER_ACQUISITION, null, null, () -> resource)));
        assertEquals(1, closed.get());
        assertEquals(List.of(cleanup), List.of(failure.getSuppressed()));
        var entry = trace.entries().getFirst();
        assertTrue(entry.callReturned());
        assertTrue(entry.cleanupOutsideCapture());
        assertNotNull(entry.counters());
        assertEquals(IllegalStateException.class.getName(), entry.observationFailureType());
        assertFalse(entry.accepted());
    }

    @Test
    void overlapRecordsMissingObservationWithoutExecutingTheRequestedCall() throws IOException {
        var trace = new LocalWorkloadIo(1);
        var calls = new AtomicInteger();
        try (var external = LocalIoObservation.open()) {
            assertNotNull(external);
            assertThrows(IllegalStateException.class, () -> trace.call(Category.NAMESPACE, null, null, calls::incrementAndGet));
        }
        assertEquals(0, calls.get());
        var entry = trace.entries().getFirst();
        assertNull(entry.counters());
        assertFalse(entry.callReturned());
        assertFalse(entry.accepted());
        assertNotNull(entry.observationFailureType());
    }

    @Test
    void unavailableTeardownCaptureStillClosesEarlierOwnerExactlyOnce() {
        var trace = new LocalWorkloadIo(1);
        var closes = new AtomicInteger();
        try (var external = LocalIoObservation.open()) {
            assertNotNull(external);
            var owner = LocalWorkloadIo.own(trace, (AutoCloseable) closes::incrementAndGet);
            assertThrows(IllegalStateException.class, owner::close);
        }
        assertEquals(1, closes.get());
        var entry = trace.entries().getFirst();
        assertEquals(Category.TEARDOWN, entry.category());
        assertTrue(entry.cleanupOutsideCapture());
        assertNull(entry.counters());
    }

    @Test
    void aThrowingTeardownCallbackIsNeverRetriedByFallbackCleanup() {
        var trace = new LocalWorkloadIo(1);
        var closes = new AtomicInteger();
        var failure = new IOException("uncertain close");
        var owner = LocalWorkloadIo.own(trace, (AutoCloseable) () -> { closes.incrementAndGet(); throw failure; });
        assertSame(failure, assertThrows(IOException.class, owner::close));
        assertEquals(1, closes.get());
        assertEquals(IOException.class.getName(), trace.entries().getFirst().operationFailureType());
    }

    @Test
    void exhaustedCapacityRejectsMutationButCannotPreventOwnerCleanup() throws IOException {
        var trace = new LocalWorkloadIo(1);
        trace.call(Category.PREPARATION, null, null, () -> true);
        var effects = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> trace.call(Category.NAMESPACE, null, null, effects::incrementAndGet));
        assertEquals(0, effects.get());
        var owner = LocalWorkloadIo.own(trace, (AutoCloseable) effects::incrementAndGet);
        assertThrows(IllegalStateException.class, owner::close);
        assertEquals(1, effects.get());
        assertEquals(1, trace.entries().size());
    }

    @Test
    void wrongThreadAndReentrantUseRejectBeforeEffectsAndLeaveTraceUsable() throws IOException, InterruptedException {
        var trace = new LocalWorkloadIo(2);
        var failure = new AtomicReference<Throwable>();
        var effects = new AtomicInteger();
        var thread = Thread.startVirtualThread(() -> {
            try { trace.call(Category.PREPARATION, null, null, effects::incrementAndGet); }
            catch (Throwable problem) { failure.set(problem); }
        });
        thread.join();
        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(0, effects.get());
        trace.call(Category.PREPARATION, null, null, () -> {
            assertThrows(IllegalStateException.class, () -> trace.call(Category.PREPARATION, null, null, effects::incrementAndGet));
            assertThrows(IllegalStateException.class, trace::entries);
            return true;
        });
        trace.call(Category.PREPARATION, null, null, effects::incrementAndGet);
        assertEquals(1, effects.get());
        assertEquals(2, trace.entries().size());
    }

    @Test
    void wrongThreadTeardownRejectsWithoutClosingOrRelabelingEarlierEvidence() throws IOException, InterruptedException {
        var trace = new LocalWorkloadIo(2);
        trace.call(Category.PREPARATION, null, null, () -> true);
        var original = trace.entries().getFirst();
        var closes = new AtomicInteger();
        var owner = LocalWorkloadIo.own(trace, (AutoCloseable) closes::incrementAndGet);
        var failure = new AtomicReference<Throwable>();
        var thread = Thread.startVirtualThread(() -> {
            try { owner.close(); } catch (Throwable problem) { failure.set(problem); }
        });
        thread.join();
        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(0, closes.get());
        assertSame(original, trace.entries().getFirst());
        assertFalse(original.cleanupOutsideCapture());
        owner.close();
        assertEquals(1, closes.get());
        assertEquals(Category.TEARDOWN, trace.entries().getLast().category());
    }

    @Test
    void invalidScopeIdentityAndCapacityFailBeforeAction() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new LocalWorkloadIo(0));
        assertThrows(IllegalArgumentException.class, () -> new LocalWorkloadIo(LocalAttributedWorkload.MAX_SCOPES + 1));
        var trace = new LocalWorkloadIo(1);
        var calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> trace.call(Category.OPERATION, null, null, calls::incrementAndGet));
        assertThrows(IllegalArgumentException.class, () -> trace.call(Category.VALIDATION, Phase.MIXED, Kind.GET, calls::incrementAndGet));
        assertEquals(0, calls.get());
        assertTrue(trace.entries().isEmpty());
        var corpus = WorkloadCorpus.generate(new WorkloadCorpus.Spec(7, 1, 4, 5, 2, 100, 0, 0, 100, 100, 100));
        var once = new LocalWorkloadRunner.Config(0, 1);
        assertThrows(IllegalArgumentException.class, () -> LocalAttributedWorkload.run(directory, corpus, once, trace));
        var used = new LocalWorkloadIo(LocalAttributedWorkload.MAX_SCOPES);
        used.call(Category.PREPARATION, null, null, () -> true);
        assertThrows(IllegalStateException.class, () -> LocalAttributedWorkload.run(directory, corpus, once, used));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test
    void postCreateObservationFailureClosesActualDatabaseAndRetainsItsFailedNamespace() throws IOException {
        var corpus = WorkloadCorpus.generate(new WorkloadCorpus.Spec(7, 1, 4, 5, 2, 100, 0, 0, 100, 100, 100));
        var failure = new IllegalStateException("post-create observation");
        var trace = new LocalWorkloadIo(LocalAttributedWorkload.admittedCapacity(corpus, new LocalWorkloadRunner.Config(0, 1)),
                snapshot -> { if (snapshot.value(LocalIo.Role.JOURNAL, Metric.WRITE_BYTES) == 42) { throw failure; } });
        var thrown = assertThrows(LocalAttributedWorkload.RunFailedException.class,
                () -> LocalAttributedWorkload.run(directory, corpus, new LocalWorkloadRunner.Config(0, 1), trace));
        if (thrown.getCause() instanceof LocalWorkloadRunner.RunFailedException parent
                && parent.getCause() instanceof LocalCheckpointStore.DirectorySyncUnsupportedException
                && System.getProperty("os.name").startsWith("Windows")) {
            assumeTrue(false, "Directory synchronization unavailable on this Windows provider");
        }
        assertSame(failure, thrown.getCause().getCause());
        assertNotNull(thrown.runRoot());
        var failed = thrown.scopes().getLast();
        assertEquals(Kind.CREATE, failed.kind());
        assertTrue(failed.cleanupOutsideCapture());
        assertFalse(failed.accepted());
        assertThrows(UnsupportedOperationException.class, () -> thrown.scopes().clear());
        try (var reopened = LocalDatabase.openWritable(thrown.runRoot().resolve("iteration-0"))) {
            assertEquals(0, reopened.head().sequence());
        }
    }
}
