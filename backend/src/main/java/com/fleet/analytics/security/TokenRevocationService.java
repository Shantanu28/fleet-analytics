package com.fleet.analytics.security;

import com.fleet.analytics.data.TokenRevocationQueries;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class TokenRevocationService {
    private final TokenRevocationQueries queries;
    private final Clock clock;

    public TokenRevocationService(TokenRevocationQueries queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    /** Called only after signature and claim validation; invalid input never reaches PostgreSQL. */
    public Jwt requireActive(Jwt verified) {
        boolean revoked;
        try {
            revoked = queries.contains(UUID.fromString(verified.getId()));
        } catch (DataAccessException e) {
            throw new RevocationUnavailableException(e);
        }
        if (revoked) {
            throw new BadJwtException("Token is no longer active");
        }
        return verified;
    }

    public void revoke(Jwt verified) {
        queries.revoke(UUID.fromString(verified.getId()),
                verified.getExpiresAt().plus(JwtDecoderFactory.CLOCK_SKEW).atOffset(ZoneOffset.UTC),
                clock.instant().atOffset(ZoneOffset.UTC));
    }
}
