package com.fleet.analytics.web.dashboard;

import com.fleet.analytics.data.ContextQueries;
import com.fleet.analytics.data.Coverage;
import com.fleet.analytics.data.NamedEntity;
import com.fleet.analytics.data.analytics.BudgetQueries;
import com.fleet.analytics.data.analytics.CoverageQueries;
import com.fleet.analytics.data.analytics.DenialQueries;
import com.fleet.analytics.data.analytics.FailureReasonQueries;
import com.fleet.analytics.data.analytics.FilterResolver;
import com.fleet.analytics.data.analytics.FunnelQueries;
import com.fleet.analytics.data.analytics.PullRequestQueries;
import com.fleet.analytics.data.analytics.SeatQueries;
import com.fleet.analytics.data.analytics.TaskQueries;
import com.fleet.analytics.data.analytics.UsageQueries;
import com.fleet.analytics.metrics.AttentionEvaluator;
import com.fleet.analytics.metrics.ComparisonTableBuilder;
import com.fleet.analytics.metrics.CoreKpiCalculator;
import com.fleet.analytics.metrics.CoverageResolver;
import com.fleet.analytics.metrics.FunnelCalculator;
import com.fleet.analytics.metrics.TrendBuilder;
import com.fleet.analytics.metrics.model.AttentionInputs;
import com.fleet.analytics.metrics.model.ComparisonTableResult;
import com.fleet.analytics.metrics.model.CoreKpis;
import com.fleet.analytics.metrics.model.CoverageWindows;
import com.fleet.analytics.metrics.model.DashboardSelection;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.FunnelResult;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.PeriodPopulation;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.ReportingWindows;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.ScopePopulation;
import com.fleet.analytics.metrics.model.SpendTotals;
import com.fleet.analytics.metrics.model.TaskCounts;
import com.fleet.analytics.metrics.model.TrendResult;
import com.fleet.analytics.metrics.rules.BudgetRiskRule;
import com.fleet.analytics.security.AuthenticatedTenant;
import java.math.BigInteger;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates one dashboard request inside a single read-only {@code REPEATABLE READ} transaction.
 *
 * <p><b>Why one transaction.</b> The response is one answer about one moment. Under read-committed,
 * each query would see whatever was committed when it ran, so a merge landing mid-request could be
 * counted by the KPI row and missed by the trend beside it — and the page would show two numbers
 * that never coexisted, with nothing to indicate which was stale. A repeatable-read snapshot,
 * established by the first statement here, makes every section describe the same instant, and makes
 * the {@code revision} served alongside them true of all of them.
 *
 * <p>Queries run sequentially on the transaction-bound connection. Parallel streams or async futures
 * would borrow other connections and silently leave the snapshot, which is the failure this design
 * exists to prevent.
 *
 * <p>Populations are selected once and reused: several cards read the same counts, and re-querying
 * for each would multiply round trips inside a held transaction for no benefit.
 *
 * <p>This endpoint writes nothing. It records no view, persists no finding and sends no
 * notification — a read is a read.
 */
@Service
public class DashboardService {

    private final DashboardRequestParser parser;
    private final FilterResolver filterResolver;
    private final ContextQueries context;
    private final CoverageQueries coverageQueries;
    private final TaskQueries tasks;
    private final PullRequestQueries pullRequests;
    private final UsageQueries usage;
    private final SeatQueries seats;
    private final FunnelQueries funnelQueries;
    private final BudgetQueries budgets;
    private final FailureReasonQueries failureReasons;
    private final DenialQueries denials;
    private final CoverageResolver coverageResolver;
    private final CoreKpiCalculator kpiCalculator;
    private final FunnelCalculator funnelCalculator;
    private final TrendBuilder trendBuilder;
    private final ComparisonTableBuilder tableBuilder;
    private final AttentionEvaluator attentionEvaluator;
    private final DashboardAssembler assembler;

    public DashboardService(DashboardRequestParser parser, FilterResolver filterResolver,
            ContextQueries context, CoverageQueries coverageQueries, TaskQueries tasks,
            PullRequestQueries pullRequests, UsageQueries usage, SeatQueries seats,
            FunnelQueries funnelQueries, BudgetQueries budgets, FailureReasonQueries failureReasons,
            DenialQueries denials, CoverageResolver coverageResolver,
            CoreKpiCalculator kpiCalculator, FunnelCalculator funnelCalculator,
            TrendBuilder trendBuilder, ComparisonTableBuilder tableBuilder,
            AttentionEvaluator attentionEvaluator, DashboardAssembler assembler) {
        this.parser = parser;
        this.filterResolver = filterResolver;
        this.context = context;
        this.coverageQueries = coverageQueries;
        this.tasks = tasks;
        this.pullRequests = pullRequests;
        this.usage = usage;
        this.seats = seats;
        this.funnelQueries = funnelQueries;
        this.budgets = budgets;
        this.failureReasons = failureReasons;
        this.denials = denials;
        this.coverageResolver = coverageResolver;
        this.kpiCalculator = kpiCalculator;
        this.funnelCalculator = funnelCalculator;
        this.trendBuilder = trendBuilder;
        this.tableBuilder = tableBuilder;
        this.attentionEvaluator = attentionEvaluator;
        this.assembler = assembler;
    }

