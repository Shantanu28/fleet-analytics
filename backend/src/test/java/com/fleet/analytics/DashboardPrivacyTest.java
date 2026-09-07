package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fleet.analytics.security.PasswordEncoderFactory;
import com.fleet.analytics.support.ContractFixture;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Role-based redaction over a real request that actually produces a network-policy finding.
 *
 * <p>The assertions are on the <em>whole serialized body</em>. A denied domain that leaks into a
 * link, an explanation or a nested copy is the same failure as one in the evidence field, and only
 * a whole-body check catches all three (04 5.1: VIEWER responses omit domain-bearing strings
 * throughout the serialized body).
 */
class DashboardPrivacyTest extends IntegrationTestBase {

    private static final UUID ORG = UUID.fromString("dc000000-0000-0000-0000-0000000000c1");
    private static final String DOMAIN = "internal-registry.corp";

    private static ContractFixture fixture;

    private final ObjectMapper json = new ObjectMapper();

    private static OffsetDateTime utc(int day, int hour) {
        return OffsetDateTime.of(2026, 1, day, hour, 0, 0, 0, ZoneOffset.UTC);
    }

    /**
     * A qualifying domain: five distinct tasks across three distinct owners inside the period, with
     * one task denied repeatedly so the distinct counting is genuinely exercised.
     */
    @BeforeAll
    static void install() throws SQLException {
        String hash = PasswordEncoderFactory.create().encode("pw");
        try (Connection c = connection()) {
            fixture = ContractFixture.install(c, ORG);
            Fixtures.user(c, UUID.randomUUID(), ORG, fixture.id("T-PAY"), "priv-admin",
                    "priv.admin", "Priv Admin", hash, "ADMIN", false);
            Fixtures.user(c, UUID.randomUUID(), ORG, fixture.id("T-PAY"), "priv-viewer",
                    "priv.viewer", "Priv Viewer", hash, "VIEWER", false);

            for (int repeat = 0; repeat < 3; repeat++) {
                Fixtures.denial(c, UUID.randomUUID(), ORG, fixture.id("T5"), null,
                        "priv-t5-" + repeat, DOMAIN, utc(18, 9 + repeat));
            }
            Fixtures.denial(c, UUID.randomUUID(), ORG, fixture.id("T6"), null, "priv-t6",
                    DOMAIN, utc(22, 14));
            Fixtures.denial(c, UUID.randomUUID(), ORG, fixture.id("T7"), null, "priv-t7",
                    DOMAIN, utc(30, 17));
            Fixtures.denial(c, UUID.randomUUID(), ORG, fixture.id("T8"), null, "priv-t8",
                    DOMAIN, utc(31, 23));

            UUID fifth = UUID.randomUUID();
            Fixtures.task(c, fifth, ORG, fixture.id("T-PAY"), fixture.id("R-WEB"),
                    fixture.id("u6"), "priv-task", "research", utc(19, 8), "completed", utc(19, 9));
            Fixtures.run(c, UUID.randomUUID(), ORG, fifth, "priv-run", 1,
                    utc(19, 8), utc(19, 9), "completed", null);
            Fixtures.denial(c, UUID.randomUUID(), ORG, fifth, null, "priv-fifth",
                    "Internal-Registry.Corp.", utc(19, 8));
        }
    }

