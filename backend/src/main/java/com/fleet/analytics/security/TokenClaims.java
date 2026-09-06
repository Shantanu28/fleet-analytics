package com.fleet.analytics.security;

import java.util.Set;

/** The claim names and role values this application issues and requires (04 §5.1). */
public final class TokenClaims {

    /** Tenant claim. Not a registered JWT claim, so it is named here once and used everywhere. */
    public static final String ORGANISATION = "org";

    public static final String ROLE = "role";

    public static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "VIEWER");

    private TokenClaims() {}
}
