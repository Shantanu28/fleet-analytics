package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.analytics.AnalyticsConditions.ownedBy;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.scopedTo;
import static com.fleet.analytics.data.analytics.AnalyticsConditions.within;
import static com.fleet.analytics.data.jooq.tables.SeatLicence.SEAT_LICENCE;
import static com.fleet.analytics.data.jooq.tables.Task.TASK;

import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.SeatCounts;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Active seats: distinct users who started at least one task in the window, by
 * {@code task.created_at} (contract 3.5). Every task type counts — adoption is about who is using
 * the platform, not what they used it for.
 *
 * <p>The licensed population is applied as a semi-join rather than a join. A join would multiply a
 * user's tasks by their seat rows; the semi-join restricts without duplicating, and the count is
 * distinct on top of that, so neither shape can inflate the figure.
 *
 * <p>Capacity stays organisation-wide even under a filter. There is no team or repository seat
 * allocation, so the calculator reports utilisation as unavailable for a filtered scope rather than
 * dividing a filtered numerator by an organisation denominator (contract 5.3).
 */
@Repository
public class SeatQueries {

    private final DSLContext db;

    public SeatQueries(DSLContext db) {
        this.db = db;
    }

    public SeatCounts active(UUID organisationId, DateWindow window, ScopeFilters filters) {
        Integer activeOwners = db.select(TASK.USER_ID.countDistinct())
                .from(TASK)
                .where(ownedBy(organisationId))
                .and(within(TASK.CREATED_AT, window))
                .and(scopedTo(filters))
                .and(TASK.USER_ID.in(db.select(SEAT_LICENCE.USER_ID)
                        .from(SEAT_LICENCE)
                        .where(SEAT_LICENCE.ORG_ID.eq(organisationId))
                        .and(SEAT_LICENCE.USER_ID.isNotNull())))
                .fetchOne(0, Integer.class);

        return new SeatCounts(
                activeOwners == null ? 0 : activeOwners, licensedCapacity(organisationId));
    }

    /** Purchased seats, assigned or not — the denominator utilisation is measured against. */
    private long licensedCapacity(UUID organisationId) {
        return db.fetchCount(SEAT_LICENCE, SEAT_LICENCE.ORG_ID.eq(organisationId));
    }
}
