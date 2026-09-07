package com.fleet.analytics.metrics.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A finding before ranking, capping and public presentation.
 *
 * <p>Internal only. It carries the {@link FindingIdentity}, which can contain a denied domain, so it
 * is never serialized: the presenter builds a separate public object rather than deleting fields
 * from this one. Building the safe shape positively is what makes a leak a compile error instead of
 * a forgotten line.
 *
 * <p>{@code evaluationPeriod} is the rule's own window — budget findings carry their evaluated month
 * rather than the selected dashboard range (contract 6.1).
 */
public record FindingCandidate(
        FindingIdentity identity,
        RuleScope scope,
        Severity severity,
        ExactMagnitude magnitude,
        DisplayValue displayMagnitude,
        RuleEvidence evidence,
        LocalDate evaluationFrom,
        LocalDate evaluationTo) {

    public FindingCandidate {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(magnitude, "magnitude");
        Objects.requireNonNull(displayMagnitude, "displayMagnitude");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(evaluationFrom, "evaluationFrom");
        Objects.requireNonNull(evaluationTo, "evaluationTo");
        if (evaluationTo.isBefore(evaluationFrom)) {
            throw new IllegalArgumentException("an evaluation period cannot end before it starts");
        }
        if (severity == Severity.HIGH && identity.ruleType() != RuleType.BUDGET_RISK) {
            throw new IllegalArgumentException(
                    "only budget risk can reach HIGH (contract 6.2 to 6.4)");
        }
    }

    public RuleType ruleType() {
        return identity.ruleType();
    }
}
