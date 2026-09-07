package com.fleet.analytics.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.data.NamedEntity;
import com.fleet.analytics.metrics.model.ComparisonRow;
import com.fleet.analytics.metrics.model.ComparisonState;
import com.fleet.analytics.metrics.model.ComparisonTableResult;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.ScopePopulation;
import com.fleet.analytics.metrics.model.SpendTotals;
import com.fleet.analytics.metrics.model.TaskCounts;
import com.fleet.analytics.metrics.model.ValueState;
import com.fleet.analytics.metrics.model.WindowCoverage;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The table from the metrics contract's worked example (8.4). Every expected figure below is the
 * contract's own, and the two groupings exist here to prove the benchmark is partition-independent.
 */
class ComparisonTableBuilderTest {

    private static final WindowCoverage COVERED =
            WindowCoverage.fullyCovered(DateWindow.ofInclusiveDates(
                    LocalDate.of(2026, 1, 16), LocalDate.of(2026, 1, 31)));

    private static final UUID PLATFORM = UUID.fromString("2a1f0c64-1d3b-4f7a-9c02-5e8b7a10d001");
    private static final UUID PAYMENTS = UUID.fromString("2a1f0c64-1d3b-4f7a-9c02-5e8b7a10d002");
    private static final UUID REPO_API = UUID.fromString("3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e001");
    private static final UUID REPO_WEB = UUID.fromString("3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e002");

    private final ComparisonTableBuilder builder = new ComparisonTableBuilder();

    private static ScopePopulation population(
            long completed, long failed, long merged, long closed, long codeChangeCents) {
        return new ScopePopulation(new TaskCounts(completed, failed), new PrCounts(merged, closed),
                new SpendTotals(BigInteger.valueOf(codeChangeCents),
                        BigInteger.valueOf(codeChangeCents)));
    }

    /** Contract 8.4: the organisation pools to 1/2 completed, 1/1 merged and 2300c. */
    private static ScopePopulation organisationBenchmark() {
        return population(1, 1, 1, 0, 2300);
    }

    private ComparisonTableResult teamView() {
        return builder.build(Grouping.TEAMS,
                List.of(new NamedEntity(PLATFORM, "Platform"), new NamedEntity(PAYMENTS, "Payments")),
                Map.of(
                        PLATFORM, population(0, 0, 1, 0, 0),
                        PAYMENTS, population(1, 1, 0, 0, 2300)),
                organisationBenchmark(), COVERED, ScopeFilters.none());
    }

    private ComparisonTableResult repositoryView() {
        return builder.build(Grouping.REPOSITORIES,
                List.of(new NamedEntity(REPO_API, "repo-api"), new NamedEntity(REPO_WEB, "repo-web")),
                Map.of(
                        REPO_API, population(0, 1, 0, 0, 500),
                        REPO_WEB, population(1, 0, 1, 0, 1800)),
                organisationBenchmark(), COVERED, ScopeFilters.none());
    }

    private static ComparisonRow named(ComparisonTableResult table, String scopeName) {
        return table.rows().stream().filter(row -> row.scopeName().equals(scopeName))
                .findFirst().orElseThrow();
    }

    // --- Ordering (AC-05.5) -----------------------------------------------------------------------

    @Test
    void rowsSortByTerminalTaskCountDescending() {
        assertThat(teamView().rows()).extracting(ComparisonRow::scopeName)
                .containsExactly("Payments", "Platform");
    }

    /**
     * The id tie-break is what makes the order total. Without it two scopes sharing a count and a
     * name would sort arbitrarily, and the table would shuffle between identical reloads.
     */
    @Test
    void equalCountsAndNamesAreBrokenDeterministicallyByIdentifier() {
        UUID lower = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID higher = UUID.fromString("00000000-0000-0000-0000-00000000000b");

        ComparisonTableResult table = builder.build(Grouping.TEAMS,
                List.of(new NamedEntity(higher, "Same"), new NamedEntity(lower, "Same")),
                Map.of(higher, population(1, 1, 0, 0, 0), lower, population(1, 1, 0, 0, 0)),
                organisationBenchmark(), COVERED, ScopeFilters.none());

        assertThat(table.rows()).extracting(ComparisonRow::scopeId).containsExactly(lower, higher);
    }

    // --- The worked team view (contract 8.4) --------------------------------------------------------

