package com.fleet.analytics.metrics.model;

import java.util.List;
import java.util.Objects;

/**
 * The comparison table for one grouping. Row order is deterministic across reloads (AC-05.5):
 * eligible terminal task count descending, then display name, then id — so a link to a specific
 * grouping always opens the same table.
 */
public record ComparisonTableResult(
        Grouping grouping, List<ComparisonRow> rows, BenchmarkResult benchmark) {

    public ComparisonTableResult {
        Objects.requireNonNull(grouping, "grouping");
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(benchmark, "benchmark");
        rows = List.copyOf(rows);
    }
}
