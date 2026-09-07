package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.jooq.tables.Repository.REPOSITORY;
import static com.fleet.analytics.data.jooq.tables.Team.TEAM;

import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.security.AuthenticatedTenant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Confirms that every requested filter identifier exists <em>inside the authenticated
 * organisation</em>.
 *
 * <p>The organisation predicate is what makes this a tenancy control rather than a lookup: the id
 * is matched against {@code org_id = <verified organisation>} in the same statement, so a real
 * identifier belonging to another tenant simply does not match. It cannot be answered with another
 * organisation's data, and it cannot be told apart from an identifier that never existed.
 */
@Component
public class FilterResolver {

    private final DSLContext db;

    public FilterResolver(DSLContext db) {
        this.db = db;
    }

    /**
     * @throws InvalidAnalyticsFilterException if a supplied identifier is unknown to this tenant.
     *     Deliberately the same failure for unknown and foreign identifiers alike.
     */
    public void requireAvailable(AuthenticatedTenant tenant, ScopeFilters filters) {
        if (filters.hasTeam() && !teamExists(tenant.organisationId(), filters.teamId())) {
            throw new InvalidAnalyticsFilterException();
        }
        if (filters.hasRepository()
                && !repositoryExists(tenant.organisationId(), filters.repositoryId())) {
            throw new InvalidAnalyticsFilterException();
        }
    }

    private boolean teamExists(UUID organisationId, UUID teamId) {
        return db.fetchExists(TEAM, TEAM.ORG_ID.eq(organisationId).and(TEAM.ID.eq(teamId)));
    }

    private boolean repositoryExists(UUID organisationId, UUID repositoryId) {
        return db.fetchExists(
                REPOSITORY, REPOSITORY.ORG_ID.eq(organisationId).and(REPOSITORY.ID.eq(repositoryId)));
    }
}
