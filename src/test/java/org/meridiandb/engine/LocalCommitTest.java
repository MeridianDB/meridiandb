// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalFormatException;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

@Timeout(30)
class LocalCommitTest {
    private static final DatabaseId DATABASE = new DatabaseId("durable-writer");
    private static final EntityId SALARY = new EntityId("salary");
    private static final EntityId BONUS = new EntityId("bonus");
    private static final Transaction FIRST = put("initial", SALARY, 100);
    private static final Transaction SECOND = new Transaction(new RequestId("correction"), List.of(
            new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 20L), 120),
            new WriteOperation.Put(BONUS, new ValidTimeInterval(null, null), 7)));

    @TempDir
    Path directory;

    @Test
    void realCreatePublishesGenesisThenAcknowledgedTransactionsReopenWithOriginalIdentity() throws IOException {
        try (var database = createSupported(); var genesis = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(position(0), database.head());
            assertEquals(Optional.of(position(0)), database.checkpointPosition());
            var first = database.transact(FIRST);
            var second = database.transact(SECOND);
            assertEquals(new CommitResult(position(1), false), first);
            assertEquals(new CommitResult(position(2), false), second);
            assertEquals(new CommitResult(position(1), true), database.transact(FIRST));
            assertEquals(position(0), genesis.position());
            assertEquals(absent(), genesis.query(new TemporalQuery.Get(SALARY, 13)));
            assertEquals(Optional.of(position(0)), database.checkpointPosition());
        }
        byte[] journalBytes = Files.readAllBytes(directory.resolve("transactions.journal"));
        assertEquals(List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND)), JournalCodec.scan(journalBytes).records());
        try (var database = LocalDatabase.openWritable(directory)) {
            assertEquals(position(2), database.head());
            assertEquals(Optional.of(position(1)), database.lookupRequest(FIRST.requestId()));
            assertEquals(Optional.of(position(2)), database.lookupRequest(SECOND.requestId()));
            assertEquals(new CommitResult(position(2), true), database.transact(SECOND));
            try (var old = database.openView(new ReadCutoff.At(position(1))); var current = database.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(present(100), old.query(new TemporalQuery.Get(SALARY, 13)));
                assertEquals(present(120), current.query(new TemporalQuery.Get(SALARY, 13)));
                assertEquals(new TemporalResult.Aggregate(2, BigInteger.valueOf(127)),
                        current.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
            }
        }
        assertArrayEquals(journalBytes, Files.readAllBytes(directory.resolve("transactions.journal")));
    }

    @Test
    void createNeverReplacesAnExistingDatabaseOrChangesItsCanonicalBytes() throws IOException {
        try (var database = createSupported()) { database.transact(FIRST); }
        var before = snapshot();
        assertThrows(IOException.class, () -> LocalDatabase.create(directory, new DatabaseId("replacement")));
        assertFilesEqual(before, snapshot());
        try (var database = LocalDatabase.openWritable(directory)) { assertEquals(position(1), database.head()); }
    }

    @Test
    void readOnlyOpenRejectsTransactionsAndPreservesExistingHistory() throws IOException {
        try (var database = createSupported()) { database.transact(FIRST); }
        var before = snapshot();
        try (var database = LocalDatabase.open(directory); var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertThrows(IllegalStateException.class, () -> database.transact(SECOND));
            assertEquals(position(1), database.head());
            assertEquals(present(100), view.query(new TemporalQuery.Get(SALARY, 13)));
        }
        assertFilesEqual(before, snapshot());
    }

    @Test
    void realNoopsRemainCommittedRequestsAndPinnedViewsKeepTheirCutoffs() throws IOException {
        var sameValue = put("same-value", SALARY, 100);
        var absentRetraction = new Transaction(new RequestId("already-absent"), List.of(
                new WriteOperation.Retract(BONUS, new ValidTimeInterval(null, null))));
        try (var database = createSupported()) {
            database.transact(FIRST);
            try (var pinned = database.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(position(2), database.transact(sameValue).position());
                assertEquals(position(3), database.transact(absentRetraction).position());
                assertEquals(position(1), pinned.position());
                assertEquals(present(100), pinned.query(new TemporalQuery.Get(SALARY, 0)));
            }
        }
        try (var database = LocalDatabase.openWritable(directory)) {
            assertEquals(position(3), database.head());
            assertEquals(Optional.of(position(2)), database.lookupRequest(sameValue.requestId()));
            assertEquals(Optional.of(position(3)), database.lookupRequest(absentRetraction.requestId()));
            assertTrue(database.transact(absentRetraction).replayed());
        }
    }

    @Test
    void writerStartupSynchronizesOnlyAfterCompleteRecoveryAndBeforeReturning() throws IOException {
        var source = new Source(List.of(new JournalRecord(1, FIRST)));
        try (var database = LocalDatabase.recoverWritable(source)) {
            assertEquals(List.of("checkpoint", "journal", "synchronize"), source.events);
            assertEquals(position(1), database.head());
            assertEquals(1, source.synchronizations);
            assertEquals(0, source.appends);
            assertEquals(0, source.forces);
            assertTrue(source.owned);
        }
    }

    @Test
    void startupSynchronizationFailureReturnsNoWriterAndReleasesOwnedSource() {
        var source = new Source(List.of(new JournalRecord(1, FIRST)));
        source.failSynchronize = true;
        assertSame(source.synchronizeFailure, assertThrows(IOException.class, () -> LocalDatabase.recoverWritable(source)));
        assertEquals(List.of("checkpoint", "journal", "synchronize", "close"), source.events);
        assertEquals(1, source.closes);
        assertFalse(source.owned);
        assertEquals(0, source.appends);
    }

    @Test
    void startupSynchronizationFailureSuppressesCleanupFailureOnItsOriginalCause() {
        var source = new Source(List.of());
        source.failSynchronize = true;
        source.failCloseCount = 1;
        var failure = assertThrows(IOException.class, () -> LocalDatabase.recoverWritable(source));
        assertSame(source.synchronizeFailure, failure);
        assertArrayEquals(new Throwable[] {source.closeFailure}, failure.getSuppressed());
        assertEquals(1, source.closes);
    }

    @Test
    void malformedRecoveredHistoryFailsBeforeAnyStartupSynchronization() {
        var source = new Source(List.of(new JournalRecord(2, FIRST)));
        assertThrows(IOException.class, () -> LocalDatabase.recoverWritable(source));
        assertEquals(0, source.synchronizations);
        assertEquals(1, source.closes);
        assertFalse(source.owned);
    }

    @Test
    void newCommitAppendsAndForcesExactlyOnceBeforePublishingRequestAndHead() throws IOException {
        var source = new Source(List.of());
        try (var database = LocalDatabase.recoverWritable(source)) {
            source.events.clear();
            source.onAppend = () -> assertEquals(position(0), database.head());
            source.onForce = () -> {
                assertEquals(position(0), database.head());
                assertEquals(Optional.empty(), database.lookupRequest(FIRST.requestId()));
                try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
                    assertEquals(absent(), view.query(new TemporalQuery.Get(SALARY, 0)));
                }
            };
            var result = database.transact(FIRST);
            assertEquals(List.of("append", "force"), source.events);
            assertEquals(new CommitResult(position(1), false), result);
            assertEquals(position(1), database.head());
            assertEquals(Optional.of(position(1)), database.lookupRequest(FIRST.requestId()));
            assertEquals(List.of(new JournalRecord(1, FIRST)), source.records);
        }
    }

    @Test
    void identicalRetriesPerformNoAppendForceOrPreparationPublication() throws IOException {
        var source = new Source(List.of());
        try (var database = LocalDatabase.recoverWritable(source)) {
            var original = database.transact(FIRST);
            database.transact(SECOND);
            source.events.clear();
            var replay = database.transact(new Transaction(FIRST.requestId(), new ArrayList<>(FIRST.operations())));
            assertEquals(new CommitResult(original.position(), true), replay);
            assertTrue(source.events.isEmpty());
            assertEquals(2, source.appends);
            assertEquals(2, source.forces);
            assertEquals(position(2), database.head());
        }
    }

    @Test
    void requestReuseAndEncodingLimitsRejectBeforeIoAndLeaveWriterAndViewsUsable() throws IOException {
        var source = new Source(List.of());
        try (var database = LocalDatabase.recoverWritable(source)) {
            database.transact(FIRST);
            source.events.clear();
            try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
                var reused = assertThrows(EngineException.class, () -> database.transact(put("initial", SALARY, 999)));
                assertEquals(EngineException.Code.REQUEST_ID_REUSED, reused.code());
                var oversized = put("x".repeat(32_769), SALARY, 5);
                var limited = assertThrows(JournalFormatException.class, () -> database.transact(oversized));
                assertEquals(JournalFormatException.Code.LIMIT_EXCEEDED, limited.code());
                assertThrows(NullPointerException.class, () -> database.transact(null));
                assertTrue(source.events.isEmpty());
                assertEquals(position(1), database.head());
                assertEquals(present(100), view.query(new TemporalQuery.Get(SALARY, 0)));
                assertEquals(position(2), database.transact(SECOND).position());
                assertEquals(position(1), view.position());
            }
        }
    }

    @Test
    void retryPayloadKeepsFiniteMinimumDistinctFromUnboundedStateEvenWhenValuesAgree() throws IOException {
        var source = new Source(List.of());
        try (var database = LocalDatabase.recoverWritable(source)) {
            database.transact(FIRST);
            source.events.clear();
            var finite = new Transaction(FIRST.requestId(), List.of(new WriteOperation.Put(
                    SALARY, new ValidTimeInterval(Long.MIN_VALUE, null), 100)));
            var failure = assertThrows(EngineException.class, () -> database.transact(finite));
            assertEquals(EngineException.Code.REQUEST_ID_REUSED, failure.code());
            assertTrue(source.events.isEmpty());
            assertEquals(new CommitResult(position(1), true), database.transact(FIRST));
        }
    }

    @Test
    void appendFailureBeforeAndAfterEffectReportsRequestPositionAndPoisonsWithoutReleasingLocks() throws IOException {
        for (boolean afterEffect : new boolean[] {false, true}) {
            var source = new Source(List.of());
            source.failAppendBefore = !afterEffect;
            source.failAppendAfter = afterEffect;
            var database = LocalDatabase.recoverWritable(source);
            try {
                var view = database.openView(ReadCutoff.Head.INSTANCE);
                var failure = assertThrows(LocalDatabase.UncertainCommitException.class, () -> database.transact(FIRST));
                assertEquals(FIRST.requestId(), failure.requestId());
                assertEquals(position(1), failure.proposedPosition());
                assertSame(source.appendFailure, failure.getCause());
                assertEquals(afterEffect ? 1 : 0, source.records.size());
                assertEquals(0, source.forces);
                assertPoisoned(database, view);
                assertTrue(source.owned);
                assertEquals(0, source.closes);
                view.close();
            } finally { database.close(); }
            assertFalse(source.owned);
        }
    }

    @Test
    void forceFailureCannotExposeAProposedCommitOrKeepEarlierViewsUsable() throws IOException {
        var source = new Source(List.of());
        var database = LocalDatabase.recoverWritable(source);
        try {
            database.transact(FIRST);
            var view = database.openView(ReadCutoff.Head.INSTANCE);
            source.failForce = true;
            var failure = assertThrows(LocalDatabase.UncertainCommitException.class, () -> database.transact(SECOND));
            assertSame(source.forceFailure, failure.getCause());
            assertEquals(SECOND.requestId(), failure.requestId());
            assertEquals(position(2), failure.proposedPosition());
            assertEquals(List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND)), source.records);
            assertPoisoned(database, view);
            assertEquals(0, source.closes);
            view.close();
        } finally { database.close(); }
    }

    @Test
    void runtimeFailureFromAttemptedStorageIsUncertainAndPreservesItsCause() throws IOException {
        var source = new Source(List.of());
        var failure = new IllegalStateException("storage callback failed");
        source.onAppend = () -> { throw failure; };
        var database = LocalDatabase.recoverWritable(source);
        try {
            var uncertain = assertThrows(LocalDatabase.UncertainCommitException.class, () -> database.transact(FIRST));
            assertSame(failure, uncertain.getCause());
            assertEquals(position(1), uncertain.proposedPosition());
            assertThrows(IllegalStateException.class, database::head);
            assertTrue(source.owned);
        } finally { database.close(); }
    }

    @Test
    void errorFromAttemptedStoragePoisonsAndRethrowsTheOriginalError() throws IOException {
        var source = new Source(List.of());
        var failure = new AssertionError("injected storage error");
        source.onForce = () -> { throw failure; };
        var database = LocalDatabase.recoverWritable(source);
        try {
            assertSame(failure, assertThrows(AssertionError.class, () -> database.transact(FIRST)));
            assertThrows(IllegalStateException.class, database::head);
            assertTrue(source.owned);
            assertEquals(0, source.closes);
        } finally { database.close(); }
    }

    @Test
    void reopenAfterUncertainCompleteAppendResolvesRetryWithoutAnotherWrite() throws IOException {
        var source = new Source(List.of());
        source.failAppendAfter = true;
        var database = LocalDatabase.recoverWritable(source);
        assertThrows(LocalDatabase.UncertainCommitException.class, () -> database.transact(FIRST));
        database.close();
        var reopenedSource = new Source(source.records);
        try (var reopened = LocalDatabase.recoverWritable(reopenedSource)) {
            reopenedSource.events.clear();
            assertEquals(new CommitResult(position(1), true), reopened.transact(FIRST));
            assertEquals(Optional.of(position(1)), reopened.lookupRequest(FIRST.requestId()));
            assertTrue(reopenedSource.events.isEmpty());
            assertEquals(0, reopenedSource.appends);
            assertEquals(0, reopenedSource.forces);
        }
    }

    @Test
    void reopenAfterAnAbsentUncertainAppendCanCommitTheSameRequestOnce() throws IOException {
        var source = new Source(List.of());
        source.failAppendBefore = true;
        var database = LocalDatabase.recoverWritable(source);
        assertThrows(LocalDatabase.UncertainCommitException.class, () -> database.transact(FIRST));
        database.close();
        var reopenedSource = new Source(source.records);
        try (var reopened = LocalDatabase.recoverWritable(reopenedSource)) {
            assertEquals(Optional.empty(), reopened.lookupRequest(FIRST.requestId()));
            assertEquals(new CommitResult(position(1), false), reopened.transact(FIRST));
            assertEquals(1, reopenedSource.appends);
            assertEquals(1, reopenedSource.forces);
        }
    }

    @Test
    void poisonedDatabaseCloseCanBeRetriedWhileViewsAndTransactionsRemainInvalid() throws IOException {
        var source = new Source(List.of());
        source.failForce = true;
        source.failCloseCount = 1;
        var database = LocalDatabase.recoverWritable(source);
        var view = database.openView(ReadCutoff.Head.INSTANCE);
        assertThrows(LocalDatabase.UncertainCommitException.class, () -> database.transact(FIRST));
        assertSame(source.closeFailure, assertThrows(IOException.class, database::close));
        assertTrue(source.owned);
        assertPoisoned(database, view);
        database.close();
        database.close();
        assertEquals(2, source.closes);
        assertFalse(source.owned);
        view.close();
    }

    @Test
    void reentrantTransactionsAndCloseFromStorageCallbacksAreRejectedWithoutHarmingOuterCommit() throws IOException {
        var source = new Source(List.of());
        try (var database = LocalDatabase.recoverWritable(source)) {
            Runnable reenter = () -> {
                assertThrows(IllegalStateException.class, () -> database.transact(SECOND));
                assertThrows(IOException.class, database::close);
                assertEquals(position(0), database.head());
            };
            source.onAppend = reenter;
            source.onForce = reenter;
            assertEquals(new CommitResult(position(1), false), database.transact(FIRST));
            assertEquals(1, source.appends);
            assertEquals(1, source.forces);
            assertEquals(0, source.closes);
        }
    }

    @Test
    void readersObserveOldStateWhileForceBlocksThenOneWholePublishedState() throws Exception {
        var source = new Source(List.of(new JournalRecord(1, FIRST)));
        try (var database = LocalDatabase.recoverWritable(source);
             var pinned = database.openView(ReadCutoff.Head.INSTANCE)) {
            var forceEntered = new CountDownLatch(1);
            var releaseForce = new CountDownLatch(1);
            source.onForce = () -> {
                forceEntered.countDown();
                await(releaseForce);
            };
            var executor = Executors.newFixedThreadPool(2);
            try {
                var writer = executor.submit(() -> database.transact(SECOND));
                assertTrue(forceEntered.await(5, TimeUnit.SECONDS));
                try {
                    var reader = executor.submit(() -> {
                        assertEquals(position(1), database.head());
                        assertEquals(Optional.empty(), database.lookupRequest(SECOND.requestId()));
                        try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
                            assertEquals(position(1), view.position());
                            assertEquals(present(100), view.query(new TemporalQuery.Get(SALARY, 13)));
                            assertEquals(absent(), view.query(new TemporalQuery.Get(BONUS, 13)));
                            assertEquals(new TemporalResult.Aggregate(1, BigInteger.valueOf(100)),
                                    view.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
                        }
                    });
                    reader.get(5, TimeUnit.SECONDS);
                } finally { releaseForce.countDown(); }
                assertEquals(new CommitResult(position(2), false), writer.get(5, TimeUnit.SECONDS));
                try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
                    assertEquals(position(2), view.position());
                    assertEquals(present(120), view.query(new TemporalQuery.Get(SALARY, 13)));
                    assertEquals(present(7), view.query(new TemporalQuery.Get(BONUS, 13)));
                    assertEquals(new TemporalResult.Aggregate(2, BigInteger.valueOf(127)),
                            view.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
                }
                assertEquals(position(1), pinned.position());
                assertEquals(present(100), pinned.query(new TemporalQuery.Get(SALARY, 13)));
                assertEquals(Optional.of(position(2)), database.lookupRequest(SECOND.requestId()));
            } finally {
                releaseForce.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void closeWaitsForAnInFlightCommitAndReleasesResourcesOnlyAfterForce() throws Exception {
        var source = new Source(List.of());
        var database = LocalDatabase.recoverWritable(source);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        source.onForce = () -> { entered.countDown(); await(release); };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> database.transact(FIRST));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closing = new CountDownLatch(1);
            var closer = executor.submit(() -> { closing.countDown(); database.close(); return null; });
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> closer.get(100, TimeUnit.MILLISECONDS));
            assertTrue(source.owned);
            release.countDown();
            assertEquals(new CommitResult(position(1), false), writer.get(5, TimeUnit.SECONDS));
            closer.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("checkpoint", "journal", "synchronize", "append", "force", "close"), source.events);
            assertThrows(IllegalStateException.class, database::head);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            database.close();
        }
    }

    @Test
    void simultaneousIdenticalRetriesPerformOneAppendAndForce() throws Exception {
        var source = new Source(List.of());
        try (var database = LocalDatabase.recoverWritable(source)) {
            int callers = 8;
            var barrier = new CyclicBarrier(callers);
            var executor = Executors.newFixedThreadPool(callers);
            try {
                var futures = new ArrayList<Future<CommitResult>>();
                for (int caller = 0; caller < callers; caller++) {
                    futures.add(executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return database.transact(FIRST); }));
                }
                var results = new ArrayList<CommitResult>();
                for (var future : futures) { results.add(future.get(5, TimeUnit.SECONDS)); }
                assertEquals(1, results.stream().filter(result -> !result.replayed()).count());
                assertEquals(7, results.stream().filter(CommitResult::replayed).count());
                assertTrue(results.stream().allMatch(result -> result.position().equals(position(1))));
                assertEquals(1, source.appends);
                assertEquals(1, source.forces);
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }

    @Test
    void concurrentDistinctWritersHaveContiguousAtomicCommitPositionsAndLoseNoChanges() throws Exception {
        var source = new Source(List.of());
        try (var database = LocalDatabase.recoverWritable(source)) {
            int callers = 6;
            var barrier = new CyclicBarrier(callers);
            var executor = Executors.newFixedThreadPool(callers);
            try {
                var futures = new ArrayList<Future<CommitResult>>();
                var entities = new ArrayList<EntityId>();
                for (int caller = 1; caller <= callers; caller++) {
                    var entity = new EntityId("entity-" + caller);
                    entities.add(entity);
                    var request = put("request-" + caller, entity, caller);
                    futures.add(executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return database.transact(request); }));
                }
                Set<Long> positions = new HashSet<>();
                for (var future : futures) { positions.add(future.get(5, TimeUnit.SECONDS).position().sequence()); }
                assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L), positions);
                assertEquals(position(6), database.head());
                assertEquals(6, source.appends);
                assertEquals(6, source.forces);
                try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
                    assertEquals(new TemporalResult.Aggregate(6, BigInteger.valueOf(21)),
                            view.query(new TemporalQuery.Sum(entities, 0)));
                }
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }

    private static void assertPoisoned(LocalDatabase database, LocalDatabase.ReadView view) {
        assertThrows(IllegalStateException.class, database::head);
        assertThrows(IllegalStateException.class, database::checkpointPosition);
        assertThrows(IllegalStateException.class, () -> database.lookupRequest(FIRST.requestId()));
        assertThrows(IllegalStateException.class, () -> database.openView(ReadCutoff.Head.INSTANCE));
        assertThrows(IllegalStateException.class, () -> database.transact(SECOND));
        assertThrows(IllegalStateException.class, view::position);
        assertThrows(IllegalStateException.class, () -> view.query(new TemporalQuery.Get(SALARY, 0)));
    }

    private LocalDatabase createSupported() throws IOException {
        try { return LocalDatabase.create(directory, DATABASE); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
            throw new AssertionError("Assumption did not abort", unsupported);
        }
    }

    private Map<String, byte[]> snapshot() throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var paths = Files.list(directory)) {
            for (var path : paths.toList()) { if (Files.isRegularFile(path)) { result.put(path.getFileName().toString(), Files.readAllBytes(path)); } }
        }
        return result;
    }

    private static void assertFilesEqual(Map<String, byte[]> before, Map<String, byte[]> after) {
        assertEquals(before.keySet(), after.keySet());
        before.forEach((name, bytes) -> assertArrayEquals(bytes, after.get(name), name));
    }

    private static CommitPosition position(long sequence) { return new CommitPosition(DATABASE, sequence); }
    private static TemporalResult.Point present(long value) { return new TemporalResult.Point(OptionalLong.of(value)); }
    private static TemporalResult.Point absent() { return new TemporalResult.Point(OptionalLong.empty()); }
    private static Transaction put(String request, EntityId entity, long value) {
        return new Transaction(new RequestId(request), List.of(new WriteOperation.Put(entity, new ValidTimeInterval(null, null), value)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Storage callback gate timed out"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Storage callback interrupted", interrupted);
        }
    }

    private static final class Source implements LocalDatabase.WriteSource {
        final List<JournalRecord> records;
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile int appends;
        volatile int forces;
        int synchronizations;
        int closes;
        boolean owned = true;
        boolean failSynchronize;
        boolean failAppendBefore;
        boolean failAppendAfter;
        boolean failForce;
        int failCloseCount;
        Runnable onAppend;
        Runnable onForce;
        final IOException synchronizeFailure = new IOException("Injected startup synchronization failure");
        final IOException appendFailure = new IOException("Injected append failure");
        final IOException forceFailure = new IOException("Injected force failure");
        final IOException closeFailure = new IOException("Injected close failure");
        Source(List<JournalRecord> records) { this.records = new ArrayList<>(records); }
        @Override public DatabaseId databaseId() { return DATABASE; }
        @Override public OptionalLong checkpointSequence() { return OptionalLong.of(0); }
        @Override public void replayCheckpoint(Consumer<? super JournalRecord> consumer) { events.add("checkpoint"); }
        @Override public void replayJournal(Consumer<? super JournalRecord> consumer) {
            events.add("journal");
            records.forEach(consumer);
        }
        @Override public long journalSequence() { return records.size(); }
        @Override public void synchronize() throws IOException {
            events.add("synchronize");
            synchronizations++;
            if (failSynchronize) { throw synchronizeFailure; }
        }
        @Override public void append(JournalRecord record) throws IOException {
            events.add("append");
            appends++;
            if (onAppend != null) { onAppend.run(); }
            if (failAppendBefore) { throw appendFailure; }
            records.add(record);
            if (failAppendAfter) { throw appendFailure; }
        }
        @Override public void force() throws IOException {
            events.add("force");
            forces++;
            if (onForce != null) { onForce.run(); }
            if (failForce) { throw forceFailure; }
        }
        @Override public void close() throws IOException {
            events.add("close");
            closes++;
            if (failCloseCount > 0) { failCloseCount--; throw closeFailure; }
            owned = false;
        }
    }
}