    /**
     * Platform's completion is undefined from 0/0 while repo-api's is a real 0.0% from 0/1. Two
     * different states with two different meanings — the distinction AC-01.6 turns on.
     */
    @Test
    void anUndefinedRateAndARealZeroAreDifferentStates() {
        assertThat(named(teamView(), "Platform").taskCompletionRate().state())
                .isEqualTo(ValueState.NO_DENOMINATOR);
        assertThat(named(teamView(), "Platform").taskCompletionRate().display()).isNull();

        ComparisonRow repoApi = named(repositoryView(), "repo-api");
        assertThat(repoApi.taskCompletionRate().state()).isEqualTo(ValueState.ZERO_OUTCOME);
        assertThat(repoApi.taskCompletionRate().display().value()).isEqualTo("0.0");
    }

    @Test
    void theWorkedTeamRowsMatchTheContract() {
        ComparisonRow payments = named(teamView(), "Payments");
        assertThat(payments.terminalTaskCount()).isEqualTo(2);
        assertThat(payments.taskCompletionRate().display().value()).isEqualTo("50.0");
        assertThat(payments.terminalMergeRate().state()).isEqualTo(ValueState.NO_DENOMINATOR);
        assertThat(payments.costPerMergedPr().state()).isEqualTo(ValueState.NO_DENOMINATOR);

        ComparisonRow platform = named(teamView(), "Platform");
        assertThat(platform.terminalTaskCount()).isZero();
        assertThat(platform.terminalMergeRate().display().value()).isEqualTo("100.0");
        assertThat(platform.costPerMergedPr().display().value()).isEqualTo("0.00");
    }

    /**
     * Contract 8.4's third point: Payments spent $23 and merged nothing. The unit cost is
     * unavailable with its own copy, and the spend stays on screen beside it (AC-01.5).
     */
    @Test
    void positiveSpendRemainsVisibleWhenNothingMerged() {
        ComparisonRow payments = named(teamView(), "Payments");

        assertThat(payments.costPerMergedPr().state()).isEqualTo(ValueState.NO_DENOMINATOR);
        assertThat(payments.costPerMergedPr().explanation().text())
                .isEqualTo("No merged PRs in this period, so cost per merged PR is not defined.");
        assertThat(payments.codeChangeSpend().state()).isEqualTo(ValueState.OK);
        assertThat(payments.codeChangeSpend().display().value()).isEqualTo("23");
    }

    /** Platform's $0.00 is the mirror image: its merged PR was produced by spend in an earlier period. */
    @Test
    void aZeroUnitCostKeepsMonetaryFormattingRatherThanBecomingARate() {
        ComparisonRow platform = named(teamView(), "Platform");

        assertThat(platform.costPerMergedPr().state()).isEqualTo(ValueState.OK);
        assertThat(platform.costPerMergedPr().display().value()).isEqualTo("0.00");
        assertThat(platform.codeChangeSpend().display().value()).isEqualTo("0");
        assertThat(platform.codeChangeSpend().roundsToZero()).isFalse();
    }

    // --- Pooling (contract 1.1, 8.4) ----------------------------------------------------------------

    /**
     * The point of pooling. The two groupings partition the same organisation differently, yet both
     * benchmarks are 50.0% from (0+1)/(0+2) and (0+1)/(1+1). A mean of member percentages could not
     * even be taken here — Platform's completion is undefined.
     */
    @Test
    void bothGroupingsProduceTheIdenticalPooledBenchmark() {
        assertThat(teamView().benchmark().taskCompletionRate().display().value()).isEqualTo("50.0");
        assertThat(repositoryView().benchmark().taskCompletionRate().display().value())
                .isEqualTo("50.0");
        assertThat(teamView().benchmark().terminalMergeRate().display().value()).isEqualTo("100.0");
        assertThat(repositoryView().benchmark().terminalMergeRate().display().value())
                .isEqualTo("100.0");
        assertThat(teamView().benchmark().costPerMergedPr().display().value()).isEqualTo("23.00");
        assertThat(repositoryView().benchmark().costPerMergedPr().display().value())
                .isEqualTo("23.00");
        assertThat(teamView().benchmark().codeChangeSpend().display().value()).isEqualTo("23");
    }

    @Test
    void theBenchmarkCarriesNoComparisonOfItsOwn() {
        assertThat(teamView().benchmark().taskCompletionRate().comparison()).isNull();
        assertThat(teamView().benchmark().costPerMergedPr().comparison()).isNull();
    }

    // --- Row-versus-benchmark states (contract 8.4) --------------------------------------------------

    /** An undefined row ratio outranks an unmet gate: there is nothing to compare, sample or not. */
    @Test
    void anUndefinedRowRatioReportsNoDenominatorRatherThanInsufficientSample() {
        ComparisonRow payments = named(teamView(), "Payments");

        assertThat(payments.terminalMergeRate().comparison().state())
                .isEqualTo(ComparisonState.NO_DENOMINATOR);
        assertThat(payments.terminalMergeRate().comparison().explanation().text())
                .isEqualTo("This row has no terminal PRs, so there is no merge rate to compare.");
        assertThat(payments.costPerMergedPr().comparison().explanation().text())
                .isEqualTo("This row has no merged PRs, so there is no unit cost to compare.");
    }

