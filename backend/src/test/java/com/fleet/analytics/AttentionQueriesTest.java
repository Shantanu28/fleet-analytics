package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.data.analytics.BudgetQueries;
import com.fleet.analytics.data.analytics.DenialQueries;
import com.fleet.analytics.data.analytics.FailureReasonQueries;
import com.fleet.analytics.metrics.model.BudgetConfiguration;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.DomainCounts;
import com.fleet.analytics.metrics.model.FailureReasonGroups;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.support.ContractFixture;
import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.IntegrationTestBase;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The attention rules' populations against real PostgreSQL: distinct counting, filter intersection
 * and failure-reason attribution.
 */
class AttentionQueriesTest extends IntegrationTestBase {

    private static final UUID ORG = UUID.fromString("e0000000-0000-0000-0000-00000000000e");
    private static final UUID OTHER_ORG = UUID.fromString("e1000000-0000-0000-0000-00000000001e");
    private static final DateWindow PERIOD = DateWindow.ofInclusiveDates(
            ContractFixture.PERIOD_FROM, ContractFixture.PERIOD_TO);

    private static ContractFixture fixture;
    private static ContractFixture otherFixture;

    @Autowired private BudgetQueries budgets;
    @Autowired private FailureReasonQueries failureReasons;
    @Autowired private DenialQueries denials;

    private static OffsetDateTime utc(int day, int hour) {
        return OffsetDateTime.of(2026, 1, day, hour, 0, 0, 0, ZoneOffset.UTC);
    }

