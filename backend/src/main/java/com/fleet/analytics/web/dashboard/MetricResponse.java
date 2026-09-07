package com.fleet.analytics.web.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fleet.analytics.metrics.model.ComparisonResult;
import com.fleet.analytics.metrics.model.MetricResult;

/**
 * The contract's {@code Metric}: a value state and, independently, an optional comparison.
 *
 * <p>{@code display} is <b>omitted</b> for every unavailable state, never written as null, and the
 * state always travels with {@code reasonCode} and {@code reason} instead. That is the convention
 * the published schema enforces with if/then, and the reason it does: a client must never have to
 * infer availability from a missing or null number.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricResponse(
        String state,
        DisplayResponse display,
        Boolean roundsToZero,
        String reasonCode,
        String reason,
        EvidenceResponse evidence,
        ComparisonResponse comparison) {

    public static MetricResponse from(MetricResult metric) {
        return from(metric, null);
    }

    public static MetricResponse from(MetricResult metric, EvidenceResponse evidence) {
        return new MetricResponse(
                metric.state().wireName(),
                DisplayResponse.from(metric.display()),
                metric.roundsToZero(),
                metric.explanation() == null ? null : metric.explanation().code(),
                metric.explanation() == null ? null : metric.explanation().text(),
                evidence,
                ComparisonResponse.from(metric.comparison(), null));
    }

    /** Used where the comparison carries its own evidence, which differs from the value's. */
    public static MetricResponse from(MetricResult metric, EvidenceResponse valueEvidence,
            EvidenceResponse comparisonEvidence) {
        return new MetricResponse(
                metric.state().wireName(),
                DisplayResponse.from(metric.display()),
                metric.roundsToZero(),
                metric.explanation() == null ? null : metric.explanation().code(),
                metric.explanation() == null ? null : metric.explanation().text(),
                valueEvidence,
                ComparisonResponse.from(metric.comparison(), comparisonEvidence));
    }

    /** The contract's {@code Comparison}, with the same omit-when-unavailable rule. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ComparisonResponse(
            String kind,
            String state,
            DisplayResponse display,
            String reasonCode,
            String reason,
            EvidenceResponse evidence) {

        static ComparisonResponse from(ComparisonResult comparison, EvidenceResponse evidence) {
            return comparison == null ? null : new ComparisonResponse(
                    comparison.kind().wireName(),
                    comparison.state().wireName(),
                    DisplayResponse.from(comparison.display()),
                    comparison.explanation() == null ? null : comparison.explanation().code(),
                    comparison.explanation() == null ? null : comparison.explanation().text(),
                    evidence);
        }
    }

    /**
     * Exact integer populations behind a metric, so on-screen copy can name a sample or show spend
     * without the client recomputing anything. Every field is optional; a metric carries only what
     * its own copy needs.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvidenceResponse(
            Long mergedPrs,
            Long terminalPrs,
            Long completedTasks,
            Long failedTasks,
            Long codeChangeSpendCents,
            Long previousMergedPrs,
            Long previousTerminalPrs,
            Long previousCompletedTasks,
            Long previousFailedTasks,
            Long previousCodeChangeSpendCents,
            Long previousActiveSeats) {

        /**
         * Every factory takes boxed values, and null means <em>unknown</em> rather than zero.
         *
         * <p>A count selected over a window whose source is not fully covered is not a fact: the
         * query returns 0 or a partial total, and publishing it as evidence would present a gap as
         * a precise measurement. Since these fields are omitted when null, an unknown figure simply
         * does not appear — which is the same convention the metric's own display follows.
         *
         * <p>Each factory collapses to null when it has nothing to say, so a metric carries no
         * empty evidence object.
         */
        public static EvidenceResponse mergedAndTerminalPrs(Long merged, Long terminal) {
            return orNull(new EvidenceResponse(merged, terminal, null, null, null,
                    null, null, null, null, null, null));
        }

        public static EvidenceResponse tasks(Long completed, Long failed) {
            return orNull(new EvidenceResponse(null, null, completed, failed, null,
                    null, null, null, null, null, null));
        }

        /**
         * The two halves are gated separately on purpose: an undefined ratio can still carry the
         * spend that is genuinely known (AC-01.5), so a missing PR source must not silence it.
         */
        public static EvidenceResponse unitCost(Long mergedPrs, Long codeChangeSpendCents) {
            return orNull(new EvidenceResponse(mergedPrs, null, null, null, codeChangeSpendCents,
                    null, null, null, null, null, null));
        }

        public static EvidenceResponse spend(Long codeChangeSpendCents) {
            return orNull(new EvidenceResponse(null, null, null, null, codeChangeSpendCents,
                    null, null, null, null, null, null));
        }

        public static EvidenceResponse previousMerged(Long merged) {
            return orNull(new EvidenceResponse(null, null, null, null, null,
                    merged, null, null, null, null, null));
        }

        public static EvidenceResponse previousPrs(Long merged, Long terminal) {
            return orNull(new EvidenceResponse(null, null, null, null, null,
                    merged, terminal, null, null, null, null));
        }

        public static EvidenceResponse previousUnitCost(Long merged, Long spendCents) {
            return orNull(new EvidenceResponse(null, null, null, null, null,
                    merged, null, null, null, spendCents, null));
        }

        public static EvidenceResponse previousTasks(Long completed, Long failed) {
            return orNull(new EvidenceResponse(null, null, null, null, null,
                    null, null, completed, failed, null, null));
        }

        public static EvidenceResponse previousSeats(Long activeSeats) {
            return orNull(new EvidenceResponse(null, null, null, null, null,
                    null, null, null, null, null, activeSeats));
        }

        private static EvidenceResponse orNull(EvidenceResponse evidence) {
            return evidence.isEmpty() ? null : evidence;
        }

        boolean isEmpty() {
            return mergedPrs == null && terminalPrs == null && completedTasks == null
                    && failedTasks == null && codeChangeSpendCents == null
                    && previousMergedPrs == null && previousTerminalPrs == null
                    && previousCompletedTasks == null && previousFailedTasks == null
                    && previousCodeChangeSpendCents == null && previousActiveSeats == null;
        }
    }
}