    /** Both ratios defined, gate unmet: the comparison is suppressed and names the row's count. */
    @Test
    void aDefinedRowBelowTheGateKeepsItsValueAndExplainsTheSuppression() {
        ComparisonRow payments = named(teamView(), "Payments");

        assertThat(payments.taskCompletionRate().display().value()).isEqualTo("50.0");
        assertThat(payments.taskCompletionRate().comparison().state())
                .isEqualTo(ComparisonState.INSUFFICIENT_SAMPLE);
        assertThat(payments.taskCompletionRate().comparison().explanation().text()).isEqualTo(
                "Comparison with the organisation benchmark needs 20 completed or failed "
                        + "code-change tasks in this row; it has 2.");
    }

    /**
     * Gates are per column, not per row: the same row can carry one suppressed comparison and one
     * computed one, so a single unmet gate must not silence its neighbours.
     */
    @Test
    void gatesApplyPerColumnRatherThanPerRow() {
        UUID scope = UUID.randomUUID();
        ComparisonTableResult table = builder.build(Grouping.TEAMS,
                List.of(new NamedEntity(scope, "Big")),
                // 40 terminal tasks clears the 20 gate; 2 terminal PRs does not clear 15.
                Map.of(scope, population(30, 10, 1, 1, 5000)),
                population(20, 20, 20, 20, 100000), COVERED, ScopeFilters.none());

        ComparisonRow row = table.rows().getFirst();
        assertThat(row.taskCompletionRate().comparison().state()).isEqualTo(ComparisonState.OK);
        assertThat(row.taskCompletionRate().comparison().display().value()).isEqualTo("25.0");
        assertThat(row.terminalMergeRate().comparison().state())
                .isEqualTo(ComparisonState.INSUFFICIENT_SAMPLE);
    }

    /** When the benchmark is what falls short, the explanation says so rather than blaming the row. */
    @Test
    void aShortBenchmarkIsNamedInsteadOfTheRow() {
        UUID scope = UUID.randomUUID();
        ComparisonTableResult table = builder.build(Grouping.TEAMS,
                List.of(new NamedEntity(scope, "Big")),
                Map.of(scope, population(30, 10, 0, 0, 0)),
                population(3, 2, 0, 0, 0), COVERED, ScopeFilters.none());

        assertThat(table.rows().getFirst().taskCompletionRate().comparison().explanation().text())
                .isEqualTo("Comparison with the organisation benchmark needs 20 completed or failed "
                        + "code-change tasks in the benchmark; it has 5.");
    }

    // --- Benchmark scope metadata (AC-05.2, AC-05.3) --------------------------------------------------

    @Test
    void benchmarkScopeDescribesTheActualSelection() {
        assertThat(teamView().benchmark().scope().repositoryFilterApplied()).isFalse();
        assertThat(teamView().benchmark().scope().mayIncludeUndisplayedTeams()).isFalse();

        ComparisonTableResult filtered = builder.build(Grouping.TEAMS,
                List.of(new NamedEntity(PAYMENTS, "Payments")),
                Map.of(PAYMENTS, population(1, 1, 0, 0, 2300)), organisationBenchmark(), COVERED,
                new ScopeFilters(PAYMENTS, REPO_API));

        // A team filter narrows the rows but not the benchmark, so it may span teams not shown.
        assertThat(filtered.benchmark().scope().mayIncludeUndisplayedTeams()).isTrue();
        assertThat(filtered.benchmark().scope().repositoryFilterApplied()).isTrue();
        assertThat(filtered.benchmark().scope().teamFilterIgnored()).isTrue();
        assertThat(filtered.benchmark().scope().includesSelectedTeam()).isTrue();
    }

    // --- Coverage --------------------------------------------------------------------------------------

    /** A scope with no rows at all is zero activity inside a covered window, and still appears. */
    @Test
    void aScopeWithNoPopulationStillGetsARow() {
        UUID quiet = UUID.randomUUID();
        ComparisonTableResult table = builder.build(Grouping.TEAMS,
                List.of(new NamedEntity(quiet, "Quiet")), Map.of(), organisationBenchmark(),
                COVERED, ScopeFilters.none());

        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().getFirst().terminalTaskCount()).isZero();
        assertThat(table.rows().getFirst().codeChangeSpend().display().value()).isEqualTo("0");
        assertThat(table.rows().getFirst().taskCompletionRate().state())
                .isEqualTo(ValueState.NO_DENOMINATOR);
    }
}
