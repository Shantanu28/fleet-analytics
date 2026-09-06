package com.fleet.analytics;

import static com.fleet.analytics.support.ConstraintViolations.CHECK_VIOLATION;
import static com.fleet.analytics.support.ConstraintViolations.FOREIGN_KEY_VIOLATION;
import static com.fleet.analytics.support.ConstraintViolations.UNIQUE_VIOLATION;
import static com.fleet.analytics.support.ConstraintViolations.assertViolates;

import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V3 analytics domain: the constraints the metrics contract relies on to be true of the data
 * before any query runs. Each test creates its own organisation and parents, inserts a valid
 * control row first, then asserts the SQLSTATE and the exact constraint that must reject the
 * invalid row — so a test cannot pass because some unrelated key happened to fire.
 *
 * <p>Cross-row invariants that a CHECK cannot express — one run per task, PRs only on completed
 * tasks, {@code target_branch = repository.default_branch} — are deliberately absent: they are
 * seed and fixture properties, asserted where the data is generated, not database guarantees.
 */
class AnalyticsSchemaConstraintsTest extends PostgresTestBase {

    private static final OffsetDateTime CREATED =
            OffsetDateTime.of(2026, 1, 5, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime ENDED =
            OffsetDateTime.of(2026, 1, 5, 9, 40, 0, 0, ZoneOffset.UTC);

    private UUID org;
    private UUID team;
    private UUID repo;
    private UUID user;

    @BeforeEach
    void createTenant() throws SQLException {
        org = UUID.randomUUID();
        team = UUID.randomUUID();
        repo = UUID.randomUUID();
        user = UUID.randomUUID();
        try (Connection c = connection()) {
            Fixtures.organisation(c, org, "Analytics Org");
            Fixtures.team(c, team, org, "t-" + team, "Platform");
            Fixtures.repository(c, repo, org, "r-" + repo, "repo-api");
            Fixtures.user(c, user, org, team, "u-" + user, "user-" + user, "User",
                    "{noop}x", "ADMIN", false);
        }
    }

    private UUID completedTask(Connection c, String sourceId) throws SQLException {
        UUID id = UUID.randomUUID();
        Fixtures.task(c, id, org, team, repo, user, sourceId, "feature", CREATED, "completed", ENDED);
        return id;
    }

    private UUID completedRun(Connection c, UUID taskId, String sourceId) throws SQLException {
        UUID id = UUID.randomUUID();
        Fixtures.run(c, id, org, taskId, sourceId, 1, CREATED, ENDED, "completed", null);
        return id;
    }

    /**
     * The MATCH SIMPLE trap. A single (org_id, task_id, run_id) key would be skipped entirely
     * whenever run_id is NULL, so the task reference would stop being checked on exactly the rows
     * that name no run. This proves the separate task key still fires there.
     */
    @Test
    void denialEventStillChecksItsTaskWhenNoRunIsNamed() throws SQLException {
        try (Connection c = connection()) {
            UUID task = completedTask(c, "task-ok");
            Fixtures.denial(c, UUID.randomUUID(), org, task, null, "d-ok", "registry.corp", CREATED);

            assertViolates(() -> Fixtures.denial(c, UUID.randomUUID(), org, UUID.randomUUID(), null,
                            "d-bad", "registry.corp", CREATED),
                    FOREIGN_KEY_VIOLATION, "denial_event_task_same_org");
        }
    }

    @Test
    void denialEventRunMustBelongToItsOwnTask() throws SQLException {
        try (Connection c = connection()) {
            UUID taskA = completedTask(c, "task-a");
            UUID runA = completedRun(c, taskA, "run-a");
            UUID taskB = completedTask(c, "task-b");
            Fixtures.denial(c, UUID.randomUUID(), org, taskA, runA, "d-ok", "registry.corp", CREATED);

            assertViolates(() -> Fixtures.denial(c, UUID.randomUUID(), org, taskB, runA,
                            "d-bad", "registry.corp", CREATED),
                    FOREIGN_KEY_VIOLATION, "denial_event_run_same_task");
        }
    }

    @Test
    void pullRequestRunMustBelongToItsOwnTask() throws SQLException {
        try (Connection c = connection()) {
            UUID taskA = completedTask(c, "task-a");
            UUID runA = completedRun(c, taskA, "run-a");
            UUID taskB = completedTask(c, "task-b");
            completedRun(c, taskB, "run-b");
            Fixtures.pullRequest(c, UUID.randomUUID(), org, taskA, runA, "pr-ok", "main",
                    ENDED, "merged", ENDED);

            assertViolates(() -> Fixtures.pullRequest(c, UUID.randomUUID(), org, taskB, runA,
                            "pr-bad", "main", ENDED, "merged", ENDED),
                    FOREIGN_KEY_VIOLATION, "pull_request_run_same_task");
        }
    }

    @Test
    void atMostOnePullRequestPerTask() throws SQLException {
        try (Connection c = connection()) {
            UUID task = completedTask(c, "task-a");
            UUID run = completedRun(c, task, "run-a");
            Fixtures.pullRequest(c, UUID.randomUUID(), org, task, run, "pr-1", "main",
                    ENDED, "merged", ENDED);

            assertViolates(() -> Fixtures.pullRequest(c, UUID.randomUUID(), org, task, run,
                            "pr-2", "main", ENDED, null, null),
                    UNIQUE_VIOLATION, "pull_request_one_per_task");
        }
    }

    /** Retries stay representable: a second attempt is valid, a repeated attempt number is not. */
    @Test
    void runAttemptNumbersAreUniqueWithinATask() throws SQLException {
        try (Connection c = connection()) {
            UUID task = completedTask(c, "task-a");
            completedRun(c, task, "run-1");
            Fixtures.run(c, UUID.randomUUID(), org, task, "run-2", 2, CREATED, ENDED, "completed", null);

            assertViolates(() -> Fixtures.run(c, UUID.randomUUID(), org, task, "run-3", 2,
                            CREATED, ENDED, "completed", null),
                    UNIQUE_VIOLATION, "run_attempt_key");
        }
    }

    @Test
    void usageCostCannotBeNegative() throws SQLException {
        try (Connection c = connection()) {
            UUID task = completedTask(c, "task-a");
            UUID run = completedRun(c, task, "run-a");
            Fixtures.usage(c, UUID.randomUUID(), org, run, "u-ok", ENDED, 0L);

            assertViolates(() -> Fixtures.usage(c, UUID.randomUUID(), org, run, "u-bad", ENDED, -1L),
                    CHECK_VIOLATION, "usage_record_cost_non_negative");
        }
    }

    /**
     * The default NULLS DISTINCT would accept two organisation-scope budgets for one month, and
     * the budget rule would then have no single amount to evaluate against.
     */
    @Test
    void organisationBudgetIsUniquePerMonthDespiteItsNullTeam() throws SQLException {
        try (Connection c = connection()) {
            LocalDate february = LocalDate.of(2026, 2, 1);
            Fixtures.budget(c, UUID.randomUUID(), org, null, "b-org", february, 200000L);
            Fixtures.budget(c, UUID.randomUUID(), org, team, "b-team", february, 50000L);

            assertViolates(() -> Fixtures.budget(c, UUID.randomUUID(), org, null, "b-dup",
                            february, 999L),
                    UNIQUE_VIOLATION, "budget_scope_month_key");
        }
    }

    @Test
    void budgetPeriodMustBeTheFirstOfAMonth() throws SQLException {
        try (Connection c = connection()) {
            Fixtures.budget(c, UUID.randomUUID(), org, null, "b-ok", LocalDate.of(2026, 2, 1), 1L);

            assertViolates(() -> Fixtures.budget(c, UUID.randomUUID(), org, team, "b-bad",
                            LocalDate.of(2026, 2, 15), 1L),
                    CHECK_VIOLATION, "budget_month_is_canonical");
        }
    }

    /** A terminal status without a time is not a representable state, and vice versa. */
    @Test
    void taskStatusAndTerminalTimeMustAgree() throws SQLException {
        try (Connection c = connection()) {
            Fixtures.task(c, UUID.randomUUID(), org, team, repo, user, "t-open", "feature",
                    CREATED, null, null);                                   // in progress: both null

            assertViolates(() -> Fixtures.task(c, UUID.randomUUID(), org, team, repo, user,
                            "t-bad", "feature", CREATED, "completed", null),
                    CHECK_VIOLATION, "task_status_time_agree");
        }
    }

    @Test
    void failedRunRequiresAFailureReason() throws SQLException {
        try (Connection c = connection()) {
            UUID task = completedTask(c, "task-a");
            Fixtures.run(c, UUID.randomUUID(), org, task, "run-ok", 1, CREATED, ENDED,
                    "failed", "tests_failed");

            assertViolates(() -> Fixtures.run(c, UUID.randomUUID(), org, task, "run-bad", 2,
                            CREATED, ENDED, "failed", null),
                    CHECK_VIOLATION, "run_failure_reason_presence");
        }
    }
}
