package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.atlassian.oai.validator.report.ValidationReport;
import com.fleet.analytics.security.PasswordEncoderFactory;
import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.IntegrationTestBase;
import com.fleet.analytics.support.OpenApiContract;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

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
}
