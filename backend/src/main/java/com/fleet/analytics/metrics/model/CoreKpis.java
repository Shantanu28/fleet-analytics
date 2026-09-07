package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * The five KPI cards, in the order 00-research.md 7 fixes, each with exactly the one comparison
 * AC-01.7 assigns it.
 *
 * <p>Both period populations travel with the results. They are the exact integer evidence the
 * response contract puts beside each card — a sample size, a cent total — so the assembler can
 * state what a number was computed from without recomputing anything or re-querying.
 */
public record CoreKpis(
        MetricResult mergedPrs,
        MetricResult terminalMergeRate,
        MetricResult costPerMergedPr,
        MetricResult taskCompletionRate,
        SeatsResult seats,
        PeriodPopulation current,
        PeriodPopulation previous) {

    public CoreKpis {
        Objects.requireNonNull(mergedPrs, "mergedPrs");
        Objects.requireNonNull(terminalMergeRate, "terminalMergeRate");
        Objects.requireNonNull(costPerMergedPr, "costPerMergedPr");
        Objects.requireNonNull(taskCompletionRate, "taskCompletionRate");
        Objects.requireNonNull(seats, "seats");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(previous, "previous");
    }
}
