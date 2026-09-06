package com.fleet.analytics.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token issuing and verification settings under {@code fleet.jwt.*}.
 *
 * <p>{@code issuer} and {@code audience} are identifier strings, not URLs to resolve: there is no
 * OIDC discovery (04 §5.1). Key locations are Spring resource locations, so {@code file:} and
 * {@code classpath:} both work; supplying exactly one of the pair is a configuration error rather
 * than a reason to fall back to generated keys.
 */
@ConfigurationProperties(prefix = "fleet.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        Duration ttl,
        String privateKeyLocation,
        String publicKeyLocation,
        boolean devKeysEnabled) {

    public JwtProperties {
        // Absent means "use the demo default"; blank means "configured wrongly" and is rejected
        // below. Key locations differ deliberately: they have no default, and an unset environment
        // variable expands to "", so blank there genuinely means absent.
        issuer = issuer == null ? "fleet-analytics" : issuer;
        audience = audience == null ? "fleet-analytics-dashboard" : audience;
        ttl = ttl == null ? Duration.ofMinutes(15) : ttl;
        privateKeyLocation = blankToNull(privateKeyLocation);
        publicKeyLocation = blankToNull(publicKeyLocation);

        if (issuer.isBlank()) {
            throw new IllegalArgumentException("fleet.jwt.issuer must not be blank");
        }
        if (audience.isBlank()) {
            throw new IllegalArgumentException("fleet.jwt.audience must not be blank");
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("fleet.jwt.ttl must be positive");
        }
        if ((privateKeyLocation == null) != (publicKeyLocation == null)) {
            throw new IllegalArgumentException(
                    "fleet.jwt.private-key-location and fleet.jwt.public-key-location must be set together");
        }
    }

    /** True when a complete key pair has been configured; the compact constructor rejects halves. */
    public boolean hasConfiguredKeys() {
        return privateKeyLocation != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
