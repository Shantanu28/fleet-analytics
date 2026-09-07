package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * The populations one comparison-table scope needs — a row, or the benchmark. Seats are absent by
 * design: no team or repository seat allocation exists (contract 5.3), so the table has no seat
 * column to fabricate one for.
 */
public record ScopePopulation(TaskCounts tasks, PrCounts pullRequests, SpendTotals spend) {

    /**
     * A scope with no matching records. Inside a covered window that is genuine zero activity, which
     * is why the row still appears rather than being dropped (AC-05.4).
     */
    public static final ScopePopulation NONE =
            new ScopePopulation(TaskCounts.NONE, PrCounts.NONE, SpendTotals.NONE);

    public ScopePopulation {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(pullRequests, "pullRequests");
        Objects.requireNonNull(spend, "spend");
    }
}
