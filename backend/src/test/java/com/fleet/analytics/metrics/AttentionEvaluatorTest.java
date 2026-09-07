package com.fleet.analytics.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.metrics.model.AttentionInputs;
import com.fleet.analytics.metrics.model.AttentionResult;
import com.fleet.analytics.metrics.model.BudgetConfiguration;
import com.fleet.analytics.metrics.model.CoverageWindows;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.DomainCounts;
import com.fleet.analytics.metrics.model.EvaluationLimit;
import com.fleet.analytics.metrics.model.EvaluationState;
import com.fleet.analytics.metrics.model.ExactMagnitude;
import com.fleet.analytics.metrics.model.FailureReasonGroups;
import com.fleet.analytics.metrics.model.Explanation;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.FindingIdentity;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.RuleEvaluation;
import com.fleet.analytics.metrics.model.RuleEvidence;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.RuleType;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.Severity;
import com.fleet.analytics.metrics.model.TaskCounts;
import com.fleet.analytics.metrics.model.WindowCoverage;
import com.fleet.analytics.metrics.rules.BudgetRiskRule;
import com.fleet.analytics.metrics.rules.MergeRateDeclineRule;
import com.fleet.analytics.metrics.rules.NetworkPolicyFrictionRule;
import com.fleet.analytics.metrics.rules.TaskFailureSpikeRule;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Evaluation accounting: what counts as a completed evaluation, and what a limit is instead.
 *
 * <p>These two numbers select AC-06.3's panel state. Conflating them makes "we looked and found
 * nothing" indistinguishable from "we could not look", and the second would read as health.
 */
class AttentionEvaluatorTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 16);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);
    private static final UUID PLATFORM = UUID.fromString("2a1f0c64-1d3b-4f7a-9c02-5e8b7a10d001");
    private static final UUID PAYMENTS = UUID.fromString("2a1f0c64-1d3b-4f7a-9c02-5e8b7a10d002");
    private static final UUID REPO_API = UUID.fromString("3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e001");
    private static final UUID REPO_WEB = UUID.fromString("3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e002");

    private final AttentionEvaluator evaluator = new AttentionEvaluator(new FindingRanker(),
            new BudgetRiskRule(), new TaskFailureSpikeRule(), new MergeRateDeclineRule(),
            new NetworkPolicyFrictionRule());

    private static FindingCandidate friction(RuleScope scope, String domain, long tasks) {
        return new FindingCandidate(
                FindingIdentity.ofDomain(
                        RuleType.NETWORK_POLICY_FRICTION, scope, "2026-01-16/2026-01-31", domain),
                scope, Severity.MEDIUM, ExactMagnitude.ofWhole(tasks), DisplayValue.count(tasks),
                new RuleEvidence.Friction(new DomainCounts(domain, tasks, 3), 5, 3), FROM, TO);
    }

    private static RuleEvaluation notEvaluated(RuleType rule, RuleScope scope, String code) {
        return new RuleEvaluation.Unavailable(new EvaluationLimit(rule, scope,
                EvaluationState.NOT_EVALUATED, new Explanation(code, "not evaluated")));
    }

    /** D-5: complete data with nothing to report is a finished look, not a limit. */
    @Test
    void aCompletedEvaluationWithNoFindingStillCounts() {
        RuleScope team = RuleScope.team(PAYMENTS, "Payments");

        AttentionResult result = evaluator.combine(List.of(
                RuleEvaluation.Completed.withNoFinding(RuleType.NETWORK_POLICY_FRICTION, team)));

        assertThat(result.evaluationsCompleted()).isEqualTo(1);
        assertThat(result.findings()).isEmpty();
        assertThat(result.limits()).isEmpty();
    }

    /** Two qualifying domains in one scope are two findings from a single evaluation. */
    @Test
    void severalFindingsFromOneScopeRemainOneEvaluation() {
        RuleScope team = RuleScope.team(PAYMENTS, "Payments");

        AttentionResult result = evaluator.combine(List.of(new RuleEvaluation.Completed(
                RuleType.NETWORK_POLICY_FRICTION, team,
                List.of(friction(team, "a.corp", 6), friction(team, "b.corp", 5)))));

        assertThat(result.evaluationsCompleted()).isEqualTo(1);
        assertThat(result.findings()).hasSize(2);
    }

    @Test
    void anUnavailableEvaluationBecomesALimitAndIsNotCounted() {
        AttentionResult result = evaluator.combine(List.of(notEvaluated(
                RuleType.TASK_FAILURE_SPIKE, RuleScope.team(PAYMENTS, "Payments"),
                "gate_terminal_tasks_20")));

        assertThat(result.evaluationsCompleted()).isZero();
        assertThat(result.limits()).hasSize(1);
        assertThat(result.findings()).isEmpty();
    }

    /**
     * A finding cut by the three-finding cap still came from a completed evaluation. Reducing the
     * count to match what fits on screen would understate how much was actually checked.
     */
    @Test
    void theDisplayCapDoesNotReduceTheCompletedCount() {
        List<RuleEvaluation> evaluations = new ArrayList<>();
        RuleScope[] scopes = {
            RuleScope.team(PLATFORM, "Platform"), RuleScope.team(PAYMENTS, "Payments"),
            RuleScope.repository(REPO_API, "repo-api"), RuleScope.repository(REPO_WEB, "repo-web"),
        };
        for (int i = 0; i < scopes.length; i++) {
            evaluations.add(RuleEvaluation.Completed.with(RuleType.NETWORK_POLICY_FRICTION,
                    scopes[i], friction(scopes[i], "d" + i + ".corp", 9 - i)));
        }

        AttentionResult result = evaluator.combine(evaluations);

        assertThat(result.evaluationsCompleted()).isEqualTo(4);
        assertThat(result.findings()).hasSize(AttentionResult.MAX_DISPLAYED_FINDINGS);
    }

    /**
     * The contract fixture's approved accounting, assembled from the same shape the dashboard
     * produces: the organisation budget and four friction scopes complete, while both team budgets
     * and eight outcome-rule scopes cannot be evaluated.
     */
    @Test
    void theContractFixtureAccountsForFiveCompletedEvaluationsAndTenLimits() {
        RuleScope organisation = RuleScope.organisation("Contract Fixture Org");
        RuleScope platform = RuleScope.team(PLATFORM, "Platform");
        RuleScope payments = RuleScope.team(PAYMENTS, "Payments");
        RuleScope repoApi = RuleScope.repository(REPO_API, "repo-api");
        RuleScope repoWeb = RuleScope.repository(REPO_WEB, "repo-web");

        List<RuleEvaluation> evaluations = new ArrayList<>(List.of(
                // Budget: the organisation evaluates and finds nothing; neither team has a budget.
                RuleEvaluation.Completed.withNoFinding(RuleType.BUDGET_RISK, organisation),
                notEvaluated(RuleType.BUDGET_RISK, platform, "no_budget_configured"),
                notEvaluated(RuleType.BUDGET_RISK, payments, "no_budget_configured")));
        for (RuleScope scope : List.of(platform, payments, repoApi, repoWeb)) {
            evaluations.add(notEvaluated(
                    RuleType.TASK_FAILURE_SPIKE, scope, "gate_terminal_tasks_20"));
            evaluations.add(notEvaluated(
                    RuleType.MERGE_RATE_DECLINE, scope, "gate_terminal_prs_15"));
            // Denial data is complete and there are no events: a finished look.
            evaluations.add(RuleEvaluation.Completed.withNoFinding(
                    RuleType.NETWORK_POLICY_FRICTION, scope));
        }

        AttentionResult result = evaluator.combine(evaluations);

        assertThat(result.findings()).isEmpty();
        assertThat(result.evaluationsCompleted()).isEqualTo(5);
        assertThat(result.limits()).hasSize(10);
    }

    // --- Coverage-driven evaluation, through evaluate(...) (A.5) --------------------------------

    private static AttentionInputs.DimensionInputs dimension(RuleScope... scopes) {
        Map<UUID, TaskCounts> current = new HashMap<>();
        Map<UUID, TaskCounts> baseline = new HashMap<>();
        Map<UUID, FailureReasonGroups> reasons = new HashMap<>();
        Map<UUID, PrCounts> currentPrs = new HashMap<>();
        Map<UUID, PrCounts> previousPrs = new HashMap<>();
        for (RuleScope scope : scopes) {
            // Populations that clear both gates and would trigger both rate rules, so a suppressed
            // evaluation can only be coverage doing the suppressing.
            current.put(scope.scopeId(), new TaskCounts(12, 12));
            baseline.put(scope.scopeId(), new TaskCounts(24, 16));
            reasons.put(scope.scopeId(), new FailureReasonGroups(12, 0, 0));
            currentPrs.put(scope.scopeId(), new PrCounts(8, 8));
            previousPrs.put(scope.scopeId(), new PrCounts(12, 8));
        }
        return new AttentionInputs.DimensionInputs(List.of(scopes), current, baseline, reasons,
                currentPrs, previousPrs, Map.of());
    }

    private static WindowCoverage window(LocalDate from, LocalDate to, LogicalSource... incomplete) {
        return new WindowCoverage(DateWindow.ofInclusiveDates(from, to), Set.of(incomplete));
    }

    /**
     * @param currentIncomplete sources missing from the selected period.
     * @param previousIncomplete sources missing from the previous period.
     * @param baselineIncomplete sources missing from the 28-day failure baseline.
     */
    private AttentionResult evaluateWith(Set<LogicalSource> currentIncomplete,
            Set<LogicalSource> previousIncomplete, Set<LogicalSource> baselineIncomplete) {
        RuleScope team = RuleScope.team(PAYMENTS, "Payments");
        AttentionInputs inputs = new AttentionInputs(
                RuleScope.organisation("Fleet"),
                dimension(team),
                new AttentionInputs.DimensionInputs(List.of(), Map.of(), Map.of(), Map.of(),
                        Map.of(), Map.of(), Map.of()),
                ScopeFilters.none(),
                new CoverageWindows(
                        new WindowCoverage(DateWindow.ofInclusiveDates(FROM, TO), currentIncomplete),
                        new WindowCoverage(DateWindow.ofInclusiveDates(
                                LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 15)),
                                previousIncomplete),
                        new WindowCoverage(DateWindow.ofInclusiveDates(
                                LocalDate.of(2025, 12, 19), LocalDate.of(2026, 1, 15)),
                                baselineIncomplete),
                        window(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 3)),
                        window(FROM, LocalDate.of(2026, 2, 3))),
                FROM, TO, YearMonth.of(2026, 2), BudgetConfiguration.none(),
                BigInteger.ZERO, Map.of(), 3, 28);
        return evaluator.evaluate(inputs);
    }

    private static EvaluationLimit limitFor(AttentionResult result, RuleType ruleType) {
        return result.limits().stream().filter(limit -> limit.ruleType() == ruleType)
                .findFirst().orElseThrow(() ->
                        new AssertionError("no limit reported for " + ruleType));
    }

    private static boolean hasFinding(AttentionResult result, RuleType ruleType) {
        return result.findings().stream().anyMatch(finding -> finding.ruleType() == ruleType);
    }

    /** Baseline sanity: with everything covered, both rate rules fire from these populations. */
    @Test
    void fullyCoveredWindowsLetBothRateRulesFire() {
        AttentionResult result = evaluateWith(Set.of(), Set.of(), Set.of());

        assertThat(hasFinding(result, RuleType.TASK_FAILURE_SPIKE)).isTrue();
        assertThat(hasFinding(result, RuleType.MERGE_RATE_DECLINE)).isTrue();
    }

    /**
     * A.5 lists the spike as "tasks, and runs for the reason shown as evidence". Without current-
     * period run data the grouping cannot be built, so the rule must report that it could not
     * evaluate rather than publish a finding whose evidence is all zeros.
     */
    @Test
    void aMissingCurrentRunsSourceSuppressesTheSpikeRatherThanEmittingEmptyGroups() {
        AttentionResult result = evaluateWith(Set.of(LogicalSource.RUNS), Set.of(), Set.of());

        assertThat(hasFinding(result, RuleType.TASK_FAILURE_SPIKE)).isFalse();
        EvaluationLimit limit = limitFor(result, RuleType.TASK_FAILURE_SPIKE);
        assertThat(limit.state()).isEqualTo(EvaluationState.NOT_EVALUATED);
        assertThat(limit.explanation().code()).isEqualTo("source_not_covered");
        assertThat(limit.explanation().text()).contains("runs");

        // The merge decline shares no dependency on runs and must keep firing.
        assertThat(hasFinding(result, RuleType.MERGE_RATE_DECLINE)).isTrue();
    }

    /**
     * The baseline supplies only a rate, never evidence, so requiring runs there would suppress
     * findings for a reason the baseline has no bearing on.
     */
    @Test
    void aMissingBaselineRunsSourceDoesNotSuppressTheSpike() {
        AttentionResult result = evaluateWith(Set.of(), Set.of(), Set.of(LogicalSource.RUNS));

        assertThat(hasFinding(result, RuleType.TASK_FAILURE_SPIKE)).isTrue();
    }

    @Test
    void aMissingBaselineTasksSourceDoesSuppressTheSpike() {
        AttentionResult result = evaluateWith(Set.of(), Set.of(), Set.of(LogicalSource.TASKS));

        assertThat(hasFinding(result, RuleType.TASK_FAILURE_SPIKE)).isFalse();
        assertThat(limitFor(result, RuleType.TASK_FAILURE_SPIKE).explanation().text())
                .contains("tasks");
    }

    /**
     * The explanation must name the source that is actually missing, from whichever window lacks
     * it. A previous-period pull-request gap described as missing task data would send a reader to
     * investigate the wrong dataset entirely.
     */
    @Test
    void aPreviousPeriodPullRequestGapIsNotDescribedAsMissingTaskData() {
        AttentionResult result =
                evaluateWith(Set.of(), Set.of(LogicalSource.PULL_REQUESTS), Set.of());

        assertThat(hasFinding(result, RuleType.MERGE_RATE_DECLINE)).isFalse();
        EvaluationLimit limit = limitFor(result, RuleType.MERGE_RATE_DECLINE);
        assertThat(limit.explanation().text()).contains("pull_requests").doesNotContain("tasks");

        // And the spike, which does not depend on pull requests, is untouched.
        assertThat(hasFinding(result, RuleType.TASK_FAILURE_SPIKE)).isTrue();
    }

    /** The same for a baseline-only repository gap: it is named, not translated into tasks. */
    @Test
    void aPreviousPeriodRepositoryGapIsNamedAsSuch() {
        AttentionResult result =
                evaluateWith(Set.of(), Set.of(LogicalSource.REPOSITORIES), Set.of());

        assertThat(hasFinding(result, RuleType.MERGE_RATE_DECLINE)).isFalse();
        assertThat(limitFor(result, RuleType.MERGE_RATE_DECLINE).explanation().text())
                .contains("repositories").doesNotContain("tasks");
    }

    /** Both windows missing different sources: the limit names both, once each. */
    @Test
    void missingSourcesFromBothWindowsAreNamedTogether() {
        AttentionResult result = evaluateWith(
                Set.of(LogicalSource.REPOSITORIES), Set.of(LogicalSource.PULL_REQUESTS), Set.of());

        assertThat(limitFor(result, RuleType.MERGE_RATE_DECLINE).explanation().text())
                .contains("pull_requests").contains("repositories");
    }

    /** Limits render in a deterministic order: rule, then scope type, then scope id. */
    @Test
    void limitsAreOrderedDeterministically() {
        AttentionResult result = evaluator.combine(List.of(
                notEvaluated(RuleType.MERGE_RATE_DECLINE,
                        RuleScope.repository(REPO_WEB, "repo-web"), "gate_terminal_prs_15"),
                notEvaluated(RuleType.BUDGET_RISK,
                        RuleScope.team(PAYMENTS, "Payments"), "no_budget_configured"),
                notEvaluated(RuleType.MERGE_RATE_DECLINE,
                        RuleScope.team(PLATFORM, "Platform"), "gate_terminal_prs_15"),
                notEvaluated(RuleType.BUDGET_RISK,
                        RuleScope.team(PLATFORM, "Platform"), "no_budget_configured")));

        assertThat(result.limits()).extracting(
                        limit -> limit.ruleType() + "/" + limit.scope().scopeName())
                .containsExactly(
                        "BUDGET_RISK/Platform", "BUDGET_RISK/Payments",
                        "MERGE_RATE_DECLINE/Platform", "MERGE_RATE_DECLINE/repo-web");
    }
}
