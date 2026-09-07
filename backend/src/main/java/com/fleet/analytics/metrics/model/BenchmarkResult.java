package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * The organisation benchmark row: pooled counts and sums over the benchmark population, never a
 * mean of the member rows' percentages (contract 1.1). Pooling is mandatory rather than preferred —
 * a row whose own rate is undefined has no percentage to average, so an average would silently drop
 * it and report a different population than it claims.
 *
 * <p>The benchmark carries no comparison of its own: it is the thing rows are compared against.
 */
public record BenchmarkResult(
        BenchmarkScope scope,
        MetricResult taskCompletionRate,
        MetricResult terminalMergeRate,
        MetricResult costPerMergedPr,
        MetricResult codeChangeSpend) {

    public BenchmarkResult {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(taskCompletionRate, "taskCompletionRate");
        Objects.requireNonNull(terminalMergeRate, "terminalMergeRate");
        Objects.requireNonNull(costPerMergedPr, "costPerMergedPr");
        Objects.requireNonNull(codeChangeSpend, "codeChangeSpend");
    }
}
