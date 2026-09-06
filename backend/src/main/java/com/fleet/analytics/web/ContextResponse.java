package com.fleet.analytics.web;

import com.fleet.analytics.data.Coverage;
import com.fleet.analytics.data.NamedEntity;
import java.util.List;
import java.util.Objects;

/** Filter options and identity for the caller's own organisation. */
public record ContextResponse(
        String organisationName,
        String role,
        List<NamedEntity> teams,
        List<NamedEntity> repositories,
        int licensedSeats,
        Coverage coverage) {

    public ContextResponse {
        Objects.requireNonNull(organisationName, "organisationName");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(coverage, "coverage");
        teams = List.copyOf(teams);
        repositories = List.copyOf(repositories);
    }
}
