package com.fleet.analytics.metrics.model;

import java.math.BigInteger;

/**
 * Eligible code-change tasks that reached a terminal outcome in a window, by {@code terminal_at}
 * (contract 3.4). Cancelled tasks are deliberately absent: cancelling is a human decision, not a
 * platform failure, so it belongs to the funnel's side exits and to no rate's denominator.
 */
public record TaskCounts(long completed, long failed) {

    public static final TaskCounts NONE = new TaskCounts(0, 0);

    public TaskCounts {
        if (completed < 0 || failed < 0) {
            throw new IllegalArgumentException("task counts cannot be negative");
        }
    }

    /** The completion rate's denominator, and the AC-05.5 sort key. */
    public long terminal() {
        return completed + failed;
    }

    public BigInteger completedExact() {
        return BigInteger.valueOf(completed);
    }

    public BigInteger terminalExact() {
        return BigInteger.valueOf(terminal());
    }
}
