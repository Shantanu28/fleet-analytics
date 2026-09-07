package com.fleet.analytics.metrics.rules;

import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.DomainCounts;
import com.fleet.analytics.metrics.model.ExactMagnitude;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.FindingIdentity;
import com.fleet.analytics.metrics.model.RuleEvaluation;
import com.fleet.analytics.metrics.model.RuleEvidence;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.RuleType;
import com.fleet.analytics.metrics.model.Severity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Network-policy friction (contract 6.4): is the same denied domain blocking enough distinct tasks,
 * across enough distinct people, to be worth reporting?
 *
 * <p>Both thresholds are required. Five tasks from two people is one person's misconfiguration; five
 * people hitting one domain is a policy problem. Requiring both is what keeps the rule about policy
 * rather than about noise, and counting <em>distinct</em> tasks and owners is what stops one
 * retrying sandbox from manufacturing either number.
 *
 * <p>One scope can produce several findings — distinct normalised domains are distinct problems and
 * are never collapsed. That is why this method returns a single evaluation carrying a list: the
 * scope was looked at exactly once, whatever number of domains qualified.
 */
@Component
public class NetworkPolicyFrictionRule {

    /** Contract 6.4 and research 8: at least 5 distinct tasks and at least 3 distinct owners. */
    static final int TASK_THRESHOLD = 5;
    static final int USER_THRESHOLD = 3;

    /**
     * @param domains every normalised domain observed in this scope and window; an empty list is a
     *     completed evaluation with no finding, not a limit.
     */
    public RuleEvaluation evaluate(
            RuleScope scope, List<DomainCounts> domains, LocalDate from, LocalDate to) {
        List<FindingCandidate> qualifying = new ArrayList<>();
        for (DomainCounts counts : domains) {
            if (counts.distinctTasks() >= TASK_THRESHOLD
                    && counts.distinctUsers() >= USER_THRESHOLD) {
                qualifying.add(candidate(scope, counts, from, to));
            }
        }
        // Deterministic within the scope; the ranker re-sorts globally but must not receive an
        // order that varies between identical requests.
        qualifying.sort(Comparator.comparing(candidate -> candidate.identity().orderingKey()));
        return new RuleEvaluation.Completed(RuleType.NETWORK_POLICY_FRICTION, scope, qualifying);
    }

    private FindingCandidate candidate(
            RuleScope scope, DomainCounts counts, LocalDate from, LocalDate to) {
        return new FindingCandidate(
                FindingIdentity.ofDomain(RuleType.NETWORK_POLICY_FRICTION, scope,
                        TaskFailureSpikeRule.periodKey(from, to), counts.normalisedDomain()),
                scope, Severity.MEDIUM,
                // Contract 6.4: magnitude is the distinct affected task count.
                ExactMagnitude.ofWhole(counts.distinctTasks()),
                DisplayValue.count(counts.distinctTasks()),
                new RuleEvidence.Friction(counts, TASK_THRESHOLD, USER_THRESHOLD),
                from, to);
    }
}
