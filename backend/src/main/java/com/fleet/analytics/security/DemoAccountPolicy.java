package com.fleet.analytics.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Demo accounts require <em>both</em> the demo profile and {@code fleet.demo.accounts-enabled}.
 * Either alone leaves them unable to sign in, so a stray property in a deployed environment — or a
 * development profile enabled for its keys — cannot turn public credentials on.
 */
@Component
public class DemoAccountPolicy {

    /** The one profile under which demo credentials are meaningful. */
    public static final String DEMO_PROFILE = "demo";

    private final boolean enabled;

    public DemoAccountPolicy(DemoAccountProperties properties, Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        this.enabled = properties.accountsEnabled() && active.contains(DEMO_PROFILE);
    }

    public boolean demoAccountsEnabled() {
        return enabled;
    }
}