    /**
     * The contract fixture plus denial and retry data it does not contain. Both are added here
     * rather than to the normative fixture: the ten-task dataset is the contract's, and a rule test
     * must not quietly change the numbers every other test asserts.
     */
    @BeforeAll
    static void install() throws SQLException {
        try (Connection c = connection()) {
            fixture = ContractFixture.install(c, ORG);
            otherFixture = ContractFixture.install(c, OTHER_ORG);

            // A qualifying denied domain in Payments: 5 distinct tasks across 3 distinct owners,
            // with one task denied repeatedly so distinct counting is actually exercised.
            denial(c, "T5", "internal-registry.corp", utc(18, 9));
            denial(c, "T5", "internal-registry.corp", utc(18, 10));
            denial(c, "T5", "internal-registry.corp", utc(18, 11));
            denial(c, "T6", "internal-registry.corp", utc(22, 14));
            denial(c, "T7", "internal-registry.corp", utc(30, 17));
            denial(c, "T8", "internal-registry.corp", utc(31, 23));
            // A fifth task owned by a third user, created inside the period.
            UUID extraTask = UUID.randomUUID();
            Fixtures.task(c, extraTask, ORG, fixture.id("T-PAY"), fixture.id("R-WEB"),
                    fixture.id("u6"), "denial-task", "research", utc(19, 8), "completed", utc(19, 9));
            Fixtures.run(c, UUID.randomUUID(), ORG, extraTask, "denial-run", 1,
                    utc(19, 8), utc(19, 9), "completed", null);
            Fixtures.denial(c, UUID.randomUUID(), ORG, extraTask, null, "denial-extra",
                    "Internal-Registry.Corp.", utc(19, 8));

            // A different domain, below both thresholds, that must never be merged with the above.
            denial(c, "T5", "backup.corp", utc(18, 12));

            // A boundary event at exactly the period end, which [start, end) excludes.
            Fixtures.denial(c, UUID.randomUUID(), ORG, fixture.id("T8"), null, "denial-boundary",
                    "edge.corp", OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        }
    }

    private static void denial(Connection c, String taskKey, String domain, OffsetDateTime at)
            throws SQLException {
        Fixtures.denial(c, UUID.randomUUID(), ORG, fixture.id(taskKey), null,
                "denial-" + taskKey + "-" + domain + "-" + at, domain, at);
    }

    // --- Budgets (contract 6.1) --------------------------------------------------------------------

    @Test
    void theOrganisationBudgetIsReadSeparatelyFromTeamBudgets() {
        BudgetConfiguration february = budgets.forMonth(ORG, YearMonth.of(2026, 2));

        assertThat(february.organisationCents())
                .isEqualTo(BigInteger.valueOf(ContractFixture.ORGANISATION_BUDGET_CENTS));
        // The contract fixture configures no team budgets, and absence is not zero.
        assertThat(february.byTeam()).isEmpty();
        assertThat(february.forTeam(fixture.id("T-PAY"))).isNull();
    }

    @Test
    void aMonthWithNoConfiguredBudgetReturnsNothingRatherThanZero() {
        BudgetConfiguration january = budgets.forMonth(ORG, YearMonth.of(2026, 1));

        assertThat(january.organisationCents()).isNull();
        assertThat(january.byTeam()).isEmpty();
    }

    /**
     * Both tenants hold the contract fixture, so both have the same organisation budget — equal
     * amounts prove nothing on their own. A team budget configured in the other tenant is what
     * makes a leak visible: it must not appear in this one.
     */
    @Test
    void budgetsAreScopedToTheirOwnOrganisation() throws SQLException {
        try (Connection c = connection()) {
            Fixtures.budget(c, UUID.randomUUID(), OTHER_ORG, otherFixture.id("T-PAY"),
                    "other-team-feb", LocalDate.of(2026, 2, 1), 999999);
        }

        BudgetConfiguration ours = budgets.forMonth(ORG, YearMonth.of(2026, 2));
        BudgetConfiguration theirs = budgets.forMonth(OTHER_ORG, YearMonth.of(2026, 2));

        assertThat(ours.byTeam()).isEmpty();
        assertThat(ours.forTeam(otherFixture.id("T-PAY"))).isNull();
        assertThat(theirs.byTeam()).containsEntry(
                otherFixture.id("T-PAY"), BigInteger.valueOf(999999));
    }

    // --- Failure reasons (research 6.4) --------------------------------------------------------------

    /**
     * T6 failed with {@code sandbox_denied} inside the period — one policy failure on repo-api, and
     * the groups must partition the window's failed-task count exactly.
     */
    @Test
    void failedTasksAreGroupedIntoAgentPlatformAndPolicy() {
        Map<UUID, FailureReasonGroups> byRepository =
                failureReasons.byScope(ORG, PERIOD, ScopeFilters.none(), Grouping.REPOSITORIES);

        FailureReasonGroups repoApi = byRepository.get(fixture.id("R-API"));
        assertThat(repoApi).isEqualTo(new FailureReasonGroups(0, 0, 1));
        assertThat(repoApi.sumsTo(1)).isTrue();
        assertThat(byRepository).doesNotContainKey(fixture.id("R-WEB"));
    }

    /** T3 failed with {@code tests_failed} in the previous period: an agent failure on repo-web. */
    @Test
    void thePreviousPeriodGroupsItsOwnFailures() {
        Map<UUID, FailureReasonGroups> byRepository = failureReasons.byScope(
                ORG, PERIOD.immediatelyBefore(), ScopeFilters.none(), Grouping.REPOSITORIES);

        assertThat(byRepository.get(fixture.id("R-WEB")))
                .isEqualTo(new FailureReasonGroups(1, 0, 0));
    }

    /**
     * A task that failed twice for different reasons is attributed once, to the attempt it ended on.
     * Without {@code DISTINCT ON} this task would appear in two groups and the evidence would claim
     * more failures than the rate it is explaining.
     */
    @Test
    void aRetriedTaskContributesExactlyOneReason() throws SQLException {
        UUID org = UUID.randomUUID();
        ContractFixture local;
        try (Connection c = connection()) {
            local = ContractFixture.install(c, org);
            UUID task = UUID.randomUUID();
            Fixtures.task(c, task, org, local.id("T-PLAT"), local.id("R-API"), local.id("u1"),
                    "retry-failed", "feature", utc(17, 9), "failed", utc(17, 12));
            Fixtures.run(c, UUID.randomUUID(), org, task, "retry-run-1", 1,
                    utc(17, 9), utc(17, 10), "failed", "timeout");
            Fixtures.run(c, UUID.randomUUID(), org, task, "retry-run-2", 2,
                    utc(17, 11), utc(17, 12), "failed", "agent_gave_up");
        }

        DateWindow day = DateWindow.ofInclusiveDates(LocalDate.of(2026, 1, 17), LocalDate.of(2026, 1, 17));
        FailureReasonGroups groups = failureReasons
                .byScope(org, day, ScopeFilters.none(), Grouping.REPOSITORIES)
                .get(local.id("R-API"));

        assertThat(groups.total()).isEqualTo(1);
        // The last attempt is the outcome the task ended on.
        assertThat(groups).isEqualTo(new FailureReasonGroups(1, 0, 0));
        assertThat(groups.sumsTo(1)).isTrue();
    }

    // --- Denials (contract 6.4) ------------------------------------------------------------------------

    /**
     * Six denial events across four tasks for one team, plus a fifth task from a third owner. The
     * repeated events from T5 must collapse: distinct tasks 5, distinct owners 3.
     */
    @Test
    void repeatedEventsFromOneTaskInflateNeitherCount() {
        List<DomainCounts> payments = denials
                .byScopeAndDomain(ORG, PERIOD, ScopeFilters.none(), Grouping.TEAMS)
                .get(fixture.id("T-PAY"));

        DomainCounts registry = payments.stream()
                .filter(counts -> counts.normalisedDomain().equals("internal-registry.corp"))
                .findFirst().orElseThrow();

        assertThat(registry.distinctTasks()).isEqualTo(5);
        assertThat(registry.distinctUsers()).isEqualTo(3);
    }

    /**
     * {@code Internal-Registry.Corp.} and {@code internal-registry.corp} are one domain. Grouping on
     * the raw value would split this population into a 4/2 and a 1/1, both below the gate, and the
     * finding would silently disappear.
     */
    @Test
    void mixedCaseAndTrailingDotCollapseIntoOneDomain() {
        List<DomainCounts> payments = denials
                .byScopeAndDomain(ORG, PERIOD, ScopeFilters.none(), Grouping.TEAMS)
                .get(fixture.id("T-PAY"));

        assertThat(payments).extracting(DomainCounts::normalisedDomain)
                .containsExactly("backup.corp", "internal-registry.corp")
                .doesNotContain("Internal-Registry.Corp.");
    }

    /** Distinct domains stay distinct populations and are never merged (contract 8.5 case N3). */
    @Test
    void separateDomainsAreCountedSeparately() {
        List<DomainCounts> payments = denials
                .byScopeAndDomain(ORG, PERIOD, ScopeFilters.none(), Grouping.TEAMS)
                .get(fixture.id("T-PAY"));

        DomainCounts backup = payments.stream()
                .filter(counts -> counts.normalisedDomain().equals("backup.corp"))
                .findFirst().orElseThrow();

        assertThat(backup.distinctTasks()).isEqualTo(1);
        assertThat(backup.distinctUsers()).isEqualTo(1);
    }

    /** Contract 8.5 case N7: an event at exactly the exclusive end belongs to the next window. */
    @Test
    void anEventAtTheWindowEndIsExcluded() {
        List<DomainCounts> payments = denials
                .byScopeAndDomain(ORG, PERIOD, ScopeFilters.none(), Grouping.TEAMS)
                .get(fixture.id("T-PAY"));

        assertThat(payments).extracting(DomainCounts::normalisedDomain).doesNotContain("edge.corp");
    }

    /** Non-code tasks count: friction is an operational rule, not a code-outcome metric (1.4). */
    @Test
    void nonCodeTasksContributeToFriction() {
        List<DomainCounts> byRepository = denials
                .byScopeAndDomain(ORG, PERIOD, ScopeFilters.none(), Grouping.REPOSITORIES)
                .get(fixture.id("R-WEB"));

        // repo-web's contributors are T7, T8 and the research task added above.
        DomainCounts registry = byRepository.stream()
                .filter(counts -> counts.normalisedDomain().equals("internal-registry.corp"))
                .findFirst().orElseThrow();
        assertThat(registry.distinctTasks()).isEqualTo(3);
    }

    /** Contract 6.0: the global filters narrow the population the rule evaluates. */
    @Test
    void theGlobalFiltersIntersectTheDenialPopulation() {
        Map<UUID, List<DomainCounts>> filtered = denials.byScopeAndDomain(ORG, PERIOD,
                new ScopeFilters(fixture.id("T-PAY"), fixture.id("R-API")), Grouping.TEAMS);

        DomainCounts registry = filtered.get(fixture.id("T-PAY")).stream()
                .filter(counts -> counts.normalisedDomain().equals("internal-registry.corp"))
                .findFirst().orElseThrow();

        // repo-api narrows the five tasks to T5 and T6, owned by u3 alone.
        assertThat(registry.distinctTasks()).isEqualTo(2);
        assertThat(registry.distinctUsers()).isEqualTo(1);
    }

    @Test
    void denialsAreScopedToTheirOwnOrganisation() {
        assertThat(denials.byScopeAndDomain(
                        OTHER_ORG, PERIOD, ScopeFilters.none(), Grouping.TEAMS))
                .isEmpty();
        assertThat(otherFixture.id("T-PAY")).isNotEqualTo(fixture.id("T-PAY"));
    }
}
