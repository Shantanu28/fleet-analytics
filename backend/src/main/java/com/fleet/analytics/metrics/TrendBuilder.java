package com.fleet.analytics.metrics;

import com.fleet.analytics.metrics.model.DailyValue;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.TrendResult;
import com.fleet.analytics.metrics.model.TrendSeries;
import com.fleet.analytics.metrics.model.TrendUnit;
import com.fleet.analytics.metrics.model.ValueState;
import com.fleet.analytics.metrics.model.WindowCoverage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The two daily series (contract 5.1), each carrying exact integers so no rounding is delegated.
 *
 * <p>The distinction this class exists to hold: a covered day with no records is an explicit
 * {@code 0}, while an uncovered source yields an unavailable series with <em>no points at all</em>.
 * A run of zeros asserts that nothing happened; an empty series admits we cannot say. Emitting the
 * first when the second is true would draw a confident flat line over missing data.
 *
 * <p>The two series are independent: a missing usage source silences spend while merged PRs keep
 * rendering.
 */
@Component
public class TrendBuilder {

    public TrendResult build(DateWindow window, WindowCoverage coverage,
            List<DailyValue> mergedPrsPerDay, List<DailyValue> spendPerDay) {
        return new TrendResult(
                series(window, coverage, LogicalSource.PR_OUTCOMES, TrendUnit.COUNT, mergedPrsPerDay),
                series(window, coverage, LogicalSource.SPEND, TrendUnit.USD_CENTS, spendPerDay));
    }

    private TrendSeries series(DateWindow window, WindowCoverage coverage,
            Set<LogicalSource> required, TrendUnit unit, List<DailyValue> observed) {
        if (!coverage.supports(required)) {
            return TrendSeries.unavailable(unit, ValueState.MISSING_DATA,
                    Explanations.sourcesNotCovered(coverage.missingFrom(required)));
        }
        return TrendSeries.of(unit, zeroFilled(window, observed));
    }

    /**
     * Every complete UTC day in the window appears exactly once, in order. Days the query returned
     * nothing for become explicit zeros — valid data inside a covered interval (contract 1.5).
     */
    private List<DailyValue> zeroFilled(DateWindow window, List<DailyValue> observed) {
        Map<LocalDate, DailyValue> byDate = new HashMap<>();
        observed.forEach(value -> byDate.put(value.date(), value));

        List<DailyValue> points = new ArrayList<>();
        for (LocalDate day : window.days()) {
            points.add(byDate.getOrDefault(day, DailyValue.zero(day)));
        }
        return points;
    }
}
