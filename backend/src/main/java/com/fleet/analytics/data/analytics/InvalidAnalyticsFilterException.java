package com.fleet.analytics.data.analytics;

/**
 * A requested team or repository filter is not available to the authenticated organisation.
 *
 * <p>One exception for two situations that must stay indistinguishable: an identifier that does not
 * exist, and one that belongs to another tenant. Reporting them differently would turn the filter
 * parameter into an oracle for enumerating other organisations' teams and repositories, so the
 * message carries no identifier and no ownership detail at all.
 *
 * <p>Status and problem type belong to the web adapter (slice D), not here.
 */
public final class InvalidAnalyticsFilterException extends RuntimeException {

    public InvalidAnalyticsFilterException() {
        super("The requested filter is not available.");
    }
}
