package com.fleet.analytics.metrics;

import com.fleet.analytics.metrics.model.AttentionResult;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.FindingIdentity;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deduplication and contract 6.5's ranking, then the top three.
 *
 * <p>The order is <b>total</b>, which is the property that matters: the same inputs must always
 * produce the same three findings in the same order, because AC-06.1 requires the panel to be
 * identical across reloads of one URL. Every key is exact and internal — severity, rule order,
 * cross-multiplied magnitude, then stable identifiers, and finally the full identity string. Step
 * five is what makes ties impossible even when a team and a repository share an id.
 *
 * <p>Nothing here reads a display string, a public HMAC id or a scope name. Sorting by a rounded
 * magnitude would let two different overruns tie; sorting by a name would let a rename or a
 * translation reorder the panel; sorting by the public id would make the order depend on the
 * configured secret.
 */
@Component
public class FindingRanker {

    private static final Comparator<FindingCandidate> CONTRACT_ORDER =
            // 1. HIGH before MEDIUM -- Severity is declared highest first.
            Comparator.comparing(FindingCandidate::severity)
                    // 2. Budget, failure spike, merge decline, friction -- RuleType's own order.
                    .thenComparingInt(candidate -> candidate.ruleType().ordinal())
                    // 3. Magnitude descending, compared by cross-multiplication.
                    .thenComparing(FindingCandidate::magnitude, Comparator.reverseOrder())
                    // 4. Stable identifiers: scope type, scope id, then domain where applicable.
                    //
                    // Ascending by the canonical scope-type string, not by enum declaration order.
                    // Contract 6.5 says "scope_type ascending" without defining an order, and its
                    // step 5 is the identity *string* ascending -- so a string comparison is the
                    // only reading under which the two keys agree. Ordinal order (team before
                    // repository) has them pulling in opposite directions, which is a latent
                    // inconsistency rather than a choice the contract makes.
                    .thenComparing(candidate -> candidate.scope().scopeType().wireName())
                    .thenComparing(FindingRanker::scopeIdKey)
                    .thenComparing(FindingRanker::domainKey)
                    // 5. The full internal identity, so the order is total.
                    .thenComparing(candidate -> candidate.identity().orderingKey());

    /**
     * @return at most {@link AttentionResult#MAX_DISPLAYED_FINDINGS}, ranked. Findings beyond the cap
     *     are dropped from display only — they still came from completed evaluations, and the
     *     completed count must not be reduced to match what fits on screen.
     */
    public List<FindingCandidate> rank(Collection<FindingCandidate> candidates) {
        return deduplicate(candidates).stream()
                .sorted(CONTRACT_ORDER)
                .limit(AttentionResult.MAX_DISPLAYED_FINDINGS)
                .toList();
    }

    /** The full ranked order, uncapped — used to reason about what the cap excluded. */
    public List<FindingCandidate> rankAll(Collection<FindingCandidate> candidates) {
        return deduplicate(candidates).stream().sorted(CONTRACT_ORDER).toList();
    }

    /**
     * Only identical identities collapse. Nothing is merged across rules, scopes or domains — two
     * denied domains in one team are two problems, and a repository finding is never absorbed by its
     * team's (contract 6.2, 6.5).
     */
    private List<FindingCandidate> deduplicate(Collection<FindingCandidate> candidates) {
        Map<FindingIdentity, FindingCandidate> unique = new LinkedHashMap<>();
        candidates.forEach(candidate -> unique.putIfAbsent(candidate.identity(), candidate));
        return List.copyOf(unique.values());
    }

    private static String scopeIdKey(FindingCandidate candidate) {
        return candidate.scope().scopeId() == null ? "" : candidate.scope().scopeId().toString();
    }

    private static String domainKey(FindingCandidate candidate) {
        String domain = candidate.identity().normalisedDomain();
        return domain == null ? "" : domain;
    }
}
