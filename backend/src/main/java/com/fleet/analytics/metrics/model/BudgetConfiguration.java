package com.fleet.analytics.metrics.model;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The configured budgets for one calendar month.
 *
 * <p>Absence is meaningful and distinct from zero: a scope with no entry has <em>no budget
 * configured</em> and is reported {@code not_evaluated}, while a scope configured at zero or less is
 * an {@code invalid_budget_configuration}. Collapsing the two would turn a missing row into a
 * deliberate allowance of nothing, and every scope would look catastrophically over budget.
 */
public record BudgetConfiguration(BigInteger organisationCents, Map<UUID, BigInteger> byTeam) {

    public BudgetConfiguration {
        Objects.requireNonNull(byTeam, "byTeam");
        byTeam = Map.copyOf(byTeam);
    }

    public static BudgetConfiguration none() {
        return new BudgetConfiguration(null, Map.of());
    }

    /** @return null when this team has no configured budget at all. */
    public BigInteger forTeam(UUID teamId) {
        return byTeam.get(teamId);
    }
}
