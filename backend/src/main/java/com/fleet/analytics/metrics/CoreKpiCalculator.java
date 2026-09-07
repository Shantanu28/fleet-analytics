package com.fleet.analytics.metrics;

import com.fleet.analytics.metrics.model.ComparisonKind;
import com.fleet.analytics.metrics.model.ComparisonResult;
import com.fleet.analytics.metrics.model.ComparisonState;
import com.fleet.analytics.metrics.model.CoreKpis;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.Explanation;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.MetricGate;
import com.fleet.analytics.metrics.model.MetricResult;
import com.fleet.analytics.metrics.model.PeriodPopulation;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.SeatCounts;
import com.fleet.analytics.metrics.model.SeatsResult;
import com.fleet.analytics.metrics.model.TaskCounts;
import com.fleet.analytics.metrics.model.WindowCoverage;
import java.math.BigInteger;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The five KPI cards (contract 3), each with exactly the one comparison AC-01.7 assigns it.
 *
 * <p>Every card follows the same two-part shape, and the parts are independent: resolve the value
 * state, then resolve the comparison state separately. A visible value beside a suppressed
 * comparison is the normal case at low volume, not a degraded one (AC-01.4).
 *
 * <p>The comparison never falls back to a different kind when its own is unavailable — it explains
 * itself instead (AC-01.8). That is why {@link ComparisonResult} keeps its {@code kind} even when
 * suppressed.
 *
 * <p>Pure: populations and coverage in, results out. No SQL, no HTTP, no clock.
 */
@Component
public class CoreKpiCalculator {

    public CoreKpis calculate(PeriodPopulation current, PeriodPopulation previous,
            WindowCoverage currentCoverage, WindowCoverage previousCoverage, ScopeFilters filters) {
        return new CoreKpis(
                mergedPrs(current.pullRequests(), previous.pullRequests(),
                        currentCoverage, previousCoverage),
                terminalMergeRate(current.pullRequests(), previous.pullRequests(),
                        currentCoverage, previousCoverage),
                costPerMergedPr(current, previous, currentCoverage, previousCoverage),
                taskCompletionRate(current.tasks(), previous.tasks(),
                        currentCoverage, previousCoverage),
                seats(current.seats(), previous.seats(), currentCoverage, previousCoverage, filters),
                current, previous);
    }

    // --- 3.1 Merged agent PRs -------------------------------------------------------------------

    private MetricResult mergedPrs(PrCounts current, PrCounts previous,
            WindowCoverage currentCoverage, WindowCoverage previousCoverage) {
        Set<LogicalSource> required = LogicalSource.PR_OUTCOMES;
        if (!currentCoverage.supports(required)) {
            return unavailableSource(currentCoverage, required, ComparisonKind.RELATIVE);
        }
        // Contract 3.1: no sample gate on a count. Only the relative form can be undefined,
        // and only because dividing by a previous count of zero has no meaning.
        return MetricCalculator.count(current.merged()).withComparison(
                relativeComparison(current.mergedExact(), previous.mergedExact(),
                        currentCoverage, previousCoverage, required));
    }

    // --- 3.2 Terminal PR merge rate --------------------------------------------------------------

    private MetricResult terminalMergeRate(PrCounts current, PrCounts previous,
            WindowCoverage currentCoverage, WindowCoverage previousCoverage) {
        Set<LogicalSource> required = LogicalSource.PR_OUTCOMES;
        if (!currentCoverage.supports(required)) {
            return unavailableSource(currentCoverage, required, ComparisonKind.PERCENTAGE_POINTS);
        }
        MetricResult value = MetricCalculator.rate(
                current.mergedExact(), current.terminalExact(), Explanations.noTerminalPrs());
        return value.withComparison(rateComparison(
                current.mergedExact(), current.terminalExact(),
                previous.mergedExact(), previous.terminalExact(),
                MetricGate.TERMINAL_PRS, current.terminal(), previous.terminal(),
                currentCoverage, previousCoverage, required));
    }

    // --- 3.3 Blended cost per merged PR ----------------------------------------------------------

