package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.analytics.AnalyticsConditions.codeChangeTask;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.groupingColumn;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.ownedBy;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.scopedTo;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.utcDay;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.within;
import static com.fleet.analytics.data.jooq.tables.Run.RUN;
import static com.fleet.analytics.data.jooq.tables.Task.TASK;
import static com.fleet.analytics.data.jooq.tables.UsageRecord.USAGE_RECORD;

import com.fleet.analytics.metrics.model.DailyValue;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.SpendTotals;
import java.math.BigDecimal;
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
 * Metered spend, placed in a window by {@code usage_record.metered_at} (contract 2). A record is
 * recognised whole at that instant and never prorated, so a task's cost can fall in a different
 * period from its own outcome — which is what makes period spend reconcile to the ledger.
 *
 * <p>Two totals from one pass: all task types for the spend trend and budgets, and eligible
 * code-change tasks only for the cost-per-merged-PR numerator (contract 1.4, 3.3). The numerator
 * counts every execution outcome — completed, failed, cancelled and still running — because the
 * cost of failure is part of unit cost.
 *
 * <p>Usage joins up through its run to its task, a chain of many-to-one steps that cannot multiply
 * rows. It never joins pull requests or denial events: those are separate one-to-many branches off
 * the same task, and combining them in one aggregate would multiply every cent by the number of
 * rows on the other branch.
 *
 * <p>PostgreSQL's {@code sum(bigint)} returns {@code numeric}, so totals are read as
 * {@link BigDecimal} and converted exactly. Narrowing to {@code long} in the mapper would be a
 * silent precision decision made in the wrong layer.
 */
@Repository
public class UsageQueries {

    private final DSLContext db;

    public UsageQueries(DSLContext db) {
        this.db = db;
    }

    public SpendTotals totals(UUID organisationId, DateWindow window, ScopeFilters filters) {
        return db.select(totalCents(), codeChangeCents())
                .from(meteredUsage())
                .where(meteredInWindow(organisationId, window, filters))
                .fetchOne(record -> new SpendTotals(
                        exactCents(record.value1()), exactCents(record.value2())));
    }

    public Map<UUID, SpendTotals> byScope(UUID organisationId, DateWindow window,
            ScopeFilters filters, Grouping grouping) {
        Field<UUID> scope = groupingColumn(grouping);
        Map<UUID, SpendTotals> totals = new HashMap<>();
        db.select(scope, totalCents(), codeChangeCents())
                .from(meteredUsage())
                .where(meteredInWindow(organisationId, window, filters))
                .groupBy(scope)
                .fetch()
                .forEach(record -> totals.put(record.value1(),
                        new SpendTotals(exactCents(record.value2()), exactCents(record.value3()))));
        return totals;
    }

    /** The spend trend covers every task type (contract 5.1), so no eligibility filter applies. */
    public List<DailyValue> spendPerDay(
            UUID organisationId, DateWindow window, ScopeFilters filters) {
        Field<LocalDate> day = utcDay(USAGE_RECORD.METERED_AT);
        return db.select(day, totalCents())
                .from(meteredUsage())
                .where(meteredInWindow(organisationId, window, filters))
                .groupBy(day)
                .fetch(record -> new DailyValue(record.value1(), exactCents(record.value2())));
    }

    private org.jooq.Table<?> meteredUsage() {
        return USAGE_RECORD
                .join(RUN).on(RUN.ORG_ID.eq(USAGE_RECORD.ORG_ID))
                        .and(RUN.ID.eq(USAGE_RECORD.RUN_ID))
                .join(TASK).on(TASK.ORG_ID.eq(RUN.ORG_ID)).and(TASK.ID.eq(RUN.TASK_ID));
    }

    private Condition meteredInWindow(
            UUID organisationId, DateWindow window, ScopeFilters filters) {
        return ownedBy(organisationId)
                .and(within(USAGE_RECORD.METERED_AT, window))
                .and(scopedTo(filters));
    }

    private Field<BigDecimal> totalCents() {
        return DSL.coalesce(DSL.sum(USAGE_RECORD.COST_CENTS), BigDecimal.ZERO);
    }

    private Field<BigDecimal> codeChangeCents() {
        return DSL.coalesce(
                DSL.sum(USAGE_RECORD.COST_CENTS).filterWhere(codeChangeTask()), BigDecimal.ZERO);
    }

    /** Cents are integral by definition; a fractional total would mean the column was misused. */
    private BigInteger exactCents(BigDecimal total) {
        return total == null ? BigInteger.ZERO : total.toBigIntegerExact();
    }
}
