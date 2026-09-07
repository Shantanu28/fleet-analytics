package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * Every population one window needs for the KPI row, selected together so a calculator never has to
 * reach back to the database. Each component was aggregated by its own query at its own grain —
 * bundling them here is presentation, not a join (04 4).
 */
public record PeriodPopulation(
        TaskCounts tasks, PrCounts pullRequests, SpendTotals spend, SeatCounts seats) {

    public PeriodPopulation {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(pullRequests, "pullRequests");
        Objects.requireNonNull(spend, "spend");
        Objects.requireNonNull(seats, "seats");
    }
}
