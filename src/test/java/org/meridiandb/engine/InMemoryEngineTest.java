// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

@Timeout(30)
class InMemoryEngineTest {
    private static final EntityId ALICE = new EntityId("alice");
    private static final EntityId BOB = new EntityId("bob");
    private static final ValidTimeInterval ALL_TIME = new ValidTimeInterval(null, null);

    @Test
    void freshInstancesHaveIndependentLineagesAndRejectForeignAndFutureCutoffs() {
        try (var engine = new InMemoryEngine(); var other = new InMemoryEngine()) {
            var genesis = engine.head();
            assertEquals(0, genesis.sequence());
            assertNotEquals(genesis.databaseId(), other.head().databaseId());
            assertEquals(Optional.empty(), engine.lookupRequest(new RequestId("missing")));
            try (var view = engine.openView(new ReadCutoff.At(genesis))) {
                assertEquals(genesis, view.position());
                assertEquals(absent(), view.query(new TemporalQuery.Get(ALICE, 0)));
            }
            assertCode(EngineException.Code.INVALID_CUTOFF,
                    () -> engine.openView(new ReadCutoff.At(other.head())));
            assertCode(EngineException.Code.INVALID_CUTOFF,
                    () -> engine.openView(new ReadCutoff.At(new CommitPosition(genesis.databaseId(), 1))));
            assertCode(EngineException.Code.INVALID_CUTOFF,
                    () -> engine.openView(new ReadCutoff.At(
                            new CommitPosition(genesis.databaseId(), Long.MAX_VALUE))));
            assertEquals(genesis, engine.head());
        }
    }

    @Test
    void headAndExplicitHistoricalViewsKeepTheirCapturedCutoff() {
        try (var engine = new InMemoryEngine(); var genesis = engine.openView(ReadCutoff.Head.INSTANCE)) {
            var first = engine.transact(put("first", ALICE, 7));
            try (var firstHead = engine.openView(ReadCutoff.Head.INSTANCE)) {
                var second = engine.transact(put("second", ALICE, 11));
                try (var historical = engine.openView(new ReadCutoff.At(first.position()));
                        var current = engine.openView(ReadCutoff.Head.INSTANCE)) {
                    assertEquals(0, genesis.position().sequence());
                    assertEquals(absent(), genesis.query(new TemporalQuery.Get(ALICE, 0)));
                    assertEquals(first.position(), firstHead.position());
                    assertEquals(present(7), firstHead.query(new TemporalQuery.Get(ALICE, 0)));
                    assertEquals(present(7), historical.query(new TemporalQuery.Get(ALICE, 0)));
                    assertEquals(second.position(), current.position());
                    assertEquals(present(11), current.query(new TemporalQuery.Get(ALICE, 0)));
                    assertEquals(first.position(), engine.lookupRequest(new RequestId("first")).orElseThrow());
                }
            }
        }
    }

    @Test
    void closingOneViewIsIdempotentAndDoesNotCloseOtherViewsOrTheEngine() {
        try (var engine = new InMemoryEngine(); var retained = engine.openView(ReadCutoff.Head.INSTANCE)) {
            var closed = engine.openView(ReadCutoff.Head.INSTANCE);
            closed.close();
            closed.close();
            assertThrows(IllegalStateException.class, closed::position);
            assertThrows(IllegalStateException.class, () -> closed.query(new TemporalQuery.Get(ALICE, 0)));
            assertEquals(0, retained.position().sequence());
            assertEquals(absent(), retained.query(new TemporalQuery.Get(ALICE, 0)));
            var commit = engine.transact(put("after-close", ALICE, 3));
            assertEquals(1, commit.position().sequence());
            try (var fresh = engine.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(present(3), fresh.query(new TemporalQuery.Get(ALICE, 0)));
            }
        }
    }

