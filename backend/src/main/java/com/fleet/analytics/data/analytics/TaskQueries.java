package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.analytics.AnalyticsConditions.codeChangeTask;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.groupingColumn;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.ownedBy;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.scopedTo;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.within;
import static com.fleet.analytics.data.jooq.tables.Task.TASK;

import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.TaskCounts;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Task outcome populations, placed in a window by {@code task.terminal_at} (contract 2).
 *
 * <p>Task grain only: no join to runs, PRs or usage. Task status is the task's own column and is
 * never read from a run — the schema permits retries, so a task with two runs has one status and
 * two run statuses, and conflating them would count that task twice.
 *
 * <p>Cancelled and in-progress tasks are excluded from these counts by contract 3.4; they remain
 * visible as funnel side exits and residuals.
 */
@Repository
public class TaskQueries {

    private final DSLContext db;

    public TaskQueries(DSLContext db) {
        this.db = db;
    }

    public TaskCounts totals(UUID organisationId, DateWindow window, ScopeFilters filters) {
        return db.select(completedCount(), failedCount())
                .from(TASK)
                .where(terminalInWindow(organisationId, window, filters))
                .fetchOne(record -> new TaskCounts(record.value1(), record.value2()));
    }

    /** Grouped by the task's creation-time attribution — the same population, partitioned. */
    public Map<UUID, TaskCounts> byScope(UUID organisationId, DateWindow window,
            ScopeFilters filters, Grouping grouping) {
        Field<UUID> scope = groupingColumn(grouping);
        Map<UUID, TaskCounts> counts = new HashMap<>();
        db.select(scope, completedCount(), failedCount())
                .from(TASK)
                .where(terminalInWindow(organisationId, window, filters))
                .groupBy(scope)
                .fetch()
                .forEach(record -> counts.put(
                        record.value1(), new TaskCounts(record.value2(), record.value3())));
        return counts;
    }

    private Condition terminalInWindow(
            UUID organisationId, DateWindow window, ScopeFilters filters) {
        return ownedBy(organisationId)
                .and(codeChangeTask())
                .and(TASK.TERMINAL_STATUS.in("completed", "failed"))
                .and(within(TASK.TERMINAL_AT, window))
                .and(scopedTo(filters));
    }

    private Field<Long> completedCount() {
        return DSL.count().filterWhere(TASK.TERMINAL_STATUS.eq("completed")).cast(Long.class);
    }

    private Field<Long> failedCount() {
        return DSL.count().filterWhere(TASK.TERMINAL_STATUS.eq("failed")).cast(Long.class);
    }
}
