package com.fleet.analytics.metrics.model;

/**
 * A metric's value state (contract 1.2). {@code zero_outcome} is a computed result, not an absence:
 * a numerator of 0 over a positive denominator is a real 0, and it must never read like missing data.
 */
public enum ValueState {
    /** Computed. */
    OK("ok"),
    /** Computed and equal to 0 because the numerator is 0 while the denominator is positive. */
    ZERO_OUTCOME("zero_outcome"),
    /** The denominator is 0, so the value does not exist. Never 0, never infinity. */
    NO_DENOMINATOR("no_denominator"),
    /** Not defined under the active filters (contract 5.3). */
    UNAVAILABLE_FOR_SCOPE("unavailable_for_scope"),
    /** A required source is absent for a covered period. Never coerced to 0 (contract 1.5). */
    MISSING_DATA("missing_data");

    private final String wireName;

    ValueState(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * True for the two states that carry a display. The OpenAPI schema enforces the same split:
     * a defined state carries a display, any other carries reasonCode and reason instead.
     */
    public boolean isDefined() {
        return this == OK || this == ZERO_OUTCOME;
    }
}
