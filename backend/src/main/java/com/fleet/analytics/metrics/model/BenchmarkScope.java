package com.fleet.analytics.metrics.model;

/**
 * What the benchmark population actually covers, stated by the server so AC-05.2 and AC-05.3 labels
 * are not deduced by the client.
 *
 * <p>{@code teamFilterIgnored} and {@code includesSelectedTeam} are definitional: the benchmark
 * never applies the team predicate, and it always includes the selected team's own contribution —
 * it is an inclusive organisational benchmark, not "everyone else" (contract 5.4). They are carried
 * anyway because the label states them, and a client must not have to know the rule to write it.
 *
 * <p>The other two are derived from the actual selection: a repository filter is honoured by the
 * benchmark, and only an active team filter can make the benchmark span teams the table is not
 * showing.
 */
public record BenchmarkScope(
        boolean teamFilterIgnored,
        boolean repositoryFilterApplied,
        boolean includesSelectedTeam,
        boolean mayIncludeUndisplayedTeams) {

    public static BenchmarkScope forSelection(ScopeFilters filters) {
        return new BenchmarkScope(true, filters.hasRepository(), true, filters.hasTeam());
    }
}
