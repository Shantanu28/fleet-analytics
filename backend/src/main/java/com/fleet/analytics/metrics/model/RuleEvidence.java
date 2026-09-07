package com.fleet.analytics.metrics.model;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Typed, rule-specific evidence carried inside a finding (AC-06.2).
 *
 * <p>Sealed rather than a {@code Map<String, Object>}: each rule's evidence has a fixed shape the
 * presenter must map exhaustively, and the compiler is what guarantees a new rule cannot ship with
 * its evidence silently dropped. It also keeps the denied domain confined to one variant, so
 * redaction has exactly one place to look.
 */
public sealed interface RuleEvidence {

    /** Contract 6.1. Exact integers; the forecast and overrun are rounded only for display. */
    record Budget(BudgetSample sample) implements RuleEvidence {
        public Budget {
            Objects.requireNonNull(sample, "sample");
        }
    }

    /**
     * Contract 6.2, with the research 6.4 grouping the finding's evidence line requires.
     *
     * <p>The published {@code FailureReasonGroups} schema states that agent, platform and policy
     * partition the window's failed tasks, so the three must sum to {@code current.failed()}. That
     * is checked here, where the evidence is built.
     *
     * <p>Reaching this check with a shortfall means the coverage metadata declared run data complete
     * while a failed task had no failed run to attribute — contradictory data that the demo's
     * one-run-per-task lifecycle makes unreachable. There is no honest way to publish it: inventing
     * a group would fabricate a cause, and emitting groups that do not add up would break the
     * contract the client relies on. So it fails loudly, exactly as a broken funnel identity does,
     * and surfaces as a sanitised server error rather than a plausible-looking wrong chart.
     */
    record FailureSpike(
            TaskCounts current, TaskCounts baseline, FailureReasonGroups reasons, int thresholdPoints)
            implements RuleEvidence {
        public FailureSpike {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(reasons, "reasons");
            if (!reasons.sumsTo(current.failed())) {
                throw new IllegalArgumentException(
                        "failure-reason groups must partition the window's failed tasks: "
                                + reasons.total() + " attributed of " + current.failed() + " failed");
            }
        }
    }

    /** Contract 6.3. */
    record MergeDecline(PrCounts current, PrCounts baseline, int thresholdPoints)
            implements RuleEvidence {
        public MergeDecline {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(baseline, "baseline");
        }
    }

    /**
     * Contract 6.4. The only variant carrying a denied domain — restricted to admins by research 9,
     * so the presenter drops it for a VIEWER while every count stays identical.
     */
    record Friction(DomainCounts counts, int taskThreshold, int userThreshold)
            implements RuleEvidence {
        public Friction {
            Objects.requireNonNull(counts, "counts");
        }
    }

    /** The exact failed-task count a failure spike's reason groups must partition. */
    static BigInteger exact(long value) {
        return BigInteger.valueOf(value);
    }
}
