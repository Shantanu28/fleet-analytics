package com.fleet.analytics.web.error;

import com.fleet.analytics.security.RevocationUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Preserves the {@code WWW-Authenticate: Bearer} challenge by delegating to Spring Security, but
 * hands the delegate a <em>sanitised</em> exception first.
 *
 * <p>{@link BearerTokenAuthenticationEntryPoint} copies {@code error} and {@code error_description}
 * into the header for an {@code OAuth2AuthenticationException}, and those descriptions carry
 * decoder internals such as Nimbus parse messages. Substituting a plain authentication exception
 * yields a bare {@code Bearer} challenge, so neither the header nor the body says why the token
 * failed.
 */
@Component
public class SanitisedBearerEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
    private final ProblemResponseWriter problems;

    public SanitisedBearerEntryPoint(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        if (exception instanceof RevocationUnavailableException) {
            problems.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemTypes.AUTHENTICATION_UNAVAILABLE,
                    "Authentication is temporarily unavailable.");
            return;
        }
        delegate.commence(request, response,
                new InsufficientAuthenticationException("Authentication is required."));
        problems.write(response, HttpStatus.UNAUTHORIZED,
                ProblemTypes.UNAUTHENTICATED, "Authentication is required.");
    }
}
