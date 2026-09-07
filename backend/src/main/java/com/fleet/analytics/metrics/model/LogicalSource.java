package com.fleet.analytics.metrics.model;

import java.util.Set;

/**
 * The eight logical sources whose per-day completeness is recorded in {@code source_day_coverage}
 * (04 Appendix A.5). A source that has no row for a day inside the published interval is
 * <em>not covered</em> for that day — unknown, never "no activity".
 *
 * <p>The dependency sets below are A.5's "required sources per metric family" table. They exist so
 * a missing source degrades exactly its dependants: a missing pull-request source leaves the
 * completion rate and the funnel's task-only stages fully available.
 */
public enum LogicalSource {
    TASKS("tasks"),
    RUNS("runs"),
    PULL_REQUESTS("pull_requests"),
    USAGE("usage"),
    SEATS("seats"),
    REPOSITORIES("repositories"),
    BUDGETS("budgets"),
    DENIALS("denials");

    /** Merged PRs and terminal merge rate: PR rows, task eligibility, repository default branch. */
    public static final Set<LogicalSource> PR_OUTCOMES = Set.of(PULL_REQUESTS, TASKS, REPOSITORIES);

    /** Cost per merged PR: the PR dependencies plus the metered ledger behind the numerator. */
    public static final Set<LogicalSource> UNIT_COST =
            Set.of(PULL_REQUESTS, TASKS, REPOSITORIES, USAGE, RUNS);

    /** Task completion rate, and the funnel's task-only stages. Tasks alone — nothing more. */
    public static final Set<LogicalSource> TASK_OUTCOMES = Set.of(TASKS);

    /** The funnel's two PR stages, which become unavailable rather than dropping to zero. */
    public static final Set<LogicalSource> FUNNEL_PR_STAGES = Set.of(TASKS, PULL_REQUESTS, REPOSITORIES);

    /** Spend and the spend trend: usage joined through runs to tasks for attribution. */
    public static final Set<LogicalSource> SPEND = Set.of(USAGE, RUNS, TASKS);

    /** Active seats: task activity, and the licensed population it must belong to. */
    public static final Set<LogicalSource> SEAT_ACTIVITY = Set.of(TASKS, SEATS);

    private final String wireName;

    LogicalSource(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Returns null for an unrecognised stored value rather than failing the whole request. */
    public static LogicalSource fromWireName(String value) {
        for (LogicalSource source : values()) {
            if (source.wireName.equals(value)) {
                return source;
            }
        }
        return null;
    }
}
