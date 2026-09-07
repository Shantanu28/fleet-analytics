package com.fleet.analytics.web.dashboard;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One finding as served. Built positively from an internal candidate rather than derived by removing
 * fields from it — the internal {@code FindingIdentity} carries a denied domain, and a shape that
 * starts safe and adds what is permitted makes a leak a compile error instead of a forgotten line.
 *
 * <p>{@code id} is the opaque HMAC identifier: stable across restarts, different per organisation,
 * granting no access and carrying no domain.
 *
 * <p>Nulls are written, not omitted. Every field here is required by the contract, and
 * {@code scopeId} is required <em>and</em> nullable — an organisation-scoped finding has no row to
 * point at, so the key must be present carrying null. Omitting it would fail schema validation for
 * exactly the findings that matter most.
 */
public record FindingResponse(
        String id,
        String ruleType,
        String severity,
        String scopeType,
        UUID scopeId,
        String scopeName,
        FindingEvidenceResponse evidence,
        DisplayResponse magnitude,
        EvaluationPeriodResponse evaluationPeriod,
        FindingLinkResponse link) {

    /** Budget findings carry their own month here, never the dashboard range (contract 6.1). */
    public record EvaluationPeriodResponse(LocalDate from, LocalDate to) {}
}
