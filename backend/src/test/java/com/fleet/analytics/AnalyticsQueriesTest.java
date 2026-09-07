package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleet.analytics.data.analytics.CoverageQueries;
import com.fleet.analytics.data.analytics.FilterResolver;
import com.fleet.analytics.data.analytics.FunnelQueries;
import com.fleet.analytics.data.analytics.InvalidAnalyticsFilterException;
import com.fleet.analytics.data.analytics.PullRequestQueries;
import com.fleet.analytics.data.analytics.SeatQueries;
import com.fleet.analytics.data.analytics.TaskQueries;
import com.fleet.analytics.data.analytics.UsageQueries;
import com.fleet.analytics.metrics.model.DailyValue;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.FunnelCounts;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.SeatCounts;
import com.fleet.analytics.metrics.model.SourceDay;
import com.fleet.analytics.metrics.model.SpendTotals;
import com.fleet.analytics.metrics.model.TaskCounts;
import com.fleet.analytics.security.AuthenticatedTenant;
import com.fleet.analytics.support.ContractFixture;
import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.IntegrationTestBase;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The population queries against real PostgreSQL and the contract's own fixture.
 *
 * <p>Every expected number below is derived from contract 7 by hand and stated in the test — none
 * was captured from a run. Where the contract already worked a figure out (8.1 to 8.4) the same
 * value appears here, so a query that drifts from the specification fails rather than redefining it.
 */
class AnalyticsQueriesTest extends IntegrationTestBase {

    /** The contract's selected period P and previous period P-1. */
    private static final DateWindow PERIOD = DateWindow.ofInclusiveDates(
            ContractFixture.PERIOD_FROM, ContractFixture.PERIOD_TO);
    private static final DateWindow PREVIOUS = PERIOD.immediatelyBefore();

    private static final UUID ORG_A = UUID.fromString("a0000000-0000-0000-0000-00000000000a");
    private static final UUID ORG_B = UUID.fromString("b0000000-0000-0000-0000-00000000000b");

    private static ContractFixture fixtureA;
    private static ContractFixture fixtureB;

    @Autowired private TaskQueries tasks;
    @Autowired private PullRequestQueries pullRequests;
    @Autowired private UsageQueries usage;
    @Autowired private SeatQueries seats;
    @Autowired private FunnelQueries funnel;
    @Autowired private CoverageQueries coverage;
    @Autowired private FilterResolver filters;

    /** Both tenants hold the same fixture, so isolation is provable on values, not only on ids. */
    @BeforeAll
    static void installBothTenants() throws SQLException {
        try (Connection c = connection()) {
            fixtureA = ContractFixture.install(c, ORG_A);
            fixtureB = ContractFixture.install(c, ORG_B);
        }
    }

    private static BigInteger cents(long value) {
        return BigInteger.valueOf(value);
    }

    private static LocalDate january(int day) {
        return LocalDate.of(2026, 1, day);
    }

    // --- Task outcomes (contract 3.4, 8.1) --------------------------------------------------------

    @Test
    void terminalTasksAreCountedByTheirOwnTerminalTimestamp() {
        assertThat(tasks.totals(ORG_A, PERIOD, ScopeFilters.none()))
                .isEqualTo(new TaskCounts(1, 1));
        assertThat(tasks.totals(ORG_A, PREVIOUS, ScopeFilters.none()))
                .isEqualTo(new TaskCounts(4, 1));
    }

    /**
     * T9 is a research task and T5 was cancelled; neither belongs to a completion rate. T8 is still
     * running and has no outcome to count at all.
     */
    @Test
    void nonCodeCancelledAndRunningTasksAreExcluded() {
        // The period holds T5 (cancelled), T6 (failed), T7 (completed) and T8 (running):
        // only two of the four are terminal code-change outcomes.
        assertThat(tasks.totals(ORG_A, PERIOD, ScopeFilters.none()).terminal()).isEqualTo(2);
        // The previous period holds six tasks, one of which (T9) is research.
        assertThat(tasks.totals(ORG_A, PREVIOUS, ScopeFilters.none()).terminal()).isEqualTo(5);
    }

