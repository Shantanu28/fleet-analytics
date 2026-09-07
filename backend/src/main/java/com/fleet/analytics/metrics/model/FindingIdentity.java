package com.fleet.analytics.metrics.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Contract 6.5's internal identity: {@code rule : scope type : scope id : evaluation period
 * [ : normalised domain ]}.
 *
 * <p>Deliberately excludes evidence, severity, magnitude and rank. Those change as data changes,
 * and a finding recomputed for a narrower scope must keep the same identity so it can be recognised
 * as the same finding rather than appearing to be a new one.
 *
 * <p>{@code evaluationPeriodKey} is the rule's own period: budget findings carry their evaluated
 * month, never the selected dashboard range.
 *
 * <p>This type is internal. It carries a denied domain for network-policy findings, so it must never
 * be serialized or logged — the public identifier is derived from it by an HMAC.
 */
public record FindingIdentity(
        RuleType ruleType,
        ScopeType scopeType,
        UUID scopeId,
        String evaluationPeriodKey,
        String normalisedDomain) {

    public FindingIdentity {
        Objects.requireNonNull(ruleType, "ruleType");
        Objects.requireNonNull(scopeType, "scopeType");
        Objects.requireNonNull(evaluationPeriodKey, "evaluationPeriodKey");
        if ((scopeType == ScopeType.ORGANISATION) != (scopeId == null)) {
            throw new IllegalArgumentException(
                    "the organisation scope has no id, and every other scope must have one");
        }
    }

    public static FindingIdentity of(RuleType ruleType, RuleScope scope, String evaluationPeriodKey) {
        return new FindingIdentity(
                ruleType, scope.scopeType(), scope.scopeId(), evaluationPeriodKey, null);
    }

    public static FindingIdentity ofDomain(RuleType ruleType, RuleScope scope,
            String evaluationPeriodKey, String normalisedDomain) {
        return new FindingIdentity(ruleType, scope.scopeType(), scope.scopeId(), evaluationPeriodKey,
                Objects.requireNonNull(normalisedDomain, "normalisedDomain"));
    }

    /**
     * The final, total tie-break of contract 6.5. Not for display or logging: it can contain a
     * denied domain.
     */
    public String orderingKey() {
        // The canonical wire names, so this really is contract 6.5's
        // `rule_id : scope_type : scope_id : evaluation_period_key [ : normalised_domain ]` and
        // agrees with the scope-type key that precedes it. Enum names would order differently from
        // the strings the contract actually specifies.
        return ruleType.wireName() + ':' + scopeType.wireName()
                + ':' + (scopeId == null ? "" : scopeId)
                + ':' + evaluationPeriodKey + ':' + (normalisedDomain == null ? "" : normalisedDomain);
    }
}