    @Test
    void closingTheEngineInvalidatesExistingViewsAndAllEngineOperations() {
        var engine = new InMemoryEngine();
        var view = engine.openView(ReadCutoff.Head.INSTANCE);
        var position = view.position();
        engine.close();
        engine.close();
        assertThrows(IllegalStateException.class, engine::head);
        assertThrows(IllegalStateException.class, () -> engine.transact(put("closed", ALICE, 1)));
        assertThrows(IllegalStateException.class, () -> engine.lookupRequest(new RequestId("closed")));
        assertThrows(IllegalStateException.class, () -> engine.openView(ReadCutoff.Head.INSTANCE));
        assertThrows(IllegalStateException.class, () -> engine.openView(new ReadCutoff.At(position)));
        assertThrows(IllegalStateException.class, view::position);
        assertThrows(IllegalStateException.class, () -> view.query(new TemporalQuery.Get(ALICE, 0)));
        view.close();
        view.close();
    }

    @Test
    void simultaneousIdenticalRetriesCommitExactlyOnce() throws Exception {
        try (var engine = new InMemoryEngine()) {
            var transaction = put("same", ALICE, 7);
            var results = concurrently(12, ignored -> engine.transact(transaction));
            assertEquals(1, results.stream().filter(result -> !result.replayed()).count());
            assertEquals(11, results.stream().filter(CommitResult::replayed).count());
            var position = results.getFirst().position();
            assertEquals(1, position.sequence());
            for (var result : results) {
                assertEquals(position, result.position());
            }
            assertEquals(position, engine.head());
            assertEquals(position, engine.lookupRequest(transaction.requestId()).orElseThrow());
            try (var view = engine.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(present(7), view.query(new TemporalQuery.Get(ALICE, 0)));
            }
        }
    }

    @Test
    void simultaneousConflictingRetriesChooseOnePayloadAndRejectAllOthers() throws Exception {
        try (var engine = new InMemoryEngine()) {
            var outcomes = concurrently(12, caller -> {
                try {
                    return new Attempt(caller, engine.transact(put("contended", ALICE, caller)), null);
                } catch (EngineException failure) {
                    return new Attempt(caller, null, failure.code());
                }
            });
            var accepted = outcomes.stream().filter(outcome -> outcome.result() != null).toList();
            assertEquals(1, accepted.size());
            var winner = accepted.getFirst();
            assertFalse(winner.result().replayed());
            assertEquals(1, winner.result().position().sequence());
            for (var outcome : outcomes) {
                if (outcome.result() == null) {
                    assertEquals(EngineException.Code.REQUEST_ID_REUSED, outcome.failure());
                }
            }
            assertEquals(winner.result().position(), engine.head());
            assertEquals(winner.result().position(), engine.lookupRequest(new RequestId("contended")).orElseThrow());
            var replay = engine.transact(put("contended", ALICE, winner.caller()));
            assertTrue(replay.replayed());
            assertEquals(winner.result().position(), replay.position());
            try (var view = engine.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(present(winner.caller()), view.query(new TemporalQuery.Get(ALICE, 0)));
            }
        }
    }

    @Test
    void concurrentDistinctCallersReceiveContiguousCommitsAndLoseNoWrites() throws Exception {
        try (var engine = new InMemoryEngine()) {
            int callers = 16;
            var results = concurrently(callers,
                    caller -> engine.transact(put("request-" + caller, new EntityId("entity-" + caller), caller)));
            Set<Long> actualSequences = new HashSet<>();
            Set<Long> expectedSequences = new HashSet<>();
            var entities = new ArrayList<EntityId>();
            for (int caller = 0; caller < callers; caller++) {
                var result = results.get(caller);
                assertFalse(result.replayed());
                actualSequences.add(result.position().sequence());
                expectedSequences.add((long) caller + 1);
                assertEquals(engine.head().databaseId(), result.position().databaseId());
                assertEquals(result.position(), engine.lookupRequest(new RequestId("request-" + caller)).orElseThrow());
                entities.add(new EntityId("entity-" + caller));
            }
            assertEquals(expectedSequences, actualSequences);
            assertEquals(callers, engine.head().sequence());
            try (var view = engine.openView(ReadCutoff.Head.INSTANCE)) {
                for (int caller = 0; caller < callers; caller++) {
                    assertEquals(present(caller), view.query(new TemporalQuery.Get(entities.get(caller), 0)));
                }
                assertEquals(new TemporalResult.Aggregate(callers, BigInteger.valueOf(120)),
                        view.query(new TemporalQuery.Sum(entities, 0)));
            }
        }
    }

