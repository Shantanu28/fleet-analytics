package com.fleet.analytics.web.dashboard;

import java.math.BigInteger;

/**
 * Checked narrowing for the exact counts and cent totals served as JSON numbers.
 *
 * <p>JSON numbers are IEEE-754 doubles in most clients, so integers above 2^53-1 lose precision
 * silently on parse. Every value here is an exact population or ledger total, and a spend figure
 * that arrives slightly wrong is worse than one that does not arrive: nothing downstream can detect
 * it.
 *
 * <p>Exceeding the bound is an internal invariant failure, not a caller mistake — it means an
 * aggregate grew past what the contract's field type can carry. It surfaces as a sanitised 500
 * rather than a zero, a truncation or a field that quietly changes type.
 */
public final class JsonSafeInteger {

    /** {@code Number.MAX_SAFE_INTEGER}: the largest integer a double represents exactly. */
    static final long MAX_SAFE = 9_007_199_254_740_991L;

    private static final BigInteger MAX_SAFE_EXACT = BigInteger.valueOf(MAX_SAFE);
    private static final BigInteger MIN_SAFE_EXACT = MAX_SAFE_EXACT.negate();

    private JsonSafeInteger() {}

    public static long of(BigInteger value) {
        if (value.compareTo(MAX_SAFE_EXACT) > 0 || value.compareTo(MIN_SAFE_EXACT) < 0) {
            throw new UnsafeNumericRangeException(value.toString());
        }
        return value.longValueExact();
    }

    public static long of(long value) {
        if (value > MAX_SAFE || value < -MAX_SAFE) {
            throw new UnsafeNumericRangeException(Long.toString(value));
        }
        return value;
    }

    /** Kept internal: the offending magnitude is a diagnostic, never part of a public response. */
    public static final class UnsafeNumericRangeException extends RuntimeException {
        UnsafeNumericRangeException(String value) {
            super("a response value exceeds the JSON-safe integer range: " + value);
        }
    }
}
