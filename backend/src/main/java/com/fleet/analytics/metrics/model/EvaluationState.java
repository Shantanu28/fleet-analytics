package com.fleet.analytics.metrics.model;

/**
 * Why a (rule, scope) pair produced no verdict. None of these renders as healthy: contract 6 is
 * explicit that a rule which cannot be evaluated is never reported as "all clear".
 */
public enum EvaluationState {
    /** A gate was unmet, or no configuration exists to evaluate against. */
    NOT_EVALUATED("not_evaluated"),
    /** Fewer than three complete days of the budget month have elapsed (contract 6.1). */
    INSUFFICIENT_HISTORY("insufficient_history"),
    /** A budget of zero or less: a configuration error, not a zero allowance (contract 6.1). */
    INVALID_BUDGET_CONFIGURATION("invalid_budget_configuration"),
    /** Not defined under the active filters — budget risk under a repository filter (contract 5.3). */
    UNAVAILABLE_FOR_SCOPE("unavailable_for_scope");

    private final String wireName;

    EvaluationState(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
