package com.fleet.analytics.web.dashboard;

import com.fleet.analytics.data.Coverage;
import com.fleet.analytics.metrics.model.AttentionResult;
import com.fleet.analytics.metrics.model.ComparisonRow;
import com.fleet.analytics.metrics.model.ComparisonTableResult;
import com.fleet.analytics.metrics.model.CoreKpis;
import com.fleet.analytics.metrics.model.CoverageWindows;
import com.fleet.analytics.metrics.model.DailyValue;
import com.fleet.analytics.metrics.model.DashboardSelection;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.FunnelResult;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.MetricResult;
import com.fleet.analytics.metrics.model.PeriodPopulation;
import com.fleet.analytics.metrics.model.ScopePopulation;
import com.fleet.analytics.metrics.model.SeatsResult;
import com.fleet.analytics.metrics.model.TrendResult;
import com.fleet.analytics.metrics.model.TrendSeries;
import com.fleet.analytics.metrics.model.WindowCoverage;
import com.fleet.analytics.web.dashboard.DashboardResponse.BenchmarkResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.BenchmarkScopeResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.ComparisonRowResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.ComparisonTableResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.CoverageResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.CoverageWindowResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.CoverageWindowsResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.FunnelResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.KpisResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.SeatsMetricResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.SelectionResponse;
import com.fleet.analytics.web.dashboard.DashboardResponse.TrendsResponse;
import com.fleet.analytics.web.dashboard.MetricResponse.EvidenceResponse;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Maps calculated results and their exact populations into the published response shape.
 *
 * <p>Mapping only. Nothing is computed here and nothing is re-queried: every number already exists
 * as an exact integer or a once-rounded display string, and evidence is read from the same
 * populations the calculations used rather than reconstructed from what was displayed.
 *
 * <p>The internal {@code Explanation} becomes the contract's {@code reasonCode} and {@code reason},
 * and an unavailable display is omitted rather than nulled — the two conventions the published
 * schema enforces.
 */
@Component
public class DashboardAssembler {

    private final FindingPresenter findingPresenter;

    public DashboardAssembler(FindingPresenter findingPresenter) {
        this.findingPresenter = findingPresenter;
    }

    /**
     * @param byScope the same grouped populations the table was built from, so row evidence is the
     *     exact selected figure rather than a value inferred from a rounded cell.
     */
    public DashboardResponse assemble(Coverage publication, DashboardSelection selection,
            CoverageWindows coverageWindows, LocalDate observationCutoff, CoreKpis kpis,
            FunnelResult funnel, TrendResult trends, ComparisonTableResult table,
            Map<UUID, ScopePopulation> byScope, ScopePopulation benchmark,
            AttentionResult attention, UUID organisationId, String role) {
        return new DashboardResponse(
                coverage(publication),
                selection(selection, observationCutoff),
                coverageWindows(coverageWindows),
                kpis(kpis, coverageWindows),
                funnel(funnel),
                trends(trends),
                comparison(table, byScope, benchmark, coverageWindows.current()),
                findingPresenter.present(attention, organisationId, role, selection));
    }

    private CoverageResponse coverage(Coverage publication) {
        return new CoverageResponse(
                publication.dataAvailableFrom(), publication.dataThrough(), publication.revision());
    }

    private SelectionResponse selection(DashboardSelection selection, LocalDate observationCutoff) {
        DateWindow window = selection.window();
        DateWindow previous = selection.previousWindow();
        return new SelectionResponse(
                window.firstDay(), window.lastDayInclusive(),
                window.startInclusive(), window.endExclusive(),
                previous.firstDay(), previous.lastDayInclusive(),
                selection.filters().teamId(), selection.filters().repositoryId(),
                selection.grouping().wireName(), observationCutoff);
    }

    private CoverageWindowsResponse coverageWindows(CoverageWindows windows) {
        return new CoverageWindowsResponse(
                window(windows.current()),
                window(windows.previousPeriod()),
                window(windows.failureBaseline28d()),
                // Omitted, not nulled, when a repository filter leaves no budget to evaluate.
                windows.budgetMonthToDate() == null ? null : window(windows.budgetMonthToDate()),
                window(windows.funnelObservation()));
    }

    private CoverageWindowResponse window(WindowCoverage coverage) {
        return new CoverageWindowResponse(
                coverage.window().startInclusive(), coverage.window().endExclusive(),
                coverage.incompleteSources().stream()
                        .map(LogicalSource::wireName).sorted().toList());
    }

