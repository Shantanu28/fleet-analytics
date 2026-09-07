package com.fleet.analytics.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Demo accounts can sign in only while the demo profile is active.
 * Development key generation alone never enables public demo credentials.
 */
@Component
public class DemoAccountPolicy {

    /** The one profile under which demo credentials are meaningful. */
    public static final String DEMO_PROFILE = "demo";

    private final boolean enabled;

    public DemoAccountPolicy(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        this.enabled = active.contains(DEMO_PROFILE);
    }

    public boolean demoAccountsEnabled() {
        return enabled;
    }
}
