package com.fleet.analytics.metrics.model;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A finding's magnitude kept as an exact rational, never as a rounded display value.
 *
 * <p>Ranking compares magnitudes, and comparing rounded strings would let two genuinely different
 * overruns tie — or, worse, order them by text. Comparison is by cross-multiplication
 * ({@code a/b} against {@code c/d} is {@code a*d} against {@code c*b}), which is exact for any
 * magnitudes and cannot drift the way a shared common denominator would.
 */
public record ExactMagnitude(BigInteger numerator, BigInteger denominator)
        implements Comparable<ExactMagnitude> {

    public ExactMagnitude {
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        if (denominator.signum() <= 0) {
            throw new IllegalArgumentException("a magnitude needs a positive denominator");
        }
    }

    public static ExactMagnitude ofWhole(long value) {
        return new ExactMagnitude(BigInteger.valueOf(value), BigInteger.ONE);
    }

    public static ExactMagnitude of(BigInteger numerator, BigInteger denominator) {
        return new ExactMagnitude(numerator, denominator);
    }

    /** Cross-multiplied, so no division and no rounding ever enters an ordering decision. */
    @Override
    public int compareTo(ExactMagnitude other) {
        return numerator.multiply(other.denominator)
                .compareTo(other.numerator.multiply(denominator));
    }
}
