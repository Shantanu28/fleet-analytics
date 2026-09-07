package com.fleet.analytics.metrics.model;

/**
 * Terminal failure reasons grouped exactly as {@code 00-research.md} 6.4 fixes them: agent
 * ({@code agent_gave_up}, {@code tests_failed}), platform ({@code timeout}, {@code internal_error},
 * {@code rate_limited}) and policy ({@code sandbox_denied}).
 *
 * <p>The grouping is what earns a place on a failure-spike finding — it separates what the agent did
 * wrong from what the platform did wrong from what policy blocked. Per-reason detail is deliberately
 * not carried, and no standalone failure-reason chart exists (research 10.1, AC-06.10).
 *
 * <p>Counts are of failed <em>tasks</em>, not of runs or denial events. In the demo each task has one
 * run, so the three groups partition the window's failed tasks exactly; {@code sumsTo} exists so a
 * fixture that breaks that assumption is caught rather than silently double-counted.
 */
public record FailureReasonGroups(long agent, long platform, long policy) {

    public static final FailureReasonGroups NONE = new FailureReasonGroups(0, 0, 0);

    public FailureReasonGroups {
        if (agent < 0 || platform < 0 || policy < 0) {
            throw new IllegalArgumentException("failure-reason counts cannot be negative");
        }
    }

    public long total() {
        return agent + platform + policy;
    }

    public boolean sumsTo(long failedTasks) {
        return total() == failedTasks;
    }
}
