package com.fleet.analytics.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.FunnelCounts;
import com.fleet.analytics.metrics.model.FunnelResult;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.ValueState;
import com.fleet.analytics.metrics.model.WindowCoverage;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Stage availability and the lifecycle invariants the funnel refuses to publish without. */
class FunnelCalculatorTest {

    private static final LocalDate CUTOFF = LocalDate.of(2026, 2, 3);
    private static final DateWindow OBSERVATION = DateWindow.ofDays(
            LocalDate.of(2026, 1, 16), LocalDate.of(2026, 2, 4));
    private static final WindowCoverage COVERED = WindowCoverage.fullyCovered(OBSERVATION);

    private final FunnelCalculator calculator = new FunnelCalculator();

    /** Contract 8.3: cohort T5, T6, T7, T8 — one completed, one failed, one cancelled, one running. */
    private static FunnelCounts fixtureCohort() {
        return new FunnelCounts(4, 1, 1, 1, 1, 1L, 1L);
    }

    @Test
    void everyStageOfTheFixtureCohortIsCounted() {
        FunnelResult funnel = calculator.build(fixtureCohort(), COVERED, CUTOFF);

        assertThat(funnel.observationCutoff()).isEqualTo(CUTOFF);
        assertThat(funnel.started().display().value()).isEqualTo("4");
        assertThat(funnel.completed().display().value()).isEqualTo("1");
        assertThat(funnel.prOpened().display().value()).isEqualTo("1");
        assertThat(funnel.prMerged().display().value()).isEqualTo("1");
        assertThat(funnel.failed().display().value()).isEqualTo("1");
        assertThat(funnel.cancelled().display().value()).isEqualTo("1");
        assertThat(funnel.inProgress().display().value()).isEqualTo("1");
    }

    /** Contract 4: P0 computes no previous-period funnel, so no stage carries a comparison. */
    @Test
    void noStageCarriesAComparison() {
        FunnelResult funnel = calculator.build(fixtureCohort(), COVERED, CUTOFF);

        assertThat(funnel.started().comparison()).isNull();
        assertThat(funnel.prMerged().comparison()).isNull();
        assertThat(funnel.inProgress().comparison()).isNull();
    }

    /**
     * A.5 lists the completion rate and the funnel's task stages apart from its PR stages precisely
     * so this can happen: five stages keep counting while two become unavailable.
     */
    @Test
    void aMissingPullRequestSourceLeavesTheTaskStagesCounting() {
        WindowCoverage prMissing =
                new WindowCoverage(OBSERVATION, Set.of(LogicalSource.PULL_REQUESTS));

        FunnelResult funnel = calculator.build(
                FunnelCounts.withoutPrStages(4, 1, 1, 1, 1), prMissing, CUTOFF);

        assertThat(funnel.started().display().value()).isEqualTo("4");
        assertThat(funnel.completed().display().value()).isEqualTo("1");
        assertThat(funnel.failed().display().value()).isEqualTo("1");
        assertThat(funnel.prOpened().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(funnel.prMerged().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(funnel.prMerged().display()).isNull();
    }

    @Test
    void aMissingTaskSourceLeavesNoStageAvailable() {
        WindowCoverage tasksMissing = new WindowCoverage(OBSERVATION, Set.of(LogicalSource.TASKS));

        FunnelResult funnel = calculator.build(
                FunnelCounts.withoutPrStages(0, 0, 0, 0, 0), tasksMissing, CUTOFF);

        assertThat(funnel.started().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(funnel.inProgress().state()).isEqualTo(ValueState.MISSING_DATA);
        assertThat(funnel.prMerged().state()).isEqualTo(ValueState.MISSING_DATA);
    }

    // --- Invariants (contract 4) -----------------------------------------------------------------

    /**
     * A funnel whose parts do not sum to its whole means a query lost or duplicated rows. Publishing
     * it would put a plausible-looking wrong chart on screen, so construction fails instead.
     */
    @Test
    void aCohortThatDoesNotSumToItsStartedCountIsRejected() {
        assertThatThrownBy(() -> new FunnelCounts(4, 1, 1, 1, 2, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("funnel identity broken");
    }

    @Test
    void aCohortWhoseStagesDoNotNestIsRejected() {
        assertThatThrownBy(() -> new FunnelCounts(4, 1, 1, 1, 1, 1L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting broken");
        assertThatThrownBy(() -> new FunnelCounts(4, 1, 1, 1, 1, 2L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting broken");
    }

    /** The two PR stages share one source, so a half-known pair is not a representable state. */
    @Test
    void thePrStagesAreKnownTogetherOrNotAtAll() {
        assertThatThrownBy(() -> new FunnelCounts(4, 1, 1, 1, 1, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("known together or not at all");
    }
}
