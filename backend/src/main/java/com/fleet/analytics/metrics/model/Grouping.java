package com.fleet.analytics.metrics.model;

/** The comparison table's partition (contract 5.4). Switching it changes rows, never a formula. */
public enum Grouping {
    TEAMS("teams"),
    REPOSITORIES("repositories");

    private final String wireName;

    Grouping(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Returns null for an unrecognised value; the caller decides what an invalid grouping means. */
    public static Grouping fromWireName(String value) {
        for (Grouping grouping : values()) {
            if (grouping.wireName.equals(value)) {
                return grouping;
            }
        }
        return null;
    }
}
