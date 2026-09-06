package com.fleet.analytics.data;

import static com.fleet.analytics.data.jooq.tables.DatasetPublication.DATASET_PUBLICATION;
import static com.fleet.analytics.data.jooq.tables.Organisation.ORGANISATION;
import static com.fleet.analytics.data.jooq.tables.Repository.REPOSITORY;
import static com.fleet.analytics.data.jooq.tables.SeatLicence.SEAT_LICENCE;
import static com.fleet.analytics.data.jooq.tables.Team.TEAM;

import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Every query takes org_id from the verified principal; no client-supplied organisation exists. */
@Repository
public class ContextQueries {

    private final DSLContext db;

    public ContextQueries(DSLContext db) {
        this.db = db;
    }

    public String organisationName(UUID organisationId) {
        String name = db.select(ORGANISATION.NAME).from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId)).fetchOne(ORGANISATION.NAME);
        if (name == null) {
            throw new ContextUnavailableException(organisationId, "organisation record");
        }
        return name;
    }

    public List<NamedEntity> teams(UUID organisationId) {
        return db.select(TEAM.ID, TEAM.NAME).from(TEAM)
                .where(TEAM.ORG_ID.eq(organisationId)).orderBy(TEAM.NAME)
                .fetch(r -> new NamedEntity(r.value1(), r.value2()));
    }

    public List<NamedEntity> repositories(UUID organisationId) {
        return db.select(REPOSITORY.ID, REPOSITORY.NAME).from(REPOSITORY)
                .where(REPOSITORY.ORG_ID.eq(organisationId)).orderBy(REPOSITORY.NAME)
                .fetch(r -> new NamedEntity(r.value1(), r.value2()));
    }

    /** Licensed capacity is the number of purchased seats, assigned or not. */
    public int licensedSeats(UUID organisationId) {
        return db.fetchCount(SEAT_LICENCE, SEAT_LICENCE.ORG_ID.eq(organisationId));
    }

    /**
     * The published reporting interval. An absent row is a broken dataset, never zero coverage:
     * substituting an empty or invented interval would misreport what the data actually covers.
     */
    public Coverage coverage(UUID organisationId) {
        Coverage coverage = db.select(DATASET_PUBLICATION.DATA_AVAILABLE_FROM,
                        DATASET_PUBLICATION.DATA_THROUGH, DATASET_PUBLICATION.REVISION)
                .from(DATASET_PUBLICATION)
                .where(DATASET_PUBLICATION.ORG_ID.eq(organisationId))
                .fetchOne(r -> new Coverage(r.value1(), r.value2(), r.value3()));
        if (coverage == null) {
            throw new ContextUnavailableException(organisationId, "dataset publication metadata");
        }
        return coverage;
    }
}
