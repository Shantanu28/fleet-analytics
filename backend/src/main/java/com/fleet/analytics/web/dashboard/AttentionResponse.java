package com.fleet.analytics.web.dashboard;

import java.util.List;

/**
 * The needs-attention panel. The three fields together select exactly one of AC-06.3's five states
 * without the client inferring anything — which is why {@code evaluationsCompleted} is served
 * explicitly rather than left to be guessed from the other two.
 */
public record AttentionResponse(
        List<FindingResponse> findings,
        int evaluationsCompleted,
        List<EvaluationLimitResponse> limits) {

    public AttentionResponse {
        findings = List.copyOf(findings);
        limits = List.copyOf(limits);
    }
}
