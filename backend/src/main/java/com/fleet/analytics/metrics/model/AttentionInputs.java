package com.fleet.analytics.metrics.model;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything the four attention rules are evaluated from, selected once and grouped by scope.
 *
 * <p>A parameter object rather than a long argument list, because the alternative is worse than
 * verbose: the rules run over two dimensions and four windows, and positional arguments of the same
 * types would let a baseline be passed where a current window belongs without the compiler noticing.
 *
 * <p>Everything here is already batched. Budget risk covers the organisation and every team, and the
 * other three cover every team and every repository, so a query per scope would turn one small panel
 * into dozens of round trips inside the request's transaction.
 */
public record AttentionInputs(
        RuleScope organisation,
        DimensionInputs teams,
        DimensionInputs repositories,
        ScopeFilters filters,
        CoverageWindows coverage,
        LocalDate from,
        LocalDate to,
        YearMonth budgetMonth,
        BudgetConfiguration budgets,
        BigInteger organisationMonthToDateCents,
        Map<UUID, BigInteger> teamMonthToDateCents,
        int elapsedDays,
        int daysInMonth) {

    public AttentionInputs {
        Objects.requireNonNull(organisation, "organisation");
        Objects.requireNonNull(teams, "teams");
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(coverage, "coverage");
        Objects.requireNonNull(budgetMonth, "budgetMonth");
        Objects.requireNonNull(budgets, "budgets");
        Objects.requireNonNull(organisationMonthToDateCents, "organisationMonthToDateCents");
        teamMonthToDateCents = Map.copyOf(teamMonthToDateCents);
    }

    /**
     * One dimension's scopes and their populations. An absent map entry is genuine zero activity for
     * that scope — the scope was still evaluated, and will report its own gate as unmet.
     */
    public record DimensionInputs(
            List<RuleScope> scopes,
            Map<UUID, TaskCounts> currentTasks,
            Map<UUID, TaskCounts> baselineTasks,
            Map<UUID, FailureReasonGroups> failureReasons,
            Map<UUID, PrCounts> currentPullRequests,
            Map<UUID, PrCounts> previousPullRequests,
            Map<UUID, List<DomainCounts>> denials) {

        public DimensionInputs {
            scopes = List.copyOf(scopes);
            currentTasks = Map.copyOf(currentTasks);
            baselineTasks = Map.copyOf(baselineTasks);
            failureReasons = Map.copyOf(failureReasons);
            currentPullRequests = Map.copyOf(currentPullRequests);
            previousPullRequests = Map.copyOf(previousPullRequests);
            denials = Map.copyOf(denials);
        }

        public TaskCounts currentTasksFor(UUID scopeId) {
            return currentTasks.getOrDefault(scopeId, TaskCounts.NONE);
        }

        public TaskCounts baselineTasksFor(UUID scopeId) {
            return baselineTasks.getOrDefault(scopeId, TaskCounts.NONE);
        }

        public FailureReasonGroups reasonsFor(UUID scopeId) {
            return failureReasons.getOrDefault(scopeId, FailureReasonGroups.NONE);
        }

        public PrCounts currentPullRequestsFor(UUID scopeId) {
            return currentPullRequests.getOrDefault(scopeId, PrCounts.NONE);
        }

        public PrCounts previousPullRequestsFor(UUID scopeId) {
            return previousPullRequests.getOrDefault(scopeId, PrCounts.NONE);
        }

        public List<DomainCounts> denialsFor(UUID scopeId) {
            return denials.getOrDefault(scopeId, List.of());
        }
    }
}
