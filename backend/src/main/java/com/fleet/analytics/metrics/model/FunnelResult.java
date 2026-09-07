package com.fleet.analytics.metrics.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The cohort funnel (contract 4). Each stage states its own availability, so a missing pull-request
 * source leaves the task-only stages intact while the two PR stages become {@code missing_data}
 * rather than dropping to zero.
 *
 * <p>{@code observationCutoff} is {@code dataThrough - 1 day}: the last complete UTC day whose
 * outcomes are reported, and the date the required on-screen label names (AC-01.3).
 */
public record FunnelResult(
        LocalDate observationCutoff,
        MetricResult started,
        MetricResult completed,
        MetricResult prOpened,
        MetricResult prMerged,
        MetricResult failed,
        MetricResult cancelled,
        MetricResult inProgress) {

    public FunnelResult {
        Objects.requireNonNull(observationCutoff, "observationCutoff");
        Objects.requireNonNull(started, "started");
        Objects.requireNonNull(completed, "completed");
        Objects.requireNonNull(prOpened, "prOpened");
        Objects.requireNonNull(prMerged, "prMerged");
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(inProgress, "inProgress");
    }
}
