package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fleet.analytics.security.JwtIssuer;
import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Absent required metadata is a broken dataset. It must be reported as a server fault, never as a
 * success carrying invented coverage or zeros — a contract-invalid 200 would be read by the client
 * as "this organisation has no data", which is a different and false statement (04 Appendix A.5).
 */
class ContextMetadataFailureTest extends IntegrationTestBase {

    /** Exists, has a name, but publishes no reporting interval. */
    private static final UUID ORG_WITHOUT_PUBLICATION =
            UUID.fromString("0f000000-0000-0000-0000-00000000000f");

    /** A token for an organisation that is not in the database at all. */
    private static final UUID ORG_ABSENT = UUID.fromString("0f000000-0000-0000-0000-0000000000ff");

    @Autowired private JwtIssuer issuer;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void fixtures() throws SQLException {
        try (Connection c = connection()) {
            Fixtures.organisation(c, ORG_WITHOUT_PUBLICATION, "Unpublished Org");
        }
    }

    private MockHttpServletResponse context(UUID organisationId) throws Exception {
        return mvc.perform(get("/api/v1/analytics/context").header("Authorization",
                        "Bearer " + issuer.issue(UUID.randomUUID(), organisationId, "ADMIN")))
                .andReturn().getResponse();
    }

    private void assertSanitisedServerFault(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(json.readTree(response.getContentAsString()).get("type").asText())
                .isEqualTo("urn:fleet:problem:context-unavailable");

        String body = response.getContentAsString();
        assertThat(body).doesNotContain("Exception").doesNotContain("com.fleet")
                .doesNotContain("select").doesNotContain("dataset_publication");
        // no invented interval, and no substituted empty success
        assertThat(body).doesNotContain("coverage").doesNotContain("dataAvailableFrom")
                .doesNotContain("licensedSeats");
    }

    @Test
    void missingPublicationMetadataIsAServerFaultNotAnEmptySuccess() throws Exception {
        assertSanitisedServerFault(context(ORG_WITHOUT_PUBLICATION));
    }

    @Test
    void tokenForAnAbsentOrganisationIsAServerFaultNotAnInventedContext() throws Exception {
        MockHttpServletResponse response = context(ORG_ABSENT);
        assertSanitisedServerFault(response);
        assertThat(response.getContentAsString()).doesNotContain(ORG_ABSENT.toString());
    }
}
