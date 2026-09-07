package com.fleet.analytics.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.metrics.model.DailyValue;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.TrendResult;
import com.fleet.analytics.metrics.model.TrendUnit;
import com.fleet.analytics.metrics.model.ValueState;
import com.fleet.analytics.metrics.model.WindowCoverage;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Zero-filling versus unavailability — the one distinction a trend must never blur. */
class TrendBuilderTest {

    private static final DateWindow WINDOW = DateWindow.ofInclusiveDates(
            LocalDate.of(2026, 1, 16), LocalDate.of(2026, 1, 31));
    private static final WindowCoverage COVERED = WindowCoverage.fullyCovered(WINDOW);

    private final TrendBuilder builder = new TrendBuilder();

    private static DailyValue on(int dayOfJanuary, long amount) {
        return new DailyValue(LocalDate.of(2026, 1, dayOfJanuary), BigInteger.valueOf(amount));
    }

    private static BigInteger amountOn(List<DailyValue> points, int dayOfJanuary) {
        return points.stream()
                .filter(point -> point.date().equals(LocalDate.of(2026, 1, dayOfJanuary)))
                .findFirst().orElseThrow().amount();
    }

    /** Contract fixture: PR-3 merged on 20 January is the only merge inside the selected range. */
    @Test
    void everyDayOfTheRangeAppearsOnceWithSparseDaysFilledWithZero() {
        TrendResult trends = builder.build(WINDOW, COVERED, List.of(on(20, 1)), List.of());

        List<DailyValue> points = trends.mergedPrsPerDay().points();
        assertThat(points).hasSize(16);
        assertThat(points.getFirst().date()).isEqualTo(LocalDate.of(2026, 1, 16));
        assertThat(points.getLast().date()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(points).extracting(DailyValue::date).doesNotHaveDuplicates().isSorted();
        assertThat(amountOn(points, 20)).isEqualTo(BigInteger.ONE);
        assertThat(amountOn(points, 19)).isEqualTo(BigInteger.ZERO);
    }

    /** Contract fixture 7.3: 150c on 18 January, 350c on 22 January, 1800c on 30 January. */
    @Test
    void spendKeepsExactCentsOnTheDayItWasMetered() {
        TrendResult trends = builder.build(
                WINDOW, COVERED, List.of(), List.of(on(18, 150), on(22, 350), on(30, 1800)));

        List<DailyValue> points = trends.spendPerDay().points();
        assertThat(trends.spendPerDay().unit()).isEqualTo(TrendUnit.USD_CENTS);
        assertThat(points).hasSize(16);
        assertThat(amountOn(points, 18)).isEqualTo(BigInteger.valueOf(150));
        assertThat(amountOn(points, 22)).isEqualTo(BigInteger.valueOf(350));
        assertThat(amountOn(points, 30)).isEqualTo(BigInteger.valueOf(1800));
        assertThat(points.stream().map(DailyValue::amount).reduce(BigInteger.ZERO, BigInteger::add))
                .isEqualTo(BigInteger.valueOf(2300));
    }

    /** A complete window with no records is genuine zero activity, not an absence (contract 1.5). */
    @Test
    void aCoveredButEmptyRangeIsARunOfRealZeros() {
        TrendResult trends = builder.build(WINDOW, COVERED, List.of(), List.of());

        assertThat(trends.mergedPrsPerDay().state()).isEqualTo(ValueState.OK);
        assertThat(trends.mergedPrsPerDay().points()).hasSize(16);
        assertThat(trends.mergedPrsPerDay().points())
                .allMatch(point -> point.amount().signum() == 0);
    }

    /**
     * The inverse case, and the one that matters: an uncovered source yields no points at all. A
     * run of zeros here would draw a confident flat line over data nobody has.
     */
    @Test
    void anUncoveredSourceYieldsNoPointsRatherThanZeros() {
        WindowCoverage usageMissing = new WindowCoverage(WINDOW, Set.of(LogicalSource.USAGE));

        TrendResult trends = builder.build(
                WINDOW, usageMissing, List.of(on(20, 1)), List.of(on(18, 150)));

        assertThat(trends.spendPerDay().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(trends.spendPerDay().points()).isEmpty();
        assertThat(trends.spendPerDay().explanation().text()).contains("usage");
    }

    /** The two series are independent: silencing one must not silence the other. */
    @Test
    void aMissingUsageSourceLeavesTheMergedPrSeriesRendering() {
        WindowCoverage usageMissing = new WindowCoverage(WINDOW, Set.of(LogicalSource.USAGE));

        TrendResult trends = builder.build(WINDOW, usageMissing, List.of(on(20, 1)), List.of());

        assertThat(trends.mergedPrsPerDay().state()).isEqualTo(ValueState.OK);
        assertThat(trends.mergedPrsPerDay().points()).hasSize(16);
        assertThat(amountOn(trends.mergedPrsPerDay().points(), 20)).isEqualTo(BigInteger.ONE);
    }

    @Test
    void anUncoveredPullRequestSourceSilencesOnlyTheMergedPrSeries() {
        WindowCoverage prMissing = new WindowCoverage(WINDOW, Set.of(LogicalSource.PULL_REQUESTS));

        TrendResult trends = builder.build(WINDOW, prMissing, List.of(), List.of(on(18, 150)));

        assertThat(trends.mergedPrsPerDay().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(trends.mergedPrsPerDay().points()).isEmpty();
        assertThat(trends.spendPerDay().state()).isEqualTo(ValueState.OK);
    }
}
