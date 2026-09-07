package com.fleet.analytics.seed;

import static com.fleet.analytics.data.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class DemoDatasetTest {
    @Test
    void fixedBusinessDataIncludesBothTenantsAndAllKnownEmptyDays() {
        var first = DemoDataset.generate();
        var second = DemoDataset.generate();
        assertThat(first.checksum()).isEqualTo(second.checksum());
        assertThat(first.rows(TASK)).hasSize(22000);
        assertThat(first.rows(RUN)).hasSize(22000);
        assertThat(first.rows(ORGANISATION)).hasSize(2);
        assertThat(first.rows(SOURCE_DAY_COVERAGE)).hasSize(2880);
        assertThat(ChronoUnit.DAYS.between(DemoDataset.FROM, DemoDataset.THROUGH)).isEqualTo(180);
        assertThat(first.rows(TASK).stream().filter(r -> r.get(TASK.ORG_ID).equals(DemoDataset.id("a", "organisation", "root"))).count()).isEqualTo(20000);
        assertThat(first.rows(TASK).stream().filter(r -> r.get(TASK.ORG_ID).equals(DemoDataset.id("b", "organisation", "root"))).count()).isEqualTo(2000);
    }
}
