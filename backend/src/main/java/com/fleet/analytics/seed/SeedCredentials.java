package com.fleet.analytics.seed;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Public synthetic credentials only. Never real credentials; never persisted as plaintext. */
final class SeedCredentials {
    static final Map<String, String> DEMO = Map.of(
            "admin", "123456", "admin123", "1234567",
            "viewer", "demo-viewer-a", "viewer123", "demo-viewer-b");

    private SeedCredentials() {}

    static String hash(String username, PasswordEncoder encoder) {
        // The remaining engineer identities have no published login. This random password is
        // immediately discarded, and their is_demo_account control still applies.
        return encoder.encode(DEMO.getOrDefault(username, UUID.randomUUID().toString()));
    }
}
