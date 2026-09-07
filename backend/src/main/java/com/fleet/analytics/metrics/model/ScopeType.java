package com.fleet.analytics.metrics.model;

/**
 * What a rule was evaluated over. Declared broadest first, which is also the order limits and
 * findings tie-break in (contract 6.5).
 */
public enum ScopeType {
    ORGANISATION("organisation"),
    TEAM("team"),
    REPOSITORY("repository");

    private final String wireName;

    ScopeType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
