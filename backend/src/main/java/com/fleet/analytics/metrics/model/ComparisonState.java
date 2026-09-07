package com.fleet.analytics.metrics.model;

/**
 * A comparison's state, resolved independently of the value state (contract 1.2). A raw value may
 * be present while its comparison is unavailable — that is the normal case at low volume.
 *
 * <p>Declaration order is the contract's precedence order, so the first matching reason wins and
 * the list cannot drift from the specification.
 */
public enum ComparisonState {
    /** Not defined under the active filters. */
    UNAVAILABLE_FOR_SCOPE("unavailable_for_scope"),
    /** A required source for the current period is absent, or that period is not fully covered. */
    MISSING_DATA("missing_data"),
    /** The baseline window is not fully covered, or a baseline source is missing. Nothing else. */
    NO_BASELINE("no_baseline"),
    /** The current or baseline value is mathematically undefined. */
    NO_DENOMINATOR("no_denominator"),
    /** One or both populations fall below the metric's gate (contract 5.4). */
    INSUFFICIENT_SAMPLE("insufficient_sample"),
    /** A relative change was requested and the previous value is 0 or null. */
    UNDEFINED_RELATIVE("undefined_relative"),
    /** Delta computed. */
    OK("ok");

    private final String wireName;

    ComparisonState(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
