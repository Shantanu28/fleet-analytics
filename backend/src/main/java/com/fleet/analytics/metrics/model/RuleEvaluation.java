package com.fleet.analytics.metrics.model;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of evaluating one rule over one scope.
 *
 * <p>The two cases are kept apart because they answer different questions, and conflating them is
 * how a panel ends up implying health it never established. A {@code Completed} evaluation reached a
 * definite verdict — which may legitimately be "nothing to report", and may equally be several
 * findings, as one scope can qualify on more than one denied domain. An {@code Unavailable}
 * evaluation reached no verdict at all and contributes a limit instead.
 *
 * <p>This is why {@code evaluationsCompleted} is counted from evaluations rather than from findings
 * or domains: zero findings from a complete look is a very different statement from zero findings
 * because nobody could look.
 */
public sealed interface RuleEvaluation {

    RuleType ruleType();

    RuleScope scope();

    record Completed(RuleType ruleType, RuleScope scope, List<FindingCandidate> candidates)
            implements RuleEvaluation {
        public Completed {
            Objects.requireNonNull(ruleType, "ruleType");
            Objects.requireNonNull(scope, "scope");
            candidates = List.copyOf(candidates);
        }

        public static Completed withNoFinding(RuleType ruleType, RuleScope scope) {
            return new Completed(ruleType, scope, List.of());
        }

        public static Completed with(
                RuleType ruleType, RuleScope scope, FindingCandidate candidate) {
            return new Completed(ruleType, scope, List.of(candidate));
        }
    }

    record Unavailable(EvaluationLimit limit) implements RuleEvaluation {
        public Unavailable {
            Objects.requireNonNull(limit, "limit");
        }

        @Override
        public RuleType ruleType() {
            return limit.ruleType();
        }

        @Override
        public RuleScope scope() {
            return limit.scope();
        }
    }
}
