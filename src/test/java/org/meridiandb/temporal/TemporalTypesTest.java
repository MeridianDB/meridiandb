// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class TemporalTypesTest {
    private static final EntityId ENTITY = new EntityId("entity");
    private static final DatabaseId DATABASE = new DatabaseId("database");
    private static final WriteOperation PUT = new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), 0);

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingIdentities(String value) {
        assertThrows(IllegalArgumentException.class, () -> new EntityId(value));
        assertThrows(IllegalArgumentException.class, () -> new RequestId(value));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseId(value));
    }

    @Test
    void preservesExactIdentityWithoutCaseWhitespaceOrUnicodeNormalization() {
        assertNotEquals(new EntityId("a"), new EntityId("A"));
        assertNotEquals(new EntityId("a"), new EntityId("a "));
        assertNotEquals(new EntityId("é"), new EntityId("e" + (char) 0x0301));
        assertNotEquals(new RequestId("r"), new RequestId("R"));
        assertNotEquals(new DatabaseId("d"), new DatabaseId("D"));
    }

    @Test
    void validatesCommitAndAcknowledgementPositions() {
        assertDoesNotThrow(() -> new CommitPosition(DATABASE, Long.MAX_VALUE));
        assertDoesNotThrow(() -> new CommitPosition(DATABASE, 0));
        assertThrows(IllegalArgumentException.class, () -> new CommitPosition(DATABASE, -1));
        assertThrows(NullPointerException.class, () -> new CommitPosition(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new CommitResult(new CommitPosition(DATABASE, 0), false));
        assertThrows(NullPointerException.class, () -> new ReadCutoff.At(null));
    }

    @Test
    void transactionsOwnAnImmutableOrderedOperationList() {
        var source = new ArrayList<>(List.of(PUT));
        var transaction = new Transaction(new RequestId("r"), source);
        source.clear();
        assertEquals(List.of(PUT), transaction.operations());
        assertThrows(UnsupportedOperationException.class, () -> transaction.operations().clear());
        assertThrows(IllegalArgumentException.class, () -> new Transaction(new RequestId("r"), List.of()));
        assertThrows(NullPointerException.class, () -> new Transaction(null, List.of(PUT)));
        assertThrows(NullPointerException.class, () -> new Transaction(new RequestId("r"), null));
        assertThrows(NullPointerException.class, () -> new Transaction(new RequestId("r"), Arrays.asList((WriteOperation) null)));
    }

    @Test
    void aggregatesOwnDistinctImmutableIdentitiesAndAllowAnEmptySet() {
        var source = new ArrayList<>(List.of(ENTITY));
        var query = new TemporalQuery.Sum(source, 0);
        source.clear();
        assertEquals(List.of(ENTITY), query.entities());
        assertThrows(UnsupportedOperationException.class, () -> query.entities().clear());
        assertDoesNotThrow(() -> new TemporalQuery.Sum(List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new TemporalQuery.Sum(List.of(ENTITY, ENTITY), 0));
        assertThrows(NullPointerException.class, () -> new TemporalQuery.Sum(Arrays.asList((EntityId) null), 0));
    }

    @Test
    void rejectsMissingOperationAndQueryObjects() {
        var interval = new ValidTimeInterval(null, null);
        assertThrows(NullPointerException.class, () -> new WriteOperation.Put(null, interval, 1));
        assertThrows(NullPointerException.class, () -> new WriteOperation.Put(ENTITY, null, 1));
        assertThrows(NullPointerException.class, () -> new WriteOperation.Retract(null, interval));
        assertThrows(NullPointerException.class, () -> new WriteOperation.Retract(ENTITY, null));
        assertThrows(NullPointerException.class, () -> new TemporalQuery.Get(null, 0));
        assertThrows(NullPointerException.class, () -> new TemporalQuery.Scan(ENTITY, null));
        assertThrows(NullPointerException.class, () -> new TemporalSegment(null, 1));
    }

    @Test
    void resultsPreserveAbsenceAndOwnTheirSegmentList() {
        assertNotEquals(new TemporalResult.Point(OptionalLong.empty()), new TemporalResult.Point(OptionalLong.of(0)));
        var source = new ArrayList<>(List.of(new TemporalSegment(new ValidTimeInterval(null, null), 0)));
        var result = new TemporalResult.Segments(source);
        source.clear();
        assertEquals(1, result.values().size());
        assertThrows(UnsupportedOperationException.class, () -> result.values().clear());
        assertThrows(NullPointerException.class, () -> new TemporalResult.Point(null));
        assertThrows(IllegalArgumentException.class, () -> new TemporalResult.Aggregate(-1, BigInteger.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TemporalResult.Aggregate(0, BigInteger.ONE));
    }
}