    @Test
    void taskCountsPartitionByTeamAndByRepository() {
        Map<UUID, TaskCounts> byTeam =
                tasks.byScope(ORG_A, PERIOD, ScopeFilters.none(), Grouping.TEAMS);
        assertThat(byTeam.get(fixtureA.id("T-PAY"))).isEqualTo(new TaskCounts(1, 1));
        // Platform has no terminal task in this period, so it is simply absent from the grouping.
        assertThat(byTeam).doesNotContainKey(fixtureA.id("T-PLAT"));

        Map<UUID, TaskCounts> byRepository =
                tasks.byScope(ORG_A, PERIOD, ScopeFilters.none(), Grouping.REPOSITORIES);
        assertThat(byRepository.get(fixtureA.id("R-API"))).isEqualTo(new TaskCounts(0, 1));
        assertThat(byRepository.get(fixtureA.id("R-WEB"))).isEqualTo(new TaskCounts(1, 0));
    }

    // --- Pull requests (contract 3.1, 3.2, 8.1) ----------------------------------------------------

    /**
     * PR-3 is the only transition inside P; PR-4 merged on 3 February is outside it. The previous
     * period holds two merges and one close, giving the contract's 2/3.
     */
    @Test
    void pullRequestsAreCountedByTheirTerminalTransition() {
        assertThat(pullRequests.terminalTotals(ORG_A, PERIOD, ScopeFilters.none()))
                .isEqualTo(new PrCounts(1, 0));
        assertThat(pullRequests.terminalTotals(ORG_A, PREVIOUS, ScopeFilters.none()))
                .isEqualTo(new PrCounts(2, 1));
    }

    @Test
    void mergesAreBucketedOnTheDayTheyMerged() {
        List<DailyValue> merges = pullRequests.mergedPerDay(ORG_A, PERIOD, ScopeFilters.none());

        assertThat(merges).containsExactly(new DailyValue(january(20), BigInteger.ONE));
    }

    /** PR-3 belongs to T4, a Platform task on repo-web — attribution reaches the PR through its task. */
    @Test
    void pullRequestsInheritTeamAndRepositoryFromTheirTask() {
        assertThat(pullRequests.terminalByScope(ORG_A, PERIOD, ScopeFilters.none(), Grouping.TEAMS))
                .containsExactly(Map.entry(fixtureA.id("T-PLAT"), new PrCounts(1, 0)));
        assertThat(pullRequests.terminalByScope(
                        ORG_A, PERIOD, ScopeFilters.none(), Grouping.REPOSITORIES))
                .containsExactly(Map.entry(fixtureA.id("R-WEB"), new PrCounts(1, 0)));
    }

    /** Contract 1.4: a PR that does not target its repository's default branch is counted by nothing. */
    @Test
    void aPullRequestOffTheDefaultBranchIsCountedByNoMetric() throws SQLException {
        UUID org = UUID.randomUUID();
        try (Connection c = connection()) {
            ContractFixture other = ContractFixture.install(c, org);
            UUID task = UUID.randomUUID();
            Fixtures.task(c, task, org, other.id("T-PLAT"), other.id("R-API"), other.id("u1"),
                    "branch-task", "feature", utc(2026, 1, 17, 9, 0), "completed",
                    utc(2026, 1, 17, 9, 30));
            Fixtures.run(c, UUID.randomUUID(), org, task, "branch-run", 1,
                    utc(2026, 1, 17, 9, 0), utc(2026, 1, 17, 9, 30), "completed", null);
            UUID run = onlyRunOf(c, org, task);
            Fixtures.pullRequest(c, UUID.randomUUID(), org, task, run, "branch-pr", "release/2026",
                    utc(2026, 1, 17, 10, 0), "merged", utc(2026, 1, 21, 10, 0));
        }

        // Without the default-branch condition this would be 2 merges rather than the fixture's 1.
        assertThat(pullRequests.terminalTotals(org, PERIOD, ScopeFilters.none()).merged())
                .isEqualTo(1);
    }

