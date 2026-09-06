package com.fleet.analytics.security;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a token the decoder has already validated into the tenant principal and its authority.
 * Every claim read here was checked by {@link JwtDecoderFactory}'s validators, so this converter
 * parses rather than re-validates.
 */
public final class TenantAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AuthenticatedTenant tenant = new AuthenticatedTenant(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(TokenClaims.ORGANISATION)),
                jwt.getClaimAsString(TokenClaims.ROLE));
        return new TenantAuthenticationToken(
                jwt, tenant, List.of(new SimpleGrantedAuthority("ROLE_" + tenant.role())));
    }
}
