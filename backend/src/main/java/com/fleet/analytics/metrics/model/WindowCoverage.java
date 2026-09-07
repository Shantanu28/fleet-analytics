package com.fleet.analytics.metrics.model;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One reporting window and the logical sources that are not fully covered across it (A.5).
 *
 * <p>An empty {@code incompleteSources} means every source is complete for every day the window
 * spans, so an empty result set inside it is genuine zero activity. A source listed here is
 * unknown for at least one day, which makes its dependent metrics {@code missing_data} — never
 * zero (contract 1.5).
 */
public record WindowCoverage(DateWindow window, Set<LogicalSource> incompleteSources) {

    public WindowCoverage {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(incompleteSources, "incompleteSources");
        incompleteSources = incompleteSources.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(incompleteSources));
    }

    public static WindowCoverage fullyCovered(DateWindow window) {
        return new WindowCoverage(window, Set.of());
    }

    /** True when every source the metric family depends on is complete across this whole window. */
    public boolean supports(Collection<LogicalSource> required) {
        return required.stream().noneMatch(incompleteSources::contains);
    }

    /**
     * The subset of a metric family's dependencies that is actually missing, so the explanation can
     * name them instead of saying "some data is unavailable".
     */
    public Set<LogicalSource> missingFrom(Collection<LogicalSource> required) {
        EnumSet<LogicalSource> missing = EnumSet.noneOf(LogicalSource.class);
        required.stream().filter(incompleteSources::contains).forEach(missing::add);
        return Set.copyOf(missing);
    }
}
