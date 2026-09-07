package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleet.analytics.security.JwtDecoderFactory;
import com.fleet.analytics.security.JwtIssuer;
import com.fleet.analytics.security.JwtProperties;
import com.fleet.analytics.security.RsaKeyProvider;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Focused unit tests for the decoder: signing, algorithm restriction, claim validation and the
 * injected clock, with no HTTP and no Spring context. The matching boundary behaviour — status,
 * headers and body — is proven separately in {@link BearerTokenHttpTest}.
 */
class JwtSecurityTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final RsaKeyProvider keys = RsaKeyProvider.ephemeral();
    private final JwtProperties props = new JwtProperties(
            "fleet-analytics", "fleet-analytics-dashboard", Duration.ofMinutes(15), null, null, true);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtIssuer issuer = new JwtIssuer(keys, props, clock);
    private final JwtDecoder decoder = JwtDecoderFactory.create(keys, props, clock);

    private JwtDecoder decoderAt(Instant instant) {
        return JwtDecoderFactory.create(keys, props, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void issuesAndVerifiesWithIdentityClaims() {
        Jwt jwt = decoder.decode(issuer.issue(USER, ORG, "ADMIN"));
        assertThat(jwt.getSubject()).isEqualTo(USER.toString());
        assertThat(jwt.getClaimAsString("org")).isEqualTo(ORG.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        // Fixed clock, same user: logins must still be independently revocable.
        Jwt anotherLogin = decoder.decode(issuer.issue(USER, ORG, "ADMIN"));
        assertThat(anotherLogin.getId()).isNotEqualTo(jwt.getId());
    }

    @Test
    void rejectsExpiredTokenUsingInjectedClock() {
        String token = issuer.issue(USER, ORG, "VIEWER");
        // 15m ttl + 60s skew = 960s; 961s is the first instant that must be rejected
        assertThatThrownBy(() -> decoderAt(NOW.plusSeconds(961)).decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void acceptsTokenInsideAllowedClockSkew() {
        String token = issuer.issue(USER, ORG, "VIEWER");
        assertThat(decoderAt(NOW.plusSeconds(930)).decode(token).getSubject()).isEqualTo(USER.toString());
    }

    @Test
    void rejectsMalformedTamperedAndForeignlySignedTokens() {
        assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("")).isInstanceOf(JwtException.class);

        String token = issuer.issue(USER, ORG, "ADMIN");
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "AAAA";
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);

        JwtIssuer otherKey = new JwtIssuer(RsaKeyProvider.ephemeral(), props, clock);
        assertThatThrownBy(() -> decoder.decode(otherKey.issue(USER, ORG, "ADMIN")))
                .isInstanceOf(JwtException.class);
    }

    /** RS256 is restricted at the decoder, so a validly signed HS256 token is still refused. */
    @Test
    void rejectsUnsupportedAlgorithm() throws JOSEException {
        byte[] secret = new byte[32];
        JWTClaimsSet claims = baseClaims().build();
        SignedJWT hs256 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        hs256.sign(new MACSigner(secret));

        assertThatThrownBy(() -> decoder.decode(hs256.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongIssuerAndAudience() {
        JwtIssuer wrongIssuer = new JwtIssuer(keys, withIssuer("someone-else"), clock);
        assertThatThrownBy(() -> decoder.decode(wrongIssuer.issue(USER, ORG, "ADMIN")))
                .isInstanceOf(JwtException.class);

        JwtIssuer wrongAudience = new JwtIssuer(keys, withAudience("another-app"), clock);
        assertThatThrownBy(() -> decoder.decode(wrongAudience.issue(USER, ORG, "ADMIN")))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsMissingOrMalformedIdentityClaims() {
        assertThatThrownBy(() -> decoder.decode(handCrafted(c -> c.jwtID(null))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(handCrafted(c -> c.jwtID("not-a-uuid"))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(handCrafted(c -> c.subject(null))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(handCrafted(c -> c.subject("not-a-uuid"))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(handCrafted(c -> c.claim("org", null))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(handCrafted(c -> c.claim("org", "nope"))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(handCrafted(c -> c.claim("role", null))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(handCrafted(c -> c.claim("role", "SUPERUSER"))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(handCrafted(c -> c.expirationTime(null))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(handCrafted(c -> c.issueTime(null))))
                .isInstanceOf(JwtException.class);
    }

    private JwtProperties withIssuer(String issuerName) {
        return new JwtProperties(issuerName, props.audience(), props.ttl(), null, null, true);
    }

    private JwtProperties withAudience(String audience) {
        return new JwtProperties(props.issuer(), audience, props.ttl(), null, null, true);
    }

    private JWTClaimsSet.Builder baseClaims() {
        return new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .subject(USER.toString())
                .issuer(props.issuer())
                .audience(props.audience())
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plus(props.ttl())))
                .claim("org", ORG.toString())
                .claim("role", "ADMIN");
    }

    /** Signs with the real key so only the claim under test differs. */
    private String handCrafted(Consumer<JWTClaimsSet.Builder> mutate) {
        try {
            JWTClaimsSet.Builder builder = baseClaims();
            mutate.accept(builder);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), builder.build());
            jwt.sign(new RSASSASigner(keys.privateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
