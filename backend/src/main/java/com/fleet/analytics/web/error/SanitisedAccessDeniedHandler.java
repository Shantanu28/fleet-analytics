package com.fleet.analytics.web.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * A 403 that names no scope, authority or resource. The bearer challenge for an insufficiently
 * privileged token would otherwise echo the required scope back to the caller.
 */
@Component
public class SanitisedAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problems;

    public SanitisedAccessDeniedHandler(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        problems.write(response, HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Access is denied.");
    }
}
