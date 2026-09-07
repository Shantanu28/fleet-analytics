package com.fleet.analytics.seed;

import static com.fleet.analytics.data.jooq.Tables.*;
import static org.assertj.core.api.Assertions.*;

import com.fleet.analytics.security.PasswordEncoderFactory;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Each case owns a fresh database. Never uses the checkout's database or M3 fixture helpers. */
@Testcontainers
class SeedInstallerTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");
    private DriverManagerDataSource source;
    private DSLContext db;

    @BeforeEach
    void database() throws Exception {
        source = freshDatabase();
        db = DSL.using(source, SQLDialect.POSTGRES);
    }

    private DriverManagerDataSource freshDatabase() throws Exception {
        String name = "seed_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute("CREATE DATABASE " + name);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
        var result = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(result).load().migrate();
        return result;
    }

    @Test
    void installsPlannerStatisticsWithoutWaitingForAutoAnalyze() throws Exception {
        var tables = new ArrayList<Table<?>>(DemoDataset.TABLES);
        tables.add(DATASET_PUBLICATION);
        tables.add(SEED_MANIFEST);
        for (var table : tables) {
            db.execute("alter table {0} set (autovacuum_enabled = false)", table);
        }
        assertThat(db.fetchCount(DSL.table("pg_stats"), DSL.field("schemaname").eq("public")))
                .isZero();

        assertThat(new SeedInstaller(source).install()).isEqualTo(SeedInstaller.Outcome.INSTALLED);

        for (var table : tables) {
            assertThat(db.fetchCount(DSL.table("pg_stats"), DSL.field("schemaname").eq("public")
                    .and(DSL.field("tablename").eq(table.getName()))))
                    .as("planner statistics for %s", table.getName()).isPositive();
        }
    }

    @Test
    void independentInstallsMatchBusinessDataDespiteRandomSaltsAndNoOpPreservesEveryRow() throws Exception {
        var installer = new SeedInstaller(source);
        assertThat(installer.install()).isEqualTo(SeedInstaller.Outcome.INSTALLED);
        var before = snapshot(db);
        assertThat(installer.install()).isEqualTo(SeedInstaller.Outcome.UNCHANGED);
        assertThat(snapshot(db)).isEqualTo(before);
        var otherSource = freshDatabase();
        assertThat(new SeedInstaller(otherSource).install()).isEqualTo(SeedInstaller.Outcome.INSTALLED);
        var other = DSL.using(otherSource, SQLDialect.POSTGRES);
        assertThat(other.fetchValue("select business_checksum from seed_manifest"))
                .isEqualTo(db.fetchValue("select business_checksum from seed_manifest"));
        assertThat(other.select(APP_USER.PASSWORD_HASH).from(APP_USER).where(APP_USER.USERNAME.eq("admin")).fetchSingle(APP_USER.PASSWORD_HASH))
                .isNotEqualTo(db.select(APP_USER.PASSWORD_HASH).from(APP_USER).where(APP_USER.USERNAME.eq("admin")).fetchSingle(APP_USER.PASSWORD_HASH));
        assertThat(new SeedInstaller(otherSource).install()).isEqualTo(SeedInstaller.Outcome.UNCHANGED);
        SeedPopulationAssertions.verify(db);
        var encoder = PasswordEncoderFactory.create();
        assertThat(encoder.matches("123456", db.select(APP_USER.PASSWORD_HASH).from(APP_USER)
                .where(APP_USER.USERNAME.eq("admin")).fetchSingle(APP_USER.PASSWORD_HASH))).isTrue();
    }

    @Test
    void refusesUnmanagedRowsWithoutMutation() throws Exception {
        db.insertInto(ORGANISATION).set(ORGANISATION.ID, UUID.randomUUID())
                .set(ORGANISATION.NAME, "Existing organisation").set(ORGANISATION.CREATED_AT, DemoDataset.FROM).execute();
        var before = snapshot(db);
        assertThatThrownBy(() -> new SeedInstaller(source).install()).isInstanceOf(SeedRefusedException.class);
        assertThat(snapshot(db)).isEqualTo(before);
    }

    @Test
    void refusesChangedBusinessDataEvenWithMatchingCountsAndManifest() throws Exception {
        new SeedInstaller(source).install();
        db.update(TASK).set(TASK.USER_ID, DemoDataset.id("a", "app_user", "2"))
                .where(TASK.ID.eq(DemoDataset.id("a", "task", "0"))).execute();
        var before = snapshot(db);
        assertThatThrownBy(() -> new SeedInstaller(source).install()).isInstanceOf(SeedRefusedException.class);
        assertThat(snapshot(db)).isEqualTo(before);
    }

    @Test
    void refusesIncompatibleManifestAndMissingCoverageRows() throws Exception {
        new SeedInstaller(source).install();
        db.execute("update seed_manifest set dataset_version = 'incompatible'");
        assertRefusalUnchanged();
        db.execute("update seed_manifest set dataset_version = '1'");
        db.deleteFrom(SOURCE_DAY_COVERAGE).where(SOURCE_DAY_COVERAGE.DAY.eq(DemoDataset.FROM.toLocalDate())).execute();
        assertRefusalUnchanged();
    }

    @Test
    void refusesPublicationRevisionAndCredentialDamageExcludedFromChecksum() throws Exception {
        new SeedInstaller(source).install();
        db.update(DATASET_PUBLICATION).set(DATASET_PUBLICATION.REVISION, "wrong").execute();
        assertRefusalUnchanged();
        db.execute("update dataset_publication set revision = (select business_checksum from seed_manifest)");
        db.update(APP_USER).set(APP_USER.PASSWORD_HASH, PasswordEncoderFactory.create().encode("wrong"))
                .where(APP_USER.USERNAME.eq("admin")).execute();
        assertRefusalUnchanged();
    }

    @Test
    void databaseFailureAfterBusinessInsertsRollsBackBothTenantsAndManifest() throws Exception {
        db.execute("create function reject_publication() returns trigger language plpgsql as $$ begin raise exception 'injected failure'; end $$");
        db.execute("create trigger reject_publication before insert on dataset_publication for each row execute function reject_publication()");
        assertThatThrownBy(() -> new SeedInstaller(source).install()).isInstanceOf(DataAccessException.class);
        for (var table : DemoDataset.TABLES) assertThat(db.fetchCount(table)).as(table.getName()).isZero();
        assertThat(db.fetchCount(DATASET_PUBLICATION)).isZero();
        assertThat(db.fetchOne("select count(*) from seed_manifest").get(0, Integer.class)).isZero();
    }

    @Test
    void concurrentInvocationsInstallExactlyOnce() throws Exception {
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            DSL.using(blocker, SQLDialect.POSTGRES).fetch("select pg_advisory_xact_lock(?)", 0x464c4545544d3401L);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> new SeedInstaller(source).install());
                var second = executor.submit(() -> new SeedInstaller(source).install());
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    int waiting;
                    do {
                        waiting = db.fetchOne("select count(*) from pg_stat_activity where datname=current_database() and wait_event='advisory'").get(0, Integer.class);
                        if (waiting == 2) break;
                        Thread.sleep(20);
                    } while (System.nanoTime() < deadline);
                    assertThat(waiting).as("both installers wait for the advisory lock before inspection").isEqualTo(2);
                    assertThat(db.fetchCount(ORGANISATION)).isZero();
                } finally {
                    blocker.commit();
                }
                assertThat(List.of(first.get(45, TimeUnit.SECONDS), second.get(45, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(SeedInstaller.Outcome.INSTALLED, SeedInstaller.Outcome.UNCHANGED);
            }
        }
        assertThat(db.fetchCount(TASK)).isEqualTo(22000);
        assertThat(db.fetchOne("select count(*) from seed_manifest").get(0, Integer.class)).isEqualTo(1);
    }

    private void assertRefusalUnchanged() {
        var before = snapshot(db);
        assertThatThrownBy(() -> new SeedInstaller(source).install()).isInstanceOf(SeedRefusedException.class);
        assertThat(snapshot(db)).isEqualTo(before);
    }

    private static List<String> snapshot(DSLContext connection) {
        var snapshot = new ArrayList<String>();
        var names = new ArrayList<>(DemoDataset.TABLES.stream().map(Table::getName).toList());
        names.add("dataset_publication");
        names.add("seed_manifest");
        for (String table : names) {
            snapshot.add(connection.fetchOne("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text, '[]') from " + table + " t").get(0, String.class));
        }
        return snapshot;
    }
}
