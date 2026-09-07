package com.fleet.analytics.metrics.model;

import java.util.Objects;

/** The two P0 trends. They are independent: one may be unavailable while the other renders. */
public record TrendResult(TrendSeries mergedPrsPerDay, TrendSeries spendPerDay) {

    public TrendResult {
        Objects.requireNonNull(mergedPrsPerDay, "mergedPrsPerDay");
        Objects.requireNonNull(spendPerDay, "spendPerDay");
    }
}
