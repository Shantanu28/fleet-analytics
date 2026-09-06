package com.fleet.analytics.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Asserts <em>which</em> constraint rejected a row, not merely that something did. Matching on
 * message text alone lets a test pass on an unrelated violation — a missing parent organisation
 * reads exactly like the cross-tenant failure it is supposed to prove.
 */
public final class ConstraintViolations {

    public static final String FOREIGN_KEY_VIOLATION = "23503";
    public static final String UNIQUE_VIOLATION = "23505";
    public static final String CHECK_VIOLATION = "23514";
    public static final String NOT_NULL_VIOLATION = "23502";

    private ConstraintViolations() {}

    @FunctionalInterface
    public interface FailingStatement {
        void run() throws Exception;
    }

    public static void assertViolates(FailingStatement statement, String sqlState, String constraintName) {
        ServerErrorMessage error = captureServerError(statement);
        assertThat(error.getSQLState()).as("SQLSTATE").isEqualTo(sqlState);
        assertThat(error.getConstraint()).as("constraint name").isEqualTo(constraintName);
    }

    private static ServerErrorMessage captureServerError(FailingStatement statement) {
        try {
            statement.run();
        } catch (PSQLException e) {
            ServerErrorMessage error = e.getServerErrorMessage();
            assertThat(error).as("server error message").isNotNull();
            return error;
        } catch (Exception e) {
            throw new AssertionError("expected a PostgreSQL constraint violation", e);
        }
        return fail("expected the statement to be rejected, but it succeeded");
    }
}
