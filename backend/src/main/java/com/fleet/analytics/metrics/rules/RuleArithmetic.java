package com.fleet.analytics.metrics.rules;

import com.fleet.analytics.metrics.model.ExactMagnitude;
import java.math.BigInteger;

/**
 * The exact comparisons the outcome rules trigger on.
 *
 * <p>Contract 1.1 requires rule triggers to be evaluated on the underlying counts, not on binary
 * floating-point percentage points, and contract 8.5 case M2 is the worked reason: {@code 29/50}
 * minus {@code 8/16} is exactly 8 percentage points, yet in IEEE-754 doubles it evaluates to
 * {@code 7.999999999999993} and a naive {@code >= 8} test fails to fire. A rule that silently
 * declines to trigger on an exact boundary is worse than one that errors, because nothing reports it.
 */
final class RuleArithmetic {

    private static final BigInteger HUNDRED = BigInteger.valueOf(100);

    private RuleArithmetic() {}

    /**
     * {@code a/b - c/d} as one rational over the common denominator {@code b*d}. Both denominators
     * must be positive; the caller resolves an absent population before reaching here.
     */
    static ExactMagnitude ratioDifference(
            BigInteger a, BigInteger b, BigInteger c, BigInteger d) {
        return ExactMagnitude.of(a.multiply(d).subtract(c.multiply(b)), b.multiply(d));
    }

    /**
     * {@code difference >= points/100}, cross-multiplied. The denominator is positive by
     * construction, so the inequality direction is stable and no division occurs.
     */
    static boolean atLeastPercentagePoints(ExactMagnitude difference, int points) {
        return difference.numerator().multiply(HUNDRED)
                .compareTo(BigInteger.valueOf(points).multiply(difference.denominator())) >= 0;
    }
}
