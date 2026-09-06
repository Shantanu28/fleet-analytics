package com.fleet.analytics.web;

import java.util.Objects;
import java.util.UUID;

/**
 * The issued token and the identity the client needs for its query keys (04 §5.2).
 * {@code userId} and {@code organisationId} are echoed from the authenticated account for cache
 * scoping only — the API always derives authorisation scope from the verified token, never from
 * anything the client sends back.
 */
public record LoginResponse(
        String accessToken,
        long expiresInSeconds,
        UUID userId,
        UUID organisationId,
        String displayName,
        String role) {

    public LoginResponse {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(organisationId, "organisationId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
    }

    @Override
    public String toString() {
        return "LoginResponse[userId=" + userId + ", organisationId=" + organisationId
                + ", role=" + role + ", accessToken=<redacted>]";
    }
}
