package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.jooq.tables.Repository.REPOSITORY;
import static com.fleet.analytics.data.jooq.tables.Task.TASK;

import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.ScopeFilters;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * The predicates every analytics query repeats, written once.
 *
 * <p>They are here because each one encodes a contract rule that must not drift between queries: if
 * "eligible code-change task" meant one set of types in the completion query and another in the
 * spend query, the two numbers would disagree and nothing would say why. Tenant scope is likewise
 * always the leading predicate, applied before any aggregation.
 */
final class AnalyticsConditions {

    /** Contract 1.4. Non-code types are excluded from outcome metrics and the unit-cost numerator. */
    static final List<String> CODE_CHANGE_TASK_TYPES =
            List.of("bugfix", "feature", "refactor", "tests", "dependency_update");

    private AnalyticsConditions() {}

    /** Tenant scope from the verified principal, never a client parameter. */
    static Condition ownedBy(UUID organisationId) {
        return TASK.ORG_ID.eq(organisationId);
    }

    static Condition codeChangeTask() {
        return TASK.TASK_TYPE.in(CODE_CHANGE_TASK_TYPES);
    }

    /**
     * Half-open {@code [startInclusive, endExclusive)} on any timestamp column. An event at the
     * exclusive end belongs to the next window, never to both.
     */
    static Condition within(Field<OffsetDateTime> timestamp, DateWindow window) {
        return timestamp.greaterOrEqual(window.startInclusive())
                .and(timestamp.lessThan(window.endExclusive()));
    }

    /**
     * Team and repository filters combined by intersection (contract 6.0). Attribution is the task's
     * own creation-time team and repository — never the owner's current team, so a reorganisation
     * cannot restate history.
     */
    static Condition scopedTo(ScopeFilters filters) {
        Condition condition = DSL.noCondition();
        if (filters.hasTeam()) {
            condition = condition.and(TASK.TEAM_ID.eq(filters.teamId()));
        }
        if (filters.hasRepository()) {
            condition = condition.and(TASK.REPO_ID.eq(filters.repositoryId()));
        }
        return condition;
    }

    /**
     * Contract 1.4: "merged" means merged to the repository's designated default branch, so every PR
     * metric carries this condition. A PR targeting any other branch is counted by no PR metric.
     */
    static Condition targetsDefaultBranch(Field<String> targetBranch) {
        return targetBranch.eq(REPOSITORY.DEFAULT_BRANCH);
    }

    /** Buckets a timestamp into the complete UTC day it falls in — the grain of both trends. */
    static Field<LocalDate> utcDay(Field<OffsetDateTime> timestamp) {
        return DSL.field("cast({0} at time zone 'UTC' as date)", LocalDate.class, timestamp);
    }

    /** The column a comparison-table row groups by, in the task's own attribution. */
    static Field<UUID> groupingColumn(Grouping grouping) {
        return switch (grouping) {
            case TEAMS -> TASK.TEAM_ID;
            case REPOSITORIES -> TASK.REPO_ID;
        };
    }
}
