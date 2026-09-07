package com.fleet.analytics.web.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleet.analytics.metrics.model.AttentionResult;
import com.fleet.analytics.metrics.model.BudgetSample;
import com.fleet.analytics.metrics.model.DashboardSelection;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.DomainCounts;
import com.fleet.analytics.metrics.model.ExactMagnitude;
import com.fleet.analytics.metrics.model.FailureReasonGroups;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.FindingIdentity;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.RuleEvidence;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.RuleType;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.Severity;
import com.fleet.analytics.metrics.model.TaskCounts;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Role-safe presentation and the tri-state navigation patch — the two places a mistake is silent.
 *
 * <p>Redaction is asserted over the <em>serialized body</em>, not over one field, because a leak
 * that matters is a domain appearing anywhere: a nested copy, an explanation, a link.
 */
class FindingPresenterTest {

    private static final UUID TEAM = UUID.fromString("2a1f0c64-1d3b-4f7a-9c02-5e8b7a10d002");
    private static final UUID REPO = UUID.fromString("3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e001");
    private static final String DOMAIN = "internal-registry.corp";
    private static final LocalDate FROM = LocalDate.of(2026, 1, 16);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);

    private final ObjectMapper json = new ObjectMapper();
    private final FindingPresenter presenter = new FindingPresenter(new FindingLinkBuilder());

    private static DashboardSelection selection() {
        return new DashboardSelection(DateWindow.ofInclusiveDates(FROM, TO),
                ScopeFilters.none(), Grouping.TEAMS);
    }

    private static FindingCandidate frictionFinding(RuleScope scope) {
        return new FindingCandidate(
                FindingIdentity.ofDomain(
                        RuleType.NETWORK_POLICY_FRICTION, scope, "2026-01-16/2026-01-31", DOMAIN),
                scope, Severity.MEDIUM, ExactMagnitude.ofWhole(6), DisplayValue.count(6),
                new RuleEvidence.Friction(new DomainCounts(DOMAIN, 6, 4), 5, 3), FROM, TO);
    }

    private static FindingCandidate budgetFinding(RuleScope scope) {
        return new FindingCandidate(
                FindingIdentity.of(RuleType.BUDGET_RISK, scope, "2026-02"),
                scope, Severity.MEDIUM,
                ExactMagnitude.of(BigInteger.valueOf(18), BigInteger.valueOf(100)),
                new DisplayValue("18.0", DisplayUnit.PERCENT),
                new RuleEvidence.Budget(new BudgetSample(
                        BigInteger.valueOf(1000000), BigInteger.valueOf(590000), 14, 28)),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 14));
    }

    private AttentionResponse present(List<FindingCandidate> findings, String role) {
        return presenter.present(
                new AttentionResult(findings, findings.size(), List.of()), role, selection());
    }

    private String serialized(List<FindingCandidate> findings, String role) {
        return json.writeValueAsString(present(findings, role));
    }

    // --- Redaction (AC-06.9, research 9) --------------------------------------------------------

    @Test
    void anAdminSeesTheDeniedDomain() {
        AttentionResponse response =
                present(List.of(frictionFinding(RuleScope.team(TEAM, "Payments"))), "ADMIN");

        assertThat(response.findings().getFirst().evidence().domain()).isEqualTo(DOMAIN);
        assertThat(serialized(List.of(frictionFinding(RuleScope.team(TEAM, "Payments"))), "ADMIN"))
                .contains(DOMAIN);
    }

    /**
     * The whole serialized body, not one field. A leak that matters is the domain appearing
     * anywhere at all — in evidence, in a link or in an explanation.
     */
    @Test
    void aViewerBodyContainsNoTraceOfTheDomain() {
        String body = serialized(List.of(frictionFinding(RuleScope.team(TEAM, "Payments"))), "VIEWER");

        assertThat(body).doesNotContain(DOMAIN)
                .doesNotContain("internal-registry")
                .doesNotContain("registry")
                .doesNotContain("corp");
        assertThat(json.readTree(body).get("findings").get(0).has("id")).isFalse();
    }

    /** Everything except the domain is identical between roles — counts, order, severity. */
    @Test
    void everythingButTheDomainIsIdenticalAcrossRoles() {
        RuleScope scope = RuleScope.team(TEAM, "Payments");
        FindingResponse admin = present(List.of(frictionFinding(scope)), "ADMIN").findings().getFirst();
        FindingResponse viewer =
                present(List.of(frictionFinding(scope)), "VIEWER").findings().getFirst();

        assertThat(viewer.severity()).isEqualTo(admin.severity());
        assertThat(viewer.magnitude()).isEqualTo(admin.magnitude());
        assertThat(viewer.scopeId()).isEqualTo(admin.scopeId());
        assertThat(viewer.link()).isEqualTo(admin.link());
        assertThat(viewer.evidence().distinctTasks()).isEqualTo(admin.evidence().distinctTasks());
        assertThat(viewer.evidence().distinctUsers()).isEqualTo(admin.evidence().distinctUsers());
        assertThat(viewer.evidence().domain()).isNull();
    }

    /** Presenting for a VIEWER must not damage the internal result a later ADMIN view would use. */
    @Test
    void presentingDoesNotMutateTheInternalFinding() {
        FindingCandidate finding = frictionFinding(RuleScope.team(TEAM, "Payments"));

        present(List.of(finding), "VIEWER");

        assertThat(((RuleEvidence.Friction) finding.evidence()).counts().normalisedDomain())
                .isEqualTo(DOMAIN);
        assertThat(present(List.of(finding), "ADMIN").findings().getFirst().evidence().domain())
                .isEqualTo(DOMAIN);
    }

    // --- Navigation patches (AC-06.4 to AC-06.7) ---------------------------------------------------

    /**
     * The case a blanket "omit nulls" serializer would break: a budget link must actively clear the
     * repository filter. Omitted, the user follows the link into a repository scope where budgets
     * are not evaluated at all and the finding cannot be reproduced.
     */
    @Test
    void aBudgetLinkClearsTheRepositoryFilterWithAnExplicitNull() {
        String body = serialized(List.of(budgetFinding(RuleScope.team(TEAM, "Payments"))), "ADMIN");

        assertThat(body).contains("\"repositoryId\":null");
        var link = json.readTree(body).get("findings").get(0).get("link");
        assertThat(link.has("repositoryId")).isTrue();
        // Flattened onto the link, not nested: the contract declares no `patch` property, and a
        // nested copy beside the flattened keys would be an undeclared field.
        assertThat(link.has("patch")).isFalse();
    }

    @Test
    void anOrganisationBudgetLinkClearsBothFilters() {
        String body = serialized(
                List.of(budgetFinding(RuleScope.organisation("Fleet"))), "ADMIN");
        var link = json.readTree(body).get("findings").get(0).get("link");

        assertThat(link.get("teamId").isNull()).isTrue();
        assertThat(link.get("repositoryId").isNull()).isTrue();
        assertThat(link.get("section").asString()).isEqualTo("spendTrend");
        // The evaluated month, not the selected range.
        assertThat(link.get("from").asString()).isEqualTo("2026-02-01");
        assertThat(link.get("periodChanged").asBoolean()).isTrue();
    }

    /**
     * A repository finding sets its own dimension and says nothing about the team — absent is how
     * the user's existing team filter is preserved (AC-06.12).
     */
    @Test
    void aRepositoryFindingLinkOmitsTheTeamKeyEntirely() {
        FindingCandidate finding = new FindingCandidate(
                FindingIdentity.of(RuleType.TASK_FAILURE_SPIKE,
                        RuleScope.repository(REPO, "repo-api"), "2026-01-16/2026-01-31"),
                RuleScope.repository(REPO, "repo-api"), Severity.MEDIUM,
                ExactMagnitude.ofWhole(12),
                new DisplayValue("12.0", DisplayUnit.PERCENTAGE_POINTS),
                new RuleEvidence.FailureSpike(new TaskCounts(12, 12), new TaskCounts(24, 16),
                        new FailureReasonGroups(7, 3, 2), 8),
                FROM, TO);

        var link = json.readTree(serialized(List.of(finding), "ADMIN"))
                .get("findings").get(0).get("link");

        assertThat(link.has("teamId")).isFalse();
        assertThat(link.has("from")).isFalse();
        assertThat(link.get("repositoryId").asString()).isEqualTo(REPO.toString());
        assertThat(link.get("grouping").asString()).isEqualTo("repositories");
        assertThat(link.get("focusRowId").asString()).isEqualTo(REPO.toString());
        assertThat(link.get("section").asString()).isEqualTo("comparisonTable");
    }

    /** A friction link targets the panel and pins no table row: there is no fourth finding promised. */
    @Test
    void aFrictionLinkTargetsAttentionWithoutAFocusedRow() {
        var link = json.readTree(serialized(
                        List.of(frictionFinding(RuleScope.team(TEAM, "Payments"))), "ADMIN"))
                .get("findings").get(0).get("link");

        assertThat(link.get("section").asString()).isEqualTo("attention");
        assertThat(link.has("focusRowId")).isFalse();
        assertThat(link.has("from")).isFalse();
        assertThat(link.get("teamId").asString()).isEqualTo(TEAM.toString());
    }

    // --- Evidence shapes -----------------------------------------------------------------------------

    @Test
    void aFailureSpikeCarriesItsRatesThresholdAndGroupedReasons() {
        FindingCandidate finding = new FindingCandidate(
                FindingIdentity.of(RuleType.TASK_FAILURE_SPIKE,
                        RuleScope.repository(REPO, "repo-api"), "2026-01-16/2026-01-31"),
                RuleScope.repository(REPO, "repo-api"), Severity.MEDIUM, ExactMagnitude.ofWhole(10),
                new DisplayValue("10.0", DisplayUnit.PERCENTAGE_POINTS),
                new RuleEvidence.FailureSpike(new TaskCounts(12, 12), new TaskCounts(24, 16),
                        new FailureReasonGroups(7, 3, 2), 8),
                FROM, TO);

        var evidence = present(List.of(finding), "ADMIN").findings().getFirst().evidence();

        assertThat(evidence.currentRate().value()).isEqualTo("50.0");
        assertThat(evidence.baselineRate().value()).isEqualTo("40.0");
        assertThat(evidence.thresholdPercentagePoints().value()).isEqualTo("8.0");
        assertThat(evidence.failedTasks()).isEqualTo(12);
        assertThat(evidence.failureReasons().agent()).isEqualTo(7);
        assertThat(evidence.failureReasons().policy()).isEqualTo(2);
        // Budget-only fields are omitted rather than nulled into the shape.
        assertThat(evidence.budgetCents()).isNull();
    }

    @Test
    void aBudgetFindingCarriesItsForecastAndOverrun() {
        var evidence = present(List.of(budgetFinding(RuleScope.team(TEAM, "Payments"))), "ADMIN")
                .findings().getFirst().evidence();

        assertThat(evidence.budgetCents()).isEqualTo(1_000_000L);
        assertThat(evidence.monthToDateSpendCents()).isEqualTo(590_000L);
        assertThat(evidence.elapsedDays()).isEqualTo(14);
        assertThat(evidence.forecast().value()).isEqualTo("11800");
        assertThat(evidence.overrun().value()).isEqualTo("18.0");
        assertThat(evidence.domain()).isNull();
    }

    // --- JSON-safe numeric boundaries, through the real presentation path -----------------------

    /**
     * Budget evidence must go through the shared guard rather than {@code longValueExact()}, which
     * accepts anything up to {@code Long.MAX_VALUE} and hands a client a cent total it silently
     * rounds on parse. Driven through {@code present(...)} so the guard is proven to be on the path
     * a response actually takes, not merely to exist.
     */
    @Test
    void anOversizedBudgetIsRejectedByThePresentationPath() {
        BigInteger beyondSafe =
                BigInteger.valueOf(9_007_199_254_740_991L).add(BigInteger.ONE);
        FindingCandidate finding = new FindingCandidate(
                FindingIdentity.of(RuleType.BUDGET_RISK, RuleScope.team(TEAM, "Payments"), "2026-02"),
                RuleScope.team(TEAM, "Payments"), Severity.MEDIUM,
                ExactMagnitude.of(BigInteger.valueOf(18), BigInteger.valueOf(100)),
                new DisplayValue("18.0", DisplayUnit.PERCENT),
                new RuleEvidence.Budget(new BudgetSample(
                        beyondSafe, BigInteger.valueOf(590000), 14, 28)),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 14));

        assertThatThrownBy(() -> present(List.of(finding), "ADMIN"))
                .isInstanceOf(JsonSafeInteger.UnsafeNumericRangeException.class)
                .hasMessageContaining("JSON-safe integer range");
    }

    /** The same guard on month-to-date spend, which is the figure most likely to grow. */
    @Test
    void anOversizedMonthToDateSpendIsRejectedByThePresentationPath() {
        BigInteger beyondSafe =
                BigInteger.valueOf(9_007_199_254_740_991L).add(BigInteger.TEN);
        FindingCandidate finding = new FindingCandidate(
                FindingIdentity.of(RuleType.BUDGET_RISK, RuleScope.team(TEAM, "Payments"), "2026-02"),
                RuleScope.team(TEAM, "Payments"), Severity.MEDIUM,
                ExactMagnitude.of(BigInteger.ONE, BigInteger.ONE),
                new DisplayValue("100.0", DisplayUnit.PERCENT),
                new RuleEvidence.Budget(new BudgetSample(
                        BigInteger.valueOf(1000000), beyondSafe, 14, 28)),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 14));

        assertThatThrownBy(() -> present(List.of(finding), "ADMIN"))
                .isInstanceOf(JsonSafeInteger.UnsafeNumericRangeException.class);
    }

    /** A value exactly on the boundary is still served: the guard rejects beyond, not at. */
    @Test
    void aBudgetExactlyOnTheSafeBoundaryIsStillServed() {
        BigInteger onBoundary = BigInteger.valueOf(9_007_199_254_740_991L);
        FindingCandidate finding = new FindingCandidate(
                FindingIdentity.of(RuleType.BUDGET_RISK, RuleScope.team(TEAM, "Payments"), "2026-02"),
                RuleScope.team(TEAM, "Payments"), Severity.MEDIUM,
                ExactMagnitude.of(BigInteger.ONE, BigInteger.ONE),
                new DisplayValue("100.0", DisplayUnit.PERCENT),
                new RuleEvidence.Budget(new BudgetSample(onBoundary, BigInteger.valueOf(1), 14, 28)),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 14));

        assertThat(present(List.of(finding), "ADMIN").findings().getFirst().evidence().budgetCents())
                .isEqualTo(onBoundary.longValueExact());
    }

    @Test
    void aMergeDeclineCarriesBothRates() {
        FindingCandidate finding = new FindingCandidate(
                FindingIdentity.of(RuleType.MERGE_RATE_DECLINE,
                        RuleScope.team(TEAM, "Payments"), "2026-01-16/2026-01-31"),
                RuleScope.team(TEAM, "Payments"), Severity.MEDIUM, ExactMagnitude.ofWhole(10),
                new DisplayValue("10.0", DisplayUnit.PERCENTAGE_POINTS),
                new RuleEvidence.MergeDecline(new PrCounts(8, 8), new PrCounts(12, 8), 8), FROM, TO);

        var evidence = present(List.of(finding), "ADMIN").findings().getFirst().evidence();

        assertThat(evidence.currentRate().value()).isEqualTo("50.0");
        assertThat(evidence.baselineRate().value()).isEqualTo("60.0");
        assertThat(evidence.failureReasons()).isNull();
    }
}
