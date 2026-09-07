package com.fleet.analytics.metrics;

import com.fleet.analytics.metrics.model.Explanation;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.MetricGate;
import com.fleet.analytics.metrics.model.ScopeType;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * The contract's own copy for every state that is not a value, stated once.
 *
 * <p>These sentences are part of the product, not decoration. Contract 1.2 requires that a
 * suppressed result never render as a good one, and AC-01.5 requires that zero merged PRs beside
 * positive spend read as "no merged PRs in this period" rather than "no activity" — so the wording
 * lives here, next to the rules, instead of being reinvented per call site.
 *
 * <p>Value-level and comparison-level wording differ deliberately: one explains why a number does
 * not exist, the other why two numbers cannot be compared.
 */
public final class Explanations {

    private Explanations() {}

    // --- Undefined values (contract 3.2, 3.3, 3.4) ---------------------------------------------

    public static Explanation noTerminalTasks() {
        return new Explanation("no_terminal_tasks",
                "No code-change tasks reached a terminal state in this period, "
                        + "so a completion rate is not defined.");
    }

    public static Explanation noTerminalPrs() {
        return new Explanation("no_terminal_prs",
                "No PRs reached a terminal state in this period, so a merge rate is not defined.");
    }

    /** Never "no activity": positive spend with zero merges is a real and important state (AC-01.5). */
    public static Explanation noMergedPrs() {
        return new Explanation("no_merged_prs",
                "No merged PRs in this period, so cost per merged PR is not defined.");
    }

    /**
     * Which period's ratio is undefined, named explicitly.
     *
     * <p>The {@code no_denominator} comparison state fires when <em>either</em> period has an empty
     * denominator, so a single sentence cannot describe it. Saying "this period has no terminal PRs"
     * when only the previous period was empty is a false statement about live data, and it points a
     * reader at the wrong window entirely.
     *
     * <p>The reason code stays the metric's own, so a client keying on it is unaffected; only the
     * sentence narrows.
     */
    public static Explanation undefinedPeriodRatio(
            MetricGate population, boolean currentUndefined, boolean previousUndefined) {
        if (!currentUndefined && !previousUndefined) {
            throw new IllegalArgumentException("at least one period must be undefined");
        }
        String noun = undefinedPopulationNoun(population);
        String derived = derivedQuantity(population);
        String text;
        if (currentUndefined && previousUndefined) {
            text = "Neither period has " + noun + ", so there is no " + derived + " to compare.";
        } else if (currentUndefined) {
            text = "This period has no " + noun + ", so there is no " + derived + " to compare.";
        } else {
            text = "The previous period had no " + noun + ", so there is no "
                    + derived + " to compare against.";
        }
        return new Explanation(undefinedReasonCode(population), text);
    }

    private static String undefinedReasonCode(MetricGate population) {
        return switch (population) {
            case TERMINAL_TASKS -> "no_terminal_tasks";
            case TERMINAL_PRS -> "no_terminal_prs";
            case MERGED_PRS -> "no_merged_prs";
        };
    }

    private static String undefinedPopulationNoun(MetricGate population) {
        return switch (population) {
            case TERMINAL_TASKS -> "terminal code-change tasks";
            case TERMINAL_PRS -> "terminal PRs";
            case MERGED_PRS -> "merged PRs";
        };
    }

    private static String derivedQuantity(MetricGate population) {
        return switch (population) {
            case TERMINAL_TASKS -> "completion rate";
            case TERMINAL_PRS -> "merge rate";
            case MERGED_PRS -> "unit cost";
        };
    }

    // --- Undefined row-versus-benchmark comparisons --------------------------------------------

    public static Explanation rowHasNoTerminalTasks() {
        return new Explanation("no_terminal_tasks",
                "This row has no terminal tasks, so there is no completion rate to compare.");
    }

    public static Explanation rowHasNoTerminalPrs() {
        return new Explanation("no_terminal_prs",
                "This row has no terminal PRs, so there is no merge rate to compare.");
    }

    public static Explanation rowHasNoMergedPrs() {
        return new Explanation("no_merged_prs",
                "This row has no merged PRs, so there is no unit cost to compare.");
    }

    // --- Sample gates (contract 5.4) -----------------------------------------------------------