    private MetricResult costPerMergedPr(PeriodPopulation current, PeriodPopulation previous,
            WindowCoverage currentCoverage, WindowCoverage previousCoverage) {
        Set<LogicalSource> required = LogicalSource.UNIT_COST;
        if (!currentCoverage.supports(required)) {
            return unavailableSource(currentCoverage, required, ComparisonKind.ABSOLUTE_USD);
        }
        BigInteger currentMerged = current.pullRequests().mergedExact();
        BigInteger previousMerged = previous.pullRequests().mergedExact();
        MetricResult value = MetricCalculator.unitCost(
                current.spend().codeChangeCents(), currentMerged, Explanations.noMergedPrs());

        EnumSet<ComparisonState> applicable = EnumSet.noneOf(ComparisonState.class);
        addCoverageReasons(applicable, currentCoverage, previousCoverage, required);
        boolean currentUndefined = currentMerged.signum() == 0;
        boolean previousUndefined = previousMerged.signum() == 0;
        if (currentUndefined || previousUndefined) {
            applicable.add(ComparisonState.NO_DENOMINATOR);
        }
        boolean gated = !MetricGate.MERGED_PRS.isMetBy(current.pullRequests().merged())
                || !MetricGate.MERGED_PRS.isMetBy(previous.pullRequests().merged());
        if (gated) {
            applicable.add(ComparisonState.INSUFFICIENT_SAMPLE);
        }

        ComparisonState state = MetricCalculator.firstApplicable(applicable);
        if (state == ComparisonState.OK) {
            return value.withComparison(ComparisonResult.computed(ComparisonKind.ABSOLUTE_USD,
                    DisplayValue.of(MetricCalculator.unitCostChangeUsd(
                            current.spend().codeChangeCents(), currentMerged,
                            previous.spend().codeChangeCents(), previousMerged), DisplayUnit.USD)));
        }
        return value.withComparison(ComparisonResult.suppressed(ComparisonKind.ABSOLUTE_USD, state,
                costExplanation(state, currentCoverage, previousCoverage, required,
                        current.pullRequests().merged(), previous.pullRequests().merged(),
                        currentUndefined, previousUndefined)));
    }

    private Explanation costExplanation(ComparisonState state, WindowCoverage currentCoverage,
            WindowCoverage previousCoverage, Set<LogicalSource> required,
            long currentMerged, long previousMerged,
            boolean currentUndefined, boolean previousUndefined) {
        return switch (state) {
            case MISSING_DATA -> Explanations.sourcesNotCovered(currentCoverage.missingFrom(required));
            case NO_BASELINE -> Explanations.baselineNotCovered(previousCoverage.missingFrom(required));
            case NO_DENOMINATOR -> Explanations.undefinedPeriodRatio(
                    MetricGate.MERGED_PRS, currentUndefined, previousUndefined);
            case INSUFFICIENT_SAMPLE ->
                    Explanations.periodGate(MetricGate.MERGED_PRS, currentMerged, previousMerged);
            case UNAVAILABLE_FOR_SCOPE, UNDEFINED_RELATIVE, OK ->
                    throw new IllegalStateException("unit cost cannot be " + state);
        };
    }

    // --- 3.4 Task completion rate ----------------------------------------------------------------

    private MetricResult taskCompletionRate(TaskCounts current, TaskCounts previous,
            WindowCoverage currentCoverage, WindowCoverage previousCoverage) {
        Set<LogicalSource> required = LogicalSource.TASK_OUTCOMES;
        if (!currentCoverage.supports(required)) {
            return unavailableSource(currentCoverage, required, ComparisonKind.PERCENTAGE_POINTS);
        }
        MetricResult value = MetricCalculator.rate(
                current.completedExact(), current.terminalExact(), Explanations.noTerminalTasks());
        return value.withComparison(rateComparison(
                current.completedExact(), current.terminalExact(),
                previous.completedExact(), previous.terminalExact(),
                MetricGate.TERMINAL_TASKS, current.terminal(), previous.terminal(),
                currentCoverage, previousCoverage, required));
    }

    // --- 3.5 Active seats / licensed seats -------------------------------------------------------

