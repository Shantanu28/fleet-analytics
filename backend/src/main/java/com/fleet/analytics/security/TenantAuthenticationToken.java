package com.fleet.analytics.security;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * An authenticated request whose principal is the resolved {@link AuthenticatedTenant}, so
 * controllers can take {@code @AuthenticationPrincipal AuthenticatedTenant} directly instead of
 * re-reading claims. The verified {@link Jwt} stays available as credentials, which Spring masks
 * in {@code toString()}.
 */
public final class TenantAuthenticationToken extends AbstractAuthenticationToken {

    private final transient Jwt token;
    private final AuthenticatedTenant tenant;

    public TenantAuthenticationToken(Jwt token, AuthenticatedTenant tenant,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.token = token;
        this.tenant = tenant;
        setAuthenticated(true);
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }

    @Override
    public AuthenticatedTenant getPrincipal() {
        return tenant;
    }
}
