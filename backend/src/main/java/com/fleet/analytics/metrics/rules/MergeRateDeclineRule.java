package com.fleet.analytics.metrics.rules;

import com.fleet.analytics.metrics.Explanations;
import com.fleet.analytics.metrics.MetricCalculator;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.EvaluationLimit;
import com.fleet.analytics.metrics.model.EvaluationState;
import com.fleet.analytics.metrics.model.ExactMagnitude;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.FindingIdentity;
import com.fleet.analytics.metrics.model.MetricGate;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.RuleEvaluation;
import com.fleet.analytics.metrics.model.RuleEvidence;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.RuleType;
import com.fleet.analytics.metrics.model.Severity;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Terminal merge-rate decline (contract 6.3): has the merge rate fallen by at least 8 percentage
 * points against the immediately preceding equal-length period?
 *
 * <p>Unlike the failure spike, the baseline here <em>is</em> the previous period, and both windows
 * need 15 terminal PRs independently. The decline is the baseline minus the current rate, so a
 * positive magnitude means things got worse — the sign convention that lets one comparator rank all
 * four rules by "how bad".
 *
 * <p>Contract 8.5 case M2 is this rule's exact-boundary test: 29/50 down to 8/16 is exactly 8.0
 * points and must trigger.
 */
@Component
public class MergeRateDeclineRule {

    /** Contract 6.3 and research 8: a decline of at least 8 percentage points. */
    static final int THRESHOLD_POINTS = 8;

    public RuleEvaluation evaluate(RuleScope scope, PrCounts current, PrCounts baseline,
            LocalDate from, LocalDate to) {
        if (!MetricGate.TERMINAL_PRS.isMetBy(current.terminal())
                || !MetricGate.TERMINAL_PRS.isMetBy(baseline.terminal())) {
            return new RuleEvaluation.Unavailable(new EvaluationLimit(
                    RuleType.MERGE_RATE_DECLINE, scope, EvaluationState.NOT_EVALUATED,
                    Explanations.ruleGate(MetricGate.TERMINAL_PRS,
                            "in the selected period and in the previous period",
                            current.terminal(), baseline.terminal())));
        }

        // Decline: baseline rate minus current rate, so a worsening scope has a positive magnitude.
        ExactMagnitude decline = RuleArithmetic.ratioDifference(
                baseline.mergedExact(), baseline.terminalExact(),
                current.mergedExact(), current.terminalExact());

        if (!RuleArithmetic.atLeastPercentagePoints(decline, THRESHOLD_POINTS)) {
            return RuleEvaluation.Completed.withNoFinding(RuleType.MERGE_RATE_DECLINE, scope);
        }

        DisplayValue magnitude = DisplayValue.of(
                MetricCalculator.percentagePointChange(
                        baseline.mergedExact(), baseline.terminalExact(),
                        current.mergedExact(), current.terminalExact()),
                DisplayUnit.PERCENTAGE_POINTS);

        return RuleEvaluation.Completed.with(RuleType.MERGE_RATE_DECLINE, scope,
                new FindingCandidate(
                        FindingIdentity.of(RuleType.MERGE_RATE_DECLINE, scope,
                                TaskFailureSpikeRule.periodKey(from, to)),
                        scope, Severity.MEDIUM, decline, magnitude,
                        new RuleEvidence.MergeDecline(current, baseline, THRESHOLD_POINTS),
                        from, to));
    }
}
