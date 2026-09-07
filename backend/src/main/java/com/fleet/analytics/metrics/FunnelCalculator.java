package com.fleet.analytics.metrics;

import com.fleet.analytics.metrics.model.FunnelCounts;
import com.fleet.analytics.metrics.model.FunnelResult;
import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.MetricResult;
import com.fleet.analytics.metrics.model.WindowCoverage;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The cohort funnel (contract 4). Every stage is a plain count and carries no comparison: P0
 * computes no previous-period funnel.
 *
 * <p>Stages state their availability separately because they do not share dependencies. The
 * task-only stages need tasks alone, while {@code prOpened} and {@code prMerged} also need
 * pull-request and repository data — so a missing PR source leaves five stages intact and turns two
 * unavailable, rather than flattening them to zero (A.5).
 */
@Component
public class FunnelCalculator {

    /**
     * @param observationCutoff {@code dataThrough - 1 day}, the date the required on-screen label
     *     names: "outcomes observed through …" (contract 4).
     */
    public FunnelResult build(
            FunnelCounts counts, WindowCoverage observationCoverage, LocalDate observationCutoff) {
        Set<LogicalSource> taskSources = LogicalSource.TASK_OUTCOMES;
        if (!observationCoverage.supports(taskSources)) {
            MetricResult unavailable = missing(observationCoverage, taskSources);
            return new FunnelResult(observationCutoff, unavailable, unavailable, unavailable,
                    unavailable, unavailable, unavailable, unavailable);
        }

        Set<LogicalSource> prSources = LogicalSource.FUNNEL_PR_STAGES;
        boolean prStagesObservable = counts.hasPrStages() && observationCoverage.supports(prSources);
        MetricResult prOpened = prStagesObservable
                ? MetricCalculator.count(counts.prOpened())
                : missing(observationCoverage, prSources);
        MetricResult prMerged = prStagesObservable
                ? MetricCalculator.count(counts.prMerged())
                : missing(observationCoverage, prSources);

        return new FunnelResult(observationCutoff,
                MetricCalculator.count(counts.started()),
                MetricCalculator.count(counts.completed()),
                prOpened,
                prMerged,
                MetricCalculator.count(counts.failed()),
                MetricCalculator.count(counts.cancelled()),
                MetricCalculator.count(counts.inProgress()));
    }

    private MetricResult missing(WindowCoverage coverage, Set<LogicalSource> required) {
        return MetricResult.missingData(
                Explanations.sourcesNotCovered(coverage.missingFrom(required)));
    }
}
