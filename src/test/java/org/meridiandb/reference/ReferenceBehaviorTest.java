// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.temporal.*;

class ReferenceBehaviorTest {
    private static final DatabaseId DATABASE = new DatabaseId("database");
    private static final EntityId ENTITY = new EntityId("entity");

    @Test
    void rejectsForeignAndFutureViewsAndKeepsGenesisPinned() {
        var model = new ReferenceModel(DATABASE);
        var genesis = model.openView(ReadCutoff.Head.INSTANCE);
        model.transact(request("r", new ValidTimeInterval(null, null), 1));
        assertEquals(new TemporalResult.Point(OptionalLong.empty()), model.query(genesis, new TemporalQuery.Get(ENTITY, 0)));
        var foreign = new CommitPosition(new DatabaseId("other"), 1);
        assertCode("INVALID_CUTOFF", () -> model.openView(new ReadCutoff.At(foreign)));
        assertCode("INVALID_CUTOFF", () -> model.query(foreign, new TemporalQuery.Get(ENTITY, 0)));
        assertCode("INVALID_CUTOFF", () -> model.openView(new ReadCutoff.At(new CommitPosition(DATABASE, Long.MAX_VALUE))));
    }

    @Test
    void retryIdentityRetainsFiniteVersusUnboundedEndpointsAndOperationOrder() {
        var model = new ReferenceModel(DATABASE);
        var first = model.transact(request("r", new ValidTimeInterval(null, null), 1));
        assertCode("REQUEST_ID_REUSED", () -> model.transact(request("r", new ValidTimeInterval(Long.MIN_VALUE, null), 1)));
        var put1 = new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), 1);
        var put2 = new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), 2);
        model.transact(new Transaction(new RequestId("ordered"), List.of(put1, put2)));
        assertCode("REQUEST_ID_REUSED", () -> model.transact(new Transaction(new RequestId("ordered"), List.of(put2, put1))));
        assertEquals(2, model.head().sequence());
        assertEquals(first.position(), model.lookupRequest(new RequestId("r")).orElseThrow());
        assertEquals(new TemporalResult.Point(OptionalLong.of(2)), model.query(model.head(), new TemporalQuery.Get(ENTITY, 0)));
    }

    @Test
    void jsonObjectOrderDoesNotBecomePayloadIdentity() {
        var first = ContractFixtures.JSON.readTree("""
                {"requestId":"r","operations":[{"op":"put","entity":"entity","from":null,"to":null,"value":7}]}
                """);
        var reordered = ContractFixtures.JSON.readTree("""
                {"operations":[{"value":7,"to":null,"from":null,"entity":"entity","op":"put"}],"requestId":"r"}
                """);
        var model = new ReferenceModel(DATABASE);
        var committed = model.transact(ContractFixtures.transaction(first));
        var replay = model.transact(ContractFixtures.transaction(reordered));
        assertTrue(replay.replayed());
        assertEquals(committed.position(), replay.position());
        assertEquals(1, model.head().sequence());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1.0", "true", "null", "9223372036854775808", "-9223372036854775809"})
    void fixtureValuesAreNeverCoercedRoundedOrTruncated(String token) {
        var raw = ContractFixtures.JSON.readTree("""
                {"requestId":"r","operations":[{"op":"put","entity":"entity","from":null,"to":null,"value":$VALUE}]}
                """.replace("$VALUE", token));
        assertCode("INVALID_REQUEST", () -> ContractFixtures.transaction(raw));
    }

    @Test
    void aMissingEndpointIsNotImplicitlyUnbounded() {
        var raw = ContractFixtures.JSON.readTree("""
                {"requestId":"r","operations":[{"op":"put","entity":"entity","to":null,"value":1}]}
                """);
        assertCode("INVALID_REQUEST", () -> ContractFixtures.transaction(raw));
    }

    private static Transaction request(String id, ValidTimeInterval interval, long value) {
        return new Transaction(new RequestId(id), List.of(new WriteOperation.Put(ENTITY, interval, value)));
    }

    private static void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(ReferenceModel.Rejected.class, action::run).code());
    }
}
