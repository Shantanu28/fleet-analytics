package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fleet.analytics.security.JwtIssuer;
import com.fleet.analytics.security.JwtProperties;
import com.fleet.analytics.security.PasswordEncoderFactory;
import com.fleet.analytics.security.RsaKeyProvider;
import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.IntegrationTestBase;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Every rejection case at the HTTP boundary: the status, the {@code WWW-Authenticate} challenge and
 * the body must all be right, and none of them may say why the token failed.
 */
class BearerTokenHttpTest extends IntegrationTestBase {

    private static final UUID ORG = UUID.fromString("0e000000-0000-0000-0000-00000000000e");
    private static final UUID USER = UUID.fromString("3e000000-0000-0000-0000-000000000001");

    @Autowired private JwtIssuer issuer;
    @Autowired private JwtProperties properties;
    @Autowired private RsaKeyProvider keys;
    @Autowired private Clock clock;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void fixtures() throws SQLException {
        String hash = PasswordEncoderFactory.create().encode("pw");
        try (Connection c = connection()) {
            Fixtures.organisation(c, ORG, "Bearer Org");
            UUID team = UUID.fromString("1e000000-0000-0000-0000-000000000001");
            Fixtures.team(c, team, ORG, "bearer-t", "Bearer Team");
            Fixtures.user(c, USER, ORG, team, "bearer-u", "bearer.user", "Bearer User", hash, "VIEWER", false);
            Fixtures.publication(c, ORG,
                    OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                    OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC), "rev-bearer");
        }
    }

    private MockHttpServletResponse context(String authorizationHeader) throws Exception {
        var request = get("/api/v1/analytics/context");
        if (authorizationHeader != null) {
            request = request.header("Authorization", authorizationHeader);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private void assertSanitisedUnauthorised(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/problem+json");

        String challenge = response.getHeader("WWW-Authenticate");
        assertThat(challenge).as("bearer challenge").isNotNull().startsWith("Bearer");
        // The challenge must not explain the failure: no error code, no description, no internals.
        assertThat(challenge)
                .doesNotContain("error").doesNotContain("description")
                .doesNotContain("Jwt").doesNotContain("expired").doesNotContain("signature");

        String body = response.getContentAsString();
        assertThat(json.readTree(body).get("type").asText()).isEqualTo("urn:fleet:problem:unauthenticated");
        assertThat(json.readTree(body).get("detail").asText()).isEqualTo("Authentication is required.");
        assertThat(body)
                .doesNotContain("Nimbus").doesNotContain("Exception").doesNotContain("com.fleet")
                .doesNotContain("signature").doesNotContain("expired").doesNotContain("issuer")
                .doesNotContain("audience");
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        var response = context("Bearer " + issuer.issue(USER, ORG, "VIEWER"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        assertSanitisedUnauthorised(context(null));
    }

    @Test
    void malformedAndNonBearerAuthorizationHeadersAreRejected() throws Exception {
        assertSanitisedUnauthorised(context("Bearer not-a-jwt"));
        assertSanitisedUnauthorised(context("Bearer "));
        assertThat(context("Basic dXNlcjpwYXNz").getStatus()).isEqualTo(401);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        // 15m ttl + 60s skew: issued 961s in the past is the first instant that must fail.
        JwtIssuer past = new JwtIssuer(keys, properties,
                Clock.fixed(clock.instant().minusSeconds(961), ZoneOffset.UTC));
        assertSanitisedUnauthorised(context("Bearer " + past.issue(USER, ORG, "VIEWER")));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = issuer.issue(USER, ORG, "VIEWER");
        assertSanitisedUnauthorised(
                context("Bearer " + token.substring(0, token.lastIndexOf('.') + 1) + "AAAA"));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        JwtIssuer foreign = new JwtIssuer(RsaKeyProvider.ephemeral(), properties, clock);
        assertSanitisedUnauthorised(context("Bearer " + foreign.issue(USER, ORG, "VIEWER")));
    }

    @Test
    void wrongIssuerAndWrongAudienceAreRejected() throws Exception {
        JwtProperties wrongIssuer = new JwtProperties("someone-else", properties.audience(),
                properties.ttl(), null, null, true);
        assertSanitisedUnauthorised(
                context("Bearer " + new JwtIssuer(keys, wrongIssuer, clock).issue(USER, ORG, "VIEWER")));

        JwtProperties wrongAudience = new JwtProperties(properties.issuer(), "another-app",
                properties.ttl(), null, null, true);
        assertSanitisedUnauthorised(
                context("Bearer " + new JwtIssuer(keys, wrongAudience, clock).issue(USER, ORG, "VIEWER")));
    }

    @Test
    void unsupportedAlgorithmIsRejected() throws Exception {
        SignedJWT hs256 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims(c -> {}));
        hs256.sign(new MACSigner(new byte[32]));
        assertSanitisedUnauthorised(context("Bearer " + hs256.serialize()));
    }

    @Test
    void missingOrMalformedRequiredClaimsAreRejected() throws Exception {
        assertSanitisedUnauthorised(context("Bearer " + signed(c -> c.subject(null))));
        assertSanitisedUnauthorised(context("Bearer " + signed(c -> c.subject("not-a-uuid"))));
        assertSanitisedUnauthorised(context("Bearer " + signed(c -> c.jwtID(null))));
        assertSanitisedUnauthorised(context("Bearer " + signed(c -> c.jwtID("not-a-uuid"))));
        assertSanitisedUnauthorised(context("Bearer " + signed(c -> c.claim("org", null))));
        assertSanitisedUnauthorised(context("Bearer " + signed(c -> c.claim("org", "nope"))));
        assertSanitisedUnauthorised(context("Bearer " + signed(c -> c.claim("role", null))));
        assertSanitisedUnauthorised(context("Bearer " + signed(c -> c.claim("role", "SUPERUSER"))));
        assertSanitisedUnauthorised(context("Bearer " + signed(c -> c.expirationTime(null))));
        assertSanitisedUnauthorised(context("Bearer " + signed(c -> c.issueTime(null))));
    }

    private JWTClaimsSet claims(Consumer<JWTClaimsSet.Builder> mutate) {
        Instant now = clock.instant();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .subject(USER.toString())
                .issuer(properties.issuer())
                .audience(properties.audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.ttl())))
                .claim("org", ORG.toString())
                .claim("role", "VIEWER");
        mutate.accept(builder);
        return builder.build();
    }

    private String signed(Consumer<JWTClaimsSet.Builder> mutate) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims(mutate));
            jwt.sign(new RSASSASigner(keys.privateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
