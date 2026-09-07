package com.fleet.analytics.web.error;

import com.fleet.analytics.auth.InvalidCredentialsException;
import com.fleet.analytics.data.ContextUnavailableException;
import com.fleet.analytics.data.analytics.InvalidAnalyticsFilterException;
import com.fleet.analytics.web.dashboard.InvalidDashboardSelectionException;
import com.fleet.analytics.web.dashboard.JsonSafeInteger;
import com.fleet.analytics.web.dashboard.SelectionProblem;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * HTTP status and {@link ProblemDetail} live here, not on the exception types.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} keeps Spring's own handling of protocol-level
 * failures — a wrong method is a 405 carrying {@code Allow}, a wrong content type a 415 carrying
 * {@code Accept} — and only replaces the body. Collapsing those into a generic 500 would misreport
 * a client mistake as a server fault and drop headers the client needs to correct it.
 *
 * <p>Every body is sanitised: no SQL, stack trace, identifier or framework message reaches a client.
 */
@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /**
     * Spring has already chosen the status and the required headers; only the body is ours. The
     * exception's own message is discarded rather than echoed.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        return super.handleExceptionInternal(ex, sanitised(statusCode), headers, statusCode, request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail onInvalidCredentials(InvalidCredentialsException e) {
        return problem(HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_CREDENTIALS, e.getMessage());
    }

    /**
     * A rejected dashboard selection. The parser has already chosen the typed reason, so this only
     * maps it to its published problem type. The message is the parser's own controlled text, which
     * never names an identifier.
     */
    @ExceptionHandler(InvalidDashboardSelectionException.class)
    public ProblemDetail onInvalidSelection(InvalidDashboardSelectionException e) {
        return problem(HttpStatus.BAD_REQUEST, selectionProblemType(e.problem()), e.getMessage());
    }

    /**
     * An unknown, malformed or foreign filter. All three produce this one response: reporting them
     * differently would let a caller enumerate other organisations' teams and repositories.
     */
    @ExceptionHandler(InvalidAnalyticsFilterException.class)
    public ProblemDetail onInvalidFilter(InvalidAnalyticsFilterException e) {
        return problem(HttpStatus.BAD_REQUEST, ProblemTypes.UNKNOWN_FILTER, e.getMessage());
    }

    /**
     * A response value outside the JSON-safe integer range. This is an internal invariant failure,
     * not a caller mistake: the request was valid and an aggregate grew past what the contract's
     * field can carry. Reporting it as a 400 would blame the client for a server-side limit.
     */
    @ExceptionHandler(JsonSafeInteger.UnsafeNumericRangeException.class)
    public ProblemDetail onUnsafeNumericRange(JsonSafeInteger.UnsafeNumericRangeException e) {
        log.error("Dashboard response value exceeds the JSON-safe integer range", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR,
                "The analytics response could not be produced.");
    }

    private static URI selectionProblemType(SelectionProblem problem) {
        return switch (problem) {
            case INVALID_DATE_FORMAT -> ProblemTypes.INVALID_DATE_FORMAT;
            case REVERSED_DATE_RANGE -> ProblemTypes.REVERSED_DATE_RANGE;
            case INCOMPLETE_DATE_RANGE -> ProblemTypes.INCOMPLETE_DATE_RANGE;
            case RANGE_OUTSIDE_COVERAGE -> ProblemTypes.RANGE_OUTSIDE_COVERAGE;
            case UNKNOWN_FILTER -> ProblemTypes.UNKNOWN_FILTER;
            case INVALID_GROUPING -> ProblemTypes.INVALID_GROUPING;
        };
    }

    /** Missing tenant metadata: a broken dataset, reported as a server fault with no detail. */
    @ExceptionHandler(ContextUnavailableException.class)
    public ProblemDetail onContextUnavailable(ContextUnavailableException e) {
        log.error("Context metadata missing for organisation {}", e.organisationId());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.CONTEXT_UNAVAILABLE,
                "The analytics context is unavailable.");
    }

    /**
     * Security failures belong to the filter chain, which owns the 401/403 status and the
     * {@code WWW-Authenticate} challenge. Rethrowing lets {@code ExceptionTranslationFilter} handle
     * them; without this the catch-all below would turn a denied request into a 500.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void onSecurityFailure(RuntimeException e) {
        throw e;
    }

    /**
     * Anything unforeseen is a sanitised 500. It is logged in full server-side and never reshaped
     * into a 401 or a 400 — in particular, an {@code IllegalArgumentException} is an internal
     * invariant violation here, not evidence that the caller sent something wrong.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        log.error("Unhandled failure serving request", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR,
                "Something went wrong. Please try again.");
    }

    /** One sanitised body per status, so no framework message is ever echoed to a client. */
    private static ProblemDetail sanitised(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400 -> problem(statusCode, ProblemTypes.INVALID_REQUEST,
                    "The request could not be read.");
            case 405 -> problem(statusCode, ProblemTypes.METHOD_NOT_ALLOWED,
                    "That method is not supported for this endpoint.");
            case 406 -> problem(statusCode, ProblemTypes.NOT_ACCEPTABLE,
                    "No supported representation is available.");
            case 415 -> problem(statusCode, ProblemTypes.UNSUPPORTED_MEDIA_TYPE,
                    "That content type is not supported.");
            default -> statusCode.is5xxServerError()
                    ? problem(statusCode, ProblemTypes.INTERNAL_ERROR,
                            "Something went wrong. Please try again.")
                    : problem(statusCode, ProblemTypes.INVALID_REQUEST, "The request was not valid.");
        };
    }

    private static ProblemDetail problem(HttpStatusCode status, URI type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        return problem;
    }
}
