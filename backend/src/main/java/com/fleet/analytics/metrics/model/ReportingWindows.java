package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * The five calendar windows a selection implies, before any coverage is consulted. Pure date
 * arithmetic: the budget month is derived from {@code dataThrough}, not from the selected range
 * (contract 6.1), and the funnel observes through {@code dataThrough} rather than the range end
 * (contract 4).
 */
public record ReportingWindows(
        DateWindow current,
        DateWindow previousPeriod,
        DateWindow failureBaseline28d,
        DateWindow budgetMonthToDate,
        DateWindow funnelObservation) {

    public ReportingWindows {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(previousPeriod, "previousPeriod");
        Objects.requireNonNull(failureBaseline28d, "failureBaseline28d");
        Objects.requireNonNull(budgetMonthToDate, "budgetMonthToDate");
        Objects.requireNonNull(funnelObservation, "funnelObservation");
    }
}
