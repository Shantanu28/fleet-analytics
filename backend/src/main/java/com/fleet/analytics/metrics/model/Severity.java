package com.fleet.analytics.metrics.model;

/**
 * Finding severity. Declared highest first, matching contract 6.5's first ranking key. Only budget
 * risk can reach {@code HIGH}; the other three rules are always {@code MEDIUM}.
 */
public enum Severity {
    HIGH("HIGH"),
    MEDIUM("MEDIUM");

    private final String wireName;

    Severity(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
