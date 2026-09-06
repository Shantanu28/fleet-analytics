package com.fleet.analytics.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Validates real HTTP interactions against {@code contracts/openapi.yaml} — operation, status,
 * content type and the full resolved response schema, including nested objects, arrays, formats,
 * enums and required fields.
 *
 * <p>The validator is the library's; only the adaptation from {@link MvcResult} to its request and
 * response model lives here, which is why the {@code -mockmvc} adapter is not a dependency.
 */
public final class OpenApiContract {

    private static final Path SPECIFICATION = Path.of("../contracts/openapi.yaml");

    private static final OpenApiInteractionValidator VALIDATOR =
            OpenApiInteractionValidator.createForSpecificationUrl(SPECIFICATION.toUri().toString())
                    .build();

    private OpenApiContract() {}

    public static void assertValid(MvcResult result) {
        ValidationReport report = validate(result);
        assertThat(report.hasErrors())
                .as("OpenAPI validation errors:%n%s", render(report))
                .isFalse();
    }

    /** Proves the validator actually rejects: used with a deliberately non-conforming response. */
    public static ValidationReport validate(MvcResult result) {
        return VALIDATOR.validate(toRequest(result), toResponse(result.getResponse()));
    }

    /**
     * Validates the response alone. Used where the <em>request</em> is deliberately invalid — an
     * unauthenticated call, or a malformed body — and it is the error response that has to conform.
     */
    public static void assertResponseValid(MvcResult result) {
        var servletRequest = result.getRequest();
        String path = servletRequest.getRequestURI().replaceFirst("^/api/v1", "");
        ValidationReport report = VALIDATOR.validateResponse(path,
                Request.Method.valueOf(servletRequest.getMethod()), toResponse(result.getResponse()));
        assertThat(report.hasErrors())
                .as("OpenAPI response validation errors:%n%s", render(report))
                .isFalse();
    }

    public static ValidationReport validateResponse(MvcResult result, String replacementBody) {
        MockHttpServletResponse response = result.getResponse();
        SimpleResponse.Builder builder = SimpleResponse.Builder.status(response.getStatus())
                .withBody(replacementBody);
        if (response.getContentType() != null) {
            builder.withContentType(response.getContentType());
        }
        return VALIDATOR.validate(toRequest(result), builder.build());
    }

    public static List<String> keys(ValidationReport report) {
        return report.getMessages().stream().map(ValidationReport.Message::getKey).toList();
    }

    public static String render(ValidationReport report) {
        StringBuilder text = new StringBuilder();
        for (ValidationReport.Message message : report.getMessages()) {
            text.append("  [").append(message.getLevel()).append("] ")
                    .append(message.getKey()).append(": ").append(message.getMessage()).append('\n');
        }
        return text.toString();
    }

    private static Request toRequest(MvcResult result) {
        var servletRequest = result.getRequest();
        // The specification's server prefix is /api/v1; the validator matches on the path after it.
        String path = servletRequest.getRequestURI().replaceFirst("^/api/v1", "");
        SimpleRequest.Builder builder = new SimpleRequest.Builder(
                Request.Method.valueOf(servletRequest.getMethod()), path);

        if (servletRequest.getContentType() != null) {
            builder.withContentType(servletRequest.getContentType());
        }
        byte[] body = servletRequest.getContentAsByteArray();
        if (body != null && body.length > 0) {
            builder.withBody(new String(body, StandardCharsets.UTF_8));
        }
        if (servletRequest.getHeader("Authorization") != null) {
            builder.withHeader("Authorization", servletRequest.getHeader("Authorization"));
        }
        return builder.build();
    }

    private static Response toResponse(MockHttpServletResponse response) {
        SimpleResponse.Builder builder = SimpleResponse.Builder.status(response.getStatus());
        if (response.getContentType() != null) {
            builder.withContentType(response.getContentType());
        }
        try {
            builder.withBody(response.getContentAsString());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return builder.build();
    }
}
