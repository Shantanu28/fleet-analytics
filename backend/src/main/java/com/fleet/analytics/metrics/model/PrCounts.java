package com.fleet.analytics.metrics.model;

import java.math.BigInteger;

/**
 * Eligible PRs whose terminal transition fell in a window, by that transition's own timestamp
 * (contract 3.2). Open PRs are excluded — they have not yet produced an outcome to rate.
 *
 * <p>{@code merged} doubles as the merged-PR count (contract 3.1): a PR merged in the window is
 * exactly a PR whose terminal transition in the window was a merge, so one population answers both
 * cards and they can never disagree.
 */
public record PrCounts(long merged, long closedUnmerged) {

    public static final PrCounts NONE = new PrCounts(0, 0);

    public PrCounts {
        if (merged < 0 || closedUnmerged < 0) {
            throw new IllegalArgumentException("PR counts cannot be negative");
        }
    }

    /** The merge rate's denominator. */
    public long terminal() {
        return merged + closedUnmerged;
    }

    public BigInteger mergedExact() {
        return BigInteger.valueOf(merged);
    }

    public BigInteger terminalExact() {
        return BigInteger.valueOf(terminal());
    }
}
