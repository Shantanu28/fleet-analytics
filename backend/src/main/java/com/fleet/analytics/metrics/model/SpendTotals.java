package com.fleet.analytics.metrics.model;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Metered cost in a window, by {@code usage_record.metered_at} (contract 2). A record is recognised
 * whole at that instant and never prorated, so a task's cost may land in a different period from
 * its own terminal timestamp. That is what makes period spend reconcile to the metered ledger.
 *
 * <p>Two totals over the same rows, because two metrics need different populations: the spend trend
 * and budgets count every task type, while the cost-per-merged-PR numerator counts only eligible
 * code-change tasks (contract 1.4, 3.3).
 */
public record SpendTotals(BigInteger allTaskCents, BigInteger codeChangeCents) {

    public static final SpendTotals NONE = new SpendTotals(BigInteger.ZERO, BigInteger.ZERO);

    public SpendTotals {
        Objects.requireNonNull(allTaskCents, "allTaskCents");
        Objects.requireNonNull(codeChangeCents, "codeChangeCents");
        if (allTaskCents.signum() < 0 || codeChangeCents.signum() < 0) {
            throw new IllegalArgumentException("spend cannot be negative");
        }
        if (codeChangeCents.compareTo(allTaskCents) > 0) {
            throw new IllegalArgumentException(
                    "code-change spend cannot exceed all-task spend over the same rows");
        }
    }
}
