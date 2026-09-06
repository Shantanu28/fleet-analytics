package com.fleet.analytics;

import static com.fleet.analytics.data.jooq.tables.Organisation.ORGANISATION;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * M1 smoke test. Proves the whole chain against a real PostgreSQL 18:
 * Flyway migration -> generated jOOQ types -> compiled query round-trip.
 * Named *Test so Surefire runs it during `verify`; it is never skipped silently.
 */
@Testcontainers
class OrganisationSchemaSmokeTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void migrationCreatesOrganisationAndGeneratedTypesRoundTrip() throws Exception {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            DSLContext db = DSL.using(connection, SQLDialect.POSTGRES);

            int inserted = db.insertInto(ORGANISATION)
                    .set(ORGANISATION.ID, id)
                    .set(ORGANISATION.NAME, "Fleet Demo")
                    .set(ORGANISATION.CREATED_AT, createdAt)
                    .execute();
            assertThat(inserted).isEqualTo(1);

            String name = db.select(ORGANISATION.NAME)
                    .from(ORGANISATION)
                    .where(ORGANISATION.ID.eq(id))
                    .fetchOne(ORGANISATION.NAME);
            assertThat(name).isEqualTo("Fleet Demo");
        }
    }
}
