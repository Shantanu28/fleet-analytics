package com.fleet.analytics.web.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Every dashboard section for one filter selection.
 *
 * <p>The sections are served together because they are one answer: they come from one snapshot, and
 * a client must not have to reconcile a KPI row read at one instant against a table read at another.
 */
public record DashboardResponse(
        CoverageResponse coverage,
        SelectionResponse selection,
        CoverageWindowsResponse coverageWindows,
        KpisResponse kpis,
        FunnelResponse funnel,
        TrendsResponse trends,
        ComparisonTableResponse comparison,
        AttentionResponse attention) {

    /** The organisation's published reporting interval and the revision it was computed from. */
    public record CoverageResponse(
            OffsetDateTime dataAvailableFrom, OffsetDateTime dataThrough, String revision) {}

    /**
     * What was actually selected, resolved. {@code teamId} and {@code repositoryId} are required and
     * nullable, so they are always written — null means "no filter", and omitting the key would make
     * a client guess whether its filter was applied or dropped.
     */
    public record SelectionResponse(
            LocalDate from,
            LocalDate to,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive,
            LocalDate previousFrom,
            LocalDate previousTo,
            UUID teamId,
            UUID repositoryId,
            String grouping,
            LocalDate observationCutoff) {}

    /** One window and the sources not fully covering it. Empty means fully covered. */
    public record CoverageWindowResponse(
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive,
            List<String> incompleteSources) {

        public CoverageWindowResponse {
            incompleteSources = List.copyOf(incompleteSources);
        }
    }

    /**
     * The five windows, each judged independently. {@code budgetMonthToDate} is omitted when a
     * repository filter leaves no budget to evaluate (contract 5.3).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CoverageWindowsResponse(
            CoverageWindowResponse current,
            CoverageWindowResponse previousPeriod,
            CoverageWindowResponse failureBaseline28d,
            CoverageWindowResponse budgetMonthToDate,
            CoverageWindowResponse funnelObservation) {}

    /** The five cards, in the order research 7 fixes. */
    public record KpisResponse(
            MetricResponse mergedPrs,
            MetricResponse terminalMergeRate,
            MetricResponse costPerMergedPr,
            MetricResponse taskCompletionRate,
            SeatsMetricResponse seats) {}

    /** Active seats, with capacity and a separately stated utilisation that carries no comparison. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SeatsMetricResponse(
            String state,
            DisplayResponse display,
            String reasonCode,
            String reason,
            long licensedSeats,
            MetricResponse.ComparisonResponse comparison,
            MetricResponse utilisation) {}

    /** Each stage states its own availability, so a missing PR source silences only two of them. */
    public record FunnelResponse(
            LocalDate observationCutoff,
            StagesResponse stages,
            SideExitsResponse sideExits,
            ResidualResponse residual) {

        public record StagesResponse(MetricResponse started, MetricResponse completed,
                MetricResponse prOpened, MetricResponse prMerged) {}

        public record SideExitsResponse(MetricResponse failed, MetricResponse cancelled) {}

        public record ResidualResponse(MetricResponse inProgress) {}
    }

    /** Two independent daily series carrying exact integers. */
    public record TrendsResponse(
            SeriesResponse<CountPointResponse> mergedPrsPerDay,
            SeriesResponse<SpendPointResponse> spendPerDay) {

        /**
         * A complete but empty range is a run of explicit zeros; an uncovered source is an
         * unavailable series with no points at all. Never the first when the second is true.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record SeriesResponse<T>(
                String state, String unit, String reasonCode, String reason, List<T> points) {

            public SeriesResponse {
                points = List.copyOf(points);
            }
        }

        public record CountPointResponse(LocalDate date, long value) {}

        public record SpendPointResponse(LocalDate date, long spendCents) {}
    }

    /** Rows plus the pooled organisational benchmark they are measured against. */
    public record ComparisonTableResponse(
            String grouping, List<ComparisonRowResponse> rows, BenchmarkResponse benchmark) {

        public ComparisonTableResponse {
            rows = List.copyOf(rows);
        }
    }

    public record ComparisonRowResponse(
            UUID scopeId,
            String scopeName,
            long terminalTaskCount,
            MetricResponse taskCompletionRate,
            MetricResponse terminalMergeRate,
            MetricResponse costPerMergedPr,
            MetricResponse codeChangeSpend) {}

    public record BenchmarkResponse(
            BenchmarkScopeResponse scope,
            MetricResponse taskCompletionRate,
            MetricResponse terminalMergeRate,
            MetricResponse costPerMergedPr,
            MetricResponse codeChangeSpend) {}

    /** Stated by the server so AC-05.2 and AC-05.3 labels are not deduced by the client. */
    public record BenchmarkScopeResponse(
            boolean teamFilterIgnored,
            boolean repositoryFilterApplied,
            boolean includesSelectedTeam,
            boolean mayIncludeUndisplayedTeams) {}
}
