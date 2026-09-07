package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fleet.analytics.security.PasswordEncoderFactory;
import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.IntegrationTestBase;
import com.fleet.analytics.support.OpenApiContract;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real responses validated against {@code contracts/openapi.yaml}: status, content type and the
 * complete resolved schema. An earlier version of this test compared required <em>field names</em>
 * only, which would accept a UUID served as a number or a role outside its enum.
 */
class OpenApiConformanceTest extends IntegrationTestBase {

    private static final UUID ORG = UUID.fromString("0d000000-0000-0000-0000-00000000000d");

    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void fixtures() throws SQLException {
        String hash = PasswordEncoderFactory.create().encode("pw");
        try (Connection c = connection()) {
            Fixtures.organisation(c, ORG, "Conformance Org");
            UUID team = UUID.fromString("1d000000-0000-0000-0000-000000000001");
            Fixtures.team(c, team, ORG, "c-t", "Team");
            Fixtures.repository(c, UUID.fromString("2d000000-0000-0000-0000-000000000001"),
                    ORG, "c-r", "repo");
            Fixtures.user(c, UUID.fromString("3d000000-0000-0000-0000-000000000001"), ORG, team,
                    "c-u", "conf.user", "Conf", hash, "VIEWER", false);
            Fixtures.seat(c, UUID.fromString("4d000000-0000-0000-0000-000000000001"), ORG, "c-s", null);
            Fixtures.publication(c, ORG,
                    OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                    OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC), "rev-c");
        }
    }

    private MvcResult login(String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"conf.user\",\"password\":\"" + password + "\"}")).andReturn();
    }

    private String token() throws Exception {
        return json.readTree(login("pw").getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void loginSuccessConformsToTheContract() throws Exception {
        MvcResult result = login("pw");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        OpenApiContract.assertValid(result);
    }

    @Test
    void loginFailureConformsToTheContract() throws Exception {
        MvcResult result = login("wrong");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        OpenApiContract.assertValid(result);
    }

    @Test
    void malformedLoginBodyConformsToTheContract() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content("{ not json")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        // The request is deliberately unparseable, so only the response is held to the contract.
        OpenApiContract.assertResponseValid(result);
    }

    @Test
    void contextSuccessConformsToTheContract() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/context")
                .header("Authorization", "Bearer " + token())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        OpenApiContract.assertValid(result);
    }

    @Test
    void unauthorisedContextConformsToTheContract() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/context")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        // Sending no credentials is the point of the test; the response must still conform.
        OpenApiContract.assertResponseValid(result);
    }

    /**
     * Proves the validator is actually inspecting schemas. Without this, a validator that silently
     * matched nothing would let every test above pass while checking nothing at all.
     */
    @Test
    void validatorRejectsAResponseThatBreaksTheSchema() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/context")
                .header("Authorization", "Bearer " + token())).andReturn();

        // role outside its enum, licensedSeats not an integer, a required field removed,
        // and a nested NamedEntity id that is not a UUID
        String broken = """
                {"organisationName":"Conformance Org","role":"SUPERUSER",
                 "teams":[{"id":"not-a-uuid","name":"Team"}],
                 "repositories":[{"name":"repo"}],
                 "licensedSeats":"one"}
                """;

        ValidationReport report = OpenApiContract.validateResponse(result, broken);
        assertThat(report.hasErrors()).as("the validator must reject this body").isTrue();

        // Each key proves a different kind of check ran: enum values, nested string formats,
        // required fields inside an array's items, scalar types, and top-level required fields.
        assertThat(OpenApiContract.keys(report)).contains(
                "validation.response.body.schema.enum",
                "validation.response.body.schema.format.uuid",
                "validation.response.body.schema.required",
                "validation.response.body.schema.type");
        assertThat(OpenApiContract.render(report))
                .contains("required property 'coverage' not found")
                .contains("required property 'id' not found");
    }

    /**
     * The request adapter previously dropped query parameters, so the validator checked every
     * request as though it carried none and could not have detected a value outside its declared
     * enum. This is the negative control proving they now arrive: if parameters were still being
     * dropped, the report would say nothing about `grouping` and this test would fail.
     *
     * <p>The endpoint now exists and rejects this grouping itself, so the response is a documented
     * 400. These assertions still look only at request-parameter findings, which is what the
     * adapter is responsible for.
     */
    @Test
    void forwardsQueryParametersSoTheValidatorChecksThem() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/dashboard")
                .param("grouping", "nonsense")
                .header("Authorization", "Bearer " + token())).andReturn();

        ValidationReport report = OpenApiContract.validate(result);
        assertThat(OpenApiContract.keys(report))
                .as("the validator must see and reject the grouping parameter")
                .contains("validation.request.parameter.schema.enum");
        // grouping is the only enumerated parameter, so its own enum values name it.
        assertThat(OpenApiContract.render(report)).contains("\"teams\", \"repositories\"");
    }

    @Test
    void documentedDashboardParametersRaiseNoParameterViolation() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/dashboard")
                .param("from", "2026-01-16")
                .param("to", "2026-01-31")
                .param("teamId", "1d000000-0000-0000-0000-000000000001")
                .param("grouping", "teams")
                .header("Authorization", "Bearer " + token())).andReturn();

        assertThat(OpenApiContract.keys(OpenApiContract.validate(result)))
                .as("a fully documented parameter set must produce no parameter violation")
                .noneMatch(key -> key.startsWith("validation.request.parameter"));
    }

    /**
     * A real dashboard response, end to end. The worked-example test below proves the schemas are
     * expressible; this proves the endpoint actually produces something they accept — including the
     * omitted-display convention and the flattened finding-link patch, both of which a hand-written
     * fixture could satisfy while the serializer did something else.
     */
    @Test
    void aRealDashboardResponseConformsToTheContract() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/dashboard")
                .param("from", "2026-01-16")
                .param("to", "2026-01-31")
                .header("Authorization", "Bearer " + token())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        OpenApiContract.assertValid(result);
    }

    /**
     * This organisation publishes an interval but has no source-day coverage rows at all, so every
     * metric is legitimately unavailable. The response must still conform — an all-unavailable body
     * exercises the omitted-display branch of every schema at once.
     */
    @Test
    void anAllUnavailableDashboardResponseStillConforms() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/dashboard")
                .header("Authorization", "Bearer " + token())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        OpenApiContract.assertValid(result);

        JsonNode kpis = json.readTree(result.getResponse().getContentAsString()).get("kpis");
        assertThat(kpis.get("mergedPrs").get("state").asString()).isEqualTo("missing_data");
        assertThat(kpis.get("mergedPrs").has("display")).isFalse();
        assertThat(kpis.get("mergedPrs").get("reasonCode").asString()).isEqualTo("source_not_covered");
    }

    /** A rejected selection's problem body is part of the contract too. */
    @Test
    void aRejectedDashboardSelectionConformsToTheContract() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/dashboard")
                .param("from", "2026-01-31")
                .param("to", "2026-01-16")
                .header("Authorization", "Bearer " + token())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        OpenApiContract.assertValid(result);
    }

    /**
     * The worked example from the metrics contract fixture, validated against the published
     * schemas independently of the endpoint --
     * every KPI state, both funnel branches, all sixteen trend days, row spend evidence and the
     * fully derived attention evaluation -- validated against the published schemas. Without it,
     * it is the hand-checkable target the implementation is measured against, and it stays useful
     * now that the endpoint exists because it pins the expected shape independently of the code
     * that produces it.
     */
    @Test
    void theWorkedFixtureExampleValidatesAgainstTheDashboardSchema() throws Exception {
        String body = new String(getClass().getResourceAsStream(
                "/contract/dashboard-example.json").readAllBytes(), StandardCharsets.UTF_8);

        ValidationReport report = OpenApiContract.validateDocumentedResponse(
                "/analytics/dashboard", Request.Method.GET, 200, body);

        assertThat(report.hasErrors())
                .as("dashboard example must satisfy the published contract:%n%s",
                        OpenApiContract.render(report))
                .isFalse();
    }

    private ObjectNode workedExample() throws Exception {
        return (ObjectNode) json.readTree(new String(getClass().getResourceAsStream(
                "/contract/dashboard-example.json").readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Mutates a copy of the worked example and returns the resulting validation report. */
    private ValidationReport reportForMutated(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode body = workedExample();
        mutation.accept(body);
        return OpenApiContract.validateDocumentedResponse(
                "/analytics/dashboard", Request.Method.GET, 200, json.writeValueAsString(body));
    }

    /**
     * Metric, Comparison and SeatsMetric all say a defined state carries a display and an
     * unavailable one carries reasonCode and reason. Described-only, that is a rule three
     * assemblers could each break differently; these three checks are the rule enforced. One
     * mutation per schema and per direction of the invariant -- deliberately not a full matrix.
     */
    @Test
    void aDefinedMetricStateWithoutADisplayIsRejected() throws Exception {
        ValidationReport report = reportForMutated(body ->
                ((ObjectNode) body.get("kpis").get("mergedPrs")).remove("display"));

        assertThat(report.hasErrors())
                .as("state ok must carry a display:%n%s", OpenApiContract.render(report))
                .isTrue();
        assertThat(OpenApiContract.render(report))
                .contains("required property 'display' not found");
    }

    @Test
    void anUnavailableComparisonWithoutAStatedReasonIsRejected() throws Exception {
        ValidationReport report = reportForMutated(body -> {
            ObjectNode comparison = (ObjectNode) body.get("kpis").get("terminalMergeRate").get("comparison");
            comparison.remove("reasonCode");
            comparison.remove("reason");
        });

        assertThat(report.hasErrors())
                .as("insufficient_sample must state its reason:%n%s", OpenApiContract.render(report))
                .isTrue();
        assertThat(OpenApiContract.render(report))
                .contains("required property 'reasonCode' not found")
                .contains("required property 'reason' not found");
    }

    @Test
    void anUnavailableSeatsMetricCarryingADisplayIsRejected() throws Exception {
        ValidationReport report = reportForMutated(body -> {
            ObjectNode seats = (ObjectNode) body.get("kpis").get("seats");
            seats.put("state", "missing_data");
            seats.put("reasonCode", "seat_source_missing");
            seats.put("reason", "The seat licence source was not published for this period.");
            // display is left in place: an unavailable state must not also present a value.
        });

        assertThat(report.hasErrors())
                .as("an unavailable state must not carry a display:%n%s", OpenApiContract.render(report))
                .isTrue();
        assertThat(OpenApiContract.keys(report))
                .contains("validation.response.body.schema.not");
    }

    /**
     * `00-research.md` 6.4 puts the agent/platform/policy failure-reason grouping inside a
     * failure-spike finding as P0 supporting evidence, and AC-06.2 requires the evidence to live in
     * the finding rather than behind its link. The worked fixture produces no findings, so this is
     * what proves the shape is actually expressible before slice C computes it.
     */
    @Test
    void aFailureSpikeFindingCanCarryGroupedFailureReasons() throws Exception {
        ValidationReport report = reportForMutated(body ->
                ((ObjectNode) body.get("attention")).set("findings",
                        json.createArrayNode().add(failureSpikeFinding())));

        assertThat(report.hasErrors())
                .as("grouped failure-reason evidence must validate:%n%s", OpenApiContract.render(report))
                .isFalse();
    }

    @Test
    void aPartialFailureReasonGroupingIsRejected() throws Exception {
        ValidationReport report = reportForMutated(body -> {
            JsonNode finding = failureSpikeFinding();
            // Only two of the three groups: a partition that silently drops policy failures would
            // understate exactly the denials the friction rule exists to surface.
            ((ObjectNode) finding.get("evidence")).set("failureReasons",
                    json.createObjectNode().put("agent", 9).put("platform", 3));
            ((ObjectNode) body.get("attention")).set("findings",
                    json.createArrayNode().add(finding));
        });

        assertThat(report.hasErrors())
                .as("all three reason groups are required:%n%s", OpenApiContract.render(report))
                .isTrue();
        assertThat(OpenApiContract.render(report))
                .contains("required property 'policy' not found");
    }

    private JsonNode failureSpikeFinding() {
        try {
            return json.readTree("""
                    {
                      "ruleType": "task_failure_spike",
                      "severity": "MEDIUM",
                      "scopeType": "repository",
                      "scopeId": "3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e001",
                      "scopeName": "repo-api",
                      "evidence": {
                        "currentRate": { "value": "34.0", "unit": "percent" },
                        "baselineRate": { "value": "22.0", "unit": "percent" },
                        "thresholdPercentagePoints": { "value": "8.0", "unit": "percentagePoints" },
                        "failedTasks": 17,
                        "failureReasons": { "agent": 9, "platform": 5, "policy": 3 }
                      },
                      "magnitude": { "value": "12.0", "unit": "percentagePoints" },
                      "evaluationPeriod": { "from": "2026-01-16", "to": "2026-01-31" },
                      "link": {
                        "section": "comparisonTable",
                        "grouping": "repositories",
                        "repositoryId": "3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e001",
                        "focusRowId": "3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e001",
                        "periodChanged": false
                      }
                    }
                    """);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failure-spike finding fixture is not valid JSON", e);
        }
    }
}
