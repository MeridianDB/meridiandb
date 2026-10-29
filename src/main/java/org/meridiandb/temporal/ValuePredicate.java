// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.temporal;

import java.util.Objects;

/** Experimental exact signed-value comparison, applied only after temporal presence is resolved. */
public record ValuePredicate(Operator operator, long operand) {
    public enum Operator { EQ, NE, LT, LE, GT, GE }

    public ValuePredicate {
        Objects.requireNonNull(operator, "operator");
    }

    public boolean matches(long value) {
        return switch (operator) {
            case EQ -> value == operand;
            case NE -> value != operand;
            case LT -> value < operand;
            case LE -> value <= operand;
            case GT -> value > operand;
            case GE -> value >= operand;
        };
    }
}
