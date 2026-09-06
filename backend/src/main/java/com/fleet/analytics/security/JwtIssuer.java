package com.fleet.analytics.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Issues RS256 tokens carrying sub, org, role, iss, aud, iat and exp. */
public final class JwtIssuer {

    private final RsaKeyProvider keys;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtIssuer(RsaKeyProvider keys, JwtProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(UUID userId, UUID organisationId, String role) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer(properties.issuer())
                .audience(properties.audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.ttl())))
                .claim("org", organisationId.toString())
                .claim("role", role)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        try {
            jwt.sign(new RSASSASigner(keys.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign token", e);
        }
        return jwt.serialize();
    }
}
