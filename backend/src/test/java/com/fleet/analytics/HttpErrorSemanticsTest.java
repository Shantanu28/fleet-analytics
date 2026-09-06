package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fleet.analytics.security.JwtIssuer;
import com.fleet.analytics.support.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Framework-level HTTP failures must keep the status and headers the protocol requires, while the
 * body stays sanitised. Collapsing them into a generic 500 misreports a client mistake as a server
 * fault and drops headers a client needs — {@code Allow} tells it which method to use.
 */
@Import(HttpErrorSemanticsTest.FailingEndpoint.class)
class HttpErrorSemanticsTest extends IntegrationTestBase {

    @Autowired private JwtIssuer issuer;

    private final ObjectMapper json = new ObjectMapper();

    /** Test-only: a genuine unexpected application fault, kept out of the production packages. */
    @TestConfiguration
    @RestController
    static class FailingEndpoint {
        @GetMapping("/api/v1/test-only/boom")
        String boom() {
            throw new IllegalStateException("internal detail that must not be published");
        }
    }

    private void assertSanitisedProblem(MockHttpServletResponse response, int status, String type)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(json.readTree(response.getContentAsString()).get("type").asText()).isEqualTo(type);
        assertThat(response.getContentAsString())
                .doesNotContain("Exception").doesNotContain("com.fleet")
                .doesNotContain("internal detail");
    }

    @Test
    void wrongMethodIsMethodNotAllowedAndAdvertisesTheSupportedOnes() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/auth/login")).andReturn().getResponse();

        assertSanitisedProblem(response, 405, "urn:fleet:problem:method-not-allowed");
        assertThat(response.getHeader("Allow")).as("Allow header").isNotNull().contains("POST");
    }

    @Test
    void wrongContentTypeIsUnsupportedMediaType() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.TEXT_PLAIN).content("username=a"))
                .andReturn().getResponse();

        assertSanitisedProblem(response, 415, "urn:fleet:problem:unsupported-media-type");
    }

    /** An unexpected application fault stays a 500 — never softened into 401 or 400. */
    @Test
    void unexpectedApplicationFailureIsASanitisedServerFault() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/test-only/boom")
                .header("Authorization", "Bearer " + validToken())).andReturn().getResponse();

        assertSanitisedProblem(response, 500, "urn:fleet:problem:internal-error");
    }

    private String validToken() {
        return issuer.issue(UUID.randomUUID(), UUID.randomUUID(), "ADMIN");
    }
}
