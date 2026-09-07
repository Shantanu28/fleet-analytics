package com.fleet.analytics.metrics.model;

import java.util.List;
import java.util.Objects;

/**
 * One daily series over the selected range (contract 5.1).
 *
 * <p>The distinction this type exists to keep: a covered range with no matching records is a full
 * run of explicit zeros, while an incomplete source is an unavailable series with <em>no</em>
 * points. A run of zeros claims "nothing happened"; an empty series says "we do not know". Emitting
 * the first when the second is true is the single worst failure a trend can have, so the compact
 * constructor refuses to let an unavailable series carry points at all.
 */
public record TrendSeries(
        ValueState state, TrendUnit unit, Explanation explanation, List<DailyValue> points) {

    public TrendSeries {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(points, "points");
        if (state != ValueState.OK && state != ValueState.MISSING_DATA
                && state != ValueState.UNAVAILABLE_FOR_SCOPE) {
            throw new IllegalArgumentException("a trend series cannot be " + state);
        }
        points = List.copyOf(points);
        if (state == ValueState.OK && explanation != null) {
            throw new IllegalArgumentException("an available series needs no explanation");
        }
        if (state != ValueState.OK) {
            Objects.requireNonNull(explanation, "an unavailable series must explain itself");
            if (!points.isEmpty()) {
                throw new IllegalArgumentException(
                        "an unavailable series must carry no points, never a run of zeros");
            }
        }
    }

    public static TrendSeries of(TrendUnit unit, List<DailyValue> points) {
        return new TrendSeries(ValueState.OK, unit, null, points);
    }

    public static TrendSeries unavailable(TrendUnit unit, ValueState state, Explanation explanation) {
        return new TrendSeries(state, unit, explanation, List.of());
    }
}
