package com.fleet.analytics.metrics;

import com.fleet.analytics.data.NamedEntity;
import com.fleet.analytics.metrics.model.BenchmarkResult;
import com.fleet.analytics.metrics.model.BenchmarkScope;
import com.fleet.analytics.metrics.model.ComparisonKind;
import com.fleet.analytics.metrics.model.ComparisonResult;
import com.fleet.analytics.metrics.model.ComparisonRow;
import com.fleet.analytics.metrics.model.ComparisonState;
import com.fleet.analytics.metrics.model.ComparisonTableResult;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.Explanation;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.MetricGate;
import com.fleet.analytics.metrics.model.MetricResult;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.ScopePopulation;
import com.fleet.analytics.metrics.model.WindowCoverage;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The comparison table: three compared columns plus the row's own spend, each row measured against a
 * pooled organisational benchmark (contract 5.4).
 *
 * <p><b>Pooling, not averaging.</b> The benchmark divides summed counts, never the mean of the rows'
 * percentages. That is mandatory rather than tidy: a row whose own rate is undefined has no
 * percentage to average, so an average would quietly drop it and describe a different population
 * than it claims. Pooling also makes the benchmark identical across groupings — the team and
 * repository views partition the same organisation two ways and must agree.
 *
 * <p><b>Gates apply per column.</b> One cell may compare while its neighbour explains why it cannot
 * (contract 5.4). A row is never suppressed as a whole, and a row whose every metric is undefined
 * still appears (AC-05.4).
 */
@Component
public class ComparisonTableBuilder {

    /**
     * @param scopes every scope to render as a row, already narrowed by any explicit filter on the
     *     grouping's own dimension. A scope with no activity still gets a row.
     * @param byScope populations per scope; an absent entry is zero activity, not missing data.
     * @param benchmark the pooled benchmark population — the same window and repository filter, with
     *     the team predicate dropped (contract 5.4).
     */
    public ComparisonTableResult build(Grouping grouping, List<NamedEntity> scopes,
            Map<UUID, ScopePopulation> byScope, ScopePopulation benchmark,
            WindowCoverage coverage, ScopeFilters filters) {
        List<ComparisonRow> rows = scopes.stream()
                .map(scope -> row(scope, byScope.getOrDefault(scope.id(), ScopePopulation.NONE),
                        benchmark, coverage))
                .sorted(rowOrder())
                .toList();
        return new ComparisonTableResult(grouping, rows, benchmark(benchmark, coverage, filters));
    }

    /**
     * AC-05.5: eligible terminal task count descending, tie-broken by display name then id. The
     * final id tie-break is what makes the order total — two scopes with the same count and the same
     * name would otherwise sort arbitrarily and shuffle between reloads.
     */
    private Comparator<ComparisonRow> rowOrder() {
        return Comparator.comparingLong(ComparisonRow::terminalTaskCount).reversed()
                .thenComparing(ComparisonRow::scopeName)
                .thenComparing(ComparisonRow::scopeId);
    }

    private ComparisonRow row(NamedEntity scope, ScopePopulation population,
            ScopePopulation benchmark, WindowCoverage coverage) {
        return new ComparisonRow(scope.id(), scope.name(), population.tasks().terminal(),
                completionRate(population, benchmark, coverage),
                mergeRate(population, benchmark, coverage),
                unitCost(population, benchmark, coverage),
                spend(population, coverage));
    }

    // --- Compared columns -------------------------------------------------------------------------

    private MetricResult completionRate(
            ScopePopulation row, ScopePopulation benchmark, WindowCoverage coverage) {
        Set<LogicalSource> required = LogicalSource.TASK_OUTCOMES;
        if (!coverage.supports(required)) {
            return unavailable(coverage, required, ComparisonKind.PERCENTAGE_POINTS);
        }
        MetricResult value = MetricCalculator.rate(row.tasks().completedExact(),
                row.tasks().terminalExact(), Explanations.noTerminalTasks());
        return value.withComparison(rateAgainstBenchmark(
                row.tasks().completedExact(), row.tasks().terminalExact(),
                benchmark.tasks().completedExact(), benchmark.tasks().terminalExact(),
                MetricGate.TERMINAL_TASKS, row.tasks().terminal(), benchmark.tasks().terminal(),
                Explanations.rowHasNoTerminalTasks()));
    }

