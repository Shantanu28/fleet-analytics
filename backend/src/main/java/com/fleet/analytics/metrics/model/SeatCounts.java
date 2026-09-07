package com.fleet.analytics.metrics.model;

import java.math.BigInteger;

/**
 * Distinct licensed users who started at least one task of any type in a window, by
 * {@code task.created_at} (contract 3.5), against the organisation's fixed licensed capacity.
 *
 * <p>Capacity stays organisation-wide even under a filter: no team or repository seat allocation
 * exists and none is invented, which is why utilisation — not the active count — becomes
 * unavailable for a filtered scope (contract 5.3).
 */
public record SeatCounts(long activeOwners, long licensedCapacity) {

    public SeatCounts {
        if (activeOwners < 0 || licensedCapacity < 0) {
            throw new IllegalArgumentException("seat counts cannot be negative");
        }
    }

    public BigInteger activeExact() {
        return BigInteger.valueOf(activeOwners);
    }

    public BigInteger licensedExact() {
        return BigInteger.valueOf(licensedCapacity);
    }
}