    private SeatsResult seats(SeatCounts current, SeatCounts previous,
            WindowCoverage currentCoverage, WindowCoverage previousCoverage, ScopeFilters filters) {
        Set<LogicalSource> required = LogicalSource.SEAT_ACTIVITY;
        if (!currentCoverage.supports(required)) {
            Explanation explanation =
                    Explanations.sourcesNotCovered(currentCoverage.missingFrom(required));
            // Utilisation is resolved on its own terms even here. Whether a ratio is *defined* for
            // the selected scope is a property of the selection, not of what data arrived: under a
            // filter there is no seat allocation to divide by, and that stays true when the seat
            // source is also missing. Reporting missing_data would imply the number exists and is
            // merely unavailable today.
            return new SeatsResult(
                    MetricResult.missingData(explanation).withComparison(ComparisonResult.suppressed(
                            ComparisonKind.ABSOLUTE_COUNT, ComparisonState.MISSING_DATA, explanation)),
                    current.licensedCapacity(),
                    utilisation(current, filters, currentCoverage));
        }

        EnumSet<ComparisonState> applicable = EnumSet.noneOf(ComparisonState.class);
        addCoverageReasons(applicable, currentCoverage, previousCoverage, required);
        ComparisonState state = MetricCalculator.firstApplicable(applicable);

        // An absolute count delta is defined whenever both counts are: a zero baseline is a real
        // number to subtract from, unlike a zero divisor. Contract 3.5 sets no gate here.
        ComparisonResult comparison = state == ComparisonState.OK
                ? ComparisonResult.computed(ComparisonKind.ABSOLUTE_COUNT,
                        DisplayValue.count(current.activeOwners() - previous.activeOwners()))
                : ComparisonResult.suppressed(ComparisonKind.ABSOLUTE_COUNT, state,
                        coverageExplanation(state, currentCoverage, previousCoverage, required));

        MetricResult active =
                MetricCalculator.count(current.activeOwners()).withComparison(comparison);
        return new SeatsResult(
                active, current.licensedCapacity(), utilisation(current, filters, currentCoverage));
    }

    /**
     * Utilisation is stated separately and never compared (AC-01.7, AC-01.8).
     *
     * <p>Scope is resolved first, and independently of coverage. Under any team or repository filter
     * the ratio is not defined at all — seat allocation exists only at the organisation, and a
     * filtered numerator over an organisation-wide denominator would be a fabricated figure
     * (contract 5.3). That verdict does not depend on which sources happen to be covered, and it
     * matches contract 1.2's precedence, where unavailable_for_scope outranks missing_data.
     */
    private MetricResult utilisation(
            SeatCounts current, ScopeFilters filters, WindowCoverage currentCoverage) {
        if (filters.hasAnyScope()) {
            return MetricResult.unavailableForScope(Explanations.seatUtilisationOutOfScope());
        }
        Set<LogicalSource> required = LogicalSource.SEAT_ACTIVITY;
        if (!currentCoverage.supports(required)) {
            return MetricResult.missingData(
                    Explanations.sourcesNotCovered(currentCoverage.missingFrom(required)));
        }
        return MetricCalculator.rate(
                current.activeExact(), current.licensedExact(), Explanations.noLicensedSeats());
    }

    // --- Shared comparison shapes -----------------------------------------------------------------