    @Test
    void concurrentReadersSeeWholeCrossEntityTransactionsAtOneCapturedPosition() throws Exception {
        try (var engine = new InMemoryEngine()) {
            engine.transact(pair("initial", 0));
            int rounds = 32;
            var round = new CyclicBarrier(5);
            concurrently(5, caller -> {
                for (int number = 1; number <= rounds; number++) {
                    await(round);
                    if (caller == 0) {
                        var commit = engine.transact(pair("pair-" + number, number));
                        assertEquals(number + 1, commit.position().sequence());
                    } else {
                        try (var view = engine.openView(ReadCutoff.Head.INSTANCE)) {
                            long expected = view.position().sequence() - 1;
                            assertTrue(expected == number - 1 || expected == number);
                            assertEquals(present(expected), view.query(new TemporalQuery.Get(ALICE, 0)));
                            assertEquals(present(expected), view.query(new TemporalQuery.Get(BOB, 0)));
                            assertEquals(new TemporalResult.Aggregate(2, BigInteger.valueOf(expected * 2)),
                                    view.query(new TemporalQuery.Sum(List.of(ALICE, BOB), 0)));
                            assertEquals(expected + 1, view.position().sequence());
                        }
                    }
                    await(round);
                }
                return null;
            });
            assertEquals(rounds + 1, engine.head().sequence());
        }
    }

    @Test
    void anOpenViewCanBeSharedByConcurrentReadersAfterNewCommits() throws Exception {
        try (var engine = new InMemoryEngine()) {
            var first = engine.transact(new Transaction(new RequestId("initial"), List.of(
                    new WriteOperation.Put(ALICE, ALL_TIME, 7),
                    new WriteOperation.Put(BOB, ALL_TIME, 11))));
            try (var view = engine.openView(ReadCutoff.Head.INSTANCE)) {
                engine.transact(pair("replacement", 99));
                var window = new ValidTimeInterval(-10L, 10L);
                var expectedScan = new TemporalResult.Segments(List.of(new TemporalSegment(window, 7)));
                concurrently(6, ignored -> {
                    for (int iteration = 0; iteration < 50; iteration++) {
                        assertEquals(first.position(), view.position());
                        assertEquals(present(7), view.query(new TemporalQuery.Get(ALICE, 0)));
                        assertEquals(expectedScan, view.query(new TemporalQuery.Scan(ALICE, window)));
                        assertEquals(new TemporalResult.Aggregate(2, BigInteger.valueOf(18)),
                                view.query(new TemporalQuery.Sum(List.of(ALICE, BOB), 0)));
                    }
                    return null;
                });
            }
        }
    }

    private static Transaction put(String requestId, EntityId entity, long value) {
        return new Transaction(new RequestId(requestId), List.of(new WriteOperation.Put(entity, ALL_TIME, value)));
    }

    private static Transaction pair(String requestId, long value) {
        return new Transaction(new RequestId(requestId), List.of(
                new WriteOperation.Put(ALICE, ALL_TIME, value), new WriteOperation.Put(BOB, ALL_TIME, value)));
    }

    private static TemporalResult.Point present(long value) {
        return new TemporalResult.Point(OptionalLong.of(value));
    }

    private static TemporalResult.Point absent() {
        return new TemporalResult.Point(OptionalLong.empty());
    }

    private static void assertCode(EngineException.Code code, Runnable action) {
        assertEquals(code, assertThrows(EngineException.class, action::run).code());
    }

    private static void await(CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
    }

    private static <T> List<T> concurrently(int callers, ConcurrentAction<T> action) throws Exception {
        var start = new CyclicBarrier(callers);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            var futures = new ArrayList<Future<T>>();
            for (int caller = 0; caller < callers; caller++) {
                int index = caller;
                futures.add(executor.submit(() -> {
                    await(start);
                    return action.run(index);
                }));
            }
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Concurrent workers did not terminate");
        }
    }

    private record Attempt(int caller, CommitResult result, EngineException.Code failure) {}

    @FunctionalInterface
    private interface ConcurrentAction<T> {
        T run(int caller) throws Exception;
    }
}
