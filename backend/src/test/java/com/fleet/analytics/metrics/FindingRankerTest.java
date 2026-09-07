package com.fleet.analytics.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.DomainCounts;
import com.fleet.analytics.metrics.model.ExactMagnitude;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.FindingIdentity;
import com.fleet.analytics.metrics.model.RuleEvidence;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.RuleType;
import com.fleet.analytics.metrics.model.Severity;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Contract 6.5: deduplication, the five ordering keys, totality, and the three-finding cap. */
class FindingRankerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 16);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);
    private static final UUID LOWER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HIGHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final FindingRanker ranker = new FindingRanker();

    private static FindingCandidate candidate(
            RuleType rule, Severity severity, RuleScope scope, long magnitude) {
        return candidate(rule, severity, scope, ExactMagnitude.ofWhole(magnitude), null);
    }

    private static FindingCandidate candidate(RuleType rule, Severity severity, RuleScope scope,
            ExactMagnitude magnitude, String domain) {
        FindingIdentity identity = domain == null
                ? FindingIdentity.of(rule, scope, "2026-01-16/2026-01-31")
                : FindingIdentity.ofDomain(rule, scope, "2026-01-16/2026-01-31", domain);
        return new FindingCandidate(identity, scope, severity, magnitude,
                DisplayValue.count(1),
                new RuleEvidence.Friction(new DomainCounts(domain == null ? "x" : domain, 5, 3), 5, 3),
                FROM, TO);
    }

    /**
     * Contract 8.5 case S1: a HIGH budget finding, a 10.0-point failure spike, a 10.0-point merge
     * decline and a network finding. Severity wins first, rule order breaks the three-way MEDIUM
     * tie, and the fourth is cut.
     */
    @Test
    void severityThenRuleOrderSelectsTheApprovedThree() {
        RuleScope team = RuleScope.team(LOWER, "Payments");
        List<FindingCandidate> ranked = ranker.rank(List.of(
                candidate(RuleType.NETWORK_POLICY_FRICTION, Severity.MEDIUM, team,
                        ExactMagnitude.ofWhole(6), "a.corp"),
                candidate(RuleType.MERGE_RATE_DECLINE, Severity.MEDIUM, team, 10),
                candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM, team, 10),
                candidate(RuleType.BUDGET_RISK, Severity.HIGH, team, 25)));

        assertThat(ranked).extracting(FindingCandidate::ruleType).containsExactly(
                RuleType.BUDGET_RISK, RuleType.TASK_FAILURE_SPIKE, RuleType.MERGE_RATE_DECLINE);
    }

    /** A MEDIUM budget finding still outranks every other rule: rule order is the second key. */
    @Test
    void ruleOrderBreaksTiesWithinOneSeverity() {
        RuleScope team = RuleScope.team(LOWER, "Payments");
        List<FindingCandidate> ranked = ranker.rank(List.of(
                candidate(RuleType.MERGE_RATE_DECLINE, Severity.MEDIUM, team, 99),
                candidate(RuleType.BUDGET_RISK, Severity.MEDIUM, team, 11)));

        assertThat(ranked).extracting(FindingCandidate::ruleType)
                .containsExactly(RuleType.BUDGET_RISK, RuleType.MERGE_RATE_DECLINE);
    }

    /**
     * Magnitudes are compared by cross-multiplication, so two rationals that round to the same
     * display string still order correctly. 1/3 and 100001/300000 both render as 33.3%.
     */
    @Test
    void magnitudeOrdersExactlyRatherThanByRoundedDisplay() {
        RuleScope team = RuleScope.team(LOWER, "Payments");
        ExactMagnitude oneThird = ExactMagnitude.of(BigInteger.ONE, BigInteger.valueOf(3));
        ExactMagnitude justAbove =
                ExactMagnitude.of(BigInteger.valueOf(100001), BigInteger.valueOf(300000));

        assertThat(justAbove).isGreaterThan(oneThird);

        List<FindingCandidate> ranked = ranker.rank(List.of(
                candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM,
                        RuleScope.team(HIGHER, "B"), oneThird, null),
                candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM, team, justAbove, null)));

        assertThat(ranked.getFirst().scope().scopeId()).isEqualTo(LOWER);
    }

    /**
     * Contract 6.5 step 4 is "scope_type ascending" without defining an order, and step 5 is the
     * identity <em>string</em> ascending. Ascending canonical strings is the only reading under
     * which the two agree — "organisation" &lt; "repository" &lt; "team" — so a repository sorts
     * ahead of a team at equal magnitude. Enum declaration order would have step 4 and step 5
     * pulling in opposite directions.
     *
     * <p>Within a scope type the id breaks the tie, and the display name never does: a rename or a
     * translation must not reorder the panel.
     */
    @Test
    void stableIdentifiersBreakEqualMagnitudesByCanonicalScopeTypeThenId() {
        List<FindingCandidate> ranked = ranker.rankAll(List.of(
                candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM,
                        RuleScope.team(HIGHER, "zzz-team"), 10),
                candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM,
                        RuleScope.repository(HIGHER, "aaa-repo"), 10),
                candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM,
                        RuleScope.team(LOWER, "mmm-team"), 10),
                candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM,
                        RuleScope.repository(LOWER, "nnn-repo"), 10)));

        assertThat(ranked).extracting(candidate -> candidate.scope().scopeName())
                .containsExactly("nnn-repo", "aaa-repo", "mmm-team", "zzz-team");
    }

    /** Step 4 and step 5 must not disagree: the identity string uses the same canonical names. */
    @Test
    void theIdentityTieBreakerUsesTheSameCanonicalNamesAsTheScopeTypeKey() {
        String team = FindingIdentity.of(RuleType.TASK_FAILURE_SPIKE,
                RuleScope.team(LOWER, "T"), "2026-01-16/2026-01-31").orderingKey();
        String repository = FindingIdentity.of(RuleType.TASK_FAILURE_SPIKE,
                RuleScope.repository(LOWER, "R"), "2026-01-16/2026-01-31").orderingKey();

        assertThat(repository).isLessThan(team);
        assertThat(repository).startsWith("task_failure_spike:repository:");
    }

    /**
     * The order must be total. A team and a repository sharing an id, at equal magnitude, still sort
     * deterministically — and so do two domains within one scope.
     */
    @Test
    void theOrderIsTotalEvenWhenScopeTypesShareAnIdentifier() {
        List<FindingCandidate> ranked = ranker.rankAll(List.of(
                candidate(RuleType.NETWORK_POLICY_FRICTION, Severity.MEDIUM,
                        RuleScope.team(LOWER, "Payments"), ExactMagnitude.ofWhole(5), "b.corp"),
                candidate(RuleType.NETWORK_POLICY_FRICTION, Severity.MEDIUM,
                        RuleScope.team(LOWER, "Payments"), ExactMagnitude.ofWhole(5), "a.corp")));

        assertThat(ranked).extracting(candidate -> candidate.identity().normalisedDomain())
                .containsExactly("a.corp", "b.corp");
    }

    /** Repeated ranking of the same inputs is identical, whatever order they arrive in (AC-06.1). */
    @Test
    void rankingIsStableAcrossInputOrderings() {
        RuleScope team = RuleScope.team(LOWER, "Payments");
        RuleScope repo = RuleScope.repository(HIGHER, "repo-api");
        List<FindingCandidate> all = List.of(
                candidate(RuleType.MERGE_RATE_DECLINE, Severity.MEDIUM, repo, 12),
                candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM, team, 9),
                candidate(RuleType.NETWORK_POLICY_FRICTION, Severity.MEDIUM, team,
                        ExactMagnitude.ofWhole(7), "a.corp"),
                candidate(RuleType.BUDGET_RISK, Severity.MEDIUM, team, 15));

        List<FindingCandidate> forwards = ranker.rankAll(all);
        List<FindingCandidate> backwards = ranker.rankAll(all.reversed());

        assertThat(forwards).isEqualTo(backwards);
    }

    /** Only identical identities collapse; a repository finding is never absorbed by its team's. */
    @Test
    void onlyIdenticalIdentitiesAreDeduplicated() {
        RuleScope team = RuleScope.team(LOWER, "Payments");
        FindingCandidate duplicate = candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM, team, 10);

        List<FindingCandidate> ranked = ranker.rankAll(List.of(
                duplicate, duplicate,
                candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM,
                        RuleScope.repository(LOWER, "repo-api"), 10)));

        assertThat(ranked).hasSize(2);
    }

    @Test
    void atMostThreeFindingsAreReturnedHoweverManyFired() {
        RuleScope team = RuleScope.team(LOWER, "Payments");
        List<FindingCandidate> many = List.of(
                candidate(RuleType.BUDGET_RISK, Severity.HIGH, team, 30),
                candidate(RuleType.TASK_FAILURE_SPIKE, Severity.MEDIUM, team, 20),
                candidate(RuleType.MERGE_RATE_DECLINE, Severity.MEDIUM, team, 15),
                candidate(RuleType.NETWORK_POLICY_FRICTION, Severity.MEDIUM, team,
                        ExactMagnitude.ofWhole(9), "a.corp"),
                candidate(RuleType.NETWORK_POLICY_FRICTION, Severity.MEDIUM, team,
                        ExactMagnitude.ofWhole(8), "b.corp"));

        assertThat(ranker.rank(many)).hasSize(3);
        assertThat(ranker.rankAll(many)).hasSize(5);
    }
}
