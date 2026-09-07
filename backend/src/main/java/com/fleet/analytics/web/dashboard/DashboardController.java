package com.fleet.analytics.web.dashboard;

import com.fleet.analytics.security.AuthenticatedTenant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard endpoint. Thin by design: it collects raw parameters and hands them to the service,
 * which owns validation, the transaction and every calculation.
 *
 * <p>Parameters arrive as a raw map rather than as typed method arguments, because binding to
 * {@code LocalDate} or {@code UUID} would let the framework decide what malformed input means. It
 * would throw its own type-mismatch exception, which maps to a generic 400 that cannot distinguish a
 * reversed range from an unparseable date — and would report a malformed identifier differently from
 * a foreign one, turning the filter parameter into an oracle. Raw strings keep those decisions in
 * {@link DashboardRequestParser}, where the contract's problem types are defined.
 *
 * <p>The map also preserves absent-versus-empty: {@code ?from=} is a supplied empty value and
 * {@code from} omitted entirely is an omission, and the two mean different things.
 *
 * <p>Organisation and role come from the verified token. No organisation parameter exists and none
 * is accepted.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class DashboardController {

    private static final String[] ACCEPTED_PARAMETERS =
            {"from", "to", "teamId", "repositoryId", "grouping"};

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(
            @AuthenticationPrincipal AuthenticatedTenant tenant,
            @RequestParam Map<String, String> parameters) {
        return service.dashboard(tenant, accepted(parameters));
    }

    /**
     * Only the documented parameters are forwarded. An unrecognised one is ignored rather than
     * rejected — it cannot change the answer, and failing on it would break clients that append
     * tracking parameters to a bookmarked URL.
     */
    private Map<String, String> accepted(Map<String, String> parameters) {
        Map<String, String> filtered = new LinkedHashMap<>();
        for (String name : ACCEPTED_PARAMETERS) {
            if (parameters.containsKey(name)) {
                filtered.put(name, parameters.get(name));
            }
        }
        return filtered;
    }
}