    /** Two rates compared in percentage points, gated on both populations (contract 5.4). */
    private ComparisonResult rateComparison(BigInteger currentNumerator, BigInteger currentDenominator,
            BigInteger baselineNumerator, BigInteger baselineDenominator, MetricGate gate,
            long currentPopulation, long previousPopulation, WindowCoverage currentCoverage,
            WindowCoverage previousCoverage, Set<LogicalSource> required) {
        EnumSet<ComparisonState> applicable = EnumSet.noneOf(ComparisonState.class);
        addCoverageReasons(applicable, currentCoverage, previousCoverage, required);
        // Kept apart rather than collapsed into one boolean: the state is the same either way, but
        // the sentence must name the period that is actually empty.
        boolean currentUndefined = currentDenominator.signum() == 0;
        boolean baselineUndefined = baselineDenominator.signum() == 0;
        if (currentUndefined || baselineUndefined) {
            applicable.add(ComparisonState.NO_DENOMINATOR);
        }
        if (!gate.isMetBy(currentPopulation) || !gate.isMetBy(previousPopulation)) {
            applicable.add(ComparisonState.INSUFFICIENT_SAMPLE);
        }

        ComparisonState state = MetricCalculator.firstApplicable(applicable);
        if (state == ComparisonState.OK) {
            return ComparisonResult.computed(ComparisonKind.PERCENTAGE_POINTS,
                    DisplayValue.of(MetricCalculator.percentagePointChange(currentNumerator,
                            currentDenominator, baselineNumerator, baselineDenominator),
                            DisplayUnit.PERCENTAGE_POINTS));
        }
        Explanation explanation = state == ComparisonState.INSUFFICIENT_SAMPLE
                ? Explanations.periodGate(gate, currentPopulation, previousPopulation)
                : state == ComparisonState.NO_DENOMINATOR
                        ? Explanations.undefinedPeriodRatio(gate, currentUndefined, baselineUndefined)
                        : coverageExplanation(state, currentCoverage, previousCoverage, required);
        return ComparisonResult.suppressed(ComparisonKind.PERCENTAGE_POINTS, state, explanation);
    }

    /** A count compared relatively, undefined only when the previous count is zero (contract 3.1). */
    private ComparisonResult relativeComparison(BigInteger current, BigInteger previous,
            WindowCoverage currentCoverage, WindowCoverage previousCoverage,
            Set<LogicalSource> required) {
        EnumSet<ComparisonState> applicable = EnumSet.noneOf(ComparisonState.class);
        addCoverageReasons(applicable, currentCoverage, previousCoverage, required);
        if (previous.signum() == 0) {
            applicable.add(ComparisonState.UNDEFINED_RELATIVE);
        }

        ComparisonState state = MetricCalculator.firstApplicable(applicable);
        if (state == ComparisonState.OK) {
            return ComparisonResult.computed(ComparisonKind.RELATIVE, DisplayValue.of(
                    MetricCalculator.relativeChangePercent(current, previous), DisplayUnit.PERCENT));
        }
        Explanation explanation = state == ComparisonState.UNDEFINED_RELATIVE
                ? Explanations.noPreviousValue()
                : coverageExplanation(state, currentCoverage, previousCoverage, required);
        return ComparisonResult.suppressed(ComparisonKind.RELATIVE, state, explanation);
    }

    /**
     * Contract 1.2 precedence positions 2 and 3. Both are offered; the enum order decides, so this
     * method never has to know which outranks which.
     */
    private void addCoverageReasons(EnumSet<ComparisonState> applicable, WindowCoverage currentCoverage,
            WindowCoverage previousCoverage, Set<LogicalSource> required) {
        if (!currentCoverage.supports(required)) {
            applicable.add(ComparisonState.MISSING_DATA);
        }
        if (!previousCoverage.supports(required)) {
            applicable.add(ComparisonState.NO_BASELINE);
        }
    }

    private Explanation coverageExplanation(ComparisonState state, WindowCoverage currentCoverage,
            WindowCoverage previousCoverage, Set<LogicalSource> required) {
        return state == ComparisonState.MISSING_DATA
                ? Explanations.sourcesNotCovered(currentCoverage.missingFrom(required))
                : Explanations.baselineNotCovered(previousCoverage.missingFrom(required));
    }

    /**
     * A value whose source is absent still states its comparison. Contract 1.2 gives every metric
     * both a value state and, independently, a comparison state — returning a bare value would
     * leave the client to infer why no delta arrived, which is exactly the inference D-6 forbids.
     */
    private MetricResult unavailableSource(
            WindowCoverage coverage, Collection<LogicalSource> required, ComparisonKind kind) {
        Explanation explanation = Explanations.sourcesNotCovered(coverage.missingFrom(required));
        return MetricResult.missingData(explanation).withComparison(
                ComparisonResult.suppressed(kind, ComparisonState.MISSING_DATA, explanation));
    }
}
