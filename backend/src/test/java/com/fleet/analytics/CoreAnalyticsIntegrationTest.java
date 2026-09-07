package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.data.ContextQueries;
import com.fleet.analytics.data.Coverage;
import com.fleet.analytics.data.NamedEntity;
import com.fleet.analytics.data.analytics.CoverageQueries;
import com.fleet.analytics.data.analytics.FunnelQueries;
import com.fleet.analytics.data.analytics.PullRequestQueries;
import com.fleet.analytics.data.analytics.SeatQueries;
import com.fleet.analytics.data.analytics.TaskQueries;
import com.fleet.analytics.data.analytics.UsageQueries;
import com.fleet.analytics.metrics.ComparisonTableBuilder;
import com.fleet.analytics.metrics.CoreKpiCalculator;
import com.fleet.analytics.metrics.CoverageResolver;
import com.fleet.analytics.metrics.FunnelCalculator;
import com.fleet.analytics.metrics.TrendBuilder;
import com.fleet.analytics.metrics.model.ComparisonRow;
import com.fleet.analytics.metrics.model.ComparisonState;
import com.fleet.analytics.metrics.model.ComparisonTableResult;
import com.fleet.analytics.metrics.model.CoreKpis;
import com.fleet.analytics.metrics.model.CoverageWindows;
import com.fleet.analytics.metrics.model.DashboardSelection;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.FunnelResult;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.PeriodPopulation;
import com.fleet.analytics.metrics.model.ReportingWindows;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.ScopePopulation;
import com.fleet.analytics.metrics.model.TrendResult;
import com.fleet.analytics.metrics.model.ValueState;
import com.fleet.analytics.support.ContractFixture;
import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.IntegrationTestBase;
import com.fleet.analytics.web.dashboard.DashboardRequestParser;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole slice end to end: parse a request, resolve coverage, select populations from real
 * PostgreSQL, and derive the KPI row, funnel, trends and comparison table.
 *
 * <p>The expectations are the metrics contract's own worked figures (8.1 to 8.4), stated here
 * rather than copied from the response example — this is the path that has to produce them, so
 * reading them back out of a JSON fixture would prove only that two files agree.
 *
 * <p><b>What this does not prove:</b> the sections here are selected by separate queries in separate
 * transactions. That every section shares one database snapshot is a property of the real service's
 * {@code REPEATABLE READ} boundary, which is slice D's work, and no test here establishes it.
 */
class CoreAnalyticsIntegrationTest extends IntegrationTestBase {

    private static final UUID ORG = UUID.fromString("c0000000-0000-0000-0000-00000000000c");
    private static ContractFixture fixture;

    @Autowired private DashboardRequestParser parser;
    @Autowired private CoverageResolver coverageResolver;
    @Autowired private CoreKpiCalculator kpiCalculator;
    @Autowired private FunnelCalculator funnelCalculator;
    @Autowired private TrendBuilder trendBuilder;
    @Autowired private ComparisonTableBuilder tableBuilder;

    @Autowired private ContextQueries context;
    @Autowired private CoverageQueries coverageQueries;
    @Autowired private TaskQueries tasks;
    @Autowired private PullRequestQueries pullRequests;
    @Autowired private UsageQueries usage;
    @Autowired private SeatQueries seats;
    @Autowired private FunnelQueries funnelQueries;

    @BeforeAll
    static void installFixture() throws SQLException {
        try (Connection c = connection()) {
            fixture = ContractFixture.install(c, ORG);
        }
    }

    // --- The pipeline under test ------------------------------------------------------------------

    private DashboardSelection selection(Map<String, String> parameters) {
        return parser.parse(parameters, context.coverage(ORG));
    }

