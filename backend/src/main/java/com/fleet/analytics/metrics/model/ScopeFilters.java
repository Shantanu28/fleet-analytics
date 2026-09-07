package com.fleet.analytics.metrics.model;

import java.util.UUID;

/**
 * The team and repository filters, combined by intersection (contract 6.0). Both are optional;
 * absent means "no restriction on that dimension", never "no rows".
 *
 * <p>These are analytical filters, not permission boundaries (04 5.1) — organisation scope always
 * comes separately from the verified token, and every query takes it as its own argument.
 */
public record ScopeFilters(UUID teamId, UUID repositoryId) {

    private static final ScopeFilters NONE = new ScopeFilters(null, null);

    public static ScopeFilters none() {
        return NONE;
    }

    public boolean hasTeam() {
        return teamId != null;
    }

    public boolean hasRepository() {
        return repositoryId != null;
    }

    /** True when any scope narrowing is active — the condition seat utilisation checks (contract 5.3). */
    public boolean hasAnyScope() {
        return hasTeam() || hasRepository();
    }

    /**
     * The benchmark population: drop the team predicate, keep an explicit repository filter
     * (contract 5.4). This is the only place in the system that ignores a filter, and it is
     * deliberate — the benchmark is inclusive of the selected team, not "everyone else".
     */
    public ScopeFilters withoutTeam() {
        return hasTeam() ? new ScopeFilters(null, repositoryId) : this;
    }

    /** Restricts one dimension to a single scope — how a comparison-table row narrows its population. */
    public ScopeFilters restrictedTo(Grouping grouping, UUID scopeId) {
        return switch (grouping) {
            case TEAMS -> new ScopeFilters(scopeId, repositoryId);
            case REPOSITORIES -> new ScopeFilters(teamId, scopeId);
        };
    }
}
