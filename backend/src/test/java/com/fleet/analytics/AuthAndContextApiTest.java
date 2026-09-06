package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fleet.analytics.security.PasswordEncoderFactory;
import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Login and the tenant-scoped context endpoint, against two organisations whose teams, repositories
 * and seat counts are entirely distinct — so "Beacon never sees Acme" is an assertion about tenant
 * scoping rather than about two names on the same underlying rows.
 */
class AuthAndContextApiTest extends IntegrationTestBase {

    private static final UUID ORG_ACME = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID ORG_BEACON = UUID.fromString("0b000000-0000-0000-0000-00000000000b");

    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void fixtures() throws SQLException {
        String acmeHash = PasswordEncoderFactory.create().encode("secret-a");
        String beaconHash = PasswordEncoderFactory.create().encode("secret-b");
        OffsetDateTime from = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime through = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        try (Connection c = connection()) {
            Fixtures.organisation(c, ORG_ACME, "Acme Engineering");
            Fixtures.organisation(c, ORG_BEACON, "Beacon Labs");

            UUID acmeTeam = UUID.fromString("1a000000-0000-0000-0000-000000000001");
            UUID beaconTeam = UUID.fromString("1b000000-0000-0000-0000-000000000001");
            Fixtures.team(c, acmeTeam, ORG_ACME, "acme-t1", "Acme Platform");
            Fixtures.team(c, beaconTeam, ORG_BEACON, "beacon-t1", "Beacon Core");
            Fixtures.repository(c, UUID.fromString("2a000000-0000-0000-0000-000000000001"),
                    ORG_ACME, "acme-r1", "acme-api");
            Fixtures.repository(c, UUID.fromString("2b000000-0000-0000-0000-000000000001"),
                    ORG_BEACON, "beacon-r1", "beacon-web");

            UUID acmeUser = UUID.fromString("3a000000-0000-0000-0000-000000000001");
            UUID beaconUser = UUID.fromString("3b000000-0000-0000-0000-000000000001");
            Fixtures.user(c, acmeUser, ORG_ACME, acmeTeam, "acme-u1",
                    "acme.admin", "Acme Admin", acmeHash, "ADMIN", false);
            Fixtures.user(c, beaconUser, ORG_BEACON, beaconTeam, "beacon-u1",
                    "beacon.viewer", "Beacon Viewer", beaconHash, "VIEWER", false);

            Fixtures.seat(c, UUID.fromString("4a000000-0000-0000-0000-000000000001"),
                    ORG_ACME, "acme-s1", acmeUser);
            Fixtures.seat(c, UUID.fromString("4a000000-0000-0000-0000-000000000002"),
                    ORG_ACME, "acme-s2", null);
            Fixtures.seat(c, UUID.fromString("4b000000-0000-0000-0000-000000000001"),
                    ORG_BEACON, "beacon-s1", beaconUser);

            Fixtures.publication(c, ORG_ACME, from, through, "rev-acme");
            Fixtures.publication(c, ORG_BEACON,
                    OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC), through, "rev-beacon");
        }
    }

    private MockHttpServletResponse login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody(username, password))))
                .andReturn().getResponse();
    }

    private String token(String username, String password) throws Exception {
        MockHttpServletResponse response = login(username, password);
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString()).get("accessToken").asText();
    }

    private record LoginBody(String username, String password) {}

    @Test
    void loginReturnsTheIdentityTheClientNeedsToScopeItsCaches() throws Exception {
        JsonNode body = json.readTree(login("acme.admin", "secret-a").getContentAsString());

        assertThat(body.get("userId").asText()).isEqualTo("3a000000-0000-0000-0000-000000000001");
        assertThat(body.get("organisationId").asText()).isEqualTo(ORG_ACME.toString());
        assertThat(body.get("displayName").asText()).isEqualTo("Acme Admin");
        assertThat(body.get("role").asText()).isEqualTo("ADMIN");
        assertThat(body.get("expiresInSeconds").asInt()).isEqualTo(900);
    }

    @Test
    void contextReturnsTheCallersOwnOrganisationOnly() throws Exception {
        var response = mvc.perform(get("/api/v1/analytics/context")
                .header("Authorization", "Bearer " + token("acme.admin", "secret-a"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);

        String raw = response.getContentAsString();
        JsonNode body = json.readTree(raw);
        assertThat(body.get("organisationName").asText()).isEqualTo("Acme Engineering");
        assertThat(body.get("role").asText()).isEqualTo("ADMIN");
        assertThat(body.get("licensedSeats").asInt()).isEqualTo(2);
        assertThat(body.get("teams").toString()).contains("Acme Platform");
        assertThat(body.get("repositories").toString()).contains("acme-api");
        assertThat(body.get("coverage").get("revision").asText()).isEqualTo("rev-acme");
        assertThat(raw).doesNotContain("Beacon").doesNotContain("beacon");
    }

    @Test
    void eachTenantSeesOnlyItsOwnEntities() throws Exception {
        String raw = mvc.perform(get("/api/v1/analytics/context")
                        .header("Authorization", "Bearer " + token("beacon.viewer", "secret-b")))
                .andReturn().getResponse().getContentAsString();

        JsonNode body = json.readTree(raw);
        assertThat(body.get("organisationName").asText()).isEqualTo("Beacon Labs");
        assertThat(body.get("role").asText()).isEqualTo("VIEWER");
        assertThat(body.get("licensedSeats").asInt()).isEqualTo(1);
        assertThat(body.get("teams").toString()).contains("Beacon Core");
        assertThat(body.get("repositories").toString()).contains("beacon-web");
        assertThat(raw).doesNotContain("Acme").doesNotContain("acme");
    }

    @Test
    void usernameIsNormalisedAtLogin() throws Exception {
        assertThat(login("  ACME.Admin  ", "secret-a").getStatus()).isEqualTo(200);
    }

    @Test
    void invalidCredentialsAreIndistinguishableAndSanitised() throws Exception {
        var wrongPassword = login("acme.admin", "nope");
        var unknownUser = login("nobody-at-all", "nope");

        assertThat(wrongPassword.getStatus()).isEqualTo(401);
        assertThat(unknownUser.getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getContentAsString()).isEqualTo(unknownUser.getContentAsString());
        assertThat(wrongPassword.getContentType()).contains("application/problem+json");
        assertThat(wrongPassword.getContentAsString())
                .doesNotContain("acme.admin").doesNotContain("argon2").doesNotContain("Exception");
        assertThat(json.readTree(wrongPassword.getContentAsString()).get("type").asText())
                .isEqualTo("urn:fleet:problem:invalid-credentials");
    }

    /** A broken body is a client error, not a credential result reported against real accounts. */
    @Test
    void malformedLoginBodyIsASanitisedBadRequest() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content("{ not json")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(json.readTree(response.getContentAsString()).get("type").asText())
                .isEqualTo("urn:fleet:problem:invalid-request");
        assertThat(response.getContentAsString())
                .doesNotContain("Exception").doesNotContain("com.fleet").doesNotContain("Jackson");
    }

    @Test
    void contextAcceptsNoOrganisationOrFilterParameter() throws Exception {
        String beaconToken = token("beacon.viewer", "secret-b");

        // Even asked directly for Acme, the response is the token's own tenant.
        String raw = mvc.perform(get("/api/v1/analytics/context")
                        .param("organisationId", ORG_ACME.toString())
                        .param("teamId", "1a000000-0000-0000-0000-000000000001")
                        .header("Authorization", "Bearer " + beaconToken))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(raw).get("organisationName").asText()).isEqualTo("Beacon Labs");
        assertThat(raw).doesNotContain("Acme").doesNotContain("acme");
    }
}
