package com.fleet.analytics.web.dashboard;

/**
 * The requested selection cannot be answered. One exception with a typed reason, not a class per
 * validation branch — the branches differ only in which message the client shows.
 *
 * <p>The message is already safe to serve: it never names an identifier, so a rejected foreign team
 * id reads exactly like a nonexistent one. Status and {@code ProblemDetail} belong to the web
 * adapter (slice D), not here.
 */
public final class InvalidDashboardSelectionException extends RuntimeException {

    private final SelectionProblem problem;

    public InvalidDashboardSelectionException(SelectionProblem problem, String message) {
        super(message);
        this.problem = problem;
    }

    public SelectionProblem problem() {
        return problem;
    }
}
