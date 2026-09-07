package com.fleet.analytics.seed;

import static com.fleet.analytics.data.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class SeedCanonicalTest {
    @Test
    void hashesRelationshipsAndCoverageButNotPasswordSalts() {
        Map<Table<?>, List<Record>> rows = emptyTables();
        var user = DSL.using(SQLDialect.POSTGRES).newRecord(APP_USER)
                .with(APP_USER.ID, UUID.randomUUID()).with(APP_USER.PASSWORD_HASH, "first salt");
        rows.get(APP_USER).add(user);
        String before = SeedCanonical.checksum(rows);
        user.setPasswordHash("different salt");
        assertThat(SeedCanonical.checksum(rows)).isEqualTo(before);
        user.setTeamId(UUID.randomUUID());
        assertThat(SeedCanonical.checksum(rows)).isNotEqualTo(before);
        String withTeam = SeedCanonical.checksum(rows);
        rows.get(SOURCE_DAY_COVERAGE).add(DSL.using(SQLDialect.POSTGRES).newRecord(SOURCE_DAY_COVERAGE)
                .with(SOURCE_DAY_COVERAGE.IS_COMPLETE, true));
        assertThat(SeedCanonical.checksum(rows)).isNotEqualTo(withTeam);
    }

    @Test
    void orderingAndDatabaseTimezoneCannotChangeBusinessIdentity() {
        Map<Table<?>, List<Record>> rows = emptyTables();
        var first = DSL.using(SQLDialect.POSTGRES).newRecord(TASK)
                .with(TASK.ID, UUID.randomUUID()).with(TASK.CREATED_AT, DemoDataset.FROM);
        var second = DSL.using(SQLDialect.POSTGRES).newRecord(TASK).with(TASK.ID, UUID.randomUUID());
        rows.get(TASK).add(first);
        rows.get(TASK).add(second);
        String before = SeedCanonical.checksum(rows);
        first.setCreatedAt(DemoDataset.FROM.withOffsetSameInstant(ZoneOffset.ofHours(4)));
        Collections.reverse(rows.get(TASK));
        assertThat(SeedCanonical.checksum(rows)).isEqualTo(before);
    }

    private static Map<Table<?>, List<Record>> emptyTables() {
        Map<Table<?>, List<Record>> rows = new LinkedHashMap<>();
        DemoDataset.TABLES.forEach(t -> rows.put(t, new ArrayList<>()));
        return rows;
    }
}
