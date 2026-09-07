package com.fleet.analytics.metrics;

import com.fleet.analytics.metrics.model.AttentionInputs;
import com.fleet.analytics.metrics.model.AttentionResult;
import com.fleet.analytics.metrics.model.BudgetSample;
import com.fleet.analytics.metrics.model.EvaluationLimit;
import com.fleet.analytics.metrics.model.EvaluationState;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.RuleEvaluation;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.RuleType;
import com.fleet.analytics.metrics.model.ScopeType;
import com.fleet.analytics.metrics.model.WindowCoverage;
import com.fleet.analytics.metrics.rules.BudgetRiskRule;
import com.fleet.analytics.metrics.rules.MergeRateDeclineRule;
import com.fleet.analytics.metrics.rules.NetworkPolicyFrictionRule;
import com.fleet.analytics.metrics.rules.TaskFailureSpikeRule;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Combines every (rule, scope) evaluation into the panel's three facts: the ranked findings, how
 * many evaluations reached a verdict, and every one that did not.
 *
 * <p>The counting rule is the whole point of this class. {@code evaluationsCompleted} counts
 * <em>evaluations</em>, never findings, domains or displayed rows:
 *
 * <ul>
 *   <li>A scope with complete denial data and no matching events is one completed evaluation with no
 *       finding — a finished look, not a limit.
 *   <li>A scope where two domains qualify is still one completed evaluation, with two candidates.
 *   <li>A finding pushed out by the three-finding cap still came from a completed evaluation, so the
 *       count does not shrink to match what fits on screen.
 * </ul>
 *
 * <p>Getting this wrong collapses AC-06.3's five panel states: "we looked everywhere and found
 * nothing" and "we could not look" would render identically, and the second would read as health.
 */
@Component
public class AttentionEvaluator {

    /** A.5: budget findings need the metered ledger and the budget configuration itself. */
    private static final Set<LogicalSource> BUDGET_SOURCES =
            Set.of(LogicalSource.USAGE, LogicalSource.RUNS, LogicalSource.TASKS, LogicalSource.BUDGETS);

    /** A.5: friction needs denial events and the tasks that supply their attribution. */
    private static final Set<LogicalSource> FRICTION_SOURCES =
            Set.of(LogicalSource.DENIALS, LogicalSource.TASKS);

    /**
     * A.5 lists the failure spike as "tasks, and runs for the reason shown as evidence". The current
     * window therefore needs both: the finding carries the agent/platform/policy grouping, which is
     * derived from run rows, and without them the groups would be zero or partial rather than
     * absent.
     */
    private static final Set<LogicalSource> SPIKE_CURRENT_SOURCES =
            Set.of(LogicalSource.TASKS, LogicalSource.RUNS);

    /**
     * The baseline contributes only a rate, never evidence, so tasks alone. Requiring runs here
     * would suppress findings for a reason the baseline has no bearing on.
     */
    private static final Set<LogicalSource> SPIKE_BASELINE_SOURCES = LogicalSource.TASK_OUTCOMES;

    private final FindingRanker ranker;
    private final BudgetRiskRule budgetRisk;
    private final TaskFailureSpikeRule failureSpike;
    private final MergeRateDeclineRule mergeDecline;
    private final NetworkPolicyFrictionRule friction;

    public AttentionEvaluator(FindingRanker ranker, BudgetRiskRule budgetRisk,
            TaskFailureSpikeRule failureSpike, MergeRateDeclineRule mergeDecline,
            NetworkPolicyFrictionRule friction) {
        this.ranker = ranker;
        this.budgetRisk = budgetRisk;
        this.failureSpike = failureSpike;
        this.mergeDecline = mergeDecline;
        this.friction = friction;
    }

    /**
     * Runs every rule over every scope it applies to, then combines the outcomes.
     *
     * <p>Scope selection follows contract 6.0: the explicit filters have already narrowed the
     * populations, and each dimension's scope list has already been restricted by any filter on that
     * same dimension. A repository finding is never suppressed because its team also triggered —
     * repositories are shared, and no parent-team ownership is assumed.
     */
    public AttentionResult evaluate(AttentionInputs inputs) {
        List<RuleEvaluation> evaluations = new ArrayList<>();
        evaluations.addAll(budgetEvaluations(inputs));
        evaluations.addAll(outcomeEvaluations(inputs, inputs.teams()));
        evaluations.addAll(outcomeEvaluations(inputs, inputs.repositories()));
        return combine(evaluations);
    }

    /**
     * Contract 6.1: with no filter, the organisation and every team. A repository filter removes
     * budget evaluation entirely — budgets have no repository allocation — and that holds even when
     * a team is also selected (contract 5.3).
     */
    private List<RuleEvaluation> budgetEvaluations(AttentionInputs inputs) {
        List<RuleScope> scopes = new ArrayList<>();
        if (!inputs.filters().hasTeam()) {
            scopes.add(inputs.organisation());
        }
        scopes.addAll(inputs.teams().scopes());

        List<RuleEvaluation> evaluations = new ArrayList<>();
        for (RuleScope scope : scopes) {
            if (inputs.filters().hasRepository()) {
                evaluations.add(budgetRisk.unavailableForRepositoryScope(scope));
                continue;
            }
            if (!inputs.coverage().budgetEvaluable()
                    || !inputs.coverage().budgetMonthToDate().supports(BUDGET_SOURCES)) {
                evaluations.add(new RuleEvaluation.Unavailable(new EvaluationLimit(
                        RuleType.BUDGET_RISK, scope, EvaluationState.NOT_EVALUATED,
                        Explanations.ruleSourcesNotCovered(missingBudgetSources(inputs)))));
                continue;
            }
            evaluations.add(budgetRisk.evaluate(scope, inputs.budgetMonth(), sampleFor(inputs, scope)));
        }
        return evaluations;
    }

