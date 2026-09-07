package com.fleet.analytics.metrics.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One team or repository row of the comparison table. Each cell states its own value and its own
 * comparison against the organisation benchmark: gates apply per metric, never to a whole row, so
 * one column can show a comparison while its neighbour explains why it cannot (contract 5.4).
 *
 * <p>A row whose every metric is undefined still appears (AC-05.4). {@code codeChangeSpend} is here
 * so "no merged PRs in this period" can keep spend on screen rather than reading as no activity —
 * the row's own spend, not a share of the organisation trend, which is a different population.
 */
public record ComparisonRow(
        UUID scopeId,
        String scopeName,
        long terminalTaskCount,
        MetricResult taskCompletionRate,
        MetricResult terminalMergeRate,
        MetricResult costPerMergedPr,
        MetricResult codeChangeSpend) {

    public ComparisonRow {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(scopeName, "scopeName");
        Objects.requireNonNull(taskCompletionRate, "taskCompletionRate");
        Objects.requireNonNull(terminalMergeRate, "terminalMergeRate");
        Objects.requireNonNull(costPerMergedPr, "costPerMergedPr");
        Objects.requireNonNull(codeChangeSpend, "codeChangeSpend");
        if (terminalTaskCount < 0) {
            throw new IllegalArgumentException("terminalTaskCount cannot be negative");
        }
    }
}