    private MetricResult mergeRate(
            ScopePopulation row, ScopePopulation benchmark, WindowCoverage coverage) {
        Set<LogicalSource> required = LogicalSource.PR_OUTCOMES;
        if (!coverage.supports(required)) {
            return unavailable(coverage, required, ComparisonKind.PERCENTAGE_POINTS);
        }
        MetricResult value = MetricCalculator.rate(row.pullRequests().mergedExact(),
                row.pullRequests().terminalExact(), Explanations.noTerminalPrs());
        return value.withComparison(rateAgainstBenchmark(
                row.pullRequests().mergedExact(), row.pullRequests().terminalExact(),
                benchmark.pullRequests().mergedExact(), benchmark.pullRequests().terminalExact(),
                MetricGate.TERMINAL_PRS, row.pullRequests().terminal(),
                benchmark.pullRequests().terminal(), Explanations.rowHasNoTerminalPrs()));
    }

    private MetricResult unitCost(
            ScopePopulation row, ScopePopulation benchmark, WindowCoverage coverage) {
        Set<LogicalSource> required = LogicalSource.UNIT_COST;
        if (!coverage.supports(required)) {
            return unavailable(coverage, required, ComparisonKind.ABSOLUTE_USD);
        }
        BigInteger rowMerged = row.pullRequests().mergedExact();
        BigInteger benchmarkMerged = benchmark.pullRequests().mergedExact();
        MetricResult value = MetricCalculator.unitCost(
                row.spend().codeChangeCents(), rowMerged, Explanations.noMergedPrs());

        EnumSet<ComparisonState> applicable = EnumSet.noneOf(ComparisonState.class);
        if (rowMerged.signum() == 0 || benchmarkMerged.signum() == 0) {
            applicable.add(ComparisonState.NO_DENOMINATOR);
        }
        if (!MetricGate.MERGED_PRS.isMetBy(row.pullRequests().merged())
                || !MetricGate.MERGED_PRS.isMetBy(benchmark.pullRequests().merged())) {
            applicable.add(ComparisonState.INSUFFICIENT_SAMPLE);
        }

        ComparisonState state = MetricCalculator.firstApplicable(applicable);
        if (state == ComparisonState.OK) {
            return value.withComparison(ComparisonResult.computed(ComparisonKind.ABSOLUTE_USD,
                    DisplayValue.of(MetricCalculator.unitCostChangeUsd(row.spend().codeChangeCents(),
                            rowMerged, benchmark.spend().codeChangeCents(), benchmarkMerged),
                            DisplayUnit.USD)));
        }
        Explanation explanation = state == ComparisonState.NO_DENOMINATOR
                ? Explanations.rowHasNoMergedPrs()
                : gateExplanation(MetricGate.MERGED_PRS, row.pullRequests().merged(),
                        benchmark.pullRequests().merged());
        return value.withComparison(
                ComparisonResult.suppressed(ComparisonKind.ABSOLUTE_USD, state, explanation));
    }

    /**
     * The row's own eligible code-change spend, uncompared. It is here so "no merged PRs in this
     * period" can sit beside a real spend figure instead of reading as no activity (AC-01.5) —
     * and it must be the row's own population, not a share of the organisation spend trend, which
     * counts every task type.
     */
    private MetricResult spend(ScopePopulation row, WindowCoverage coverage) {
        Set<LogicalSource> required = LogicalSource.SPEND;
        if (!coverage.supports(required)) {
            return MetricResult.missingData(
                    Explanations.sourcesNotCovered(coverage.missingFrom(required)));
        }
        return MetricCalculator.spendTotal(row.spend().codeChangeCents());
    }

    // --- Benchmark --------------------------------------------------------------------------------

