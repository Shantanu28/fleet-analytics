package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * One (rule, scope) pair that could not reach a verdict, with the contract's reason.
 *
 * <p>A limit is not a finding and must never be counted or styled as one (AC-06.13): it consumes no
 * part of the three-finding cap. It exists so the panel can say what it could not evaluate instead
 * of implying that silence means health.
 */
public record EvaluationLimit(
        RuleType ruleType, RuleScope scope, EvaluationState state, Explanation explanation) {

    public EvaluationLimit {
        Objects.requireNonNull(ruleType, "ruleType");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(explanation, "explanation");
    }

    /** Rule order, then scope type, then scope id — deterministic across reloads. */
    public String orderingKey() {
        return ruleType.ordinal() + ":" + scope.orderingKey();
    }
}