    /**
     * The transaction boundary. It is on this public method, invoked through Spring's proxy — a
     * private helper annotated instead would be silently ignored and every section would read its
     * own snapshot while appearing to be transactional.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardResponse dashboard(AuthenticatedTenant tenant, Map<String, String> parameters) {
        UUID organisationId = tenant.organisationId();

        // 1-3. Publication metadata establishes the snapshot; the selection is then validated
        // against it, and its filters are confirmed to belong to this tenant.
        Coverage publication = context.coverage(organisationId);
        DashboardSelection selection = parser.parse(parameters, publication);
        filterResolver.requireAvailable(tenant, selection.filters());

        ScopeFilters filters = selection.filters();
        DateWindow window = selection.window();
        DateWindow previousWindow = selection.previousWindow();

        // 4. Scope names and coverage, both bounded to the interval every window spans.
        ReportingWindows windows = coverageResolver.windows(selection, publication);
        DateWindow fetch = coverageResolver.coverageFetchInterval(windows);
        CoverageWindows coverage = coverageResolver.resolve(windows, filters,
                coverageQueries.sourceDays(
                        organisationId, fetch.firstDay(), fetch.lastDayInclusive()));
        List<NamedEntity> teams = context.teams(organisationId);
        List<NamedEntity> repositories = context.repositories(organisationId);

        // 5. Current and previous populations, selected once and shared by every card that needs them.
        PeriodPopulation current = population(organisationId, window, filters);
        PeriodPopulation previous = population(organisationId, previousWindow, filters);

        // 6. Funnel and trends.
        boolean prStagesObservable =
                coverage.funnelObservation().supports(LogicalSource.FUNNEL_PR_STAGES);
        FunnelResult funnel = funnelCalculator.build(
                funnelQueries.cohort(organisationId, window, publication.dataThrough(), filters,
                        prStagesObservable),
                coverage.funnelObservation(), CoverageResolver.observationCutoff(publication));
        TrendResult trends = trendBuilder.build(window, coverage.current(),
                pullRequests.mergedPerDay(organisationId, window, filters),
                usage.spendPerDay(organisationId, window, filters));

        // 7. Grouped rows and the separately scoped benchmark.
        Grouping grouping = selection.grouping();
        Map<UUID, ScopePopulation> byScope =
                groupedPopulations(organisationId, window, filters, grouping);
        ScopeFilters benchmarkFilters = filters.withoutTeam();
        ScopePopulation benchmark = new ScopePopulation(
                tasks.totals(organisationId, window, benchmarkFilters),
                pullRequests.terminalTotals(organisationId, window, benchmarkFilters),
                usage.totals(organisationId, window, benchmarkFilters));

        // 8-9. Attention populations and every calculation.
        CoreKpis kpis = kpiCalculator.calculate(
                current, previous, coverage.current(), coverage.previousPeriod(), filters);
        ComparisonTableResult table = tableBuilder.build(grouping,
                scopesFor(grouping, filters, teams, repositories), byScope, benchmark,
                coverage.current(), filters);
        AttentionInputs attentionInputs = attentionInputs(
                organisationId, selection, windows, coverage, publication, teams, repositories);

        // 10. Assemble, with the caller's role deciding what evidence is permitted.
        return assembler.assemble(publication, selection, coverage,
                CoverageResolver.observationCutoff(publication), kpis, funnel, trends, table,
                byScope, benchmark, attentionEvaluator.evaluate(attentionInputs),
                organisationId, tenant.role());
    }

    private PeriodPopulation population(
            UUID organisationId, DateWindow window, ScopeFilters filters) {
        return new PeriodPopulation(
                tasks.totals(organisationId, window, filters),
                pullRequests.terminalTotals(organisationId, window, filters),
                usage.totals(organisationId, window, filters),
                seats.active(organisationId, window, filters));
    }

    private Map<UUID, ScopePopulation> groupedPopulations(
            UUID organisationId, DateWindow window, ScopeFilters filters, Grouping grouping) {
        Map<UUID, TaskCounts> tasksByScope =
                tasks.byScope(organisationId, window, filters, grouping);
        Map<UUID, PrCounts> prsByScope =
                pullRequests.terminalByScope(organisationId, window, filters, grouping);
        Map<UUID, SpendTotals> spendByScope =
                usage.byScope(organisationId, window, filters, grouping);

        Set<UUID> ids = new HashSet<>(tasksByScope.keySet());
        ids.addAll(prsByScope.keySet());
        ids.addAll(spendByScope.keySet());

        Map<UUID, ScopePopulation> byScope = new HashMap<>();
        ids.forEach(id -> byScope.put(id, new ScopePopulation(
                tasksByScope.getOrDefault(id, TaskCounts.NONE),
                prsByScope.getOrDefault(id, PrCounts.NONE),
                spendByScope.getOrDefault(id, SpendTotals.NONE))));
        return byScope;
    }

    /**
     * Rows are the grouping's own scopes, narrowed by any explicit filter on that same dimension. A
     * scope with no activity still gets a row (AC-05.4).
     */
    private List<NamedEntity> scopesFor(Grouping grouping, ScopeFilters filters,
            List<NamedEntity> teams, List<NamedEntity> repositories) {
        List<NamedEntity> all = grouping == Grouping.TEAMS ? teams : repositories;
        UUID restriction = grouping == Grouping.TEAMS ? filters.teamId() : filters.repositoryId();
        return restriction == null
                ? all
                : all.stream().filter(scope -> scope.id().equals(restriction)).toList();
    }

