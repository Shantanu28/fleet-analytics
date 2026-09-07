package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * The five windows the contract evaluates independently, each carrying its own completeness.
 * Evaluating them apart is the point: a complete current window beside an incomplete previous one
 * yields a visible value with a suppressed comparison, not a suppressed value (A.5).
 *
 * <p>{@code budgetMonthToDate} is null when a repository filter makes budget evaluation
 * unavailable for the scope (contract 5.3, 6.1). The month itself is never redefined by a filter —
 * it is simply not evaluated, and the response omits the window.
 */
public record CoverageWindows(
        WindowCoverage current,
        WindowCoverage previousPeriod,
        WindowCoverage failureBaseline28d,
        WindowCoverage budgetMonthToDate,
        WindowCoverage funnelObservation) {

    public CoverageWindows {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(previousPeriod, "previousPeriod");
        Objects.requireNonNull(failureBaseline28d, "failureBaseline28d");
        Objects.requireNonNull(funnelObservation, "funnelObservation");
    }

    public boolean budgetEvaluable() {
        return budgetMonthToDate != null;
    }
}
