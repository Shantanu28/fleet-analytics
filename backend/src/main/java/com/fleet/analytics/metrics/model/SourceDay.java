package com.fleet.analytics.metrics.model;

import java.time.LocalDate;
import java.util.Objects;

/** One {@code source_day_coverage} row: is this logical source complete for this UTC day? */
public record SourceDay(LogicalSource source, LocalDate day, boolean complete) {

    public SourceDay {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(day, "day");
    }
}
