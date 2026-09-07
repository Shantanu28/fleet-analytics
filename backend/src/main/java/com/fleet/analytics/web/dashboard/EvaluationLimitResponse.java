package com.fleet.analytics.web.dashboard;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A (rule, scope) pair that reached no verdict, with the contract's reason.
 *
 * <p>Rendered compactly and never as a finding: it consumes no part of the three-finding cap
 * (AC-06.13). It exists so the panel can say what it could not evaluate rather than letting silence
 * read as health.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationLimitResponse(
        String ruleType,
        String scopeType,
        UUID scopeId,
        String scopeName,
        String state,
        String reasonCode,
        String reason) {}
