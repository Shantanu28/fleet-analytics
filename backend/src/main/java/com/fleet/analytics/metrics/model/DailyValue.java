package com.fleet.analytics.metrics.model;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One complete UTC day of a trend series, carrying an exact integer — a count or a cent total, never
 * a rounded figure. No rounding decision is delegated to the client (contract 5.1, 8.6).
 */
public record DailyValue(LocalDate date, BigInteger amount) {

    public DailyValue {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(amount, "amount");
    }

    public static DailyValue zero(LocalDate date) {
        return new DailyValue(date, BigInteger.ZERO);
    }
}
