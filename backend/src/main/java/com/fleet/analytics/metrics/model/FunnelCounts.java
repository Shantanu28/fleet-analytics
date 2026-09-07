package com.fleet.analytics.metrics.model;

/**
 * The cohort funnel's populations (contract 4): eligible code-change tasks created in the selected
 * window, with their stages observed through {@code dataThrough} — including events after the
 * window ends.
 *
 * <p>{@code prOpened} and {@code prMerged} are nullable, and that is the point. They depend on
 * sources the task stages do not, so when pull-request data is not covered they are <em>unknown</em>
 * rather than zero. Storing 0 there would manufacture a fact the query never established, and the
 * funnel would claim nothing was merged when it simply could not look.
 *
 * <p>The identity {@code completed + failed + cancelled + inProgress = started} and the nesting
 * {@code prMerged <= prOpened <= completed} follow from the lifecycle, so they are checked here: a
 * violation means a query lost or duplicated rows, and a silently wrong funnel is worse than a loud
 * failure.
 */
public record FunnelCounts(
        long started,
        long completed,
        long failed,
        long cancelled,
        long inProgress,
        Long prOpened,
        Long prMerged) {

    public FunnelCounts {
        if (started < 0 || completed < 0 || failed < 0 || cancelled < 0 || inProgress < 0) {
            throw new IllegalArgumentException("funnel counts cannot be negative");
        }
        if (completed + failed + cancelled + inProgress != started) {
            throw new IllegalArgumentException(
                    "funnel identity broken: completed + failed + cancelled + inProgress != started");
        }
        if ((prOpened == null) != (prMerged == null)) {
            throw new IllegalArgumentException(
                    "the two PR stages share one source, so they are known together or not at all");
        }
        if (prOpened != null) {
            if (prOpened < 0 || prMerged < 0) {
                throw new IllegalArgumentException("funnel counts cannot be negative");
            }
            if (prMerged > prOpened || prOpened > completed) {
                throw new IllegalArgumentException(
                        "funnel nesting broken: prMerged <= prOpened <= completed does not hold");
            }
        }
    }

    /** Task stages only, because the pull-request source is not covered for the observation window. */
    public static FunnelCounts withoutPrStages(
            long started, long completed, long failed, long cancelled, long inProgress) {
        return new FunnelCounts(started, completed, failed, cancelled, inProgress, null, null);
    }

    public boolean hasPrStages() {
        return prOpened != null;
    }
}
