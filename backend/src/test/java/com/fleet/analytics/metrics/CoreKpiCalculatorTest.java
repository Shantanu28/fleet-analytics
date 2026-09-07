package com.fleet.analytics.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.metrics.model.ComparisonKind;
import com.fleet.analytics.metrics.model.ComparisonState;
import com.fleet.analytics.metrics.model.CoreKpis;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.PeriodPopulation;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.SeatCounts;
import com.fleet.analytics.metrics.model.SpendTotals;
import com.fleet.analytics.metrics.model.TaskCounts;
import com.fleet.analytics.metrics.model.ValueState;
import com.fleet.analytics.metrics.model.WindowCoverage;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The five cards, from independently stated populations. The fixture figures below are the metrics
 * contract's own worked values (8.1), transcribed from the specification rather than captured from
 * a run of this calculator.
 */
class CoreKpiCalculatorTest {

    private static final WindowCoverage COVERED =
            WindowCoverage.fullyCovered(DateWindow.ofInclusiveDates(
                    LocalDate.of(2026, 1, 16), LocalDate.of(2026, 1, 31)));
    private static final WindowCoverage PREVIOUS_COVERED =
            WindowCoverage.fullyCovered(DateWindow.ofInclusiveDates(
                    LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 15)));

    private final CoreKpiCalculator calculator = new CoreKpiCalculator();

    private static BigInteger cents(long value) {
        return BigInteger.valueOf(value);
    }

    /** Contract 7 for period P: 1 merged PR of 1 terminal, 1 completed of 2 terminal, 2300c, 2 seats. */
    private static PeriodPopulation fixtureCurrent() {
        return new PeriodPopulation(new TaskCounts(1, 1), new PrCounts(1, 0),
                new SpendTotals(cents(2300), cents(2300)), new SeatCounts(2, 6));
    }

    /** Contract 7 for period P-1: 2 merged of 3 terminal, 4 completed of 5 terminal, 4700c code. */
    private static PeriodPopulation fixturePrevious() {
        return new PeriodPopulation(new TaskCounts(4, 1), new PrCounts(2, 1),
                new SpendTotals(cents(4900), cents(4700)), new SeatCounts(3, 6));
    }

    private CoreKpis fixtureKpis(ScopeFilters filters) {
        return calculator.calculate(
                fixtureCurrent(), fixturePrevious(), COVERED, PREVIOUS_COVERED, filters);
    }

    private static WindowCoverage missing(WindowCoverage base, LogicalSource... sources) {
        return new WindowCoverage(base.window(), Set.of(sources));
    }

    // --- The worked fixture (contract 8.1) ------------------------------------------------------

    @Test
    void mergedPrsCountsOneAndComparesRelatively() {
        var mergedPrs = fixtureKpis(ScopeFilters.none()).mergedPrs();

        assertThat(mergedPrs.state()).isEqualTo(ValueState.OK);
        assertThat(mergedPrs.display().value()).isEqualTo("1");
        assertThat(mergedPrs.display().unit()).isEqualTo(DisplayUnit.COUNT);
        assertThat(mergedPrs.comparison().kind()).isEqualTo(ComparisonKind.RELATIVE);
        assertThat(mergedPrs.comparison().state()).isEqualTo(ComparisonState.OK);
        assertThat(mergedPrs.comparison().display().value()).isEqualTo("-50.0");
        assertThat(mergedPrs.comparison().display().unit()).isEqualTo(DisplayUnit.PERCENT);
    }

    /**
     * The value is 100.0% and the comparison is suppressed — the case AC-01.4 exists for. A
     * suppressed comparison must never blank the value it sits beside.
     */
    @Test
    void theMergeRateIsVisibleWhileItsComparisonIsSuppressed() {
        var mergeRate = fixtureKpis(ScopeFilters.none()).terminalMergeRate();

        assertThat(mergeRate.display().value()).isEqualTo("100.0");
        assertThat(mergeRate.comparison().state()).isEqualTo(ComparisonState.INSUFFICIENT_SAMPLE);
        assertThat(mergeRate.comparison().display()).isNull();
        assertThat(mergeRate.comparison().explanation().code()).isEqualTo("gate_terminal_prs_15");
    }

    /**
     * The explanation names the threshold and both observed populations. "Not enough data" would
     * leave a reader unable to tell a near miss from a total absence.
     */
    @Test
    void aSuppressedComparisonNamesTheThresholdAndBothPopulations() {
        var kpis = fixtureKpis(ScopeFilters.none());

        assertThat(kpis.terminalMergeRate().comparison().explanation().text()).isEqualTo(
                "Comparison needs 15 terminal PRs in both periods; "
                        + "this period had 1 and the previous period had 3.");
        assertThat(kpis.costPerMergedPr().comparison().explanation().text()).isEqualTo(
                "Comparison needs 15 merged PRs in both periods; "
                        + "this period had 1 and the previous period had 2.");
        assertThat(kpis.taskCompletionRate().comparison().explanation().text()).isEqualTo(
                "Comparison needs 20 completed or failed code-change tasks in both periods; "
                        + "this period had 2 and the previous period had 5.");
    }

    @Test
    void costPerMergedPrDividesCodeChangeSpendByMergedPrs() {
        var cost = fixtureKpis(ScopeFilters.none()).costPerMergedPr();

        assertThat(cost.display().value()).isEqualTo("23.00");
        assertThat(cost.display().unit()).isEqualTo(DisplayUnit.USD);
        assertThat(cost.comparison().kind()).isEqualTo(ComparisonKind.ABSOLUTE_USD);
    }

    @Test
    void taskCompletionRateExcludesCancelledAndInProgressTasks() {
        var completion = fixtureKpis(ScopeFilters.none()).taskCompletionRate();

        assertThat(completion.display().value()).isEqualTo("50.0");
        assertThat(completion.comparison().kind()).isEqualTo(ComparisonKind.PERCENTAGE_POINTS);
    }

    @Test
    void seatsCompareAnAbsoluteCountAndStateUtilisationSeparately() {
        var seats = fixtureKpis(ScopeFilters.none()).seats();

        assertThat(seats.activeSeats().display().value()).isEqualTo("2");
        assertThat(seats.licensedSeats()).isEqualTo(6);
        assertThat(seats.activeSeats().comparison().kind()).isEqualTo(ComparisonKind.ABSOLUTE_COUNT);
        assertThat(seats.activeSeats().comparison().display().value()).isEqualTo("-1");
        assertThat(seats.utilisation().display().value()).isEqualTo("33.3");
        // AC-01.8: one comparison per card. Utilisation never carries a second badge.
        assertThat(seats.utilisation().comparison()).isNull();
    }

    // --- Scope exceptions (contract 5.3) --------------------------------------------------------

    /**
     * Under a filter the active count is real and stays; utilisation becomes unavailable, because no
     * team or repository seat allocation exists and inventing one would be a fabricated denominator.
     */
    @Test
    void aFilteredScopeKeepsTheActiveCountAndDropsUtilisation() {
        var seats = fixtureKpis(new ScopeFilters(UUID.randomUUID(), null)).seats();

        assertThat(seats.activeSeats().state()).isEqualTo(ValueState.OK);
        assertThat(seats.activeSeats().display().value()).isEqualTo("2");
        assertThat(seats.utilisation().state()).isEqualTo(ValueState.UNAVAILABLE_FOR_SCOPE);
        assertThat(seats.utilisation().display()).isNull();
        assertThat(seats.utilisation().explanation().code())
                .isEqualTo("seat_allocation_not_defined_for_scope");
    }

    @Test
    void aRepositoryFilterAlsoRemovesUtilisation() {
        var seats = fixtureKpis(new ScopeFilters(null, UUID.randomUUID())).seats();

        assertThat(seats.utilisation().state()).isEqualTo(ValueState.UNAVAILABLE_FOR_SCOPE);
    }

    // --- Missing data is never zero (contract 1.5) ----------------------------------------------

    /**
     * The rule that matters most: an absent PR source makes the count unavailable, not zero. A zero
     * would read as "nothing merged this period", which is a different and false claim.
     */
    @Test
    void anAbsentPullRequestSourceLeavesTheCountUnavailableRatherThanZero() {
        CoreKpis kpis = calculator.calculate(fixtureCurrent(), fixturePrevious(),
                missing(COVERED, LogicalSource.PULL_REQUESTS), PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.mergedPrs().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(kpis.mergedPrs().display()).isNull();
        assertThat(kpis.mergedPrs().explanation().text()).contains("pull_requests");
        assertThat(kpis.terminalMergeRate().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(kpis.costPerMergedPr().state()).isEqualTo(ValueState.MISSING_DATA);
    }

    /**
     * Contract 1.2 gives every metric a value state <em>and</em> a comparison state. An unavailable
     * value must not silently drop its comparison: a client that saw no delta would have to guess
     * whether one was suppressed or simply forgotten.
     */
    @Test
    void anUnavailableValueStillStatesWhyItsComparisonIsAbsent() {
        CoreKpis kpis = calculator.calculate(fixtureCurrent(), fixturePrevious(),
                missing(COVERED, LogicalSource.PULL_REQUESTS), PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.mergedPrs().comparison()).isNotNull();
        assertThat(kpis.mergedPrs().comparison().state()).isEqualTo(ComparisonState.MISSING_DATA);
        assertThat(kpis.mergedPrs().comparison().kind()).isEqualTo(ComparisonKind.RELATIVE);
        assertThat(kpis.mergedPrs().comparison().explanation().code()).isEqualTo("source_not_covered");
    }

    /** A.5: a missing source degrades exactly its dependants and nothing else. */
    @Test
    void anAbsentPullRequestSourceLeavesTaskAndSeatMetricsIntact() {
        CoreKpis kpis = calculator.calculate(fixtureCurrent(), fixturePrevious(),
                missing(COVERED, LogicalSource.PULL_REQUESTS), PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.taskCompletionRate().state()).isEqualTo(ValueState.OK);
        assertThat(kpis.taskCompletionRate().display().value()).isEqualTo("50.0");
        assertThat(kpis.seats().activeSeats().state()).isEqualTo(ValueState.OK);
    }

    /** Contract 1.2: an uncovered baseline suppresses the comparison alone, never the value. */
    @Test
    void anUncoveredBaselineSuppressesOnlyTheComparison() {
        CoreKpis kpis = calculator.calculate(fixtureCurrent(), fixturePrevious(),
                COVERED, missing(PREVIOUS_COVERED, LogicalSource.TASKS), ScopeFilters.none());

        assertThat(kpis.taskCompletionRate().state()).isEqualTo(ValueState.OK);
        assertThat(kpis.taskCompletionRate().display().value()).isEqualTo("50.0");
        assertThat(kpis.taskCompletionRate().comparison().state())
                .isEqualTo(ComparisonState.NO_BASELINE);
        assertThat(kpis.taskCompletionRate().comparison().explanation().code())
                .isEqualTo("baseline_not_covered");
    }

    /** Precedence: an uncovered current period outranks an uncovered baseline (contract 1.2). */
    @Test
    void anUncoveredCurrentPeriodOutranksAnUncoveredBaseline() {
        CoreKpis kpis = calculator.calculate(fixtureCurrent(), fixturePrevious(),
                missing(COVERED, LogicalSource.TASKS),
                missing(PREVIOUS_COVERED, LogicalSource.TASKS), ScopeFilters.none());

        assertThat(kpis.taskCompletionRate().comparison().state())
                .isEqualTo(ComparisonState.MISSING_DATA);
    }

    // --- Undefined comparisons ------------------------------------------------------------------

    /** Contract 1.2: a count baseline of 0 leaves a relative change undefined. */
    @Test
    void aZeroPreviousCountMakesTheRelativeChangeUndefined() {
        PeriodPopulation previous = new PeriodPopulation(new TaskCounts(4, 1), new PrCounts(0, 0),
                new SpendTotals(cents(4900), cents(4700)), new SeatCounts(0, 6));

        CoreKpis kpis = calculator.calculate(
                fixtureCurrent(), previous, COVERED, PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.mergedPrs().comparison().state())
                .isEqualTo(ComparisonState.UNDEFINED_RELATIVE);
        assertThat(kpis.mergedPrs().display().value()).isEqualTo("1");
        // An absolute count delta stays defined against a zero baseline.
        assertThat(kpis.seats().activeSeats().comparison().state()).isEqualTo(ComparisonState.OK);
        assertThat(kpis.seats().activeSeats().comparison().display().value()).isEqualTo("2");
    }

    /** An undefined ratio in either period outranks an unmet gate (contract 8.4). */
    @Test
    void anUndefinedBaselineRateOutranksTheSampleGate() {
        PeriodPopulation previous = new PeriodPopulation(new TaskCounts(0, 0), new PrCounts(2, 1),
                new SpendTotals(cents(4900), cents(4700)), new SeatCounts(3, 6));

        CoreKpis kpis = calculator.calculate(
                fixtureCurrent(), previous, COVERED, PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.taskCompletionRate().comparison().state())
                .isEqualTo(ComparisonState.NO_DENOMINATOR);
    }

    // --- Comparisons that do qualify -------------------------------------------------------------

    /**
     * Contract 8.5 case M1 at the KPI level: 8/16 against 12/20 is a 10.0 pp decline, and both
     * populations clear the 15-PR gate, so it is displayed rather than suppressed.
     */
    @Test
    void aQualifyingSampleProducesADisplayedComparison() {
        PeriodPopulation current = new PeriodPopulation(new TaskCounts(15, 5), new PrCounts(8, 8),
                new SpendTotals(cents(30000), cents(30000)), new SeatCounts(5, 6));
        PeriodPopulation previous = new PeriodPopulation(new TaskCounts(16, 4), new PrCounts(12, 8),
                new SpendTotals(cents(50000), cents(50000)), new SeatCounts(4, 6));

        CoreKpis kpis = calculator.calculate(
                current, previous, COVERED, PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.terminalMergeRate().comparison().state()).isEqualTo(ComparisonState.OK);
        assertThat(kpis.terminalMergeRate().comparison().display().value()).isEqualTo("-10.0");
        assertThat(kpis.terminalMergeRate().comparison().display().unit())
                .isEqualTo(DisplayUnit.PERCENTAGE_POINTS);

        assertThat(kpis.taskCompletionRate().comparison().state()).isEqualTo(ComparisonState.OK);
        assertThat(kpis.taskCompletionRate().comparison().display().value()).isEqualTo("-5.0");

        // The cost gate counts merged PRs, not terminal ones: 8 merged is below 15, so this card
        // is still suppressed while its neighbours compare. Gates are per metric, never per row.
        assertThat(kpis.costPerMergedPr().comparison().state())
                .isEqualTo(ComparisonState.INSUFFICIENT_SAMPLE);
    }

    /**
     * Cost per merged PR gates on <em>merged</em> PRs, so it needs its own populations: 15 merged
     * at 30000c is $20.00 against 20 merged at 50000c, or $25.00 — a change of -$5.00.
     */
    @Test
    void aQualifyingMergedPrSampleProducesADisplayedUnitCostComparison() {
        PeriodPopulation current = new PeriodPopulation(new TaskCounts(15, 5), new PrCounts(15, 1),
                new SpendTotals(cents(30000), cents(30000)), new SeatCounts(5, 6));
        PeriodPopulation previous = new PeriodPopulation(new TaskCounts(16, 4), new PrCounts(20, 1),
                new SpendTotals(cents(50000), cents(50000)), new SeatCounts(4, 6));

        CoreKpis kpis = calculator.calculate(
                current, previous, COVERED, PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.costPerMergedPr().display().value()).isEqualTo("20.00");
        assertThat(kpis.costPerMergedPr().comparison().state()).isEqualTo(ComparisonState.OK);
        assertThat(kpis.costPerMergedPr().comparison().display().value()).isEqualTo("-5.00");
        assertThat(kpis.costPerMergedPr().comparison().display().unit()).isEqualTo(DisplayUnit.USD);
    }

    // --- Which period is actually undefined (contract 1.2) -------------------------------------

    /**
     * {@code no_denominator} fires when <em>either</em> period has an empty denominator, so the
     * sentence has to say which. Claiming "this period has no terminal PRs" when the current period
     * has one and the previous had none is a false statement about live data, and it sends a reader
     * to inspect the wrong window.
     */
    @Test
    void anEmptyPreviousPeriodIsNotDescribedAsAnEmptyCurrentOne() {
        PeriodPopulation previous = new PeriodPopulation(new TaskCounts(0, 0), new PrCounts(0, 0),
                new SpendTotals(cents(4900), cents(4700)), new SeatCounts(3, 6));

        CoreKpis kpis = calculator.calculate(
                fixtureCurrent(), previous, COVERED, PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.terminalMergeRate().comparison().state())
                .isEqualTo(ComparisonState.NO_DENOMINATOR);
        assertThat(kpis.terminalMergeRate().comparison().explanation().text()).isEqualTo(
                "The previous period had no terminal PRs, so there is no merge rate "
                        + "to compare against.");
        assertThat(kpis.taskCompletionRate().comparison().explanation().text()).isEqualTo(
                "The previous period had no terminal code-change tasks, so there is no "
                        + "completion rate to compare against.");
        assertThat(kpis.costPerMergedPr().comparison().explanation().text()).isEqualTo(
                "The previous period had no merged PRs, so there is no unit cost to compare against.");

        // The current values are all present and correct throughout.
        assertThat(kpis.terminalMergeRate().display().value()).isEqualTo("100.0");
        assertThat(kpis.costPerMergedPr().display().value()).isEqualTo("23.00");
    }

    @Test
    void anEmptyCurrentPeriodIsNamedAsSuch() {
        PeriodPopulation current = new PeriodPopulation(new TaskCounts(0, 0), new PrCounts(0, 0),
                new SpendTotals(cents(2300), cents(2300)), new SeatCounts(2, 6));

        CoreKpis kpis = calculator.calculate(
                current, fixturePrevious(), COVERED, PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.terminalMergeRate().comparison().explanation().text())
                .isEqualTo("This period has no terminal PRs, so there is no merge rate to compare.");
        assertThat(kpis.costPerMergedPr().comparison().explanation().text())
                .isEqualTo("This period has no merged PRs, so there is no unit cost to compare.");
    }

    @Test
    void twoEmptyPeriodsAreNamedTogether() {
        PeriodPopulation empty = new PeriodPopulation(TaskCounts.NONE, PrCounts.NONE,
                SpendTotals.NONE, new SeatCounts(0, 6));

        CoreKpis kpis = calculator.calculate(
                empty, empty, COVERED, PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.taskCompletionRate().comparison().explanation().text()).isEqualTo(
                "Neither period has terminal code-change tasks, so there is no completion rate "
                        + "to compare.");
        assertThat(kpis.terminalMergeRate().comparison().state())
                .isEqualTo(ComparisonState.NO_DENOMINATOR);
    }

    /** The reason code still identifies the metric, so a client keying on it is unaffected. */
    @Test
    void narrowingTheSentenceDoesNotChangeTheReasonCode() {
        PeriodPopulation previous = new PeriodPopulation(new TaskCounts(0, 0), new PrCounts(0, 0),
                new SpendTotals(cents(4900), cents(4700)), new SeatCounts(3, 6));

        CoreKpis kpis = calculator.calculate(
                fixtureCurrent(), previous, COVERED, PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.terminalMergeRate().comparison().explanation().code())
                .isEqualTo("no_terminal_prs");
        assertThat(kpis.taskCompletionRate().comparison().explanation().code())
                .isEqualTo("no_terminal_tasks");
        assertThat(kpis.costPerMergedPr().comparison().explanation().code())
                .isEqualTo("no_merged_prs");
    }

    /** Precedence is untouched by the wording change: coverage still outranks an empty denominator. */
    @Test
    void anUncoveredBaselineStillOutranksAnEmptyDenominator() {
        PeriodPopulation previous = new PeriodPopulation(new TaskCounts(0, 0), new PrCounts(0, 0),
                new SpendTotals(cents(4900), cents(4700)), new SeatCounts(3, 6));

        CoreKpis kpis = calculator.calculate(fixtureCurrent(), previous, COVERED,
                missing(PREVIOUS_COVERED, LogicalSource.TASKS), ScopeFilters.none());

        assertThat(kpis.taskCompletionRate().comparison().state())
                .isEqualTo(ComparisonState.NO_BASELINE);
    }

    // --- Utilisation scope is independent of coverage (contract 5.3) ----------------------------

    /**
     * Whether utilisation is <em>defined</em> depends on the selection, not on what data arrived.
     * Under a filter there is no seat allocation to divide by, and that stays true when the seat
     * source is missing too — reporting missing_data would imply the number exists and is merely
     * unavailable today.
     */
    @Test
    void aFilteredUtilisationIsOutOfScopeEvenWhenTheSeatSourceIsMissing() {
        CoreKpis kpis = calculator.calculate(fixtureCurrent(), fixturePrevious(),
                missing(COVERED, LogicalSource.SEATS), PREVIOUS_COVERED,
                new ScopeFilters(UUID.randomUUID(), null));

        assertThat(kpis.seats().activeSeats().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(kpis.seats().utilisation().state()).isEqualTo(ValueState.UNAVAILABLE_FOR_SCOPE);
        assertThat(kpis.seats().utilisation().explanation().code())
                .isEqualTo("seat_allocation_not_defined_for_scope");
    }

    /** Unfiltered, the same missing source does make utilisation missing_data rather than absent. */
    @Test
    void anUnfilteredUtilisationFollowsTheSeatSource() {
        CoreKpis kpis = calculator.calculate(fixtureCurrent(), fixturePrevious(),
                missing(COVERED, LogicalSource.SEATS), PREVIOUS_COVERED, ScopeFilters.none());

        assertThat(kpis.seats().utilisation().state()).isEqualTo(ValueState.MISSING_DATA);
    }

    @Test
    void thePopulationsTravelWithTheResultsAsEvidence() {
        CoreKpis kpis = fixtureKpis(ScopeFilters.none());

        assertThat(kpis.current().pullRequests().merged()).isEqualTo(1);
        assertThat(kpis.previous().pullRequests().terminal()).isEqualTo(3);
        assertThat(kpis.current().spend().codeChangeCents()).isEqualTo(cents(2300));
    }
}
