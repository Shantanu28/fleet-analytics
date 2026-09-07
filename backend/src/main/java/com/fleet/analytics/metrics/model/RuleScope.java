package com.fleet.analytics.metrics.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One scope a rule is evaluated over: the organisation itself, a team, or a repository.
 *
 * <p>{@code scopeId} is null only for the organisation, which has no row of its own to point at.
 * The display name travels with the identity so a limit or finding can name its scope without a
 * second lookup — but ranking never uses the name, because a translated or renamed scope must not
 * reorder the panel (contract 6.5).
 */
public record RuleScope(ScopeType scopeType, UUID scopeId, String scopeName) {

    public RuleScope {
        Objects.requireNonNull(scopeType, "scopeType");
        Objects.requireNonNull(scopeName, "scopeName");
        if ((scopeType == ScopeType.ORGANISATION) != (scopeId == null)) {
            throw new IllegalArgumentException(
                    "the organisation scope has no id, and every other scope must have one");
        }
    }

    public static RuleScope organisation(String name) {
        return new RuleScope(ScopeType.ORGANISATION, null, name);
    }

    public static RuleScope team(UUID id, String name) {
        return new RuleScope(ScopeType.TEAM, id, name);
    }

    public static RuleScope repository(UUID id, String name) {
        return new RuleScope(ScopeType.REPOSITORY, id, name);
    }

    /** Stable, id-based ordering key. Never the display name. */
    public String orderingKey() {
        return scopeType.ordinal() + ":" + (scopeId == null ? "" : scopeId);
    }
}
