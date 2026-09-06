package com.fleet.analytics.security;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * Chooses the RS256 key pair. Separate from {@link SecurityConfig} so the choice — and the startup
 * failures it must produce — can be exercised on its own, without building a filter chain.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class KeyConfiguration {

    /** The only profiles under which a generated, throwaway key pair is permitted. */
    public static final Set<String> KEY_GENERATION_PROFILES = Set.of("dev", "test");

    /**
     * Configured keys take precedence and are never replaced on failure: an unreadable, malformed,
     * undersized or mismatched pair fails startup even under a development profile. Generation is
     * reached only when nothing is configured, and then only with the flag <em>and</em> an allowed
     * profile — the flag alone, in any other profile, does nothing.
     */
    @Bean
    public RsaKeyProvider rsaKeyProvider(JwtProperties properties, ResourceLoader resourceLoader,
            Environment environment) {
        if (properties.hasConfiguredKeys()) {
            return RsaKeyProvider.fromPem(
                    resourceLoader.getResource(properties.privateKeyLocation()),
                    resourceLoader.getResource(properties.publicKeyLocation()));
        }

        if (properties.devKeysEnabled() && generationProfileActive(environment)) {
            return RsaKeyProvider.ephemeral();
        }

        throw new RsaKeyProvider.KeyConfigurationException(
                "No JWT signing keys configured. Set fleet.jwt.private-key-location and "
                        + "fleet.jwt.public-key-location, or — for local development only — enable "
                        + "fleet.jwt.dev-keys-enabled under one of these profiles: "
                        + KEY_GENERATION_PROFILES.stream().sorted().toList() + ".");
    }

    private static boolean generationProfileActive(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        return active.stream().anyMatch(KEY_GENERATION_PROFILES::contains);
    }
}
