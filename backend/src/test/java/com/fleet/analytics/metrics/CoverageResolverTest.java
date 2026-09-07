package com.fleet.analytics.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.data.Coverage;
import com.fleet.analytics.metrics.model.CoverageWindows;
import com.fleet.analytics.metrics.model.DashboardSelection;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.ReportingWindows;
import com.fleet.analytics.metrics.model.ScopeFilters;
import com.fleet.analytics.metrics.model.SourceDay;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Window arithmetic and per-window completeness, with no database: coverage is decided from
 * source-day metadata alone, never from whether any business rows happen to exist.
 */
class CoverageResolverTest {

    private static final Coverage PUBLICATION = new Coverage(
            OffsetDateTime.of(2025, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 2, 4, 0, 0, 0, 0, ZoneOffset.UTC),
            "contract-fixture-1");

    private final CoverageResolver resolver = new CoverageResolver();

    private static DashboardSelection selection(ScopeFilters filters) {
        return new DashboardSelection(
                DateWindow.ofInclusiveDates(LocalDate.of(2026, 1, 16), LocalDate.of(2026, 1, 31)),
                filters, Grouping.TEAMS);
    }

    /** Complete metadata for every source across an interval, i.e. "nothing is missing". */
    private static List<SourceDay> allComplete(LocalDate from, LocalDate toExclusive) {
        List<SourceDay> days = new ArrayList<>();
        for (LocalDate day = from; day.isBefore(toExclusive); day = day.plusDays(1)) {
            for (LogicalSource source : LogicalSource.values()) {
                days.add(new SourceDay(source, day, true));
            }
        }
        return days;
    }

