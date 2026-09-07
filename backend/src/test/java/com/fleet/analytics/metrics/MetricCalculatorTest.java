package com.fleet.analytics.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.metrics.model.ComparisonState;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.MetricGate;
import com.fleet.analytics.metrics.model.MetricResult;
import com.fleet.analytics.metrics.model.ValueState;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The arithmetic and state rules every section shares. No database, no fixtures: each expectation is
 * stated independently from the contract, not read back from an implementation.
 */
class MetricCalculatorTest {

    private static BigInteger n(long value) {
        return BigInteger.valueOf(value);
    }

    // --- Single rounding, applied to exact integers (contract 1.1, D-2) -------------------------

    /**
     * The regression the contract calls out by name. 600/4001 is 0.14996…, which rounds once to
     * 0.1. Rounding an intermediate quotient first — 0.15 to one decimal — gives 0.2, a number that
     * is wrong by a whole display step and would be indistinguishable from a real 0.2% on screen.
     */
    @Test
    void aRateIsRoundedOnceFromTheExactCounts() {
        assertThat(MetricCalculator.percentage(n(6), n(4001)).toPlainString()).isEqualTo("0.1");
    }

    @ParameterizedTest(name = "{0}/{1} -> {2}%")
    @CsvSource({
        "1, 1,  100.0",
        "2, 3,  66.7",
        "1, 2,  50.0",
        "4, 5,  80.0",
        "2, 6,  33.3",
        "0, 1,  0.0",
        "0, 10, 0.0",
    })
    void ratesCarryOneDecimal(long numerator, long denominator, String expected) {
        assertThat(MetricCalculator.percentage(n(numerator), n(denominator)).toPlainString())
                .isEqualTo(expected);
    }

    /**
     * Contract 8.5 case M2, the float regression. 29/50 minus 8/16 is exactly 8 percentage points;
     * in IEEE-754 doubles it evaluates to 7.999999999999993 and a naive {@code >= 8} test fails to
     * trigger. Combining the four counts into one rational keeps it exact.
     */
    @Test
    void aPercentagePointChangeIsOneRationalOverFourIntegers() {
        assertThat(MetricCalculator.percentagePointChange(n(8), n(16), n(29), n(50)).toPlainString())
                .isEqualTo("-8.0");
    }

    /**
     * Seat utilisation in the contract fixture: 2/6 against 3/6. Subtracting the two rounded rates
     * (33.3 - 50.0) gives -16.7 here by luck; combining first gives -16.666… which rounds to the
     * same answer for the right reason. The exactness matters where the two disagree.
     */
    @Test
    void aPercentagePointChangeCombinesBeforeRounding() {
        assertThat(MetricCalculator.percentagePointChange(n(2), n(6), n(3), n(6)).toPlainString())
                .isEqualTo("-16.7");
        // 1/1 against 2/3: +33.333… pp, the fixture's suppressed merge-rate improvement.
        assertThat(MetricCalculator.percentagePointChange(n(1), n(1), n(2), n(3)).toPlainString())
                .isEqualTo("33.3");
    }

    /** Contract 1.2: a defined baseline rate of 0% is valid data, and 0% to 20% is +20.0 pp. */
    @Test
    void aBaselineRateOfZeroIsAValidComparison() {
        assertThat(MetricCalculator.percentagePointChange(n(2), n(10), n(0), n(10)).toPlainString())
                .isEqualTo("20.0");
    }

    @ParameterizedTest(name = "{0} vs {1} -> {2}%")
    @CsvSource({
        "1, 2, -50.0",
        "2, 3, -33.3",
        "3, 2, 50.0",
    })
    void relativeChangeIsRoundedOnceFromTheExactCounts(long current, long previous, String expected) {
        assertThat(MetricCalculator.relativeChangePercent(n(current), n(previous)).toPlainString())
                .isEqualTo(expected);
    }

    // --- Money (contract 8.6) -------------------------------------------------------------------

    @ParameterizedTest(name = "{0}c / {1} PRs -> ${2}")
    @CsvSource({
        "2300, 1, 23.00",
        "4700, 2, 23.50",
        "0,    1, 0.00",
        "1800, 1, 18.00",
    })
    void costPerMergedPrCarriesTwoDecimals(long cents, long mergedPrs, String expected) {
        assertThat(MetricCalculator.unitCostUsd(n(cents), n(mergedPrs)).toPlainString())
                .isEqualTo(expected);
    }

    @Test
    void spendTotalsAreWholeDollars() {
        assertThat(MetricCalculator.spendTotal(n(2300)).display().value()).isEqualTo("23");
        assertThat(MetricCalculator.spendTotal(n(0)).display().value()).isEqualTo("0");
    }

