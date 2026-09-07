package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * One metric: a value state and, independently, an optional comparison (contract 1.2).
 *
 * <p>The compact constructor enforces the contract's display rule — a defined state carries a
 * display, any other state carries an explanation instead and no display at all. Making that
 * unrepresentable here means no assembler can emit a body the OpenAPI schema would reject, and no
 * caller can mistake an absent value for a zero.
 *
 * <p>{@code roundsToZero} is set only on spend totals, where a positive amount rounding to whole
 * dollars of zero must read as "&lt;$1" (contract 8.6). It is deliberately absent elsewhere: cost
 * per merged PR carries two decimals and has no sub-dollar rule.
 */
public record MetricResult(
        ValueState state,
        DisplayValue display,
        Boolean roundsToZero,
        Explanation explanation,
        ComparisonResult comparison) {

    public MetricResult {
        Objects.requireNonNull(state, "state");
        if (state.isDefined() && display == null) {
            throw new IllegalArgumentException(state + " must carry a display");
        }
        if (!state.isDefined() && display != null) {
            throw new IllegalArgumentException(state + " must not carry a display");
        }
        if (!state.isDefined() && explanation == null) {
            throw new IllegalArgumentException(state + " must explain itself");
        }
    }

    public static MetricResult of(DisplayValue display) {
        return new MetricResult(ValueState.OK, display, null, null, null);
    }

    /** A real 0 over a positive denominator — visually and textually distinct from an absence (AC-01.6). */
    public static MetricResult zeroOutcome(DisplayValue display) {
        return new MetricResult(ValueState.ZERO_OUTCOME, display, null, null, null);
    }

    public static MetricResult noDenominator(Explanation explanation) {
        return new MetricResult(ValueState.NO_DENOMINATOR, null, null, explanation, null);
    }

    public static MetricResult unavailableForScope(Explanation explanation) {
        return new MetricResult(ValueState.UNAVAILABLE_FOR_SCOPE, null, null, explanation, null);
    }

    public static MetricResult missingData(Explanation explanation) {
        return new MetricResult(ValueState.MISSING_DATA, null, null, explanation, null);
    }

    /** Spend totals only: whole dollars plus the sub-dollar indicator contract 8.6 requires. */
    public static MetricResult spend(DisplayValue display, boolean roundsToZero) {
        return new MetricResult(ValueState.OK, display, roundsToZero, null, null);
    }

    public MetricResult withComparison(ComparisonResult comparison) {
        return new MetricResult(state, display, roundsToZero, explanation, comparison);
    }

    public boolean hasValue() {
        return state.isDefined();
    }
}