    // --- KPI row -----------------------------------------------------------------------------------

    /**
     * Evidence is gated on the coverage of the window it was selected from, per source group.
     *
     * <p>A population read over a window whose source is not fully covered is not a measurement —
     * the query returns zero or a partial total because there was nothing to read, not because
     * nothing happened. Attaching it as evidence would turn a gap into an apparently exact figure,
     * which is precisely the confusion the {@code missing_data} state exists to prevent.
     *
     * <p>Gating is per source group rather than per metric, so independently known evidence
     * survives: a cost-per-PR ratio undefined for want of pull-request data still reports the spend
     * that is genuinely known. Sample gates are not consulted at all — an insufficient sample is a
     * reason to suppress a <em>comparison</em>, never a reason to withhold counts that were measured.
     */
    private KpisResponse kpis(CoreKpis kpis, CoverageWindows windows) {
        PeriodPopulation current = kpis.current();
        PeriodPopulation previous = kpis.previous();
        WindowCoverage now = windows.current();
        WindowCoverage before = windows.previousPeriod();

        Long currentMerged = known(now, LogicalSource.PR_OUTCOMES, current.pullRequests().merged());
        Long currentTerminalPrs =
                known(now, LogicalSource.PR_OUTCOMES, current.pullRequests().terminal());
        Long currentSpend = knownCents(now, LogicalSource.SPEND, current.spend().codeChangeCents());
        Long previousMerged =
                known(before, LogicalSource.PR_OUTCOMES, previous.pullRequests().merged());
        Long previousTerminalPrs =
                known(before, LogicalSource.PR_OUTCOMES, previous.pullRequests().terminal());
        Long previousSpend =
                knownCents(before, LogicalSource.SPEND, previous.spend().codeChangeCents());

        return new KpisResponse(
                MetricResponse.from(kpis.mergedPrs(), null,
                        EvidenceResponse.previousMerged(previousMerged)),
                MetricResponse.from(kpis.terminalMergeRate(),
                        EvidenceResponse.mergedAndTerminalPrs(currentMerged, currentTerminalPrs),
                        EvidenceResponse.previousPrs(previousMerged, previousTerminalPrs)),
                MetricResponse.from(kpis.costPerMergedPr(),
                        EvidenceResponse.unitCost(currentMerged, currentSpend),
                        EvidenceResponse.previousUnitCost(previousMerged, previousSpend)),
                MetricResponse.from(kpis.taskCompletionRate(),
                        EvidenceResponse.tasks(
                                known(now, LogicalSource.TASK_OUTCOMES, current.tasks().completed()),
                                known(now, LogicalSource.TASK_OUTCOMES, current.tasks().failed())),
                        EvidenceResponse.previousTasks(
                                known(before, LogicalSource.TASK_OUTCOMES,
                                        previous.tasks().completed()),
                                known(before, LogicalSource.TASK_OUTCOMES,
                                        previous.tasks().failed()))),
                seats(kpis.seats(), previous, before));
    }

    /** @return null when the window does not fully cover the sources the figure was read from. */
    private Long known(WindowCoverage coverage, Set<LogicalSource> required, long value) {
        return coverage.supports(required) ? JsonSafeInteger.of(value) : null;
    }

    private Long knownCents(WindowCoverage coverage, Set<LogicalSource> required, BigInteger cents) {
        return coverage.supports(required) ? JsonSafeInteger.of(cents) : null;
    }

    private SeatsMetricResponse seats(
            SeatsResult seats, PeriodPopulation previous, WindowCoverage before) {
        MetricResult active = seats.activeSeats();
        return new SeatsMetricResponse(
                active.state().wireName(),
                DisplayResponse.from(active.display()),
                active.explanation() == null ? null : active.explanation().code(),
                active.explanation() == null ? null : active.explanation().text(),
                JsonSafeInteger.of(seats.licensedSeats()),
                MetricResponse.ComparisonResponse.from(active.comparison(),
                        EvidenceResponse.previousSeats(known(before, LogicalSource.SEAT_ACTIVITY,
                                previous.seats().activeOwners()))),
                MetricResponse.from(seats.utilisation()));
    }

    // --- Funnel and trends ---------------------------------------------------------------------------

