package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.analytics.AnalyticsConditions.codeChangeTask;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.groupingColumn;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.ownedBy;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.scopedTo;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.targetsDefaultBranch;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.utcDay;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.within;
import static com.fleet.analytics.data.jooq.tables.PullRequest.PULL_REQUEST;
import static com.fleet.analytics.data.jooq.tables.Repository.REPOSITORY;
import static com.fleet.analytics.data.jooq.tables.Task.TASK;

import com.fleet.analytics.metrics.model.DailyValue;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.ScopeFilters;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Pull-request outcome populations, placed in a window by the PR's own terminal transition — its
 * merge or close instant, not its task's terminal timestamp (contract 2, 3.2).
 *
 * <p>Two eligibility conditions travel with every PR metric. The task must be an eligible
 * code-change task, and the PR must target its repository's designated default branch, because
 * "merged" means merged to the default branch. A PR targeting any other branch is counted by no PR
 * metric at all.
 *
 * <p>The joins are PR to task to repository — each many-to-one going up, so no row multiplies. This
 * query deliberately never touches usage or runs: those are independent one-to-many branches off the
 * same task, and joining them here would fan a PR out across its task's usage records.
 */
@Repository
public class PullRequestQueries {

    private final DSLContext db;

    public PullRequestQueries(DSLContext db) {
        this.db = db;
    }

    /**
     * Merged and closed-unmerged counts. {@code merged} is also the merged-PR card's value: a PR
     * merged in the window is exactly a PR whose terminal transition in the window was a merge, so
     * the two cards cannot disagree (contract 3.1, 3.2).
     */
    public PrCounts terminalTotals(UUID organisationId, DateWindow window, ScopeFilters filters) {
        return db.select(stateCount("merged"), stateCount("closed_unmerged"))
                .from(eligiblePullRequests())
                .where(terminalInWindow(organisationId, window, filters))
                .fetchOne(record -> new PrCounts(record.value1(), record.value2()));
    }

    public Map<UUID, PrCounts> terminalByScope(UUID organisationId, DateWindow window,
            ScopeFilters filters, Grouping grouping) {
        Field<UUID> scope = groupingColumn(grouping);
        Map<UUID, PrCounts> counts = new HashMap<>();
        db.select(scope, stateCount("merged"), stateCount("closed_unmerged"))
                .from(eligiblePullRequests())
                .where(terminalInWindow(organisationId, window, filters))
                .groupBy(scope)
                .fetch()
                .forEach(record -> counts.put(
                        record.value1(), new PrCounts(record.value2(), record.value3())));
        return counts;
    }

    /**
     * Merges per complete UTC day. Sparse by nature — days with no merges are absent here and are
     * zero-filled in Java, where coverage is known and an uncovered day can be told from an empty one.
     */
    public List<DailyValue> mergedPerDay(
            UUID organisationId, DateWindow window, ScopeFilters filters) {
        Field<LocalDate> day = utcDay(PULL_REQUEST.TERMINAL_AT);
        return db.select(day, DSL.count())
                .from(eligiblePullRequests())
                .where(terminalInWindow(organisationId, window, filters))
                .and(PULL_REQUEST.TERMINAL_STATE.eq("merged"))
                .groupBy(day)
                .fetch(record -> new DailyValue(
                        record.value1(), BigInteger.valueOf(record.value2())));
    }

    /** Composite joins carry {@code org_id}, so a PR can never reach another tenant's task. */
    private org.jooq.Table<?> eligiblePullRequests() {
        return PULL_REQUEST
                .join(TASK).on(TASK.ORG_ID.eq(PULL_REQUEST.ORG_ID))
                        .and(TASK.ID.eq(PULL_REQUEST.TASK_ID))
                .join(REPOSITORY).on(REPOSITORY.ORG_ID.eq(TASK.ORG_ID))
                        .and(REPOSITORY.ID.eq(TASK.REPO_ID));
    }

    private Condition terminalInWindow(
            UUID organisationId, DateWindow window, ScopeFilters filters) {
        return ownedBy(organisationId)
                .and(codeChangeTask())
                .and(targetsDefaultBranch(PULL_REQUEST.TARGET_BRANCH))
                .and(PULL_REQUEST.TERMINAL_STATE.isNotNull())
                .and(within(PULL_REQUEST.TERMINAL_AT, window))
                .and(scopedTo(filters));
    }

    private Field<Long> stateCount(String terminalState) {
        return DSL.count().filterWhere(PULL_REQUEST.TERMINAL_STATE.eq(terminalState))
                .cast(Long.class);
    }
}
