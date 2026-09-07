package com.fleet.analytics.seed;

import static com.fleet.analytics.data.jooq.Tables.*;

import com.fleet.analytics.security.PasswordEncoderFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;

/** Owns the complete installation transaction. No component annotation: explicit launcher only. */
public final class SeedInstaller {
    public enum Outcome { INSTALLED, UNCHANGED }
    private static final long LOCK = 0x464c4545544d3401L;
    private final DataSource source;

    public SeedInstaller(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    public Outcome install() throws SQLException {
        try (Connection connection = source.getConnection()) {
            // A waiting installer must take its inspection snapshot AFTER acquiring the lock.
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            DSLContext db = DSL.using(connection, SQLDialect.POSTGRES);
            try {
                db.fetch("select pg_advisory_xact_lock(?)", LOCK);
                // Also exclude ordinary writers while validating/installing. Readers remain free.
                // Names are fixed schema identifiers, never caller input.
                db.execute("lock table organisation, team, repository, app_user, seat_licence, budget, task, run, pull_request, usage_record, denial_event, source_day_coverage, dataset_publication, seed_manifest in share row exclusive mode");
                DemoDataset expected = DemoDataset.generate();
                var manifests = db.selectFrom(SEED_MANIFEST).fetch();
                Outcome outcome;
                if (manifests.isEmpty()) {
                    for (Table<?> table : DemoDataset.TABLES) {
                        if (db.fetchExists(table)) throw new SeedRefusedException("unmanaged business rows exist");
                    }
                    if (db.fetchExists(DATASET_PUBLICATION)) throw new SeedRefusedException("unmanaged publication exists");
                    insert(db, expected);
                    outcome = Outcome.INSTALLED;
                } else {
                    if (manifests.size() != 1) throw new SeedRefusedException("unexpected manifests");
                    var manifest = manifests.getFirst();
                    if (!DemoDataset.DATASET.equals(manifest.getDatasetId())
                            || !DemoDataset.VERSION.equals(manifest.getDatasetVersion())
                            || manifest.getSeed() != DemoDataset.SEED
                            || !expected.checksum().equals(manifest.getBusinessChecksum())
                            || !db.fetchExists(SEED_MANIFEST, SEED_MANIFEST.EXPECTED_COUNTS.eq(JSONB.valueOf(expected.counts())))) {
                        throw new SeedRefusedException("incompatible manifest");
                    }
                    outcome = Outcome.UNCHANGED;
                }
                validate(db, expected);
                // Bulk inserts do not create planner statistics. Collect them before declaring
                // the dataset ready, including on a validated repeat run of an older install.
                // Keep this inside the installation transaction and its existing writer lock.
                for (Table<?> table : DemoDataset.TABLES) db.execute("analyze {0}", table);
                db.execute("analyze {0}", DATASET_PUBLICATION);
                db.execute("analyze {0}", SEED_MANIFEST);
                connection.commit();
                return outcome;
            } catch (RuntimeException | SQLException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    private static void insert(DSLContext db, DemoDataset expected) {
        var encoder = PasswordEncoderFactory.create();
        for (Table<?> table : DemoDataset.TABLES) {
            List<Query> batch = new ArrayList<>();
            for (Record row : expected.rows(table)) {
                Map<Field<?>, Object> values = new LinkedHashMap<>();
                for (var field : row.fields()) values.put(field, row.get(field));
                if (table.equals(APP_USER)) {
                    values.put(APP_USER.PASSWORD_HASH, SeedCredentials.hash(row.get(APP_USER.USERNAME), encoder));
                }
                batch.add(db.insertInto(table).set(values));
                if (batch.size() == 500) { db.batch(batch).execute(); batch.clear(); }
            }
            if (!batch.isEmpty()) db.batch(batch).execute();
        }
        String checksum = expected.checksum();
        db.insertInto(SEED_MANIFEST).set(SEED_MANIFEST.DATASET_ID, DemoDataset.DATASET)
                .set(SEED_MANIFEST.DATASET_VERSION, DemoDataset.VERSION).set(SEED_MANIFEST.SEED, DemoDataset.SEED)
                .set(SEED_MANIFEST.EXPECTED_COUNTS, JSONB.valueOf(expected.counts()))
                .set(SEED_MANIFEST.BUSINESS_CHECKSUM, checksum)
                .set(SEED_MANIFEST.INSTALLED_AT, OffsetDateTime.now(ZoneOffset.UTC)).execute();
        for (Record org : expected.rows(ORGANISATION)) {
            db.insertInto(DATASET_PUBLICATION).set(DATASET_PUBLICATION.ORG_ID, org.get(ORGANISATION.ID))
                    .set(DATASET_PUBLICATION.DATA_AVAILABLE_FROM, DemoDataset.FROM)
                    .set(DATASET_PUBLICATION.DATA_THROUGH, DemoDataset.THROUGH)
                    .set(DATASET_PUBLICATION.REVISION, checksum).execute();
        }
    }

    private static void validate(DSLContext db, DemoDataset expected) {
        Map<Table<?>, List<Record>> actual = new LinkedHashMap<>();
        for (Table<?> table : DemoDataset.TABLES) {
            // Bound the read before loading rows from an unmanaged or damaged database.
            if (db.fetchCount(table) != expected.rows(table).size()) {
                throw new SeedRefusedException("unexpected " + table.getName() + " count");
            }
            actual.put(table, new ArrayList<>(db.selectFrom(table).fetch()));
        }
        if (!expected.counts().equals(SeedCanonical.counts(actual))) {
            throw new SeedRefusedException("unexpected per-tenant counts");
        }
        // Exact expected business content also validates all cross-row fixture invariants,
        // attribution and source-day completeness, rather than trusting a stored checksum.
        if (!expected.checksum().equals(SeedCanonical.checksum(actual))) {
            throw new SeedRefusedException("business data differs");
        }
        var publications = db.selectFrom(DATASET_PUBLICATION).fetch();
        if (publications.size() != 2 || publications.stream().anyMatch(p ->
                !p.getDataAvailableFrom().isEqual(DemoDataset.FROM)
                || !p.getDataThrough().isEqual(DemoDataset.THROUGH)
                || !p.getRevision().equals(expected.checksum()))) {
            throw new SeedRefusedException("publication differs");
        }
        var encoder = PasswordEncoderFactory.create();
        for (Record user : actual.get(APP_USER)) {
            String hash = user.get(APP_USER.PASSWORD_HASH);
            if (hash == null || !hash.matches("\\{argon2@SpringSecurity_v5_8}\\$argon2id\\$v=19\\$m=16384,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}")) {
                throw new SeedRefusedException("invalid demo password encoding");
            }
            String password = SeedCredentials.DEMO.get(user.get(APP_USER.USERNAME));
            if (password != null && !encoder.matches(password, hash)) {
                throw new SeedRefusedException("demo credentials differ");
            }
        }
    }
}
