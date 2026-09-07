package com.fleet.analytics.metrics.model;

/**
 * The four approved attention rules, declared in the contract 6.5 ranking order so rule precedence
 * is a property of the enum rather than a comparator that could disagree with it.
 */
public enum RuleType {
    BUDGET_RISK("budget_risk"),
    TASK_FAILURE_SPIKE("task_failure_spike"),
    MERGE_RATE_DECLINE("merge_rate_decline"),
    NETWORK_POLICY_FRICTION("network_policy_friction");

    private final String wireName;

    RuleType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
