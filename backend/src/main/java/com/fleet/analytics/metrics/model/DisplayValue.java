package com.fleet.analytics.metrics.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A presentation-precision value: the result of exactly one rounding applied directly to the exact
 * integer counts and cent sums behind it (contract 1.1, 8.6).
 *
 * <p>It is a string, not a number, because its scale is part of the answer — {@code "23.00"} and
 * {@code "23"} are different presentations of the same money, and a JSON number would lose that
 * distinction the moment it was parsed. The client formats separators, currency symbols and sign
 * placement; it never divides, re-rounds, sums, or compares this to a threshold.
 */
public record DisplayValue(String value, DisplayUnit unit) {

    public DisplayValue {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(unit, "unit");
    }

    public static DisplayValue of(BigDecimal value, DisplayUnit unit) {
        return new DisplayValue(value.toPlainString(), unit);
    }

    public static DisplayValue count(long value) {
        return new DisplayValue(Long.toString(value), DisplayUnit.COUNT);
    }
}