    // --- Spend (contract 2, 3.3, 8.2) ---------------------------------------------------------------

    /**
     * All-task and code-change totals diverge in the previous period only, where T9's research spend
     * of 200c belongs to the trend but not to the unit-cost numerator.
     */
    @Test
    void spendSeparatesAllTaskTypesFromCodeChangeTasks() {
        assertThat(usage.totals(ORG_A, PERIOD, ScopeFilters.none()))
                .isEqualTo(new SpendTotals(cents(2300), cents(2300)));
        assertThat(usage.totals(ORG_A, PREVIOUS, ScopeFilters.none()))
                .isEqualTo(new SpendTotals(cents(4900), cents(4700)));
    }

    /**
     * T8 was created inside the period but metered on 2 February. Spend follows the meter, never the
     * task's own timestamps, which is what makes period spend reconcile to the ledger.
     */
    @Test
    void spendFollowsTheMeteredInstantRatherThanTheTask() {
        assertThat(usage.spendPerDay(ORG_A, PERIOD, ScopeFilters.none()))
                .containsExactlyInAnyOrder(
                        new DailyValue(january(18), cents(150)),
                        new DailyValue(january(22), cents(350)),
                        new DailyValue(january(30), cents(1800)));
    }

    @Test
    void spendPartitionsByTheTasksOwnAttribution() {
        Map<UUID, SpendTotals> byRepository =
                usage.byScope(ORG_A, PERIOD, ScopeFilters.none(), Grouping.REPOSITORIES);

        // repo-api: T5 150c + T6 350c. repo-web: T7 1800c.
        assertThat(byRepository.get(fixtureA.id("R-API")).codeChangeCents()).isEqualTo(cents(500));
        assertThat(byRepository.get(fixtureA.id("R-WEB")).codeChangeCents()).isEqualTo(cents(1800));
    }

    // --- Fan-out safety -------------------------------------------------------------------------------

    /**
     * The regression the layering exists to prevent. One task with two runs, two usage records of
     * <em>equal</em> value and one PR: spend must be 200c and the merged count 1.
     *
     * <p>Equal amounts are deliberate. A query that fanned usage out across a second branch and then
     * "fixed" it with {@code SUM(DISTINCT cost_cents)} would collapse the two 100c rows into one and
     * report 100 — a plausible-looking number that no unequal-amount fixture would catch. A PR query
     * that joined runs would likewise see the PR twice and report 2 merges.
     */
    @Test
    void independentChildBranchesDoNotMultiplyEachOther() throws SQLException {
        UUID org = UUID.randomUUID();
        try (Connection c = connection()) {
            ContractFixture other = ContractFixture.install(c, org);
            UUID task = UUID.randomUUID();
            UUID firstRun = UUID.randomUUID();
            UUID secondRun = UUID.randomUUID();

            Fixtures.task(c, task, org, other.id("T-PLAT"), other.id("R-API"), other.id("u1"),
                    "retry-task", "feature", utc(2026, 1, 17, 9, 0), "completed",
                    utc(2026, 1, 17, 12, 0));
            Fixtures.run(c, firstRun, org, task, "retry-run-1", 1,
                    utc(2026, 1, 17, 9, 0), utc(2026, 1, 17, 10, 0), "failed", "timeout");
            Fixtures.run(c, secondRun, org, task, "retry-run-2", 2,
                    utc(2026, 1, 17, 11, 0), utc(2026, 1, 17, 12, 0), "completed", null);
            Fixtures.usage(c, UUID.randomUUID(), org, firstRun, "retry-usage-1",
                    utc(2026, 1, 17, 10, 0), 100);
            Fixtures.usage(c, UUID.randomUUID(), org, secondRun, "retry-usage-2",
                    utc(2026, 1, 17, 12, 0), 100);
            Fixtures.pullRequest(c, UUID.randomUUID(), org, task, secondRun, "retry-pr", "main",
                    utc(2026, 1, 17, 12, 30), "merged", utc(2026, 1, 19, 9, 0));
        }

        DateWindow day = DateWindow.ofInclusiveDates(january(17), january(17));
        assertThat(usage.totals(org, day, ScopeFilters.none()).allTaskCents()).isEqualTo(cents(200));
        assertThat(usage.spendPerDay(org, day, ScopeFilters.none()))
                .containsExactly(new DailyValue(january(17), cents(200)));

        DateWindow merged = DateWindow.ofInclusiveDates(january(19), january(19));
        assertThat(pullRequests.terminalTotals(org, merged, ScopeFilters.none()).merged())
                .isEqualTo(1);
        // The task itself is still one task, not one per run.
        assertThat(tasks.totals(org, DateWindow.ofInclusiveDates(january(17), january(17)),
                        ScopeFilters.none()).completed()).isEqualTo(1);
    }

