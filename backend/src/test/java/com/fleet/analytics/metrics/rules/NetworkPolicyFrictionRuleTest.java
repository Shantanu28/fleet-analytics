package com.fleet.analytics.metrics.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.metrics.model.DomainCounts;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.RuleEvaluation;
import com.fleet.analytics.metrics.model.RuleEvidence;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.Severity;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Contract 8.5 cases N1 to N3 and N6: both thresholds, and domains that must never be merged. */
class NetworkPolicyFrictionRuleTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 16);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);
    private static final RuleScope TEAM =
            RuleScope.team(UUID.fromString("2a1f0c64-1d3b-4f7a-9c02-5e8b7a10d002"), "Payments");

    private final NetworkPolicyFrictionRule rule = new NetworkPolicyFrictionRule();

    private static List<FindingCandidate> findings(RuleEvaluation evaluation) {
        assertThat(evaluation).isInstanceOf(RuleEvaluation.Completed.class);
        return ((RuleEvaluation.Completed) evaluation).candidates();
    }

    /** N1: six tasks across four owners clears both thresholds; magnitude is the task count. */
    @Test
    void aDomainClearingBothThresholdsProducesAFinding() {
        List<FindingCandidate> found = findings(rule.evaluate(TEAM,
                List.of(new DomainCounts("internal-registry.corp", 6, 4)), FROM, TO));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().severity()).isEqualTo(Severity.MEDIUM);
        assertThat(found.getFirst().displayMagnitude().value()).isEqualTo("6");
        assertThat(((RuleEvidence.Friction) found.getFirst().evidence()).counts().normalisedDomain())
                .isEqualTo("internal-registry.corp");
    }

    /**
     * N2 and N6. Both conditions are required: five tasks from two people is one person's
     * misconfiguration, not a policy problem, and the rule must not report it as one.
     */
    @ParameterizedTest(name = "{0} tasks / {1} users -> finding={2}")
    @CsvSource({
        "5, 3, true",
        "6, 4, true",
        "4, 3, false",
        "5, 2, false",
        "6, 2, false",
        "4, 9, false",
        "99, 2, false",
    })
    void bothThresholdsAreRequiredAndInclusive(long tasks, long users, boolean expected) {
        List<FindingCandidate> found =
                findings(rule.evaluate(TEAM, List.of(new DomainCounts("a.corp", tasks, users)),
                        FROM, TO));

        assertThat(found.isEmpty()).isNotEqualTo(expected);
    }

    /** N3: two qualifying domains are two separate problems and are never collapsed into one. */
    @Test
    void distinctDomainsProduceDistinctFindings() {
        List<FindingCandidate> found = findings(rule.evaluate(TEAM,
                List.of(new DomainCounts("a.corp", 5, 3), new DomainCounts("b.corp", 5, 3)),
                FROM, TO));

        assertThat(found).hasSize(2);
        assertThat(found).extracting(candidate -> candidate.identity().normalisedDomain())
                .containsExactly("a.corp", "b.corp");
        assertThat(found.get(0).identity()).isNotEqualTo(found.get(1).identity());
    }

    /**
     * Several findings from one look. The scope was evaluated exactly once however many domains
     * qualified, which is what keeps the completed-evaluation count honest.
     */
    @Test
    void severalQualifyingDomainsRemainOneEvaluation() {
        RuleEvaluation evaluation = rule.evaluate(TEAM,
                List.of(new DomainCounts("a.corp", 5, 3), new DomainCounts("b.corp", 8, 6)),
                FROM, TO);

        assertThat(evaluation).isInstanceOf(RuleEvaluation.Completed.class);
        assertThat(((RuleEvaluation.Completed) evaluation).candidates()).hasSize(2);
    }

    /**
     * The contract fixture's case: complete denial data with no events at all. That is a finished
     * look with nothing to report — a completed evaluation, not a limit (approved decision D-5).
     */
    @Test
    void noDeniedDomainsIsACompletedEvaluationWithNoFinding() {
        RuleEvaluation evaluation = rule.evaluate(TEAM, List.of(), FROM, TO);

        assertThat(evaluation).isInstanceOf(RuleEvaluation.Completed.class);
        assertThat(((RuleEvaluation.Completed) evaluation).candidates()).isEmpty();
    }

    @Test
    void theThresholdsTravelWithTheEvidence() {
        RuleEvidence.Friction evidence = (RuleEvidence.Friction) findings(rule.evaluate(TEAM,
                List.of(new DomainCounts("a.corp", 5, 3)), FROM, TO)).getFirst().evidence();

        assertThat(evidence.taskThreshold()).isEqualTo(5);
        assertThat(evidence.userThreshold()).isEqualTo(3);
    }
}
