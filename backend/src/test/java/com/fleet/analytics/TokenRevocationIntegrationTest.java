package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleet.analytics.data.TokenRevocationQueries;
import com.fleet.analytics.security.JwtDecoderFactory;
import com.fleet.analytics.security.JwtIssuer;
import com.fleet.analytics.security.JwtProperties;
import com.fleet.analytics.security.RsaKeyProvider;
import com.fleet.analytics.security.TokenRevocationService;
import com.fleet.analytics.support.IntegrationTestBase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class TokenRevocationIntegrationTest extends IntegrationTestBase {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private TokenRevocationQueries queries;

    @Test
    void revocationSurvivesServiceRecreationAndCleanupKeepsTheEntireSkewWindow() throws Exception {
        var keys = RsaKeyProvider.ephemeral();
        var properties = new JwtProperties("fleet-analytics", "fleet-analytics-dashboard",
                Duration.ofMinutes(15), null, null, true);
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        var decoder = JwtDecoderFactory.create(keys, properties, clock);
        String token = new JwtIssuer(keys, properties, clock)
                .issue(UUID.randomUUID(), UUID.randomUUID(), "VIEWER");
        Jwt verified = decoder.decode(token);
        UUID tokenId = UUID.fromString(verified.getId());
        new TokenRevocationService(queries, clock).revoke(verified);
        new TokenRevocationService(queries, clock).revoke(verified); // repeated writes are idempotent

        try (var c = connection(); var query = c.prepareStatement(
                "select token_id, retain_until from revoked_token where token_id = ?")) {
            query.setObject(1, tokenId);
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, UUID.class)).isEqualTo(tokenId);
                assertThat(rows.getObject(2, OffsetDateTime.class).toInstant())
                        .isEqualTo(Instant.parse("2026-01-01T00:16:00Z"));
                assertThat(rows.next()).isFalse();
            }
        }

        Clock boundary = Clock.fixed(START.plusSeconds(960), ZoneOffset.UTC);
        var anotherInstance = new TokenRevocationService(queries, boundary);
        // The cryptographic validator still accepts it at the boundary; the shared store must not.
        Jwt acceptedByTime = JwtDecoderFactory.create(keys, properties, boundary).decode(token);
        String boundaryToken = new JwtIssuer(keys, properties, boundary)
                .issue(UUID.randomUUID(), UUID.randomUUID(), "VIEWER");
        // A different token triggers cleanup, so accidental removal cannot be hidden by reinsertion.
        anotherInstance.revoke(JwtDecoderFactory.create(keys, properties, boundary).decode(boundaryToken));
        assertThatThrownBy(() -> anotherInstance.requireActive(acceptedByTime))
                .isInstanceOf(JwtException.class);

        Clock afterBoundary = Clock.fixed(START.plusSeconds(961), ZoneOffset.UTC);
        String later = new JwtIssuer(keys, properties, afterBoundary)
                .issue(UUID.randomUUID(), UUID.randomUUID(), "VIEWER");
        var laterDecoder = JwtDecoderFactory.create(keys, properties, afterBoundary);
        new TokenRevocationService(queries, afterBoundary).revoke(laterDecoder.decode(later));
        assertThat(queries.contains(tokenId)).isFalse();
        assertThatThrownBy(() -> laterDecoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
