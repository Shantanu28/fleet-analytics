package com.fleet.analytics.seed;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class SeedApplicationTest {
    @Test
    void refusesWithoutTheExplicitSeedProfileBeforeConnectingToDatabase() {
        assertThatThrownBy(() -> SeedApplication.main(new String[] {"--spring.profiles.active=test", "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/not-a-database"}))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("seed profile");
    }
}
