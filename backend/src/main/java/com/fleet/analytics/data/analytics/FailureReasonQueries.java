package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.analytics.AnalyticsConditions.codeChangeTask;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.groupingColumn;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.ownedBy;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.scopedTo;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.within;
import static com.fleet.analytics.data.jooq.tables.Run.RUN;
import static com.fleet.analytics.data.jooq.tables.Task.TASK;

import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.FailureReasonGroups;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.ScopeFilters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record4;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Failed tasks grouped by reason family for a failure-spike finding's evidence line
 * ({@code 00-research.md} 6.4).
 *
 * <p><b>Tasks, not runs.</b> The failure rate counts tasks, so its evidence must too. A task that
 * failed twice for different reasons would otherwise appear in two groups and the evidence would
 * overstate the failures it is explaining. {@code DISTINCT ON} attributes each task to exactly one
 * run — the highest-numbered failed attempt, which is the outcome the task actually ended on — so
 * double counting is impossible by construction rather than by convention.
 *
 * <p>A consequence worth stating: a task marked failed with no failed run of its own is attributable
 * to no group, so the three groups can sum to <em>less</em> than the window's failed-task count.
 * That is truthful — some failures could not be attributed — and is deliberately preferred to
 * inventing a group for them. {@link FailureReasonGroups#sumsTo} is how a caller checks.
 */
@Repository
public class FailureReasonQueries {

    /** {@code 00-research.md} 6.4 fixes these three families; the schema constrains the values. */
    private static final List<String> AGENT = List.of("agent_gave_up", "tests_failed");
    private static final List<String> PLATFORM = List.of("timeout", "internal_error", "rate_limited");
    private static final List<String> POLICY = List.of("sandbox_denied");

    private final DSLContext db;

    public FailureReasonQueries(DSLContext db) {
        this.db = db;
    }

    public Map<UUID, FailureReasonGroups> byScope(UUID organisationId, DateWindow window,
            ScopeFilters filters, Grouping grouping) {
        Field<UUID> scope = groupingColumn(grouping);
        Table<Record4<UUID, UUID, UUID, String>> attributed =
                DSL.selectDistinct(TASK.ID, TASK.TEAM_ID, TASK.REPO_ID, RUN.FAILURE_REASON)
                        .on(TASK.ID)
                        .from(TASK)
                        .join(RUN).on(RUN.ORG_ID.eq(TASK.ORG_ID)).and(RUN.TASK_ID.eq(TASK.ID))
                        .where(ownedBy(organisationId))
                        .and(codeChangeTask())
                        .and(TASK.TERMINAL_STATUS.eq("failed"))
                        .and(within(TASK.TERMINAL_AT, window))
                        .and(scopedTo(filters))
                        .and(RUN.RUN_STATUS.eq("failed"))
                        .and(RUN.FAILURE_REASON.isNotNull())
                        // The attempt the task ended on, so retries do not each contribute a reason.
                        .orderBy(TASK.ID, RUN.ATTEMPT_NO.desc())
                        .asTable("attributed");

        Field<UUID> scopeColumn = attributed.field(scope.getName(), UUID.class);
        Field<String> reason = attributed.field(RUN.FAILURE_REASON.getName(), String.class);

        Map<UUID, FailureReasonGroups> groups = new HashMap<>();
        db.select(scopeColumn,
                        DSL.count().filterWhere(reason.in(AGENT)).cast(Long.class),
                        DSL.count().filterWhere(reason.in(PLATFORM)).cast(Long.class),
                        DSL.count().filterWhere(reason.in(POLICY)).cast(Long.class))
                .from(attributed)
                .groupBy(scopeColumn)
                .fetch()
                .forEach(record -> groups.put(record.value1(),
                        new FailureReasonGroups(record.value2(), record.value3(), record.value4())));
        return groups;
    }
}
