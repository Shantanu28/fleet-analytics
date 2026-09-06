package com.fleet.analytics.security;

import java.util.UUID;

/** Tenant scope and role, resolved from the verified token — never from a request parameter. */
public record AuthenticatedTenant(UUID userId, UUID organisationId, String role) {}
