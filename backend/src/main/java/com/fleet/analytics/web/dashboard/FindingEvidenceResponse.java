package com.fleet.analytics.web.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The public shape of a finding's evidence (AC-06.2). Every field is optional: a finding carries
 * only what its own rule produced, and the rest are omitted rather than nulled.
 *
 * <p>{@code domain} is present only for an ADMIN. For a VIEWER the key is absent entirely while
 * every count is identical (AC-06.9), which is why this object is <em>built</em> per role rather
 * than built once and edited.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FindingEvidenceResponse(
        String domain,
        Long distinctTasks,
        Long distinctUsers,
        Integer thresholdTasks,
        Integer thresholdUsers,
        DisplayResponse currentRate,
        DisplayResponse baselineRate,
        DisplayResponse thresholdPercentagePoints,
        Long failedTasks,
        FailureReasonGroupsResponse failureReasons,
        Long budgetCents,
        Long monthToDateSpendCents,
        Integer elapsedDays,
        Integer daysInMonth,
        DisplayResponse forecast,
        DisplayResponse overrun) {

    /** Agent, platform and policy counts — the grouping research 6.4 fixes. */
    public record FailureReasonGroupsResponse(long agent, long platform, long policy) {}
}