    private AttentionInputs attentionInputs(UUID organisationId, DashboardSelection selection,
            ReportingWindows windows, CoverageWindows coverage, Coverage publication,
            List<NamedEntity> teams, List<NamedEntity> repositories) {
        ScopeFilters filters = selection.filters();
        DateWindow window = selection.window();

        List<RuleScope> teamScopes = ruleScopes(teams, filters.teamId(), true);
        List<RuleScope> repositoryScopes = ruleScopes(repositories, filters.repositoryId(), false);

        DateWindow budgetWindow = windows.budgetMonthToDate();
        YearMonth budgetMonth = BudgetRiskRule.evaluatedMonth(budgetWindow.firstDay());
        SpendTotals organisationMonthToDate =
                usage.totals(organisationId, budgetWindow, ScopeFilters.none());
        Map<UUID, BigInteger> teamMonthToDate = new HashMap<>();
        usage.byScope(organisationId, budgetWindow, ScopeFilters.none(), Grouping.TEAMS)
                .forEach((teamId, totals) -> teamMonthToDate.put(teamId, totals.allTaskCents()));

        return new AttentionInputs(
                RuleScope.organisation(context.organisationName(organisationId)),
                dimension(organisationId, selection, windows, teamScopes, Grouping.TEAMS),
                dimension(organisationId, selection, windows, repositoryScopes, Grouping.REPOSITORIES),
                filters, coverage, window.firstDay(), window.lastDayInclusive(),
                budgetMonth, budgets.forMonth(organisationId, budgetMonth),
                organisationMonthToDate.allTaskCents(), teamMonthToDate,
                (int) budgetWindow.lengthInDays(), budgetMonth.lengthOfMonth());
    }

    private AttentionInputs.DimensionInputs dimension(UUID organisationId,
            DashboardSelection selection, ReportingWindows windows, List<RuleScope> scopes,
            Grouping grouping) {
        ScopeFilters filters = selection.filters();
        DateWindow window = selection.window();
        return new AttentionInputs.DimensionInputs(scopes,
                tasks.byScope(organisationId, window, filters, grouping),
                tasks.byScope(organisationId, windows.failureBaseline28d(), filters, grouping),
                failureReasons.byScope(organisationId, window, filters, grouping),
                pullRequests.terminalByScope(organisationId, window, filters, grouping),
                pullRequests.terminalByScope(
                        organisationId, windows.previousPeriod(), filters, grouping),
                denials.byScopeAndDomain(organisationId, window, filters, grouping));
    }

    /** Contract 6.0: a filter on a dimension restricts which of its scopes are evaluated at all. */
    private List<RuleScope> ruleScopes(List<NamedEntity> all, UUID restriction, boolean team) {
        return all.stream()
                .filter(scope -> restriction == null || scope.id().equals(restriction))
                .map(scope -> team
                        ? RuleScope.team(scope.id(), scope.name())
                        : RuleScope.repository(scope.id(), scope.name()))
                .toList();
    }
}
