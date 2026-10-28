// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class PreparedCommitTest {
    private static final EntityId ALICE = new EntityId("alice");
    private static final EntityId BOB = new EntityId("bob");
    private static final ValidTimeInterval ALL_TIME = new ValidTimeInterval(null, null);

    @Test
    void preparedValuesHistoryAndRequestIdentityStayHiddenUntilOnePublication() {
        try (var engine = new InMemoryEngine(); var genesis = engine.openView(ReadCutoff.Head.INSTANCE)) {
            var request = new Transaction(new RequestId("pair"), List.of(
                    new WriteOperation.Put(ALICE, ALL_TIME, 7), new WriteOperation.Put(BOB, ALL_TIME, 11)));
            var before = engine.head();
            var prepared = engine.prepare(request);
            var result = prepared.result();
            assertFalse(result.replayed());
            assertEquals(new CommitPosition(before.databaseId(), 1), result.position());
            assertEquals(before, engine.head());
            assertEquals(Optional.empty(), engine.lookupRequest(request.requestId()));
            try (var current = engine.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(before, current.position());
                assertEquals(absent(), current.query(new TemporalQuery.Get(ALICE, 0)));
                assertEquals(new TemporalResult.Aggregate(0, BigInteger.ZERO),
                        current.query(new TemporalQuery.Sum(List.of(ALICE, BOB), 0)));
            }
            var invalid = assertThrows(EngineException.class,
                    () -> engine.openView(new ReadCutoff.At(result.position())));
            assertEquals(EngineException.Code.INVALID_CUTOFF, invalid.code());
            assertSame(result, engine.publish(prepared), "Publication returns the already allocated result");
            assertEquals(result.position(), engine.head());
            assertEquals(Optional.of(result.position()), engine.lookupRequest(request.requestId()));
            try (var published = engine.openView(new ReadCutoff.At(result.position()))) {
                assertEquals(new TemporalResult.Point(OptionalLong.of(7)),
                        published.query(new TemporalQuery.Get(ALICE, 0)));
                assertEquals(new TemporalResult.Point(OptionalLong.of(11)),
                        published.query(new TemporalQuery.Get(BOB, 0)));
                assertEquals(new TemporalResult.Aggregate(2, BigInteger.valueOf(18)),
                        published.query(new TemporalQuery.Sum(List.of(ALICE, BOB), 0)));
            }
            assertEquals(absent(), genesis.query(new TemporalQuery.Get(ALICE, 0)));
            assertEquals(before, genesis.position());
        }
    }

    @Test
    void aPreparedCommitCannotOverwriteACommitPublishedSinceItsPreparation() {
        try (var engine = new InMemoryEngine()) {
            var first = engine.prepare(put("first", 1));
            var competing = engine.prepare(put("competing", 99));
            engine.publish(first);
            assertThrows(IllegalStateException.class, () -> engine.publish(competing));
            assertThrows(IllegalStateException.class, () -> engine.publish(first));
            assertEquals(first.result().position(), engine.head());
            assertEquals(Optional.empty(), engine.lookupRequest(new RequestId("competing")));
            try (var view = engine.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(new TemporalResult.Point(OptionalLong.of(1)), view.query(new TemporalQuery.Get(ALICE, 0)));
            }
            var fresh = engine.prepare(put("competing", 99));
            assertEquals(2, engine.publish(fresh).position().sequence());
        }
    }

    @Test
    void evenAnEngineWithTheSameLineageCannotPublishAnotherOwnersPreparation() {
        var lineage = new DatabaseId("same-lineage");
        try (var owner = new InMemoryEngine(lineage); var other = new InMemoryEngine(lineage)) {
            var prepared = owner.prepare(put("first", 7));
            assertThrows(IllegalStateException.class, () -> other.publish(prepared));
            assertEquals(0, other.head().sequence());
            assertEquals(Optional.empty(), other.lookupRequest(new RequestId("first")));
            assertEquals(0, owner.head().sequence());
            assertSame(prepared.result(), owner.publish(prepared));
            assertEquals(1, owner.head().sequence());
        }
    }

    @Test
    void replayPreparationRetainsOriginalIdentityWithoutRevertingNewerValuesOrReservingConflicts() {
        try (var engine = new InMemoryEngine()) {
            var originalRequest = put("original", 7);
            var original = engine.transact(originalRequest);
            var newer = engine.transact(put("newer", 11));
            var prepared = engine.prepare(originalRequest);
            assertTrue(prepared.result().replayed());
            assertEquals(original.position(), prepared.result().position());
            assertEquals(newer.position(), engine.head());
            assertSame(prepared.result(), engine.publish(prepared));
            assertEquals(newer.position(), engine.head());
            assertEquals(Optional.of(original.position()), engine.lookupRequest(originalRequest.requestId()));
            var conflict = assertThrows(EngineException.class, () -> engine.prepare(put("original", 99)));
            assertEquals(EngineException.Code.REQUEST_ID_REUSED, conflict.code());
            assertEquals(newer.position(), engine.head());
            try (var view = engine.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(new TemporalResult.Point(OptionalLong.of(11)), view.query(new TemporalQuery.Get(ALICE, 0)));
            }
        }
    }

    @Test
    void closeRejectsNewPreparationAndPublicationOfAnAlreadyPreparedCommit() {
        var engine = new InMemoryEngine();
        var prepared = engine.prepare(put("pending", 7));
        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.prepare(put("later", 11)));
        assertThrows(IllegalStateException.class, () -> engine.publish(prepared));
        engine.close();
    }

    private static Transaction put(String id, long value) {
        return new Transaction(new RequestId(id), List.of(new WriteOperation.Put(ALICE, ALL_TIME, value)));
    }

    private static TemporalResult.Point absent() {
        return new TemporalResult.Point(OptionalLong.empty());
    }
}
