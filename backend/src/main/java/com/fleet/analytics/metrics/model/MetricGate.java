package com.fleet.analytics.metrics.model;

/**
 * The three sample gates of contract 5.4. These are product heuristics, not tests of statistical
 * significance — reusing the number 15 for two of them gives it no statistical meaning.
 *
 * <p>A gate suppresses a <em>comparison</em> and never a value: a mathematically defined number
 * stays on screen no matter how small the sample behind it. Gates also apply per metric, never to a
 * whole row, so one column may compare while its neighbour explains why it cannot.
 *
 * <p>{@code populationName} exists so an explanation can say what was actually counted. "Needs 20
 * completed or failed code-change tasks" tells a reader what to change; "not enough data" does not.
 */
public enum MetricGate {
    TERMINAL_TASKS(20, "gate_terminal_tasks_20", "completed or failed code-change tasks"),
    TERMINAL_PRS(15, "gate_terminal_prs_15", "terminal PRs"),
    MERGED_PRS(15, "gate_merged_prs_15", "merged PRs");

    private final int threshold;
    private final String reasonCode;
    private final String populationName;

    MetricGate(int threshold, String reasonCode, String populationName) {
        this.threshold = threshold;
        this.reasonCode = reasonCode;
        this.populationName = populationName;
    }

    /** Inclusive: contract 5.4 gates are "at least", so exactly the threshold qualifies. */
    public boolean isMetBy(long population) {
        return population >= threshold;
    }

    public int threshold() {
        return threshold;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String populationName() {
        return populationName;
    }
}
