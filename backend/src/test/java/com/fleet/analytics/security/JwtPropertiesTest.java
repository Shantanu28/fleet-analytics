package com.fleet.analytics.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Configuration invariants, enforced where the properties are built rather than at first use. */
class JwtPropertiesTest {

    private static JwtProperties with(String issuer, String audience, Duration ttl) {
        return new JwtProperties(issuer, audience, ttl, null, null, false);
    }

    @Test
    void appliesDemoDefaultsWhenNothingIsConfigured() {
        JwtProperties properties = with(null, null, null);
        assertThat(properties.issuer()).isEqualTo("fleet-analytics");
        assertThat(properties.audience()).isEqualTo("fleet-analytics-dashboard");
        assertThat(properties.ttl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.hasConfiguredKeys()).isFalse();
    }

    @Test
    void rejectsBlankIssuerAndAudience() {
        assertThatThrownBy(() -> with("   ", "aud", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("iss", "   ", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> with("iss", "aud", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> with("iss", "aud", Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void rejectsHalfConfiguredKeyPair() {
        assertThatThrownBy(() -> new JwtProperties("iss", "aud", Duration.ofMinutes(15),
                "file:/tmp/private.pem", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be set together");
        assertThatThrownBy(() -> new JwtProperties("iss", "aud", Duration.ofMinutes(15),
                null, "file:/tmp/public.pem", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** An empty environment variable expands to "", which must read as absent, not as a location. */
    @Test
    void treatsBlankKeyLocationsAsAbsent() {
        JwtProperties properties = new JwtProperties("iss", "aud", Duration.ofMinutes(15), "", "", false);
        assertThat(properties.hasConfiguredKeys()).isFalse();
    }
}
