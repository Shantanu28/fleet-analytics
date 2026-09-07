package com.fleet.analytics.web.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleet.analytics.metrics.Explanations;
import com.fleet.analytics.metrics.MetricCalculator;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.MetricResult;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The three wire-level conventions a schema check alone would not pin down: what is omitted, what is
 * written as an explicit null, and what happens to a number too large to survive a JSON round trip.
 */
class DashboardAssemblerTest {

    // --- Omission versus null ---------------------------------------------------------------------

    /**
     * An unavailable display is <b>absent</b>, never null, and its reason takes its place. A client
     * that saw {@code "display": null} would have to decide whether that meant zero, unknown or
     * not-applicable — the ambiguity the convention exists to remove.
     */
    @Test
    void anUnavailableMetricOmitsItsDisplayAndCarriesItsReason() {
        MetricResponse response = MetricResponse.from(
                MetricResult.noDenominator(Explanations.noMergedPrs()));

        assertThat(response.state()).isEqualTo("no_denominator");
        assertThat(response.display()).isNull();
        assertThat(response.reasonCode()).isEqualTo("no_merged_prs");
        assertThat(response.reason()).contains("No merged PRs in this period");
    }

    /** A defined metric is the mirror image: a display, and no reason at all. */
    @Test
    void aDefinedMetricCarriesADisplayAndNoReason() {
        MetricResponse response = MetricResponse.from(
                MetricCalculator.rate(BigInteger.ONE, BigInteger.TWO, Explanations.noTerminalTasks()));

        assertThat(response.state()).isEqualTo("ok");
        assertThat(response.display().value()).isEqualTo("50.0");
        assertThat(response.reasonCode()).isNull();
        assertThat(response.reason()).isNull();
    }

    /** {@code roundsToZero} appears only where contract 8.6 defines it: on spend totals. */
    @Test
    void roundsToZeroAppearsOnlyOnSpendTotals() {
        assertThat(MetricResponse.from(MetricCalculator.spendTotal(BigInteger.valueOf(40)))
                .roundsToZero()).isTrue();
        assertThat(MetricResponse.from(MetricCalculator.unitCost(BigInteger.valueOf(2300),
                        BigInteger.ONE, Explanations.noMergedPrs())).roundsToZero()).isNull();
    }

    @Test
    void aMetricWithNoComparisonOmitsTheComparisonEntirely() {
        assertThat(MetricResponse.from(MetricCalculator.count(4)).comparison()).isNull();
    }

    // --- The finding link's tri-state patch ---------------------------------------------------------

    /**
     * Absent means preserve and an explicit null means clear, so the two must stay distinguishable
     * in the map itself. A record with nullable fields could not express both under one include
     * policy, and either choice would break half the finding types silently.
     */
    @Test
    void theLinkPatchDistinguishesAbsentFromExplicitlyNull() {
        java.util.Map<String, Object> patch = new java.util.LinkedHashMap<>();
        patch.put("repositoryId", null);
        patch.put("grouping", "teams");

        FindingLinkResponse link = new FindingLinkResponse("spendTrend", patch);

        assertThat(link.patchFields()).containsKey("repositoryId");
        assertThat(link.patchFields().get("repositoryId")).isNull();
        assertThat(link.patchFields()).doesNotContainKey("teamId");
    }

    /** The patch is copied defensively, and the copy must tolerate the null values it carries. */
    @Test
    void theLinkPatchIsCopiedWithoutRejectingItsNulls() {
        java.util.Map<String, Object> patch = new java.util.LinkedHashMap<>();
        patch.put("teamId", null);

        FindingLinkResponse link = new FindingLinkResponse("attention", patch);
        patch.put("teamId", "mutated-after-construction");

        assertThat(link.patchFields().get("teamId")).isNull();
    }

    // --- JSON-safe integers ---------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is safe")
    @ValueSource(longs = {0, 1, 2300, 9_007_199_254_740_991L, -9_007_199_254_740_991L})
    void valuesWithinTheSafeRangePassThroughExactly(long value) {
        assertThat(JsonSafeInteger.of(value)).isEqualTo(value);
        assertThat(JsonSafeInteger.of(BigInteger.valueOf(value))).isEqualTo(value);
    }

    /**
     * Above 2^53-1 a JSON number loses precision on parse in most clients. A spend figure that
     * arrives slightly wrong is worse than one that fails, because nothing downstream can detect it
     * — so this is refused rather than truncated, zeroed or silently re-typed.
     */
    @Test
    void aValueBeyondTheSafeRangeIsRefusedRatherThanTruncated() {
        BigInteger tooLarge = BigInteger.valueOf(JsonSafeInteger.MAX_SAFE).add(BigInteger.ONE);

        assertThatThrownBy(() -> JsonSafeInteger.of(tooLarge))
                .isInstanceOf(JsonSafeInteger.UnsafeNumericRangeException.class)
                .hasMessageContaining("JSON-safe integer range");
        assertThatThrownBy(() -> JsonSafeInteger.of(tooLarge.negate()))
                .isInstanceOf(JsonSafeInteger.UnsafeNumericRangeException.class);
    }

    @Test
    void aValueFarBeyondLongIsAlsoRefused() {
        assertThatThrownBy(() -> JsonSafeInteger.of(
                        BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN)))
                .isInstanceOf(JsonSafeInteger.UnsafeNumericRangeException.class);
    }

    /** Display values stay strings: their scale is part of the answer, not a formatting choice. */
    @Test
    void displayValuesAreServedAsStringsWithTheirScaleIntact() {
        assertThat(DisplayResponse.from(new DisplayValue("23.00", DisplayUnit.USD)).value())
                .isEqualTo("23.00");
        assertThat(DisplayResponse.from(new DisplayValue("23", DisplayUnit.USD)).value())
                .isEqualTo("23");
        assertThat(DisplayResponse.from(null)).isNull();
    }
}