    private FunnelResponse funnel(FunnelResult funnel) {
        return new FunnelResponse(
                funnel.observationCutoff(),
                new FunnelResponse.StagesResponse(
                        MetricResponse.from(funnel.started()),
                        MetricResponse.from(funnel.completed()),
                        MetricResponse.from(funnel.prOpened()),
                        MetricResponse.from(funnel.prMerged())),
                new FunnelResponse.SideExitsResponse(
                        MetricResponse.from(funnel.failed()),
                        MetricResponse.from(funnel.cancelled())),
                new FunnelResponse.ResidualResponse(MetricResponse.from(funnel.inProgress())));
    }

    private TrendsResponse trends(TrendResult trends) {
        return new TrendsResponse(
                series(trends.mergedPrsPerDay(), point -> new TrendsResponse.CountPointResponse(
                        point.date(), JsonSafeInteger.of(point.amount()))),
                series(trends.spendPerDay(), point -> new TrendsResponse.SpendPointResponse(
                        point.date(), JsonSafeInteger.of(point.amount()))));
    }

    private <T> TrendsResponse.SeriesResponse<T> series(
            TrendSeries series, Function<DailyValue, T> mapper) {
        return new TrendsResponse.SeriesResponse<>(
                series.state().wireName(),
                series.unit().wireName(),
                series.explanation() == null ? null : series.explanation().code(),
                series.explanation() == null ? null : series.explanation().text(),
                series.points().stream().map(mapper).toList());
    }

    // --- Comparison table -------------------------------------------------------------------------------

    /** The table is current-period only, so every cell's evidence is gated on that one window. */
    private ComparisonTableResponse comparison(ComparisonTableResult table,
            Map<UUID, ScopePopulation> byScope, ScopePopulation benchmark, WindowCoverage now) {
        List<ComparisonRowResponse> rows = table.rows().stream()
                .map(row -> row(row, byScope.getOrDefault(row.scopeId(), ScopePopulation.NONE), now))
                .toList();
        return new ComparisonTableResponse(table.grouping().wireName(), rows,
                new BenchmarkResponse(
                        new BenchmarkScopeResponse(
                                table.benchmark().scope().teamFilterIgnored(),
                                table.benchmark().scope().repositoryFilterApplied(),
                                table.benchmark().scope().includesSelectedTeam(),
                                table.benchmark().scope().mayIncludeUndisplayedTeams()),
                        MetricResponse.from(table.benchmark().taskCompletionRate(),
                                taskEvidence(benchmark, now)),
                        MetricResponse.from(table.benchmark().terminalMergeRate(),
                                prEvidence(benchmark, now)),
                        MetricResponse.from(table.benchmark().costPerMergedPr(),
                                unitCostEvidence(benchmark, now)),
                        MetricResponse.from(table.benchmark().codeChangeSpend(),
                                EvidenceResponse.spend(spendOf(benchmark, now)))));
    }

    private ComparisonRowResponse row(
            ComparisonRow row, ScopePopulation population, WindowCoverage now) {
        return new ComparisonRowResponse(
                row.scopeId(), row.scopeName(), JsonSafeInteger.of(row.terminalTaskCount()),
                MetricResponse.from(row.taskCompletionRate(), taskEvidence(population, now)),
                MetricResponse.from(row.terminalMergeRate(), prEvidence(population, now)),
                MetricResponse.from(row.costPerMergedPr(), unitCostEvidence(population, now)),
                MetricResponse.from(row.codeChangeSpend(),
                        EvidenceResponse.spend(spendOf(population, now))));
    }

    private EvidenceResponse taskEvidence(ScopePopulation population, WindowCoverage now) {
        return EvidenceResponse.tasks(
                known(now, LogicalSource.TASK_OUTCOMES, population.tasks().completed()),
                known(now, LogicalSource.TASK_OUTCOMES, population.tasks().failed()));
    }

    private EvidenceResponse prEvidence(ScopePopulation population, WindowCoverage now) {
        return EvidenceResponse.mergedAndTerminalPrs(
                known(now, LogicalSource.PR_OUTCOMES, population.pullRequests().merged()),
                known(now, LogicalSource.PR_OUTCOMES, population.pullRequests().terminal()));
    }

    private EvidenceResponse unitCostEvidence(ScopePopulation population, WindowCoverage now) {
        return EvidenceResponse.unitCost(
                known(now, LogicalSource.PR_OUTCOMES, population.pullRequests().merged()),
                spendOf(population, now));
    }

    private Long spendOf(ScopePopulation population, WindowCoverage now) {
        return knownCents(now, LogicalSource.SPEND, population.spend().codeChangeCents());
    }
}