    // --- Seats (contract 3.5) --------------------------------------------------------------------------

    @Test
    void activeSeatsCountDistinctLicensedOwnersByTaskCreation() {
        assertThat(seats.active(ORG_A, PERIOD, ScopeFilters.none()))
                .isEqualTo(new SeatCounts(2, 6));
        assertThat(seats.active(ORG_A, PREVIOUS, ScopeFilters.none()))
                .isEqualTo(new SeatCounts(3, 6));
    }

    /** u3 owns two tasks in the period and must still count once. */
    @Test
    void aUserWithSeveralTasksCountsOnce() {
        assertThat(seats.active(ORG_A, PERIOD,
                        new ScopeFilters(fixtureA.id("T-PAY"), fixtureA.id("R-API")))
                .activeOwners()).isEqualTo(1);
    }

    // --- Funnel (contract 4, 8.3) ------------------------------------------------------------------------

    @Test
    void theCohortIsFixedByCreationAndObservedThroughDataThrough() {
        FunnelCounts cohort = funnel.cohort(
                ORG_A, PERIOD, ContractFixture.DATA_THROUGH, ScopeFilters.none(), true);

        assertThat(cohort.started()).isEqualTo(4);
        assertThat(cohort.completed()).isEqualTo(1);
        assertThat(cohort.failed()).isEqualTo(1);
        assertThat(cohort.cancelled()).isEqualTo(1);
        assertThat(cohort.inProgress()).isEqualTo(1);
        assertThat(cohort.prOpened()).isEqualTo(1);
        assertThat(cohort.prMerged()).isEqualTo(1);
    }

    /**
     * The funnel and the KPI row count <em>different PRs</em>, and equal counts of one would hide
     * that. Moving the observation cutoff back before PR-4's merge drops the funnel's merged stage
     * to zero while the period's merged-PR count is untouched — because the period's merge is PR-3,
     * from T4, a task created before the period and therefore outside the cohort entirely.
     */
    @Test
    void theFunnelAndTheKpiCountDisjointPullRequests() {
        OffsetDateTime beforePr4Merged = utc(2026, 2, 3, 0, 0);

        FunnelCounts cohort =
                funnel.cohort(ORG_A, PERIOD, beforePr4Merged, ScopeFilters.none(), true);

        assertThat(cohort.prOpened()).isEqualTo(1);
        assertThat(cohort.prMerged()).isZero();
        assertThat(pullRequests.terminalTotals(ORG_A, PERIOD, ScopeFilters.none()).merged())
                .isEqualTo(1);
        // And the period's single merge landed on 20 January, well before PR-4 existed.
        assertThat(pullRequests.mergedPerDay(ORG_A, PERIOD, ScopeFilters.none()))
                .containsExactly(new DailyValue(january(20), BigInteger.ONE));
    }

    @Test
    void skippingThePrStagesLeavesThemUnknownRatherThanZero() {
        FunnelCounts cohort = funnel.cohort(
                ORG_A, PERIOD, ContractFixture.DATA_THROUGH, ScopeFilters.none(), false);

        assertThat(cohort.hasPrStages()).isFalse();
        assertThat(cohort.prMerged()).isNull();
        assertThat(cohort.started()).isEqualTo(4);
    }

