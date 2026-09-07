package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * The active-seats card: the active count with its own comparison, the fixed licensed capacity, and
 * utilisation stated separately.
 *
 * <p>Utilisation carries a value and a state but never a comparison. AC-01.7 fixes this card's one
 * displayed comparison to the absolute change in active seats, and AC-01.8 forbids a second badge —
 * so a utilisation delta, even when computable, is not offered here.
 */
public record SeatsResult(MetricResult activeSeats, long licensedSeats, MetricResult utilisation) {

    public SeatsResult {
        Objects.requireNonNull(activeSeats, "activeSeats");
        Objects.requireNonNull(utilisation, "utilisation");
        if (licensedSeats < 0) {
            throw new IllegalArgumentException("licensedSeats cannot be negative");
        }
        if (utilisation.comparison() != null) {
            throw new IllegalArgumentException("utilisation must not carry a comparison (AC-01.8)");
        }
    }
}