    private static Map<String, String> contractPeriod(String... extra) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("from", ContractFixture.PERIOD_FROM.toString());
        parameters.put("to", ContractFixture.PERIOD_TO.toString());
        for (int i = 0; i < extra.length; i += 2) {
            parameters.put(extra[i], extra[i + 1]);
        }
        return parameters;
    }

    private CoverageWindows coverageFor(DashboardSelection selection) {
        return coverageFor(ORG, selection, context.coverage(ORG));
    }

    /** The bounds come from the resolver so this path cannot drift from the one slice D will use. */
    private CoverageWindows coverageFor(
            UUID organisationId, DashboardSelection selection, Coverage publication) {
        ReportingWindows windows = coverageResolver.windows(selection, publication);
        DateWindow fetch = coverageResolver.coverageFetchInterval(windows);
        return coverageResolver.resolve(windows, selection.filters(),
                coverageQueries.sourceDays(
                        organisationId, fetch.firstDay(), fetch.lastDayInclusive()));
    }

    private PeriodPopulation populationOf(DateWindow window, ScopeFilters filters) {
        return new PeriodPopulation(
                tasks.totals(ORG, window, filters),
                pullRequests.terminalTotals(ORG, window, filters),
                usage.totals(ORG, window, filters),
                seats.active(ORG, window, filters));
    }

    private CoreKpis kpis(DashboardSelection selection) {
        CoverageWindows coverage = coverageFor(selection);
        return kpiCalculator.calculate(
                populationOf(selection.window(), selection.filters()),
                populationOf(selection.previousWindow(), selection.filters()),
                coverage.current(), coverage.previousPeriod(), selection.filters());
    }

    private FunnelResult funnel(DashboardSelection selection) {
        CoverageWindows coverage = coverageFor(selection);
        boolean prStages = coverage.funnelObservation().supports(LogicalSource.FUNNEL_PR_STAGES);
        return funnelCalculator.build(
                funnelQueries.cohort(ORG, selection.window(), context.coverage(ORG).dataThrough(),
                        selection.filters(), prStages),
                coverage.funnelObservation(),
                CoverageResolver.observationCutoff(context.coverage(ORG)));
    }

    private TrendResult trends(DashboardSelection selection) {
        return trendBuilder.build(selection.window(), coverageFor(selection).current(),
                pullRequests.mergedPerDay(ORG, selection.window(), selection.filters()),
                usage.spendPerDay(ORG, selection.window(), selection.filters()));
    }

    private ComparisonTableResult table(DashboardSelection selection) {
        Grouping grouping = selection.grouping();
        ScopeFilters filters = selection.filters();
        DateWindow window = selection.window();

        List<NamedEntity> scopes = scopesFor(grouping, filters);
        var tasksByScope = tasks.byScope(ORG, window, filters, grouping);
        var prsByScope = pullRequests.terminalByScope(ORG, window, filters, grouping);
        var spendByScope = usage.byScope(ORG, window, filters, grouping);

        // Each grouped query returns only the scopes it actually saw, so the union is what has any
        // population at all. Scopes in neither map fall back to zero activity in the builder.
        Set<UUID> ids = new HashSet<>(tasksByScope.keySet());
        ids.addAll(prsByScope.keySet());
        ids.addAll(spendByScope.keySet());

        Map<UUID, ScopePopulation> byScope = new HashMap<>();
        ids.forEach(id -> byScope.put(id, new ScopePopulation(
                tasksByScope.getOrDefault(id, ScopePopulation.NONE.tasks()),
                prsByScope.getOrDefault(id, ScopePopulation.NONE.pullRequests()),
                spendByScope.getOrDefault(id, ScopePopulation.NONE.spend()))));

        // The benchmark drops only the team predicate and keeps any explicit repository filter.
        ScopeFilters benchmarkFilters = filters.withoutTeam();
        ScopePopulation benchmark = new ScopePopulation(
                tasks.totals(ORG, window, benchmarkFilters),
                pullRequests.terminalTotals(ORG, window, benchmarkFilters),
                usage.totals(ORG, window, benchmarkFilters));

        return tableBuilder.build(grouping, scopes, byScope, benchmark,
                coverageFor(selection).current(), filters);
    }

    private List<NamedEntity> scopesFor(Grouping grouping, ScopeFilters filters) {
        List<NamedEntity> all = grouping == Grouping.TEAMS
                ? context.teams(ORG)
                : context.repositories(ORG);
        UUID restriction = grouping == Grouping.TEAMS ? filters.teamId() : filters.repositoryId();
        return restriction == null
                ? all
                : all.stream().filter(scope -> scope.id().equals(restriction)).toList();
    }

    private static ComparisonRow named(ComparisonTableResult table, String scopeName) {
        return table.rows().stream().filter(row -> row.scopeName().equals(scopeName))
                .findFirst().orElseThrow();
    }

    // --- KPI row (contract 8.1) --------------------------------------------------------------------

    @Test
    void theFiveCardsReproduceTheContractsWorkedValues() {
        CoreKpis kpis = kpis(selection(contractPeriod()));

        assertThat(kpis.mergedPrs().display().value()).isEqualTo("1");
        assertThat(kpis.mergedPrs().comparison().display().value()).isEqualTo("-50.0");

        assertThat(kpis.terminalMergeRate().display().value()).isEqualTo("100.0");
        assertThat(kpis.terminalMergeRate().comparison().state())
                .isEqualTo(ComparisonState.INSUFFICIENT_SAMPLE);

        assertThat(kpis.costPerMergedPr().display().value()).isEqualTo("23.00");
        assertThat(kpis.taskCompletionRate().display().value()).isEqualTo("50.0");

        assertThat(kpis.seats().activeSeats().display().value()).isEqualTo("2");
        assertThat(kpis.seats().licensedSeats()).isEqualTo(6);
        assertThat(kpis.seats().activeSeats().comparison().display().value()).isEqualTo("-1");
        assertThat(kpis.seats().utilisation().display().value()).isEqualTo("33.3");
    }

    @Test
    void suppressedComparisonsCarryTheContractsOwnSampleCounts() {
        CoreKpis kpis = kpis(selection(contractPeriod()));

        assertThat(kpis.terminalMergeRate().comparison().explanation().text())
                .isEqualTo("Comparison needs 15 terminal PRs in both periods; "
                        + "this period had 1 and the previous period had 3.");
        assertThat(kpis.taskCompletionRate().comparison().explanation().text())
                .isEqualTo("Comparison needs 20 completed or failed code-change tasks in both "
                        + "periods; this period had 2 and the previous period had 5.");
    }

    /** Contract 5.3, through the whole pipeline rather than only the calculator. */
    @Test
    void aTeamFilterKeepsTheSeatCountAndRemovesUtilisation() {
        CoreKpis kpis = kpis(selection(contractPeriod("teamId", fixture.id("T-PAY").toString())));

        assertThat(kpis.seats().activeSeats().display().value()).isEqualTo("2");
        assertThat(kpis.seats().utilisation().state()).isEqualTo(ValueState.UNAVAILABLE_FOR_SCOPE);
    }

    // --- Funnel (contract 8.3) -----------------------------------------------------------------------

    @Test
    void theFunnelReproducesTheContractsCohort() {
        FunnelResult result = funnel(selection(contractPeriod()));

        assertThat(result.observationCutoff()).isEqualTo(LocalDate.of(2026, 2, 3));
        assertThat(result.started().display().value()).isEqualTo("4");
        assertThat(result.completed().display().value()).isEqualTo("1");
        assertThat(result.prOpened().display().value()).isEqualTo("1");
        assertThat(result.prMerged().display().value()).isEqualTo("1");
        assertThat(result.failed().display().value()).isEqualTo("1");
        assertThat(result.cancelled().display().value()).isEqualTo("1");
        assertThat(result.inProgress().display().value()).isEqualTo("1");
    }

    // --- Trends (contract 5.1, 8.2) --------------------------------------------------------------------

    @Test
    void bothTrendsCoverEveryDayOfTheRange() {
        TrendResult result = trends(selection(contractPeriod()));

        assertThat(result.mergedPrsPerDay().points()).hasSize(16);
        assertThat(result.spendPerDay().points()).hasSize(16);
        assertThat(result.mergedPrsPerDay().points().stream()
                        .filter(point -> point.amount().signum() > 0))
                .singleElement()
                .satisfies(point -> assertThat(point.date()).isEqualTo(LocalDate.of(2026, 1, 20)));
        assertThat(result.spendPerDay().points().stream()
                        .filter(point -> point.amount().signum() > 0))
                .extracting(point -> point.date() + "=" + point.amount())
                .containsExactly("2026-01-18=150", "2026-01-22=350", "2026-01-30=1800");
    }

    // --- Comparison table (contract 8.4) ------------------------------------------------------------------

    @Test
    void theTeamViewReproducesTheContractsTable() {
        ComparisonTableResult table = table(selection(contractPeriod()));

        assertThat(table.rows()).extracting(ComparisonRow::scopeName)
                .containsExactly("Payments", "Platform");

        ComparisonRow payments = named(table, "Payments");
        assertThat(payments.taskCompletionRate().display().value()).isEqualTo("50.0");
        assertThat(payments.terminalMergeRate().state()).isEqualTo(ValueState.NO_DENOMINATOR);
        assertThat(payments.costPerMergedPr().state()).isEqualTo(ValueState.NO_DENOMINATOR);
        assertThat(payments.codeChangeSpend().display().value()).isEqualTo("23");

        ComparisonRow platform = named(table, "Platform");
        assertThat(platform.taskCompletionRate().state()).isEqualTo(ValueState.NO_DENOMINATOR);
        assertThat(platform.terminalMergeRate().display().value()).isEqualTo("100.0");
        assertThat(platform.costPerMergedPr().display().value()).isEqualTo("0.00");
        assertThat(platform.codeChangeSpend().display().value()).isEqualTo("0");
    }

    @Test
    void theRepositoryViewReproducesTheContractsTable() {
        ComparisonTableResult table =
                table(selection(contractPeriod("grouping", "repositories")));

        ComparisonRow repoApi = named(table, "repo-api");
        assertThat(repoApi.taskCompletionRate().state()).isEqualTo(ValueState.ZERO_OUTCOME);
        assertThat(repoApi.taskCompletionRate().display().value()).isEqualTo("0.0");
        assertThat(repoApi.terminalMergeRate().state()).isEqualTo(ValueState.NO_DENOMINATOR);
        assertThat(repoApi.codeChangeSpend().display().value()).isEqualTo("5");

        ComparisonRow repoWeb = named(table, "repo-web");
        assertThat(repoWeb.taskCompletionRate().display().value()).isEqualTo("100.0");
        assertThat(repoWeb.terminalMergeRate().display().value()).isEqualTo("100.0");
        assertThat(repoWeb.costPerMergedPr().display().value()).isEqualTo("18.00");
    }

    /**
     * Contract 8.4's first point, proved through real SQL: the two groupings partition the same
     * organisation differently and still pool to the identical benchmark. A mean of the member rows
     * could not do this — Platform's completion rate does not exist to be averaged.
     */
    @Test
    void bothGroupingsPoolToTheSameBenchmark() {
        ComparisonTableResult teams = table(selection(contractPeriod()));
        ComparisonTableResult repositories =
                table(selection(contractPeriod("grouping", "repositories")));

        assertThat(teams.benchmark().taskCompletionRate().display().value()).isEqualTo("50.0");
        assertThat(repositories.benchmark().taskCompletionRate().display().value()).isEqualTo("50.0");
        assertThat(teams.benchmark().terminalMergeRate().display().value()).isEqualTo("100.0");
        assertThat(repositories.benchmark().terminalMergeRate().display().value()).isEqualTo("100.0");
        assertThat(teams.benchmark().costPerMergedPr().display().value()).isEqualTo("23.00");
        assertThat(repositories.benchmark().costPerMergedPr().display().value()).isEqualTo("23.00");
    }

    /** Contract 5.4: a team filter narrows the rows but leaves the benchmark at organisation scope. */
    @Test
    void aTeamFilterNarrowsTheRowsButNotTheBenchmark() {
        ComparisonTableResult table =
                table(selection(contractPeriod("teamId", fixture.id("T-PAY").toString())));

        assertThat(table.rows()).extracting(ComparisonRow::scopeName).containsExactly("Payments");
        assertThat(table.benchmark().taskCompletionRate().display().value()).isEqualTo("50.0");
        assertThat(table.benchmark().costPerMergedPr().display().value()).isEqualTo("23.00");
        assertThat(table.benchmark().scope().mayIncludeUndisplayedTeams()).isTrue();
        assertThat(table.benchmark().scope().teamFilterIgnored()).isTrue();
    }

    /** A repository filter is honoured by the benchmark as well as by the rows (AC-05.3). */
    @Test
    void aRepositoryFilterRestrictsTheBenchmarkToo() {
        ComparisonTableResult table = table(selection(
                contractPeriod("repositoryId", fixture.id("R-WEB").toString())));

        // repo-web alone: T7 completed, PR-3 merged, 1800c of T7 spend.
        assertThat(table.benchmark().taskCompletionRate().display().value()).isEqualTo("100.0");
        assertThat(table.benchmark().costPerMergedPr().display().value()).isEqualTo("18.00");
        assertThat(table.benchmark().scope().repositoryFilterApplied()).isTrue();
    }

    /**
     * The same defect proved against real coverage rows: a 31-day January selection has a previous
     * period reaching back to 1 December, three days earlier than the 28-day baseline. If the fetch
     * bounds came from the baseline those rows would be absent, the previous period would read as
     * uncovered, and every comparison would be suppressed as no_baseline.
     */
    @Test
    void aThirtyOneDaySelectionSeesItsWholePreviousPeriod() {
        Map<String, String> january = new HashMap<>();
        january.put("from", "2026-01-01");
        january.put("to", "2026-01-31");
        DashboardSelection selection = selection(january);

        assertThat(selection.previousWindow().firstDay()).isEqualTo(LocalDate.of(2025, 12, 1));

        CoverageWindows coverage = coverageFor(selection);
        assertThat(coverage.previousPeriod().incompleteSources()).isEmpty();
        assertThat(coverage.current().incompleteSources()).isEmpty();

        // And the comparison genuinely computes rather than reporting an uncovered baseline.
        CoreKpis kpis = kpis(selection);
        assertThat(kpis.taskCompletionRate().comparison().state())
                .isNotEqualTo(ComparisonState.NO_BASELINE);
        assertThat(kpis.mergedPrs().comparison().state()).isNotEqualTo(ComparisonState.NO_BASELINE);
    }

    // --- Missing data stays distinct from zero (contract 1.5) -----------------------------------------------

    /**
     * The same organisation, the same period, one day of pull-request coverage withdrawn. Every
     * PR-dependent result must become unavailable while the task and spend results are untouched —
     * and none of them may quietly become zero.
     */
    @Test
    void withdrawingOneDayOfCoverageMakesDependantsUnavailableRatherThanZero() throws SQLException {
        UUID org = UUID.randomUUID();
        try (Connection c = connection()) {
            ContractFixture.install(c, org);
            Fixtures.sourceDay(c, org, LogicalSource.PULL_REQUESTS.wireName(),
                    LocalDate.of(2026, 1, 20), false);
        }

        Coverage publication = context.coverage(org);
        DashboardSelection selection = parser.parse(contractPeriod(), publication);
        CoverageWindows coverage = coverageFor(org, selection, publication);

        CoreKpis kpis = kpiCalculator.calculate(
                new PeriodPopulation(tasks.totals(org, selection.window(), ScopeFilters.none()),
                        pullRequests.terminalTotals(org, selection.window(), ScopeFilters.none()),
                        usage.totals(org, selection.window(), ScopeFilters.none()),
                        seats.active(org, selection.window(), ScopeFilters.none())),
                new PeriodPopulation(tasks.totals(org, selection.previousWindow(), ScopeFilters.none()),
                        pullRequests.terminalTotals(org, selection.previousWindow(), ScopeFilters.none()),
                        usage.totals(org, selection.previousWindow(), ScopeFilters.none()),
                        seats.active(org, selection.previousWindow(), ScopeFilters.none())),
                coverage.current(), coverage.previousPeriod(), ScopeFilters.none());

        assertThat(kpis.mergedPrs().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(kpis.mergedPrs().display()).isNull();
        assertThat(kpis.terminalMergeRate().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(kpis.costPerMergedPr().state()).isEqualTo(ValueState.MISSING_DATA);

        // Everything that does not depend on pull requests still renders its real value.
        assertThat(kpis.taskCompletionRate().display().value()).isEqualTo("50.0");
        assertThat(kpis.seats().activeSeats().display().value()).isEqualTo("2");
        assertThat(kpis.seats().utilisation().display().value()).isEqualTo("33.3");

        TrendResult trends = trendBuilder.build(selection.window(), coverage.current(),
                pullRequests.mergedPerDay(org, selection.window(), ScopeFilters.none()),
                usage.spendPerDay(org, selection.window(), ScopeFilters.none()));
        assertThat(trends.mergedPrsPerDay().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(trends.mergedPrsPerDay().points()).isEmpty();
        assertThat(trends.spendPerDay().state()).isEqualTo(ValueState.OK);
        assertThat(trends.spendPerDay().points()).hasSize(16);
    }
}