    /** Names the threshold and both observed populations, so the shortfall is visible on screen. */
    public static Explanation periodGate(MetricGate gate, long current, long previous) {
        return new Explanation(gate.reasonCode(),
                "Comparison needs " + gate.threshold() + " " + gate.populationName()
                        + " in both periods; this period had " + current
                        + " and the previous period had " + previous + ".");
    }

    public static Explanation benchmarkGate(MetricGate gate, long rowPopulation) {
        return new Explanation(gate.reasonCode(),
                "Comparison with the organisation benchmark needs " + gate.threshold() + " "
                        + gate.populationName() + " in this row; it has " + rowPopulation + ".");
    }

    /**
     * The benchmark, not the row, is what falls short. Naming the row's own count here would tell a
     * reader to grow a population that is already large enough.
     */
    public static Explanation benchmarkPopulationGate(MetricGate gate, long benchmarkPopulation) {
        return new Explanation(gate.reasonCode(),
                "Comparison with the organisation benchmark needs " + gate.threshold() + " "
                        + gate.populationName() + " in the benchmark; it has "
                        + benchmarkPopulation + ".");
    }

    // --- Coverage (contract 1.5, A.5) ----------------------------------------------------------

    public static Explanation sourcesNotCovered(Collection<LogicalSource> missing) {
        return new Explanation("source_not_covered",
                "The " + names(missing) + " data is not fully covered for this period, "
                        + "so this value is unavailable.");
    }

    public static Explanation baselineNotCovered(Collection<LogicalSource> missing) {
        return new Explanation("baseline_not_covered",
                "The " + names(missing) + " data is not fully covered for the previous period, "
                        + "so there is nothing to compare against.");
    }

    // --- Scope and relative-change exceptions --------------------------------------------------

    /** Contract 5.3: no team or repository seat allocation exists, and none is invented. */
    public static Explanation seatUtilisationOutOfScope() {
        return new Explanation("seat_allocation_not_defined_for_scope",
                "Seat allocation is not defined for a team or repository scope, "
                        + "so utilisation is not shown for this selection.");
    }

    /** A licensed population of zero leaves utilisation with no denominator to divide by. */
    public static Explanation noLicensedSeats() {
        return new Explanation("no_licensed_seats",
                "No licensed seats are recorded for this organisation, "
                        + "so utilisation is not defined.");
    }

    public static Explanation noPreviousValue() {
        return new Explanation("no_previous_value",
                "The previous period was zero, so a percentage change is not defined.");
    }

    // --- Attention-rule limits (contract 6) ----------------------------------------------------

    public static Explanation noBudgetConfigured(ScopeType scopeType) {
        return new Explanation("no_budget_configured",
                "No budget is configured for this " + scopeType.wireName()
                        + ", so budget risk was not evaluated for it.");
    }

    /** A budget of zero or less is a configuration error, not an allowance of nothing. */
    public static Explanation invalidBudgetConfiguration() {
        return new Explanation("invalid_budget_configuration",
                "The configured budget is not a positive amount, so no forecast comparison "
                        + "is possible.");
    }

    public static Explanation insufficientBudgetHistory(int elapsedDays) {
        return new Explanation("insufficient_history",
                "A month-end forecast needs at least 3 complete days of the month; "
                        + elapsedDays + " have elapsed.");
    }

    public static Explanation budgetUnavailableUnderRepositoryFilter() {
        return new Explanation("budget_unavailable_for_repository_scope",
                "Budgets have no repository allocation, so budget risk is not evaluated "
                        + "under a repository filter.");
    }

    public static Explanation ruleGate(MetricGate gate, String windows, long current, long baseline) {
        return new Explanation(gate.reasonCode(),
                "Needs " + gate.threshold() + " " + gate.populationName() + " " + windows
                        + "; this scope had " + current + " and " + baseline + ".");
    }

    public static Explanation ruleSourcesNotCovered(Collection<LogicalSource> missing) {
        return new Explanation("source_not_covered",
                "The " + names(missing) + " data is not fully covered for the evaluated windows, "
                        + "so this rule was not evaluated.");
    }

    private static String names(Collection<LogicalSource> missing) {
        return missing.stream().map(LogicalSource::wireName).sorted()
                .collect(Collectors.joining(", "));
    }
}