    private static List<SourceDay> fullyCoveredFixtureInterval() {
        return allComplete(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 2, 4));
    }

    @Test
    void theFiveWindowsFollowFromTheSelectionAndTheDataThroughCutoff() {
        ReportingWindows windows = resolver.windows(selection(ScopeFilters.none()), PUBLICATION);

        assertThat(windows.current().firstDay()).isEqualTo(LocalDate.of(2026, 1, 16));
        assertThat(windows.current().lastDayInclusive()).isEqualTo(LocalDate.of(2026, 1, 31));

        assertThat(windows.previousPeriod().firstDay()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(windows.previousPeriod().lastDayInclusive()).isEqualTo(LocalDate.of(2026, 1, 15));

        assertThat(windows.failureBaseline28d().firstDay()).isEqualTo(LocalDate.of(2025, 12, 19));
        assertThat(windows.failureBaseline28d().lastDayInclusive()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(windows.failureBaseline28d().lengthInDays()).isEqualTo(28);

        // The budget month comes from dataThrough, never from the selected range (contract 6.1).
        assertThat(windows.budgetMonthToDate().firstDay()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(windows.budgetMonthToDate().lastDayInclusive()).isEqualTo(LocalDate.of(2026, 2, 3));

        // The funnel observes past the end of the range, up to dataThrough (contract 4).
        assertThat(windows.funnelObservation().firstDay()).isEqualTo(LocalDate.of(2026, 1, 16));
        assertThat(windows.funnelObservation().lastDayInclusive()).isEqualTo(LocalDate.of(2026, 2, 3));
    }

    /**
     * Contract 6.1 case B5. With dataThrough at midnight on 1 February the last complete day is
     * 31 January, so the evaluated month is January in full — not an empty February.
     */
    @Test
    void theBudgetMonthIsTheMonthContainingTheLastCompleteDay() {
        Coverage atMonthBoundary = new Coverage(PUBLICATION.dataAvailableFrom(),
                OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC), "boundary");

        ReportingWindows windows = resolver.windows(
                new DashboardSelection(
                        DateWindow.ofInclusiveDates(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                        ScopeFilters.none(), Grouping.TEAMS),
                atMonthBoundary);

        assertThat(windows.budgetMonthToDate().firstDay()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(windows.budgetMonthToDate().lengthInDays()).isEqualTo(31);
    }

    @Test
    void theObservationCutoffIsTheLastCompleteDay() {
        assertThat(CoverageResolver.observationCutoff(PUBLICATION)).isEqualTo(LocalDate.of(2026, 2, 3));
    }

    /**
     * The JDBC driver returns a timestamptz in the JVM's zone, so publication instants reach us as
     * the right moment wearing the wrong offset. Every window must be derived from the UTC calendar
     * day: read naively, a western offset names the previous day and shifts every boundary by one.
     */
    @Test
    void windowsAreDerivedFromTheUtcDayWhateverOffsetTheInstantArrivesIn() {
        Coverage westOfUtc = new Coverage(
                PUBLICATION.dataAvailableFrom().withOffsetSameInstant(ZoneOffset.ofHours(-5)),
                PUBLICATION.dataThrough().withOffsetSameInstant(ZoneOffset.ofHours(-5)), "shifted");
        Coverage eastOfUtc = new Coverage(
                PUBLICATION.dataAvailableFrom().withOffsetSameInstant(ZoneOffset.ofHours(4)),
                PUBLICATION.dataThrough().withOffsetSameInstant(ZoneOffset.ofHours(4)), "shifted");

        assertThat(CoverageResolver.observationCutoff(westOfUtc)).isEqualTo(LocalDate.of(2026, 2, 3));
        assertThat(CoverageResolver.observationCutoff(eastOfUtc)).isEqualTo(LocalDate.of(2026, 2, 3));

        for (Coverage publication : new Coverage[] {westOfUtc, eastOfUtc}) {
            ReportingWindows windows = resolver.windows(selection(ScopeFilters.none()), publication);
            assertThat(windows.budgetMonthToDate().firstDay()).isEqualTo(LocalDate.of(2026, 2, 1));
            assertThat(windows.budgetMonthToDate().lastDayInclusive())
                    .isEqualTo(LocalDate.of(2026, 2, 3));
            assertThat(windows.funnelObservation().lastDayInclusive())
                    .isEqualTo(LocalDate.of(2026, 2, 3));
        }
    }

    /**
     * The fetch interval must span every evaluated window, not the failure baseline alone. For a
     * 31-day selection the previous period reaches 31 days back while the baseline reaches only 28,
     * so starting at the baseline would silently omit three days of the previous period.
     */
    @Test
    void theFetchIntervalSpansTheEarliestStartAndLatestEndOfAllWindows() {
        DashboardSelection january = new DashboardSelection(
                DateWindow.ofInclusiveDates(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                ScopeFilters.none(), Grouping.TEAMS);
        ReportingWindows windows = resolver.windows(january, PUBLICATION);

        // Previous period 1-31 December; baseline only 4-31 December.
        assertThat(windows.previousPeriod().firstDay()).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(windows.failureBaseline28d().firstDay()).isEqualTo(LocalDate.of(2025, 12, 4));

        DateWindow fetch = resolver.coverageFetchInterval(windows);
        assertThat(fetch.firstDay()).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(fetch.lastDayInclusive()).isEqualTo(LocalDate.of(2026, 2, 3));
    }

    /**
     * The consequence, end to end: fetched with the interval, a fully covered previous period reads
     * as covered. Fetched from the baseline it would not, and every comparison on the page would be
     * suppressed as no_baseline — a wrong answer wearing the shape of a legitimate one.
     */
    @Test
    void aThirtyOneDaySelectionKeepsItsFullyCoveredPreviousPeriod() {
        DashboardSelection january = new DashboardSelection(
                DateWindow.ofInclusiveDates(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                ScopeFilters.none(), Grouping.TEAMS);
        ReportingWindows windows = resolver.windows(january, PUBLICATION);
        DateWindow fetch = resolver.coverageFetchInterval(windows);

        List<SourceDay> fetched = allComplete(fetch.firstDay(), fetch.endExclusive().toLocalDate())
                .stream().filter(day -> !day.day().isBefore(fetch.firstDay())
                        && !day.day().isAfter(fetch.lastDayInclusive())).toList();

        CoverageWindows coverage = resolver.resolve(windows, ScopeFilters.none(), fetched);

        assertThat(coverage.current().incompleteSources()).isEmpty();
        assertThat(coverage.previousPeriod().incompleteSources()).isEmpty();
        assertThat(coverage.failureBaseline28d().incompleteSources()).isEmpty();
        assertThat(coverage.budgetMonthToDate().incompleteSources()).isEmpty();
        assertThat(coverage.funnelObservation().incompleteSources()).isEmpty();
    }

    @Test
    void completeMetadataAcrossEveryWindowLeavesNothingIncomplete() {
        CoverageWindows coverage = resolver.resolve(
                resolver.windows(selection(ScopeFilters.none()), PUBLICATION),
                ScopeFilters.none(), fullyCoveredFixtureInterval());

        assertThat(coverage.current().incompleteSources()).isEmpty();
        assertThat(coverage.previousPeriod().incompleteSources()).isEmpty();
        assertThat(coverage.failureBaseline28d().incompleteSources()).isEmpty();
        assertThat(coverage.funnelObservation().incompleteSources()).isEmpty();
        assertThat(coverage.budgetMonthToDate().incompleteSources()).isEmpty();
    }

    /** A.5: an absent row inside the published interval means not covered, never "no activity". */
    @Test
    void aMissingSourceDayRowMakesThatSourceIncomplete() {
        List<SourceDay> days = new ArrayList<>(fullyCoveredFixtureInterval());
        days.removeIf(day -> day.source() == LogicalSource.USAGE
                && day.day().equals(LocalDate.of(2026, 1, 20)));

        CoverageWindows coverage = resolver.resolve(
                resolver.windows(selection(ScopeFilters.none()), PUBLICATION),
                ScopeFilters.none(), days);

        assertThat(coverage.current().incompleteSources()).containsExactly(LogicalSource.USAGE);
    }

    @Test
    void anExplicitlyIncompleteDayIsTreatedTheSameAsAMissingOne() {
        List<SourceDay> days = new ArrayList<>(fullyCoveredFixtureInterval());
        days.replaceAll(day -> day.source() == LogicalSource.USAGE
                && day.day().equals(LocalDate.of(2026, 1, 20))
                ? new SourceDay(day.source(), day.day(), false)
                : day);

        CoverageWindows coverage = resolver.resolve(
                resolver.windows(selection(ScopeFilters.none()), PUBLICATION),
                ScopeFilters.none(), days);

        assertThat(coverage.current().incompleteSources()).containsExactly(LogicalSource.USAGE);
    }

    /**
     * The case the whole day-grain design exists for: a complete current window beside an
     * incomplete baseline. Comparisons are suppressed; current values are not.
     */
    @Test
    void aBaselineOnlyGapLeavesTheCurrentWindowUntouched() {
        List<SourceDay> days = new ArrayList<>(fullyCoveredFixtureInterval());
        days.removeIf(day -> day.source() == LogicalSource.TASKS
                && day.day().equals(LocalDate.of(2026, 1, 3)));

        CoverageWindows coverage = resolver.resolve(
                resolver.windows(selection(ScopeFilters.none()), PUBLICATION),
                ScopeFilters.none(), days);

        assertThat(coverage.current().incompleteSources()).isEmpty();
        assertThat(coverage.previousPeriod().incompleteSources()).containsExactly(LogicalSource.TASKS);
        assertThat(coverage.failureBaseline28d().incompleteSources()).containsExactly(LogicalSource.TASKS);
    }

    /**
     * A missing pull-request source degrades exactly its dependants. The completion rate and the
     * funnel's task-only stages need tasks alone and must stay available (A.5).
     */
    @Test
    void aPullRequestGapDegradesOnlyThePullRequestDependants() {
        List<SourceDay> days = new ArrayList<>(fullyCoveredFixtureInterval());
        days.removeIf(day -> day.source() == LogicalSource.PULL_REQUESTS
                && day.day().equals(LocalDate.of(2026, 1, 20)));

        CoverageWindows coverage = resolver.resolve(
                resolver.windows(selection(ScopeFilters.none()), PUBLICATION),
                ScopeFilters.none(), days);

        assertThat(coverage.current().supports(LogicalSource.TASK_OUTCOMES)).isTrue();
        assertThat(coverage.current().supports(LogicalSource.SEAT_ACTIVITY)).isTrue();
        assertThat(coverage.current().supports(LogicalSource.SPEND)).isTrue();
        assertThat(coverage.current().supports(LogicalSource.PR_OUTCOMES)).isFalse();
        assertThat(coverage.current().supports(LogicalSource.UNIT_COST)).isFalse();
        assertThat(coverage.current().supports(LogicalSource.FUNNEL_PR_STAGES)).isFalse();
        assertThat(coverage.current().missingFrom(LogicalSource.UNIT_COST))
                .containsExactly(LogicalSource.PULL_REQUESTS);
    }

    /** Contract 5.3: a repository filter makes budget evaluation unavailable for the scope. */
    @Test
    void aRepositoryFilterLeavesNoBudgetWindowToEvaluate() {
        ScopeFilters filters = new ScopeFilters(null, UUID.randomUUID());

        CoverageWindows coverage = resolver.resolve(
                resolver.windows(selection(filters), PUBLICATION), filters, fullyCoveredFixtureInterval());

        assertThat(coverage.budgetEvaluable()).isFalse();
        assertThat(coverage.budgetMonthToDate()).isNull();
        assertThat(coverage.current().incompleteSources()).isEmpty();
    }

    /** A team filter narrows which budget is evaluated, not whether one is (contract 6.1). */
    @Test
    void aTeamFilterKeepsTheBudgetWindow() {
        ScopeFilters filters = new ScopeFilters(UUID.randomUUID(), null);

        CoverageWindows coverage = resolver.resolve(
                resolver.windows(selection(filters), PUBLICATION), filters, fullyCoveredFixtureInterval());

        assertThat(coverage.budgetEvaluable()).isTrue();
        assertThat(coverage.budgetMonthToDate().window().firstDay()).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    /**
     * A baseline reaching before the published interval has no metadata at all, so it is not
     * covered. Answering it as zero activity would invent history.
     */
    @Test
    void aBaselineReachingBeforeThePublishedIntervalIsNotCovered() {
        DashboardSelection early = new DashboardSelection(
                DateWindow.ofInclusiveDates(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 16)),
                ScopeFilters.none(), Grouping.TEAMS);

        CoverageWindows coverage = resolver.resolve(
                resolver.windows(early, PUBLICATION), ScopeFilters.none(), fullyCoveredFixtureInterval());

        assertThat(coverage.current().incompleteSources()).isEmpty();
        assertThat(coverage.previousPeriod().incompleteSources())
                .containsExactlyInAnyOrder(LogicalSource.values());
    }
}
