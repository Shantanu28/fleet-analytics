package com.fleet.analytics.data;

import java.util.Objects;
import java.util.UUID;

/**
 * A resolved login account. Carries the stored password hash, so {@link #toString()} is overridden:
 * the generated record form would print the hash into any log or diagnostic that renders an account.
 */
public record Account(
        UUID id,
        UUID organisationId,
        String role,
        String displayName,
        String passwordHash,
        boolean demoAccount) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organisationId, "organisationId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(passwordHash, "passwordHash");
    }

    @Override
    public String toString() {
        return "Account[id=" + id + ", organisationId=" + organisationId + ", role=" + role
                + ", demoAccount=" + demoAccount + ", passwordHash=<redacted>]";
    }
}
