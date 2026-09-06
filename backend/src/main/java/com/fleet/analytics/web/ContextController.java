package com.fleet.analytics.web;

import com.fleet.analytics.data.ContextQueries;
import com.fleet.analytics.security.AuthenticatedTenant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Accepts no organisation or filter parameter: scope comes only from the verified token. */
@RestController
@RequestMapping("/api/v1/analytics")
public class ContextController {

    private final ContextQueries queries;

    public ContextController(ContextQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/context")
    public ContextResponse context(@AuthenticationPrincipal AuthenticatedTenant tenant) {
        UUID organisationId = tenant.organisationId();
        return new ContextResponse(
                queries.organisationName(organisationId),
                tenant.role(),
                queries.teams(organisationId),
                queries.repositories(organisationId),
                queries.licensedSeats(organisationId),
                queries.coverage(organisationId));
    }
}
