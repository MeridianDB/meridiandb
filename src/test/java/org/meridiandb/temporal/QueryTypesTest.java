// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class QueryTypesTest {
    @ParameterizedTest
    @EnumSource(ValuePredicate.Operator.class)
    void signedComparisonsHaveExactBoundaryTruthTables(ValuePredicate.Operator operator) {
        var values = new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE};
        String wanted = switch (operator) {
            case EQ -> "00100";
            case NE -> "11011";
            case LT -> "11000";
            case LE -> "11100";
            case GT -> "00011";
            case GE -> "00111";
        };
        var predicate = new ValuePredicate(operator, 0);
        for (int i = 0; i < values.length; i++) {
            assertEquals(wanted.charAt(i) == '1', predicate.matches(values[i]), operator + " at " + values[i]);
        }
        boolean minVsMax = switch (operator) {
            case EQ, GT, GE -> false;
            case NE, LT, LE -> true;
        };
        boolean maxVsMin = switch (operator) {
            case EQ, LT, LE -> false;
            case NE, GT, GE -> true;
        };
        assertEquals(minVsMax, new ValuePredicate(operator, Long.MAX_VALUE).matches(Long.MIN_VALUE));
        assertEquals(maxVsMin, new ValuePredicate(operator, Long.MIN_VALUE).matches(Long.MAX_VALUE));
        assertEquals(operator == ValuePredicate.Operator.EQ || operator == ValuePredicate.Operator.LE
                || operator == ValuePredicate.Operator.GE,
                new ValuePredicate(operator, Long.MAX_VALUE).matches(Long.MAX_VALUE));
    }

    @Test
    void typedQueriesValidateAndOwnTheirListsWithoutTreatingAbsenceAsAValue() {
        var entity = new EntityId("a");
        var predicate = new ValuePredicate(ValuePredicate.Operator.NE, 0);
        var input = new ArrayList<>(List.of(entity));
        var select = new TemporalQuery.Select(input, Long.MIN_VALUE, predicate);
        input.clear();
        assertEquals(List.of(entity), select.entities());
        assertThrows(UnsupportedOperationException.class, () -> select.entities().clear());
        assertThrows(IllegalArgumentException.class, () -> new TemporalQuery.Select(List.of(entity, entity), 0, predicate));
        assertThrows(NullPointerException.class, () -> new TemporalQuery.Select(null, 0, predicate));
        assertThrows(NullPointerException.class, () -> new TemporalQuery.Select(Arrays.asList(entity, null), 0, predicate));
        assertThrows(NullPointerException.class, () -> new TemporalQuery.Select(List.of(), 0, null));
        assertThrows(NullPointerException.class, () -> new TemporalQuery.FilterScan(null, new ValidTimeInterval(null, null), predicate));
        assertThrows(NullPointerException.class, () -> new TemporalQuery.FilterScan(entity, null, predicate));
        assertThrows(NullPointerException.class, () -> new TemporalQuery.FilterScan(entity, new ValidTimeInterval(null, null), null));
        assertThrows(NullPointerException.class, () -> new ValuePredicate(null, 0));
    }

    @Test
    void rowsAreImmutableOwnedValuesWithExplicitEntityIdentity() {
        var row = new EntityValue(new EntityId("a"), Long.MIN_VALUE);
        var source = new ArrayList<>(List.of(row));
        var result = new TemporalResult.Rows(source);
        source.clear();
        assertEquals(List.of(row), result.values());
        assertThrows(UnsupportedOperationException.class, () -> result.values().clear());
        assertThrows(NullPointerException.class, () -> new TemporalResult.Rows(Arrays.asList(row, null)));
        assertThrows(NullPointerException.class, () -> new EntityValue(null, 0));
    }
}
