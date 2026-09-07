package com.fleet.analytics.metrics.model;

/** What a display value's number means. The client formats it; it never reinterprets it. */
public enum DisplayUnit {
    COUNT("count"),
    PERCENT("percent"),
    PERCENTAGE_POINTS("percentagePoints"),
    USD("usd");

    private final String wireName;

    DisplayUnit(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
