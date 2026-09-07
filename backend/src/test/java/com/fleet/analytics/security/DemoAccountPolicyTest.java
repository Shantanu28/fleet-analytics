package com.fleet.analytics.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The demo profile is the only switch; development and test profiles do not enable logins. */
class DemoAccountPolicyTest {

    private static boolean enabled(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        if (activeProfiles.length > 0) {
            environment.setProperty("spring.profiles.active", String.join(",", activeProfiles));
        }
        return new DemoAccountPolicy(environment).demoAccountsEnabled();
    }

    @Test
    void theDemoProfileAloneEnablesDemoAccounts() {
        assertThat(enabled("demo")).isTrue();
        assertThat(enabled()).isFalse();
    }

    @Test
    void developmentProfileDoesNotEnableDemoAccounts() {
        assertThat(enabled("dev")).isFalse();
        assertThat(enabled("test")).isFalse();
    }

    @Test
    void demoProfileAlongsideOthersStillCounts() {
        assertThat(enabled("test", "demo")).isTrue();
    }
}