    // --- Coverage metadata ---------------------------------------------------------------------------------

    @Test
    void coverageRowsAreReadForTheRequestedIntervalOnly() {
        List<SourceDay> days = coverage.sourceDays(ORG_A, january(16), january(31));

        assertThat(days).hasSize(16 * LogicalSource.values().length);
        assertThat(days).allMatch(SourceDay::complete);
        assertThat(days).extracting(SourceDay::day)
                .allMatch(day -> !day.isBefore(january(16)) && !day.isAfter(january(31)));
    }

    // --- Tenant isolation ------------------------------------------------------------------------------------

    /**
     * Both organisations hold an identical fixture, so a leak would be invisible in the totals. What
     * proves isolation is that A's figures are unchanged by B's existence, and that B's identifiers
     * select nothing at all inside A.
     */
    @Test
    void oneTenantsPopulationsNeverIncludeAnothers() {
        assertThat(tasks.totals(ORG_A, PERIOD, ScopeFilters.none()))
                .isEqualTo(tasks.totals(ORG_B, PERIOD, ScopeFilters.none()))
                .isEqualTo(new TaskCounts(1, 1));
        assertThat(usage.totals(ORG_A, PERIOD, ScopeFilters.none()).allTaskCents())
                .isEqualTo(cents(2300));
    }

    /** A real identifier from another tenant selects nothing, rather than that tenant's rows. */
    @Test
    void aForeignScopeIdentifierSelectsNothingInThisOrganisation() {
        ScopeFilters foreign = new ScopeFilters(fixtureB.id("T-PAY"), null);

        assertThat(tasks.totals(ORG_A, PERIOD, foreign)).isEqualTo(new TaskCounts(0, 0));
        assertThat(usage.totals(ORG_A, PERIOD, foreign).allTaskCents()).isEqualTo(BigInteger.ZERO);
        assertThat(seats.active(ORG_A, PERIOD, foreign).activeOwners()).isZero();
        assertThat(funnel.cohort(ORG_A, PERIOD, ContractFixture.DATA_THROUGH, foreign, true)
                .started()).isZero();
    }

    /** Unknown and foreign identifiers must be indistinguishable to the caller. */
    @Test
    void foreignAndNonexistentFiltersFailIdentically() {
        AuthenticatedTenant tenantA = new AuthenticatedTenant(UUID.randomUUID(), ORG_A, "ADMIN");

        assertThatThrownBy(() -> filters.requireAvailable(
                        tenantA, new ScopeFilters(fixtureB.id("T-PAY"), null)))
                .isInstanceOf(InvalidAnalyticsFilterException.class)
                .hasMessage("The requested filter is not available.");

        assertThatThrownBy(() -> filters.requireAvailable(
                        tenantA, new ScopeFilters(UUID.randomUUID(), null)))
                .isInstanceOf(InvalidAnalyticsFilterException.class)
                .hasMessage("The requested filter is not available.");

        assertThatThrownBy(() -> filters.requireAvailable(
                        tenantA, new ScopeFilters(null, fixtureB.id("R-API"))))
                .isInstanceOf(InvalidAnalyticsFilterException.class)
                .hasMessage("The requested filter is not available.");
    }

    @Test
    void anOwnedFilterIsAccepted() {
        AuthenticatedTenant tenantA = new AuthenticatedTenant(UUID.randomUUID(), ORG_A, "ADMIN");

        filters.requireAvailable(tenantA, new ScopeFilters(fixtureA.id("T-PAY"), fixtureA.id("R-API")));
        filters.requireAvailable(tenantA, ScopeFilters.none());
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    private static OffsetDateTime utc(int year, int month, int day, int hour, int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC);
    }

    private static UUID onlyRunOf(Connection c, UUID org, UUID task) throws SQLException {
        try (var s = c.prepareStatement("select id from run where org_id = ? and task_id = ?")) {
            s.setObject(1, org);
            s.setObject(2, task);
            try (var rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getObject(1, UUID.class);
            }
        }
    }
}
