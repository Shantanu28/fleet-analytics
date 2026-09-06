package com.fleet.analytics.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;

/**
 * Builds the local {@link JwtDecoder}: the configured public key, RS256 restricted at the decoder
 * rather than merely defaulted, and Spring Security's own validators composed with
 * {@link DelegatingOAuth2TokenValidator}. There is no OIDC discovery — issuer and audience are
 * identifier strings compared locally (04 §5.1).
 */
public final class JwtDecoderFactory {

    /** Tolerated clock skew on expiry, matching Spring Security's default. */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private JwtDecoderFactory() {}

    public static JwtDecoder create(RsaKeyProvider keys, JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        decoder.setClaimSetConverter(requireIssuedAt());

        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, aud -> aud != null && aud.contains(properties.audience())),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<Instant>(JwtClaimNames.IAT, Objects::nonNull),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, JwtDecoderFactory::isUuid),
                new JwtClaimValidator<String>(TokenClaims.ORGANISATION, JwtDecoderFactory::isUuid),
                new JwtClaimValidator<String>(
                        TokenClaims.ROLE, role -> role != null && TokenClaims.ALLOWED_ROLES.contains(role))));

        return decoder;
    }

    /**
     * {@code iat} is a required claim (04 §5.1), but Spring's default claim-set converter
     * substitutes {@code exp - 1s} whenever it is absent — so a validator testing the mapped claim
     * for null can never fire. Presence is therefore checked on the raw claims, before mapping.
     */
    private static Converter<Map<String, Object>, Map<String, Object>> requireIssuedAt() {
        Converter<Map<String, Object>, Map<String, Object>> defaults =
                MappedJwtClaimSetConverter.withDefaults(Map.of());
        return claims -> {
            if (!claims.containsKey(JwtClaimNames.IAT)) {
                throw new BadJwtException("Missing required claim");
            }
            return defaults.convert(claims);
        };
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
