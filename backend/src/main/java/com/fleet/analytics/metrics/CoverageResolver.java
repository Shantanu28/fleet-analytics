package com.fleet.analytics.metrics;

import com.fleet.analytics.data.Coverage;
import com.fleet.analytics.metrics.model.CoverageWindows;
import com.fleet.analytics.metrics.model.DashboardSelection;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.ReportingWindows;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.SourceDay;
import com.fleet.analytics.metrics.model.WindowCoverage;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Derives the five reporting windows from a selection, then decides each one's completeness
 * independently from {@code source_day_coverage} metadata.
 *
 * <p>Two rules drive everything here. First, <b>coverage is never inferred from data</b>: an empty
 * day inside a covered interval is real zero activity, and the only way to tell that apart from an
 * unknown day is metadata that says so. Second, <b>each window is judged alone</b> — that is why a
 * complete current period can sit beside an uncovered baseline and produce a visible value with a
 * suppressed comparison, rather than suppressing both (A.5).
 *
 * <p>Pure: no SQL, no HTTP. The source-day rows are handed in.
 */
@Component
public class CoverageResolver {

    /** Contract 6.2: the 28 complete days immediately before the range, non-overlapping. */
    private static final int FAILURE_BASELINE_DAYS = 28;

    /**
     * The last complete UTC day, {@code dataThrough - 1 day}. {@code dataThrough} is exclusive, so
     * this is the date the funnel label and the reporting-cutoff line name (AC-01.3, contract 4).
     */
    public static LocalDate observationCutoff(Coverage publication) {
        // Read as a UTC calendar day, not as whatever zone the driver handed back: in a western
        // zone the raw toLocalDate() would already be the previous day, and subtracting one more
        // would report a cutoff a full day early.
        return DateWindow.utcDateOf(publication.dataThrough()).minusDays(1);
    }

    /** Pure calendar arithmetic; no coverage is consulted and no filter changes a window's bounds. */
    public ReportingWindows windows(DashboardSelection selection, Coverage publication) {
        DateWindow current = selection.window();
        return new ReportingWindows(
                current,
                current.immediatelyBefore(),
                current.precedingDays(FAILURE_BASELINE_DAYS),
                budgetMonthToDate(publication),
                new DateWindow(current.startInclusive(), publication.dataThrough()));
    }

    /**
     * The calendar month containing the last complete day, running to {@code dataThrough} — never
     * the selected range (contract 6.1). At a month boundary this is the whole preceding month, not
     * an empty new one.
     */
    private DateWindow budgetMonthToDate(Coverage publication) {
        LocalDate lastCompleteDay = observationCutoff(publication);
        return new DateWindow(
                DateWindow.atUtcMidnight(lastCompleteDay.withDayOfMonth(1)), publication.dataThrough());
    }

    /**
     * The smallest interval containing every evaluated window, so one bounded read serves them all.
     *
     * <p>It must be the minimum start and maximum end across <em>all five</em> windows, not any one
     * of them. The 28-day failure baseline is not always the earliest: a selection longer than 28
     * days has a previous period that reaches further back, and fetching from the baseline would
     * leave the first days of that previous period without coverage rows. Those days would then read
     * as uncovered, and every comparison on the page would be suppressed as {@code no_baseline} —
     * a wrong answer that looks like a legitimate one. Symmetrically, the budget month and the funnel
     * observation both run past the selected range's end.
     */
    public DateWindow coverageFetchInterval(ReportingWindows windows) {
        List<DateWindow> evaluated = List.of(windows.current(), windows.previousPeriod(),
                windows.failureBaseline28d(), windows.budgetMonthToDate(),
                windows.funnelObservation());
        OffsetDateTime start = evaluated.stream().map(DateWindow::startInclusive)
                .min(OffsetDateTime::compareTo).orElseThrow();
        OffsetDateTime end = evaluated.stream().map(DateWindow::endExclusive)
                .max(OffsetDateTime::compareTo).orElseThrow();
        return new DateWindow(start, end);
    }

    /**
     * @param sourceDays every coverage row spanning the requested interval; rows outside it are
     *     harmless, but a day with no row at all is treated as not covered.
     */
    public CoverageWindows resolve(
            ReportingWindows windows, ScopeFilters filters, List<SourceDay> sourceDays) {
        Map<LogicalSource, Set<LocalDate>> completeDays = indexCompleteDays(sourceDays);
        return new CoverageWindows(
                coverageOf(windows.current(), completeDays),
                coverageOf(windows.previousPeriod(), completeDays),
                coverageOf(windows.failureBaseline28d(), completeDays),
                // A repository filter leaves no budget to evaluate: budgets have no repository
                // allocation and none is invented (contract 5.3, 6.1). The month is unchanged --
                // it is simply not evaluated, so the response omits the window entirely.
                filters.hasRepository() ? null : coverageOf(windows.budgetMonthToDate(), completeDays),
                coverageOf(windows.funnelObservation(), completeDays));
    }

    private Map<LogicalSource, Set<LocalDate>> indexCompleteDays(List<SourceDay> sourceDays) {
        Map<LogicalSource, Set<LocalDate>> index = new EnumMap<>(LogicalSource.class);
        for (SourceDay sourceDay : sourceDays) {
            if (sourceDay.complete()) {
                index.computeIfAbsent(sourceDay.source(), source -> new HashSet<>())
                        .add(sourceDay.day());
            }
        }
        return index;
    }

    private WindowCoverage coverageOf(
            DateWindow window, Map<LogicalSource, Set<LocalDate>> completeDays) {
        Collection<LocalDate> days = window.days();
        EnumSet<LogicalSource> incomplete = EnumSet.noneOf(LogicalSource.class);
        for (LogicalSource source : LogicalSource.values()) {
            Set<LocalDate> covered = completeDays.getOrDefault(source, Set.of());
            if (!covered.containsAll(days)) {
                incomplete.add(source);
            }
        }
        return new WindowCoverage(window, incomplete);
    }
}