    private String token(String username) throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}")).andReturn();
        return json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private MvcResult dashboard(String username) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/dashboard")
                .param("from", ContractFixture.PERIOD_FROM.toString())
                .param("to", ContractFixture.PERIOD_TO.toString())
                .header("Authorization", "Bearer " + token(username))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        OpenApiContract.assertValid(result);
        return result;
    }

    @Test
    void theFixtureProducesAFrictionFindingForBothRoles() throws Exception {
        for (String user : new String[] {"priv.admin", "priv.viewer"}) {
            JsonNode findings = json.readTree(dashboard(user).getResponse().getContentAsString())
                    .get("attention").get("findings");

            assertThat(findings).isNotEmpty();
            assertThat(findings.get(0).get("ruleType").asString())
                    .isEqualTo("network_policy_friction");
            assertThat(findings.get(0).get("evidence").get("distinctTasks").asLong()).isEqualTo(5);
            assertThat(findings.get(0).get("evidence").get("distinctUsers").asLong()).isEqualTo(3);
        }
    }

    @Test
    void anAdminReceivesTheDeniedDomain() throws Exception {
        String body = dashboard("priv.admin").getResponse().getContentAsString();

        assertThat(body).contains(DOMAIN);
        assertThat(json.readTree(body).get("attention").get("findings").get(0)
                .get("evidence").get("domain").asString()).isEqualTo(DOMAIN);
    }

    /** The whole body, every field. A domain anywhere is the failure, not a domain in one place. */
    @Test
    void aViewerBodyContainsNoTraceOfTheDomainAnywhere() throws Exception {
        String body = dashboard("priv.viewer").getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain(DOMAIN)
                .doesNotContain("Internal-Registry.Corp.")
                .doesNotContain("internal-registry")
                .doesNotContainIgnoringCase("registry");

        JsonNode finding = json.readTree(body).get("attention").get("findings").get(0);
        assertThat(finding.get("evidence").has("domain")).isFalse();
        // Nor in the navigation patch, which must carry no domain-bearing field at all.
        assertThat(finding.get("link").toString()).doesNotContainIgnoringCase("registry");
    }

    /** AC-06.9: identical identifiers, counts, severity and ordering; only the domain differs. */
    @Test
    void identifiersCountsSeverityAndOrderMatchAcrossRoles() throws Exception {
        JsonNode admin = json.readTree(dashboard("priv.admin").getResponse().getContentAsString())
                .get("attention");
        JsonNode viewer = json.readTree(dashboard("priv.viewer").getResponse().getContentAsString())
                .get("attention");

        assertThat(viewer.get("evaluationsCompleted").asInt())
                .isEqualTo(admin.get("evaluationsCompleted").asInt());
        assertThat(viewer.get("limits").size()).isEqualTo(admin.get("limits").size());
        assertThat(viewer.get("findings").size()).isEqualTo(admin.get("findings").size());

        for (int i = 0; i < admin.get("findings").size(); i++) {
            JsonNode adminFinding = admin.get("findings").get(i);
            JsonNode viewerFinding = viewer.get("findings").get(i);
            assertThat(viewerFinding.get("id").asString()).isEqualTo(adminFinding.get("id").asString());
            assertThat(viewerFinding.get("severity").asString())
                    .isEqualTo(adminFinding.get("severity").asString());
            assertThat(viewerFinding.get("magnitude").toString())
                    .isEqualTo(adminFinding.get("magnitude").toString());
            assertThat(viewerFinding.get("scopeId").toString())
                    .isEqualTo(adminFinding.get("scopeId").toString());
            assertThat(viewerFinding.get("link").toString())
                    .isEqualTo(adminFinding.get("link").toString());
        }
    }

    /**
     * Presenting for a VIEWER must not damage shared internal state. An ADMIN request that follows
     * one must still see the domain — if it did not, redaction would be mutating results rather
     * than building a separate safe shape.
     */
    @Test
    void aViewerRequestDoesNotStripLaterAdminRequests() throws Exception {
        dashboard("priv.viewer");
        dashboard("priv.viewer");

        assertThat(dashboard("priv.admin").getResponse().getContentAsString()).contains(DOMAIN);
    }

    /** The public identifier is opaque: it must not encode the domain it was derived from. */
    @Test
    void thePublicFindingIdentifierRevealsNoDomain() throws Exception {
        String id = json.readTree(dashboard("priv.admin").getResponse().getContentAsString())
                .get("attention").get("findings").get(0).get("id").asString();

        assertThat(id).doesNotContainIgnoringCase("registry").doesNotContainIgnoringCase("corp");
        assertThat(id).isNotBlank();
    }
}
