package com.fleet.analytics.web.dashboard;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A finding's navigation patch: a <em>tri-state</em> change to the current selection.
 *
 * <ul>
 *   <li>a key that is <b>absent</b> means preserve whatever the user already has;
 *   <li>a key present with an explicit <b>null</b> means clear that filter;
 *   <li>a key with a value means set it.
 * </ul>
 *
 * <p>The patch is a map rather than a record precisely because of the middle case. A record with
 * nullable fields cannot express "absent" and "null" separately under one include policy: omit nulls
 * and a budget finding silently stops clearing the repository filter, keeping the user inside a
 * repository scope where budgets are not even evaluated; write nulls always and every non-budget
 * finding starts wiping the filters it was supposed to preserve. Both failures look like working
 * navigation.
 *
 * <p>{@code section} is a fixed field because it is always present; only the patch is conditional.
 * No field here ever carries a denied domain.
 */
public record FindingLinkResponse(String section, @JsonIgnore Map<String, Object> patch) {

    public FindingLinkResponse {
        // LinkedHashMap, not Map.copyOf: the latter rejects null values, which are the whole point.
        patch = new LinkedHashMap<>(patch);
    }

    /**
     * The patch is flattened into the link object, not nested under a key of its own — the contract
     * declares {@code teamId} and {@code repositoryId} directly on the link.
     *
     * <p>The component itself is {@code @JsonIgnore}d because Jackson would otherwise emit it
     * <em>as well as</em> the flattened keys, producing an undeclared {@code patch} property beside
     * them. That duplication is invisible to a client reading only the fields it expects, which is
     * why the schema forbids additional properties and the conformance test is what catches it.
     */
    @JsonAnyGetter
    public Map<String, Object> patchFields() {
        return patch;
    }
}
