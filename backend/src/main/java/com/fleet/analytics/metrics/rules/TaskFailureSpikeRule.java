package com.fleet.analytics.metrics.rules;

import com.fleet.analytics.metrics.Explanations;
import com.fleet.analytics.metrics.MetricCalculator;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.EvaluationLimit;
import com.fleet.analytics.metrics.model.EvaluationState;
import com.fleet.analytics.metrics.model.ExactMagnitude;
import com.fleet.analytics.metrics.model.FailureReasonGroups;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.FindingIdentity;
import com.fleet.analytics.metrics.model.MetricGate;
import com.fleet.analytics.metrics.model.RuleEvaluation;
import com.fleet.analytics.metrics.model.RuleEvidence;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.RuleType;
import com.fleet.analytics.metrics.model.Severity;
import com.fleet.analytics.metrics.model.TaskCounts;
import java.math.BigInteger;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Task failure spike (contract 6.2): has the failure rate risen by at least 8 percentage points
 * against the 28 complete days immediately before the selection?
 *
 * <p>The baseline is non-overlapping and is <em>not</em> the previous period — it is a fixed 28-day
 * trailing window, so a 7-day selection and a 60-day one are judged against the same amount of
 * history. Both windows must independently carry 20 terminal tasks; otherwise the rule reports
 * {@code not_evaluated} rather than a reassuring silence.
 *
 * <p>The trigger is exact. {@code failed/terminal - baselineFailed/baselineTerminal >= 8/100}
 * becomes a cross-multiplied comparison of integers, so a rise landing exactly on 8.0 points
 * triggers — which binary floating point cannot be trusted to decide.
 */
@Component
public class TaskFailureSpikeRule {

    /** Contract 6.2 and research 8: a rise of at least 8 percentage points. */
    static final int THRESHOLD_POINTS = 8;

    public RuleEvaluation evaluate(RuleScope scope, TaskCounts current, TaskCounts baseline,
            FailureReasonGroups reasons, LocalDate from, LocalDate to) {
        if (!MetricGate.TERMINAL_TASKS.isMetBy(current.terminal())
                || !MetricGate.TERMINAL_TASKS.isMetBy(baseline.terminal())) {
            return new RuleEvaluation.Unavailable(new EvaluationLimit(
                    RuleType.TASK_FAILURE_SPIKE, scope, EvaluationState.NOT_EVALUATED,
                    Explanations.ruleGate(MetricGate.TERMINAL_TASKS,
                            "in the selected period and in the 28-day baseline",
                            current.terminal(), baseline.terminal())));
        }

        // failedNow/terminalNow - failedThen/terminalThen, as one exact rational.
        ExactMagnitude rise = RuleArithmetic.ratioDifference(
                BigInteger.valueOf(current.failed()), current.terminalExact(),
                BigInteger.valueOf(baseline.failed()), baseline.terminalExact());

        if (!RuleArithmetic.atLeastPercentagePoints(rise, THRESHOLD_POINTS)) {
            return RuleEvaluation.Completed.withNoFinding(RuleType.TASK_FAILURE_SPIKE, scope);
        }

        DisplayValue magnitude = DisplayValue.of(
                MetricCalculator.percentagePointChange(
                        BigInteger.valueOf(current.failed()), current.terminalExact(),
                        BigInteger.valueOf(baseline.failed()), baseline.terminalExact()),
                DisplayUnit.PERCENTAGE_POINTS);

        return RuleEvaluation.Completed.with(RuleType.TASK_FAILURE_SPIKE, scope,
                new FindingCandidate(
                        FindingIdentity.of(RuleType.TASK_FAILURE_SPIKE, scope, periodKey(from, to)),
                        scope, Severity.MEDIUM, rise, magnitude,
                        new RuleEvidence.FailureSpike(current, baseline, reasons, THRESHOLD_POINTS),
                        from, to));
    }

    static String periodKey(LocalDate from, LocalDate to) {
        return from + "/" + to;
    }
}
