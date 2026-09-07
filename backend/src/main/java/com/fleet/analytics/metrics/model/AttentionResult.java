package com.fleet.analytics.metrics.model;

import java.util.List;
import java.util.Objects;

/**
 * The needs-attention panel's data: at most three ranked findings, how many (rule, scope) pairs
 * reached a verdict, and every pair that did not.
 *
 * <p>These three together select exactly one of AC-06.3's five panel states without the client
 * inferring anything. The completed count is the load-bearing one: it is what distinguishes "we
 * looked everywhere and found nothing" from "we could not look", and it is counted from evaluations
 * rather than from findings, domains or displayed rows.
 */
public record AttentionResult(
        List<FindingCandidate> findings, int evaluationsCompleted, List<EvaluationLimit> limits) {

    /** AC-06.1: at most three findings are displayed, however many fired. */
    public static final int MAX_DISPLAYED_FINDINGS = 3;

    public AttentionResult {
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(limits, "limits");
        findings = List.copyOf(findings);
        limits = List.copyOf(limits);
        if (findings.size() > MAX_DISPLAYED_FINDINGS) {
            throw new IllegalArgumentException(
                    "at most " + MAX_DISPLAYED_FINDINGS + " findings may be displayed (AC-06.1)");
        }
        if (evaluationsCompleted < 0) {
            throw new IllegalArgumentException("evaluationsCompleted cannot be negative");
        }
    }
}
