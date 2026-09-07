package com.fleet.analytics.metrics;

import com.fleet.analytics.metrics.model.ComparisonState;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.Explanation;
import com.fleet.analytics.metrics.model.MetricResult;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Set;

/**
 * The arithmetic every dashboard section shares, and the precedence that decides which reason a
 * suppressed comparison reports.
 *
 * <p><b>Exactly one rounding, applied to the exact integers.</b> Every operation here takes counts
 * or cent sums and produces the displayed value in a single division at presentation scale. Nothing
 * rounds an intermediate quotient: a percentage-point change is one rational over four integers
 * rather than a subtraction of two rounded rates, and a relative change divides the exact
 * difference rather than two rounded values. Contract 8.5 case M2 exists because the naive form is
 * wrong by a whole display step, and worse, wrong in a way that silently fails a threshold test.
 *
 * <p><b>No floating point anywhere.</b> {@code double} cannot represent tenths, so a chain that
 * touches it once can never be made exact again.
 *
 * <p>The division operations require a valid denominator. Deciding that a denominator is absent is
 * a <em>result</em>, not an arithmetic error, so callers resolve that state first — {@link #rate}
 * and {@link #unitCost} do exactly that and are the usual entry points.
 */
public final class MetricCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigInteger HUNDRED_EXACT = BigInteger.valueOf(100);

    /** Rates and percentage-point deltas: one decimal (contract 8.6). */
    private static final int RATE_SCALE = 1;

    /** Cost per merged PR: two decimals, with no sub-dollar rule (contract 8.6). */
    private static final int UNIT_COST_SCALE = 2;

    /** Spend totals: whole dollars, with the sub-dollar indicator instead (contract 8.6). */
    private static final int SPEND_SCALE = 0;

    private MetricCalculator() {}

    // --- Rates ----------------------------------------------------------------------------------

    /**
     * {@code numerator / denominator} as a percentage, rounded once. Requires a positive denominator.
     */
    public static BigDecimal percentage(BigInteger numerator, BigInteger denominator) {
        requirePositive(denominator, "denominator");
        return new BigDecimal(numerator).multiply(HUNDRED)
                .divide(new BigDecimal(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The change between two rates in percentage points, as the single rational
     * {@code 100 * (cn*bd - bn*cd) / (cd*bd)}. Never a subtraction of two rounded rates.
     */
    public static BigDecimal percentagePointChange(BigInteger currentNumerator,
            BigInteger currentDenominator, BigInteger baselineNumerator, BigInteger baselineDenominator) {
        requirePositive(currentDenominator, "currentDenominator");
        requirePositive(baselineDenominator, "baselineDenominator");
        BigInteger numerator = currentNumerator.multiply(baselineDenominator)
                .subtract(baselineNumerator.multiply(currentDenominator));
        BigInteger denominator = currentDenominator.multiply(baselineDenominator);
        return new BigDecimal(numerator).multiply(HUNDRED)
                .divide(new BigDecimal(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** Relative change against a previous value, rounded once. Requires a non-zero previous value. */
    public static BigDecimal relativeChangePercent(BigInteger current, BigInteger previous) {
        if (previous.signum() == 0) {
            throw new IllegalArgumentException("a relative change needs a non-zero previous value");
        }
        return new BigDecimal(current.subtract(previous)).multiply(HUNDRED)
                .divide(new BigDecimal(previous), RATE_SCALE, RoundingMode.HALF_UP);
    }

    // --- Money ----------------------------------------------------------------------------------

    /** Cents over merged PRs, expressed in dollars at two decimals. Requires a positive count. */
    public static BigDecimal unitCostUsd(BigInteger cents, BigInteger mergedPrs) {
        requirePositive(mergedPrs, "mergedPrs");
        return new BigDecimal(cents)
                .divide(new BigDecimal(mergedPrs.multiply(HUNDRED_EXACT)), UNIT_COST_SCALE,
                        RoundingMode.HALF_UP);
    }

    /** The change in unit cost, combined exactly before its single rounding. */
    public static BigDecimal unitCostChangeUsd(BigInteger currentCents, BigInteger currentMergedPrs,
            BigInteger baselineCents, BigInteger baselineMergedPrs) {
        requirePositive(currentMergedPrs, "currentMergedPrs");
        requirePositive(baselineMergedPrs, "baselineMergedPrs");
        BigInteger numerator = currentCents.multiply(baselineMergedPrs)
                .subtract(baselineCents.multiply(currentMergedPrs));
        BigInteger denominator =
                currentMergedPrs.multiply(baselineMergedPrs).multiply(HUNDRED_EXACT);
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), UNIT_COST_SCALE, RoundingMode.HALF_UP);
    }

    // --- Metric shapes --------------------------------------------------------------------------

    /**
     * A rate metric, resolving its own value state: an absent denominator has no value, a zero
     * numerator over a positive denominator is a real 0 (AC-01.6).
     */
    public static MetricResult rate(
            BigInteger numerator, BigInteger denominator, Explanation whenUndefined) {
        if (denominator.signum() == 0) {
            return MetricResult.noDenominator(whenUndefined);
        }
        DisplayValue display =
                DisplayValue.of(percentage(numerator, denominator), DisplayUnit.PERCENT);
        return numerator.signum() == 0
                ? MetricResult.zeroOutcome(display)
                : MetricResult.of(display);
    }

    /**
     * Cost per merged PR. A zero unit cost is {@code ok} and keeps monetary formatting: AC-01.6's
     * {@code zero_outcome} is about rate metrics, and $0.00 is not 0.0%.
     */
    public static MetricResult unitCost(
            BigInteger cents, BigInteger mergedPrs, Explanation whenUndefined) {
        if (mergedPrs.signum() == 0) {
            return MetricResult.noDenominator(whenUndefined);
        }
        return MetricResult.of(DisplayValue.of(unitCostUsd(cents, mergedPrs), DisplayUnit.USD));
    }

    /**
     * A spend total in whole dollars, flagged when a positive amount rounds away to zero so it can
     * read as "&lt;$1" rather than as no spend at all (contract 8.6).
     */
    public static MetricResult spendTotal(BigInteger cents) {
        BigDecimal dollars = new BigDecimal(cents)
                .divide(HUNDRED, SPEND_SCALE, RoundingMode.HALF_UP);
        boolean roundsToZero = cents.signum() > 0 && dollars.signum() == 0;
        return MetricResult.spend(DisplayValue.of(dollars, DisplayUnit.USD), roundsToZero);
    }

    public static MetricResult count(long value) {
        return MetricResult.of(DisplayValue.count(value));
    }

    // --- Comparison precedence ------------------------------------------------------------------

    /**
     * The first applicable reason in contract 1.2's precedence order, or {@code ok} when none
     * applies.
     *
     * <p>{@link ComparisonState} is declared in that order, so the winner is simply the earliest
     * member present. A caller therefore cannot change the outcome by offering its reasons in a
     * different sequence — the precedence lives in one place and is checked by one test.
     */
    public static ComparisonState firstApplicable(Set<ComparisonState> applicable) {
        for (ComparisonState state : ComparisonState.values()) {
            if (applicable.contains(state)) {
                return state;
            }
        }
        return ComparisonState.OK;
    }

    private static void requirePositive(BigInteger value, String name) {
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    name + " must be positive; resolve the undefined state before dividing");
        }
    }
}
