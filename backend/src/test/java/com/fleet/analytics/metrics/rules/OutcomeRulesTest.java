package com.fleet.analytics.metrics.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleet.analytics.metrics.model.FailureReasonGroups;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.RuleEvaluation;
import com.fleet.analytics.metrics.model.RuleEvidence;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.RuleType;
import com.fleet.analytics.metrics.model.Severity;
import com.fleet.analytics.metrics.model.TaskCounts;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Contract 8.5 cases F1 to F3 and M1 to M2: the two rate rules, their independent gates, their
 * different baselines, and the exact threshold that binary floating point gets wrong.
 */
class OutcomeRulesTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 16);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);
    private static final RuleScope REPO = RuleScope.repository(
            UUID.fromString("3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e001"), "repo-api");

    private final TaskFailureSpikeRule failureSpike = new TaskFailureSpikeRule();
    private final MergeRateDeclineRule mergeDecline = new MergeRateDeclineRule();

    private static FindingCandidate findingFrom(RuleEvaluation evaluation) {
        assertThat(evaluation).isInstanceOf(RuleEvaluation.Completed.class);
        RuleEvaluation.Completed completed = (RuleEvaluation.Completed) evaluation;
        assertThat(completed.candidates()).hasSize(1);
        return completed.candidates().getFirst();
    }

    private static boolean isCompletedWithNoFinding(RuleEvaluation evaluation) {
        return evaluation instanceof RuleEvaluation.Completed completed
                && completed.candidates().isEmpty();
    }

    /**
     * The reason groups must partition the window's failed tasks, so the helper attributes them all
     * to one family. Passing {@code NONE} beside a positive failed count would build evidence the
     * published schema forbids.
     */
    private RuleEvaluation spike(TaskCounts current, TaskCounts baseline) {
        return failureSpike.evaluate(REPO, current, baseline,
                new FailureReasonGroups(current.failed(), 0, 0), FROM, TO);
    }

    // --- Failure spike (contract 8.5 F1 to F3) ----------------------------------------------------

    /** F1: 12/24 failed against 16/40 is a 10.0 point rise, comfortably over the 8-point trigger. */
    @Test
    void aTenPointRiseInFailureRateTriggers() {
        FindingCandidate finding =
                findingFrom(spike(new TaskCounts(12, 12), new TaskCounts(24, 16)));

        assertThat(finding.ruleType()).isEqualTo(RuleType.TASK_FAILURE_SPIKE);
        assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(finding.displayMagnitude().value()).isEqualTo("10.0");
    }

    /** F2: the same current window against a 42.5% baseline is a 7.5 point rise — below the trigger. */
    @Test
    void aSevenAndAHalfPointRiseDoesNotTrigger() {
        assertThat(isCompletedWithNoFinding(spike(new TaskCounts(12, 12), new TaskCounts(23, 17))))
                .isTrue();
    }

    /**
     * F3: a 19-task baseline is below the gate, so the rule reports that it could not evaluate. It
     * must not fall through to "no finding", which would read as a clean bill of health.
     */
    @Test
    void aBaselineBelowTheGateIsNotEvaluatedRatherThanHealthy() {
        RuleEvaluation evaluation = spike(new TaskCounts(12, 12), new TaskCounts(10, 9));

        assertThat(evaluation).isInstanceOf(RuleEvaluation.Unavailable.class);
        assertThat(((RuleEvaluation.Unavailable) evaluation).limit().explanation().text())
                .isEqualTo("Needs 20 completed or failed code-change tasks in the selected period "
                        + "and in the 28-day baseline; this scope had 24 and 19.");
    }

    /** Each window is gated independently: a large baseline cannot rescue a thin current period. */
    @Test
    void eachWindowIsGatedIndependently() {
        assertThat(spike(new TaskCounts(9, 10), new TaskCounts(24, 16)))
                .isInstanceOf(RuleEvaluation.Unavailable.class);
        assertThat(spike(new TaskCounts(12, 12), new TaskCounts(12, 12)))
                .isInstanceOf(RuleEvaluation.Completed.class);
    }

    /**
     * The failure-reason grouping travels with the finding as its evidence line (research 6.4), and
     * the three groups must partition the window's failed tasks — 7 + 3 + 2 against 12 failed.
     */
    @Test
    void aFailureSpikeCarriesGroupedReasonsThatPartitionItsFailures() {
        FindingCandidate finding = findingFrom(failureSpike.evaluate(REPO, new TaskCounts(12, 12),
                new TaskCounts(24, 16), new FailureReasonGroups(7, 3, 2), FROM, TO));

        RuleEvidence.FailureSpike evidence = (RuleEvidence.FailureSpike) finding.evidence();
        assertThat(evidence.reasons().agent()).isEqualTo(7);
        assertThat(evidence.reasons().platform()).isEqualTo(3);
        assertThat(evidence.reasons().policy()).isEqualTo(2);
        assertThat(evidence.reasons().sumsTo(evidence.current().failed())).isTrue();
        assertThat(evidence.thresholdPoints()).isEqualTo(8);
    }

    /**
     * Coverage can declare run data complete while a failed task has no failed run to attribute.
     * The published schema states the three groups partition the failed tasks, and there is no
     * honest way to serve a shortfall — inventing a group fabricates a cause, and emitting groups
     * that do not add up breaks the contract. Construction fails loudly instead.
     */
    @Test
    void reasonGroupsThatDoNotPartitionTheFailedTasksAreRejected() {
        assertThatThrownBy(() -> failureSpike.evaluate(REPO, new TaskCounts(12, 12),
                        new TaskCounts(24, 16), new FailureReasonGroups(7, 3, 0), FROM, TO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must partition the window's failed tasks")
                .hasMessageContaining("10 attributed of 12 failed");

        // Over-attribution is equally rejected: it would overstate the failures being explained.
        assertThatThrownBy(() -> failureSpike.evaluate(REPO, new TaskCounts(12, 12),
                        new TaskCounts(24, 16), new FailureReasonGroups(7, 3, 5), FROM, TO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A non-triggering evaluation builds no evidence, so an unattributed shortfall cannot bite. */
    @Test
    void aNonTriggeringSpikeIsUnaffectedByReasonAttribution() {
        assertThat(isCompletedWithNoFinding(failureSpike.evaluate(REPO, new TaskCounts(12, 12),
                        new TaskCounts(23, 17), FailureReasonGroups.NONE, FROM, TO)))
                .isTrue();
    }

    // --- Merge decline (contract 8.5 M1 to M2) -------------------------------------------------------

    /** M1: 12/20 down to 8/16 is a 10.0 point decline. */
    @Test
    void aTenPointDeclineInMergeRateTriggers() {
        FindingCandidate finding = findingFrom(mergeDecline.evaluate(
                REPO, new PrCounts(8, 8), new PrCounts(12, 8), FROM, TO));

        assertThat(finding.ruleType()).isEqualTo(RuleType.MERGE_RATE_DECLINE);
        assertThat(finding.displayMagnitude().value()).isEqualTo("10.0");
    }

    /**
     * M2, the reason exact arithmetic is mandated. 29/50 down to 8/16 is exactly 8 percentage
     * points; in IEEE-754 doubles the same subtraction yields 7.999999999999993 and a naive
     * {@code >= 8} test silently declines to report a real decline.
     */
    @Test
    void aDeclineOfExactlyEightPointsTriggers() {
        FindingCandidate finding = findingFrom(mergeDecline.evaluate(
                REPO, new PrCounts(8, 8), new PrCounts(29, 21), FROM, TO));

        assertThat(finding.displayMagnitude().value()).isEqualTo("8.0");
        assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
    }

    /** Just under the boundary must not trigger, or the threshold would mean nothing. */
    @Test
    void aDeclineJustUnderEightPointsDoesNotTrigger() {
        // 28/50 = 56.0% down to 8/16 = 50.0%: a 6.0 point decline.
        assertThat(isCompletedWithNoFinding(mergeDecline.evaluate(
                        REPO, new PrCounts(8, 8), new PrCounts(28, 22), FROM, TO)))
                .isTrue();
    }

    /** An improving merge rate is not a finding: the rule reports decline, not change. */
    @Test
    void animprovingMergeRateProducesNoFinding() {
        assertThat(isCompletedWithNoFinding(mergeDecline.evaluate(
                        REPO, new PrCounts(12, 8), new PrCounts(8, 8), FROM, TO)))
                .isTrue();
    }

    @ParameterizedTest(name = "current {0}/{1}, baseline {2}/{3}")
    @CsvSource({
        "7, 7, 12, 8",    // current 14 terminal, below the 15 gate
        "8, 8, 7, 7",     // baseline 14 terminal, below the gate
        "7, 7, 7, 7",     // both below
    })
    void mergeDeclineNeedsFifteenTerminalPrsInEachPeriod(
            int currentMerged, int currentClosed, int baselineMerged, int baselineClosed) {
        RuleEvaluation evaluation = mergeDecline.evaluate(REPO,
                new PrCounts(currentMerged, currentClosed),
                new PrCounts(baselineMerged, baselineClosed), FROM, TO);

        assertThat(evaluation).isInstanceOf(RuleEvaluation.Unavailable.class);
        assertThat(((RuleEvaluation.Unavailable) evaluation).limit().explanation().code())
                .isEqualTo("gate_terminal_prs_15");
    }

    /**
     * The two rules use different baselines — a trailing 28 days versus the previous equal-length
     * period — so the same scope can be evaluable for one and not the other.
     */
    @Test
    void theTwoRulesCarryTheirOwnEvaluationPeriodKeys() {
        FindingCandidate spikeFinding =
                findingFrom(spike(new TaskCounts(12, 12), new TaskCounts(24, 16)));
        FindingCandidate declineFinding = findingFrom(mergeDecline.evaluate(
                REPO, new PrCounts(8, 8), new PrCounts(12, 8), FROM, TO));

        assertThat(spikeFinding.identity().evaluationPeriodKey()).isEqualTo("2026-01-16/2026-01-31");
        assertThat(declineFinding.identity().evaluationPeriodKey())
                .isEqualTo("2026-01-16/2026-01-31");
        assertThat(spikeFinding.identity()).isNotEqualTo(declineFinding.identity());
    }
}
