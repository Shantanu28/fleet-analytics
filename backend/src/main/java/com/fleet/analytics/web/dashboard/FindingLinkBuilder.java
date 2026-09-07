package com.fleet.analytics.web.dashboard;

import com.fleet.analytics.metrics.model.DashboardSelection;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.RuleType;
import com.fleet.analytics.metrics.model.ScopeType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds each finding's navigation patch (AC-06.4 to AC-06.7, AC-06.12).
 *
 * <p>Two rules shape every patch. A <b>budget</b> link moves the user to its own evaluated month and
 * clears the repository restriction, because budgets have no repository allocation and following the
 * link into one would land on a view where the finding cannot be reproduced. Every <b>non-budget</b>
 * link preserves the date range and the other dimension's filter, so the population is narrowed to
 * the one that produced the finding and never broadened past it.
 *
 * <p>Absent means preserve and explicit null means clear, so this class is deliberate about which it
 * emits. Nothing here ever carries a denied domain — a friction link points at the attention section
 * and its scope, not at the domain that triggered it, because P0 has no denied-domain view to open
 * (AC-06.10).
 */
@Component
public class FindingLinkBuilder {

    private static final String SPEND_TREND = "spendTrend";
    private static final String COMPARISON_TABLE = "comparisonTable";
    private static final String ATTENTION = "attention";

    public FindingLinkResponse build(FindingCandidate finding, DashboardSelection selection) {
        return switch (finding.ruleType()) {
            case BUDGET_RISK -> budgetLink(finding);
            case TASK_FAILURE_SPIKE, MERGE_RATE_DECLINE -> tableLink(finding);
            case NETWORK_POLICY_FRICTION -> frictionLink(finding, selection);
        };
    }

    /**
     * AC-06.4: the finding's own month-to-date, its team (or none for the organisation), and the
     * repository restriction explicitly cleared.
     */
    private FindingLinkResponse budgetLink(FindingCandidate finding) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("from", finding.evaluationFrom().toString());
        patch.put("to", finding.evaluationTo().toString());
        // Explicit nulls: both filters are cleared, not preserved.
        patch.put("teamId", finding.scope().scopeType() == ScopeType.TEAM
                ? finding.scope().scopeId()
                : null);
        patch.put("repositoryId", null);
        // The reporting period differs from whatever the user had selected, and must say so.
        patch.put("periodChanged", true);
        return new FindingLinkResponse(SPEND_TREND, patch);
    }

    /**
     * AC-06.6 and AC-06.12: apply the finding's own scope, switch the table to the matching grouping
     * and focus its row. Dates and the other dimension's filter are absent from the patch, which is
     * how they are preserved.
     */
    private FindingLinkResponse tableLink(FindingCandidate finding) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (finding.scope().scopeType() == ScopeType.TEAM) {
            patch.put("teamId", finding.scope().scopeId());
            patch.put("grouping", Grouping.TEAMS.wireName());
        } else {
            patch.put("repositoryId", finding.scope().scopeId());
            patch.put("grouping", Grouping.REPOSITORIES.wireName());
        }
        patch.put("focusRowId", finding.scope().scopeId());
        patch.put("periodChanged", false);
        return new FindingLinkResponse(COMPARISON_TABLE, patch);
    }

    /**
     * AC-06.7: land on the attention section under the same dates and filters, with this finding's
     * own scope applied. No focused table row is set — the destination is the panel, which
     * recomputes and reranks for the narrowed scope. The originating finding may or may not survive
     * that, and both outcomes are acceptable, so the link promises nothing about it.
     */
    private FindingLinkResponse frictionLink(
            FindingCandidate finding, DashboardSelection selection) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (finding.scope().scopeType() == ScopeType.TEAM) {
            patch.put("teamId", finding.scope().scopeId());
        } else if (finding.scope().scopeType() == ScopeType.REPOSITORY) {
            patch.put("repositoryId", finding.scope().scopeId());
        }
        patch.put("grouping", selection.grouping().wireName());
        patch.put("periodChanged", false);
        return new FindingLinkResponse(ATTENTION, patch);
    }

    /** Exposed so the presenter can assert the section it expects for a rule. */
    static String sectionFor(RuleType ruleType) {
        return switch (ruleType) {
            case BUDGET_RISK -> SPEND_TREND;
            case TASK_FAILURE_SPIKE, MERGE_RATE_DECLINE -> COMPARISON_TABLE;
            case NETWORK_POLICY_FRICTION -> ATTENTION;
        };
    }
}
