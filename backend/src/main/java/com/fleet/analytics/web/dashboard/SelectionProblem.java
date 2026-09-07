package com.fleet.analytics.web.dashboard;

/**
 * Why a dashboard selection was rejected. Each constant carries the stable problem-type slug the
 * API contract publishes, so the HTTP adapter maps a reason to a response without re-deciding it.
 *
 * <p>These are distinct types rather than one "bad request" because the client keys its on-screen
 * message on them: telling a user their range is reversed is useful, telling them "invalid input"
 * is not. {@code UNKNOWN_FILTER} covers malformed, nonexistent and foreign identifiers alike — a
 * caller must not be able to tell another organisation's id from one that never existed.
 */
public enum SelectionProblem {
    INVALID_DATE_FORMAT("invalid-date-format"),
    REVERSED_DATE_RANGE("reversed-date-range"),
    INCOMPLETE_DATE_RANGE("incomplete-date-range"),
    RANGE_OUTSIDE_COVERAGE("range-outside-coverage"),
    UNKNOWN_FILTER("unknown-filter"),
    INVALID_GROUPING("invalid-grouping");

    private final String problemType;

    SelectionProblem(String problemType) {
        this.problemType = problemType;
    }

    public String problemType() {
        return problemType;
    }
}
