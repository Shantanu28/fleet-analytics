package com.fleet.analytics.metrics.model;

/** What a trend series' exact integers mean. Cents, never dollars: no rounding is delegated. */
public enum TrendUnit {
    COUNT("count"),
    USD_CENTS("usdCents");

    private final String wireName;

    TrendUnit(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
