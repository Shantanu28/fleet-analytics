package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * One normalised denied domain within one scope, with the two distinct counts contract 6.4 gates on.
 *
 * <p>Both counts are distinct by construction: repeated denial events from a single task inflate
 * neither, which is what stops one noisy sandbox from manufacturing a finding.
 *
 * <p>The domain is internal. Contract 6.4 and research 9 restrict it to platform admins because
 * internal hostnames can reveal architecture, so this record must not be serialized directly.
 */
public record DomainCounts(String normalisedDomain, long distinctTasks, long distinctUsers) {

    public DomainCounts {
        Objects.requireNonNull(normalisedDomain, "normalisedDomain");
        if (distinctTasks < 0 || distinctUsers < 0) {
            throw new IllegalArgumentException("distinct counts cannot be negative");
        }
    }
}