    /**
     * Contract 8.6's sub-dollar rule. A positive amount that rounds to zero must be flagged so it
     * reads as "&lt;$1"; a genuine zero must not be, or real inactivity would look like rounding.
     */
    @Test
    void onlyAPositiveSpendThatRoundsToZeroIsFlagged() {
        assertThat(MetricCalculator.spendTotal(n(40)).roundsToZero()).isTrue();
        assertThat(MetricCalculator.spendTotal(n(40)).display().value()).isEqualTo("0");
        assertThat(MetricCalculator.spendTotal(n(0)).roundsToZero()).isFalse();
        assertThat(MetricCalculator.spendTotal(n(50)).roundsToZero()).isFalse();
        assertThat(MetricCalculator.spendTotal(n(50)).display().value()).isEqualTo("1");
    }

    // --- Value states (contract 1.2) ------------------------------------------------------------

    /** AC-01.6: a real 0.0% and an undefined rate are different states with different copy. */
    @Test
    void aZeroNumeratorOverAPositiveDenominatorIsARealResult() {
        MetricResult result = MetricCalculator.rate(n(0), n(10), Explanations.noTerminalTasks());

        assertThat(result.state()).isEqualTo(ValueState.ZERO_OUTCOME);
        assertThat(result.display().value()).isEqualTo("0.0");
        assertThat(result.display().unit()).isEqualTo(DisplayUnit.PERCENT);
    }

    @Test
    void aZeroDenominatorHasNoValueAtAll() {
        MetricResult result = MetricCalculator.rate(n(0), n(0), Explanations.noTerminalTasks());

        assertThat(result.state()).isEqualTo(ValueState.NO_DENOMINATOR);
        assertThat(result.display()).isNull();
        assertThat(result.explanation().code()).isEqualTo("no_terminal_tasks");
    }

    @Test
    void anUndefinedUnitCostKeepsItsOwnCopyRatherThanReadingAsNoActivity() {
        MetricResult result = MetricCalculator.unitCost(n(2300), n(0), Explanations.noMergedPrs());

        assertThat(result.state()).isEqualTo(ValueState.NO_DENOMINATOR);
        assertThat(result.explanation().text()).contains("No merged PRs in this period");
        assertThat(result.explanation().text()).doesNotContain("no activity");
    }

    // --- Gates (contract 5.4) -------------------------------------------------------------------

    /**
     * Gates are inclusive, and the boundary is where an off-by-one hides: 14 fails and 15 passes,
     * 19 fails and 20 passes. A gate compared with {@code >} instead of {@code >=} would silently
     * suppress every comparison sitting exactly on the threshold.
     */
    @ParameterizedTest(name = "{0} of {1} -> met={2}")
    @CsvSource({
        "14, TERMINAL_PRS,   false",
        "15, TERMINAL_PRS,   true",
        "14, MERGED_PRS,     false",
        "15, MERGED_PRS,     true",
        "19, TERMINAL_TASKS, false",
        "20, TERMINAL_TASKS, true",
        "0,  TERMINAL_TASKS, false",
    })
    void gatesAreInclusiveAtTheirExactThreshold(long population, MetricGate gate, boolean met) {
        assertThat(gate.isMetBy(population)).isEqualTo(met);
    }

    // --- Comparison precedence (contract 1.2) ---------------------------------------------------

    /**
     * When several reasons apply the first in the contract's list wins. Declaring the enum in
     * precedence order and picking the first applicable member means a call site cannot get the
     * order wrong by listing its reasons in a different sequence.
     */
    @Test
    void theFirstApplicableReasonWinsRegardlessOfHowItWasOffered() {
        Set<ComparisonState> several = EnumSet.of(ComparisonState.INSUFFICIENT_SAMPLE,
                ComparisonState.NO_DENOMINATOR, ComparisonState.MISSING_DATA);

        assertThat(MetricCalculator.firstApplicable(several)).isEqualTo(ComparisonState.MISSING_DATA);
    }

    @Test
    void anUndefinedRatioOutranksAnUnmetGate() {
        assertThat(MetricCalculator.firstApplicable(
                        EnumSet.of(ComparisonState.INSUFFICIENT_SAMPLE, ComparisonState.NO_DENOMINATOR)))
                .isEqualTo(ComparisonState.NO_DENOMINATOR);
    }

    @Test
    void anUncoveredBaselineOutranksAnUndefinedRatio() {
        assertThat(MetricCalculator.firstApplicable(
                        EnumSet.of(ComparisonState.NO_DENOMINATOR, ComparisonState.NO_BASELINE)))
                .isEqualTo(ComparisonState.NO_BASELINE);
    }

    @Test
    void noApplicableReasonMeansTheComparisonIsComputed() {
        assertThat(MetricCalculator.firstApplicable(EnumSet.noneOf(ComparisonState.class)))
                .isEqualTo(ComparisonState.OK);
    }
}
