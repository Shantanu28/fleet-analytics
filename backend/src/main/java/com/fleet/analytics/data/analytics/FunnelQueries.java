package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.analytics.AnalyticsConditions.codeChangeTask;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.ownedBy;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.scopedTo;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.targetsDefaultBranch;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.within;
import static com.fleet.analytics.data.jooq.tables.PullRequest.PULL_REQUEST;
import static com.fleet.analytics.data.jooq.tables.Repository.REPOSITORY;
import static com.fleet.analytics.data.jooq.tables.Task.TASK;

import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.FunnelCounts;
import com.fleet.analytics.metrics.model.ScopeFilters;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record4;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * The cohort funnel (contract 4). Membership is fixed by {@code task.created_at} inside the selected
 * window; each stage is then observed for events strictly before {@code dataThrough}, <em>including
 * events after the window ends</em>.
 *
 * <p>That is why the funnel and the KPI row do not reconcile, by design: a PR merged after the
 * period but before the cutoff counts for its cohort and not for the period's merged-PR card, and a
 * PR merged inside the period whose task was created earlier counts for the card and not for the
 * cohort.
 *
 * <p>Two queries, two grains. The task stages aggregate over tasks alone; the PR stages join
 * pull requests and count distinct tasks. Splitting them keeps each aggregate at one grain and lets
 * the PR stages be skipped entirely when their source is not covered — so the caller never receives
 * a manufactured zero for something it could not observe.
 */
@Repository
public class FunnelQueries {

    private final DSLContext db;

    public FunnelQueries(DSLContext db) {
        this.db = db;
    }

    /**
     * @param observationCutoff {@code dataThrough}; stage events at or after it are not yet reported.
     * @param includePrStages false when the pull-request source is not covered for the observation
     *     window, leaving the two PR stages unknown rather than zero.
     */
    public FunnelCounts cohort(UUID organisationId, DateWindow cohortWindow,
            OffsetDateTime observationCutoff, ScopeFilters filters, boolean includePrStages) {
        Record4<Long, Long, Long, Long> stages = db.select(
                        DSL.count().cast(Long.class),
                        observedStatus("completed", observationCutoff),
                        observedStatus("failed", observationCutoff),
                        observedStatus("cancelled", observationCutoff))
                .from(TASK)
                .where(cohort(organisationId, cohortWindow, filters))
                .fetchOne();

        long started = stages.value1();
        long completed = stages.value2();
        long failed = stages.value3();
        long cancelled = stages.value4();
        // Anything not yet observed as terminal is residual, never a side exit (contract 4). This
        // subtraction is also what keeps the identity exact for a task whose outcome lands after
        // the cutoff: it has not reached an exit yet, so it is still in progress.
        long inProgress = started - completed - failed - cancelled;

        if (!includePrStages) {
            return FunnelCounts.withoutPrStages(started, completed, failed, cancelled, inProgress);
        }
        return new FunnelCounts(started, completed, failed, cancelled, inProgress,
                prStage(organisationId, cohortWindow, filters, observationCutoff, false),
                prStage(organisationId, cohortWindow, filters, observationCutoff, true));
    }

    /**
     * Distinct cohort tasks that reached a PR stage. Counting distinct tasks rather than PRs keeps
     * the stage a task count even if a task ever carried more than one PR.
     */
    private long prStage(UUID organisationId, DateWindow cohortWindow, ScopeFilters filters,
            OffsetDateTime observationCutoff, boolean merged) {
        Condition observed = merged
                ? PULL_REQUEST.TERMINAL_STATE.eq("merged")
                        .and(PULL_REQUEST.TERMINAL_AT.lessThan(observationCutoff))
                : PULL_REQUEST.OPENED_AT.lessThan(observationCutoff);

        Integer count = db.select(TASK.ID.countDistinct())
                .from(PULL_REQUEST)
                .join(TASK).on(TASK.ORG_ID.eq(PULL_REQUEST.ORG_ID))
                        .and(TASK.ID.eq(PULL_REQUEST.TASK_ID))
                .join(REPOSITORY).on(REPOSITORY.ORG_ID.eq(TASK.ORG_ID))
                        .and(REPOSITORY.ID.eq(TASK.REPO_ID))
                .where(cohort(organisationId, cohortWindow, filters))
                .and(targetsDefaultBranch(PULL_REQUEST.TARGET_BRANCH))
                .and(observed)
                .fetchOne(0, Integer.class);
        return count == null ? 0 : count;
    }

    private Condition cohort(UUID organisationId, DateWindow cohortWindow, ScopeFilters filters) {
        return ownedBy(organisationId)
                .and(codeChangeTask())
                .and(within(TASK.CREATED_AT, cohortWindow))
                .and(scopedTo(filters));
    }

    private Field<Long> observedStatus(String status, OffsetDateTime observationCutoff) {
        return DSL.count().filterWhere(TASK.TERMINAL_STATUS.eq(status)
                .and(TASK.TERMINAL_AT.lessThan(observationCutoff))).cast(Long.class);
    }
}