    private Set<LogicalSource> missingBudgetSources(AttentionInputs inputs) {
        return inputs.coverage().budgetEvaluable()
                ? inputs.coverage().budgetMonthToDate().missingFrom(BUDGET_SOURCES)
                : Set.of(LogicalSource.BUDGETS);
    }

    /** Null when the scope has no configured budget row: absence is not a budget of zero. */
    private BudgetSample sampleFor(AttentionInputs inputs, RuleScope scope) {
        BigInteger budget = scope.scopeType() == ScopeType.ORGANISATION
                ? inputs.budgets().organisationCents()
                : inputs.budgets().forTeam(scope.scopeId());
        if (budget == null) {
            return null;
        }
        BigInteger spend = scope.scopeType() == ScopeType.ORGANISATION
                ? inputs.organisationMonthToDateCents()
                : inputs.teamMonthToDateCents().getOrDefault(scope.scopeId(), BigInteger.ZERO);
        return new BudgetSample(budget, spend, inputs.elapsedDays(), inputs.daysInMonth());
    }

    /**
     * The three non-budget rules, over every scope of one dimension.
     *
     * <p>Each rule's missing-source set is computed <em>once</em> and drives both the decision and
     * the explanation. Deriving them separately is how a limit ends up describing the wrong thing:
     * a previous-period pull-request gap would suppress the merge decline while the sentence blamed
     * task data. Because the sets are per-window and not per-scope, they are also computed outside
     * the loop.
     */
    private List<RuleEvaluation> outcomeEvaluations(
            AttentionInputs inputs, AttentionInputs.DimensionInputs dimension) {
        Set<LogicalSource> spikeMissing = missingAcross(
                inputs.coverage().current(), SPIKE_CURRENT_SOURCES,
                inputs.coverage().failureBaseline28d(), SPIKE_BASELINE_SOURCES);
        Set<LogicalSource> declineMissing = missingAcross(
                inputs.coverage().current(), LogicalSource.PR_OUTCOMES,
                inputs.coverage().previousPeriod(), LogicalSource.PR_OUTCOMES);
        Set<LogicalSource> frictionMissing =
                inputs.coverage().current().missingFrom(FRICTION_SOURCES);

        List<RuleEvaluation> evaluations = new ArrayList<>();
        for (RuleScope scope : dimension.scopes()) {
            UUID id = scope.scopeId();

            evaluations.add(spikeMissing.isEmpty()
                    ? failureSpike.evaluate(scope, dimension.currentTasksFor(id),
                            dimension.baselineTasksFor(id), dimension.reasonsFor(id),
                            inputs.from(), inputs.to())
                    : notCovered(RuleType.TASK_FAILURE_SPIKE, scope, spikeMissing));

            evaluations.add(declineMissing.isEmpty()
                    ? mergeDecline.evaluate(scope, dimension.currentPullRequestsFor(id),
                            dimension.previousPullRequestsFor(id), inputs.from(), inputs.to())
                    : notCovered(RuleType.MERGE_RATE_DECLINE, scope, declineMissing));

            evaluations.add(frictionMissing.isEmpty()
                    ? friction.evaluate(scope, dimension.denialsFor(id), inputs.from(), inputs.to())
                    : notCovered(RuleType.NETWORK_POLICY_FRICTION, scope, frictionMissing));
        }
        return evaluations;
    }

    /**
     * Every dependency missing from either window, named by the window that actually lacks it. The
     * two windows carry different requirements — a spike's evidence needs runs in the current window
     * but not in the baseline — so they are asked separately and unioned.
     */
    private Set<LogicalSource> missingAcross(WindowCoverage current, Set<LogicalSource> currentRequired,
            WindowCoverage baseline, Set<LogicalSource> baselineRequired) {
        EnumSet<LogicalSource> missing = EnumSet.noneOf(LogicalSource.class);
        missing.addAll(current.missingFrom(currentRequired));
        missing.addAll(baseline.missingFrom(baselineRequired));
        return Set.copyOf(missing);
    }

    /**
     * @param missing must be non-empty: it is the reason the rule was not evaluated. Substituting a
     *     placeholder source when it is empty would put a false statement in front of the user, so
     *     an empty set is an internal inconsistency rather than something to paper over.
     */
    private RuleEvaluation notCovered(
            RuleType ruleType, RuleScope scope, Set<LogicalSource> missing) {
        if (missing.isEmpty()) {
            throw new IllegalStateException(
                    "a rule reported as not covered must name the sources it is missing");
        }
        return new RuleEvaluation.Unavailable(new EvaluationLimit(ruleType, scope,
                EvaluationState.NOT_EVALUATED, Explanations.ruleSourcesNotCovered(missing)));
    }

    public AttentionResult combine(List<RuleEvaluation> evaluations) {
        List<FindingCandidate> candidates = new ArrayList<>();
        List<EvaluationLimit> limits = new ArrayList<>();
        int completed = 0;

        for (RuleEvaluation evaluation : evaluations) {
            switch (evaluation) {
                case RuleEvaluation.Completed done -> {
                    completed++;
                    candidates.addAll(done.candidates());
                }
                case RuleEvaluation.Unavailable unavailable -> limits.add(unavailable.limit());
            }
        }

        limits.sort(Comparator.comparing(EvaluationLimit::orderingKey));
        return new AttentionResult(ranker.rank(candidates), completed, limits);
    }
}
