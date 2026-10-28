// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ValidTimeIntervalTest {
    private static final long FEBRUARY_1 = 1_769_904_000_000_000L;
    private static final long MARCH_1 = 1_772_323_200_000_000L;

    @ParameterizedTest(name = "{0}")
    @MethodSource("membershipCases")
    void answersContractBoundaries(String description, Long from, Long to,
                                   long instant, boolean expected) {
        var interval = new ValidTimeInterval(from, to);
        assertEquals(expected, interval.contains(instant), description);
    }

    static Stream<Arguments> membershipCases() {
        // Salary boundary cases and finite-limit cases from the temporal contract.
        return Stream.of(
                Arguments.of("before correction", FEBRUARY_1, MARCH_1, FEBRUARY_1 - 1, false),
                Arguments.of("correction start included", FEBRUARY_1, MARCH_1, FEBRUARY_1, true),
                Arguments.of("last correction microsecond", FEBRUARY_1, MARCH_1, MARCH_1 - 1, true),
                Arguments.of("correction end excluded", FEBRUARY_1, MARCH_1, MARCH_1, false),
                Arguments.of("unbounded includes minimum", null, null, Long.MIN_VALUE, true),
                Arguments.of("unbounded includes maximum", null, null, Long.MAX_VALUE, true),
                Arguments.of("minimum is not infinity", Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, true),
                Arguments.of("finite maximum end excluded", Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, false),
                Arguments.of("final finite tick included", Long.MAX_VALUE, null, Long.MAX_VALUE, true),
                Arguments.of("before final finite tick", Long.MAX_VALUE, null, Long.MAX_VALUE - 1, false),
                Arguments.of("first finite tick included", null, Long.MIN_VALUE + 1, Long.MIN_VALUE, true),
                Arguments.of("first finite tick end excluded", null, Long.MIN_VALUE + 1, Long.MIN_VALUE + 1, false));
    }

    @ParameterizedTest(name = "reject [{0}, {1})")
    @MethodSource("invalidIntervals")
    void rejectsEmptyAndReversedIntervals(Long from, Long to) {
        assertThrows(IllegalArgumentException.class, () -> new ValidTimeInterval(from, to));
    }

    static Stream<Arguments> invalidIntervals() {
        return Stream.of(
                Arguments.of(0L, 0L),
                Arguments.of(2L, 1L),
                Arguments.of(null, Long.MIN_VALUE),
                Arguments.of(Long.MIN_VALUE, Long.MIN_VALUE),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE),
                Arguments.of(Long.MAX_VALUE, Long.MIN_VALUE));
    }
}
