package com.fleet.analytics.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Both controls are required, and development key generation is not one of them. */
class DemoAccountPolicyTest {

    private static boolean enabled(boolean accountsEnabled, String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        if (activeProfiles.length > 0) {
            environment.setProperty("spring.profiles.active", String.join(",", activeProfiles));
        }
        return new DemoAccountPolicy(new DemoAccountProperties(accountsEnabled), environment)
                .demoAccountsEnabled();
    }

    @Test
    void requiresBothTheDemoProfileAndTheFlag() {
        assertThat(enabled(true, "demo")).isTrue();
        assertThat(enabled(false, "demo")).isFalse();
        assertThat(enabled(true)).isFalse();
        assertThat(enabled(false)).isFalse();
    }

    @Test
    void developmentProfileDoesNotEnableDemoAccounts() {
        assertThat(enabled(true, "dev")).isFalse();
        assertThat(enabled(true, "test")).isFalse();
    }

    @Test
    void demoProfileAlongsideOthersStillCounts() {
        assertThat(enabled(true, "test", "demo")).isTrue();
    }
}
