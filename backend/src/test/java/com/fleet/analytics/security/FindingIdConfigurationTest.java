package com.fleet.analytics.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The finding-identifier secret is required configuration, in every profile.
 *
 * <p>These assertions exist because the failure they prevent is invisible. An ephemeral or
 * defaulted key would let the application start, every test pass, and every response look correct —
 * while finding identifiers silently changed on each restart, breaking the stability the contract
 * promises to clients that bookmark or correlate on them. Failing at startup is the only outcome
 * that surfaces the mistake.
 */
class FindingIdConfigurationTest {

    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString(new byte[FindingIdProperties.MINIMUM_KEY_BYTES]);

    // FindingIdGenerator carries @EnableConfigurationProperties, so it registers and binds its own
    // properties bean -- which is exactly the wiring the application relies on.
    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(FindingIdGenerator.class);

    @Test
    void aValidSecretStartsTheGenerator() {
        contexts.withPropertyValues("fleet.findings.id-secret=" + VALID_KEY)
                .run(context -> assertThat(context).hasSingleBean(FindingIdGenerator.class));
    }

    /** No fallback, not even in dev: an absent secret stops startup rather than generating one. */
    @Test
    void anAbsentSecretFailsStartup() {
        contexts.run(context -> assertThat(context).hasFailed());
        contexts.withPropertyValues("fleet.findings.id-secret=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anAbsentSecretFailsInDevelopmentToo() {
        contexts.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMalformedSecretFailsStartup() {
        contexts.withPropertyValues("fleet.findings.id-secret=not base64 at all!!")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anUndersizedSecretFailsStartup() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        contexts.withPropertyValues("fleet.findings.id-secret=" + tooShort)
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * Enabling development JWT keys must not imply anything about this secret: the two are separate
     * controls over separate key material, and one must never unlock the other.
     */
    @Test
    void developmentJwtKeysDoNotSupplyAFindingSecret() {
        contexts.withPropertyValues("fleet.jwt.dev-keys-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
