package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.analytics.AnalyticsConditions.groupingColumn;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.ownedBy;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.scopedTo;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.within;
import static com.fleet.analytics.data.jooq.tables.DenialEvent.DENIAL_EVENT;
import static com.fleet.analytics.data.jooq.tables.Task.TASK;

import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.DomainCounts;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.ScopeFilters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

/**
 * Sandbox denials per scope and normalised domain (contract 6.4).
 *
 * <p>Both counts are {@code COUNT(DISTINCT …)} in SQL, which is what makes the rule about policy
 * rather than about noise: a sandbox retrying the same blocked domain twelve times contributes one
 * task and one owner, not twelve of each. Getting this wrong would let a single misconfigured task
 * manufacture a finding on its own.
 *
 * <p>Grouping is on {@code domain_normalised}, so {@code Registry.Corp.} and {@code registry.corp}
 * are one domain rather than two populations each below the gate.
 *
 * <p>Every task type counts, code-change or not (contract 1.4): friction is an operational rule
 * about sandbox policy, not a code-outcome metric. Attribution — team, repository and owner — comes
 * from the task, so a denial recorded without a run still counts.
 */
@Repository
public class DenialQueries {

    private final DSLContext db;

    public DenialQueries(DSLContext db) {
        this.db = db;
    }

    public Map<UUID, List<DomainCounts>> byScopeAndDomain(UUID organisationId, DateWindow window,
            ScopeFilters filters, Grouping grouping) {
        Field<UUID> scope = groupingColumn(grouping);
        Map<UUID, List<DomainCounts>> byScope = new HashMap<>();

        db.select(scope, DENIAL_EVENT.DOMAIN_NORMALISED,
                        DENIAL_EVENT.TASK_ID.countDistinct(), TASK.USER_ID.countDistinct())
                .from(DENIAL_EVENT)
                .join(TASK).on(TASK.ORG_ID.eq(DENIAL_EVENT.ORG_ID))
                        .and(TASK.ID.eq(DENIAL_EVENT.TASK_ID))
                .where(ownedBy(organisationId))
                .and(within(DENIAL_EVENT.OCCURRED_AT, window))
                .and(scopedTo(filters))
                .groupBy(scope, DENIAL_EVENT.DOMAIN_NORMALISED)
                // Deterministic, so repeated identical requests rank identically (AC-06.1).
                .orderBy(scope, DENIAL_EVENT.DOMAIN_NORMALISED)
                .fetch()
                .forEach(record -> byScope
                        .computeIfAbsent(record.value1(), key -> new ArrayList<>())
                        .add(new DomainCounts(record.value2(), record.value3(), record.value4())));

        return byScope;
    }
}
