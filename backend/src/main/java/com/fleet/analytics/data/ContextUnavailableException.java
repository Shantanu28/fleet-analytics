package com.fleet.analytics.data;

import java.util.UUID;

/**
 * Required tenant metadata is missing. Every authenticated organisation must have a name row and a
 * publication row; their absence is a broken dataset, not an empty result, so it must not be
 * answered with invented coverage or a contract-invalid success (04 Appendix A.5).
 *
 * <p>The organisation id stays internal for logging and never reaches the response.
 */
public final class ContextUnavailableException extends RuntimeException {

    private final UUID organisationId;

    public ContextUnavailableException(UUID organisationId, String missing) {
        super("Missing " + missing + " for organisation " + organisationId);
        this.organisationId = organisationId;
    }

    public UUID organisationId() {
        return organisationId;
    }
}
