package com.fleet.analytics.metrics.model;

/**
 * The single comparison a card displays, fixed per metric by AC-01.7. The contract may compute
 * others; only this one is ever shown, and no card substitutes a different kind when its own is
 * unavailable (AC-01.8).
 */
public enum ComparisonKind {
    /** Relative change against the previous period — merged PRs. */
    RELATIVE("relative"),
    /** Percentage-point change — the two rate metrics. */
    PERCENTAGE_POINTS("percentagePoints"),
    /** Absolute change in dollars — cost per merged PR. */
    ABSOLUTE_USD("absoluteUsd"),
    /** Absolute change in whole units — active seats. */
    ABSOLUTE_COUNT("absoluteCount");

    private final String wireName;

    ComparisonKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