    /** The benchmark is what rows are measured against, so it carries no comparison of its own. */
    private BenchmarkResult benchmark(
            ScopePopulation benchmark, WindowCoverage coverage, ScopeFilters filters) {
        return new BenchmarkResult(
                BenchmarkScope.forSelection(filters),
                coverage.supports(LogicalSource.TASK_OUTCOMES)
                        ? MetricCalculator.rate(benchmark.tasks().completedExact(),
                                benchmark.tasks().terminalExact(), Explanations.noTerminalTasks())
                        : missing(coverage, LogicalSource.TASK_OUTCOMES),
                coverage.supports(LogicalSource.PR_OUTCOMES)
                        ? MetricCalculator.rate(benchmark.pullRequests().mergedExact(),
                                benchmark.pullRequests().terminalExact(), Explanations.noTerminalPrs())
                        : missing(coverage, LogicalSource.PR_OUTCOMES),
                coverage.supports(LogicalSource.UNIT_COST)
                        ? MetricCalculator.unitCost(benchmark.spend().codeChangeCents(),
                                benchmark.pullRequests().mergedExact(), Explanations.noMergedPrs())
                        : missing(coverage, LogicalSource.UNIT_COST),
                coverage.supports(LogicalSource.SPEND)
                        ? MetricCalculator.spendTotal(benchmark.spend().codeChangeCents())
                        : missing(coverage, LogicalSource.SPEND));
    }

    // --- Shared -----------------------------------------------------------------------------------

    /** Contract 5.4: a row-versus-benchmark comparison needs <em>both</em> populations to qualify. */
    private ComparisonResult rateAgainstBenchmark(BigInteger rowNumerator, BigInteger rowDenominator,
            BigInteger benchmarkNumerator, BigInteger benchmarkDenominator, MetricGate gate,
            long rowPopulation, long benchmarkPopulation, Explanation whenUndefined) {
        EnumSet<ComparisonState> applicable = EnumSet.noneOf(ComparisonState.class);
        if (rowDenominator.signum() == 0 || benchmarkDenominator.signum() == 0) {
            applicable.add(ComparisonState.NO_DENOMINATOR);
        }
        if (!gate.isMetBy(rowPopulation) || !gate.isMetBy(benchmarkPopulation)) {
            applicable.add(ComparisonState.INSUFFICIENT_SAMPLE);
        }

        ComparisonState state = MetricCalculator.firstApplicable(applicable);
        if (state == ComparisonState.OK) {
            return ComparisonResult.computed(ComparisonKind.PERCENTAGE_POINTS,
                    DisplayValue.of(MetricCalculator.percentagePointChange(rowNumerator,
                            rowDenominator, benchmarkNumerator, benchmarkDenominator),
                            DisplayUnit.PERCENTAGE_POINTS));
        }
        Explanation explanation = state == ComparisonState.NO_DENOMINATOR
                ? whenUndefined
                : gateExplanation(gate, rowPopulation, benchmarkPopulation);
        return ComparisonResult.suppressed(ComparisonKind.PERCENTAGE_POINTS, state, explanation);
    }

    /** Names whichever population actually fell short, so the reader is not sent to grow the wrong one. */
    private Explanation gateExplanation(MetricGate gate, long rowPopulation, long benchmarkPopulation) {
        return gate.isMetBy(rowPopulation)
                ? Explanations.benchmarkPopulationGate(gate, benchmarkPopulation)
                : Explanations.benchmarkGate(gate, rowPopulation);
    }

    private MetricResult unavailable(
            WindowCoverage coverage, Set<LogicalSource> required, ComparisonKind kind) {
        Explanation explanation = Explanations.sourcesNotCovered(coverage.missingFrom(required));
        return MetricResult.missingData(explanation).withComparison(
                ComparisonResult.suppressed(kind, ComparisonState.MISSING_DATA, explanation));
    }

    private MetricResult missing(WindowCoverage coverage, Set<LogicalSource> required) {
        return MetricResult.missingData(
                Explanations.sourcesNotCovered(coverage.missingFrom(required)));
    }
}
