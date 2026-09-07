package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * One resolved dashboard selection: the current window, the active filters and the table grouping.
 *
 * <p>Produced only by parsing, so every downstream component receives a selection that is already
 * known to be well-formed and inside the published coverage. There is no organisation here — scope
 * comes from the verified token and is passed separately to every query (04 5.1).
 */
public record DashboardSelection(DateWindow window, ScopeFilters filters, Grouping grouping) {

    public DashboardSelection {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(grouping, "grouping");
    }

    /** The immediately preceding equal-length block used by every period-over-period comparison. */
    public DateWindow previousWindow() {
        return window.immediatelyBefore();
    }
}
