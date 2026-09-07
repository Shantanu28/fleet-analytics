package com.fleet.analytics.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Parameterised fixture inserts. Every value is bound, never concatenated, so a fixture name
 * containing a quote is data rather than a broken statement.
 */
public final class Fixtures {

    private Fixtures() {}

    /** Idempotent, so a test can guarantee its own prerequisites without depending on other tests. */
    public static void organisation(Connection c, UUID id, String name) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into organisation(id, name, created_at) values (?, ?, now())"
                        + " on conflict (id) do nothing")) {
            s.setObject(1, id);
            s.setString(2, name);
            s.execute();
        }
    }

    public static void team(Connection c, UUID id, UUID orgId, String sourceEntityId, String name)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into team(id, org_id, source, source_entity_id, name) values (?, ?, 'fixture', ?, ?)")) {
            s.setObject(1, id);
            s.setObject(2, orgId);
            s.setString(3, sourceEntityId);
            s.setString(4, name);
            s.execute();
        }
    }

    public static void repository(Connection c, UUID id, UUID orgId, String sourceEntityId, String name)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into repository(id, org_id, source, source_entity_id, name, default_branch)"
                        + " values (?, ?, 'fixture', ?, ?, 'main')")) {
            s.setObject(1, id);
            s.setObject(2, orgId);
            s.setString(3, sourceEntityId);
            s.setString(4, name);
            s.execute();
        }
    }

    public static void user(Connection c, UUID id, UUID orgId, UUID teamId, String sourceEntityId,
            String username, String displayName, String passwordHash, String role, boolean demoAccount)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into app_user(id, org_id, team_id, source, source_entity_id, username,"
                        + " display_name, password_hash, role, is_demo_account)"
                        + " values (?, ?, ?, 'fixture', ?, ?, ?, ?, ?, ?)")) {
            s.setObject(1, id);
            s.setObject(2, orgId);
            s.setObject(3, teamId);
            s.setString(4, sourceEntityId);
            s.setString(5, username);
            s.setString(6, displayName);
            s.setString(7, passwordHash);
            s.setString(8, role);
            s.setBoolean(9, demoAccount);
            s.execute();
        }
    }

    /** {@code userId} null means an unassigned seat, which must also leave {@code assigned_at} null. */
    public static void seat(Connection c, UUID id, UUID orgId, String sourceEntityId, UUID userId)
            throws SQLException {
        seat(c, id, orgId, sourceEntityId, userId, userId != null);
    }

    /** Assignment columns are set independently so tests can drive the null-pairing constraint. */
    public static void seat(Connection c, UUID id, UUID orgId, String sourceEntityId, UUID userId,
            boolean withAssignedAt) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into seat_licence(id, org_id, source, source_entity_id, user_id, assigned_at)"
                        + " values (?, ?, 'fixture', ?, ?, ?)")) {
            s.setObject(1, id);
            s.setObject(2, orgId);
            s.setString(3, sourceEntityId);
            s.setObject(4, userId);
            s.setObject(5, withAssignedAt ? Timestamp.from(java.time.Instant.now()) : null);
            s.execute();
        }
    }

    public static void publication(Connection c, UUID orgId, OffsetDateTime from, OffsetDateTime through,
            String revision) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into dataset_publication(org_id, data_available_from, data_through, revision)"
                        + " values (?, ?, ?, ?) on conflict (org_id) do nothing")) {
            s.setObject(1, orgId);
            s.setObject(2, from);
            s.setObject(3, through);
            s.setString(4, revision);
            s.execute();
        }
    }

    /**
     * One {@code source_day_coverage} row. Coverage is metadata, never inferred from whether
     * business rows exist, so a fixture that wants a covered day must say so explicitly (A.5).
     */
    public static void sourceDay(Connection c, UUID orgId, String logicalSource, LocalDate day,
            boolean complete) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into source_day_coverage(org_id, logical_source, day, is_complete)"
                        + " values (?, ?, ?, ?)"
                        + " on conflict (org_id, logical_source, day) do update"
                        + " set is_complete = excluded.is_complete")) {
            s.setObject(1, orgId);
            s.setString(2, logicalSource);
            s.setObject(3, day);
            s.setBoolean(4, complete);
            s.execute();
        }
    }

    public static void budget(Connection c, UUID id, UUID orgId, UUID teamId, String sourceEntityId,
            LocalDate periodMonth, long amountCents) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into budget(id, org_id, team_id, source, source_entity_id, period_month,"
                        + " amount_cents) values (?, ?, ?, 'fixture', ?, ?, ?)")) {
            s.setObject(1, id);
            s.setObject(2, orgId);
            s.setObject(3, teamId);
            s.setString(4, sourceEntityId);
            s.setObject(5, periodMonth);
            s.setLong(6, amountCents);
            s.execute();
        }
    }

    public static void task(Connection c, UUID id, UUID orgId, UUID teamId, UUID repoId, UUID userId,
            String sourceEntityId, String taskType, OffsetDateTime createdAt,
            String terminalStatus, OffsetDateTime terminalAt) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into task(id, org_id, team_id, repo_id, user_id, source, source_entity_id,"
                        + " task_type, created_at, terminal_status, terminal_at)"
                        + " values (?, ?, ?, ?, ?, 'fixture', ?, ?, ?, ?, ?)")) {
            s.setObject(1, id);
            s.setObject(2, orgId);
            s.setObject(3, teamId);
            s.setObject(4, repoId);
            s.setObject(5, userId);
            s.setString(6, sourceEntityId);
            s.setString(7, taskType);
            s.setObject(8, createdAt);
            s.setString(9, terminalStatus);
            s.setObject(10, terminalAt);
            s.execute();
        }
    }

    public static void run(Connection c, UUID id, UUID orgId, UUID taskId, String sourceEntityId,
            int attemptNo, OffsetDateTime startedAt, OffsetDateTime endedAt, String runStatus,
            String failureReason) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into run(id, org_id, task_id, source, source_entity_id, attempt_no,"
                        + " started_at, ended_at, run_status, failure_reason)"
                        + " values (?, ?, ?, 'fixture', ?, ?, ?, ?, ?, ?)")) {
            s.setObject(1, id);
            s.setObject(2, orgId);
            s.setObject(3, taskId);
            s.setString(4, sourceEntityId);
            s.setInt(5, attemptNo);
            s.setObject(6, startedAt);
            s.setObject(7, endedAt);
            s.setString(8, runStatus);
            s.setString(9, failureReason);
            s.execute();
        }
    }

    public static void pullRequest(Connection c, UUID id, UUID orgId, UUID taskId, UUID runId,
            String sourceEntityId, String targetBranch, OffsetDateTime openedAt,
            String terminalState, OffsetDateTime terminalAt) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into pull_request(id, org_id, task_id, run_id, source, source_entity_id,"
                        + " target_branch, opened_at, terminal_state, terminal_at)"
                        + " values (?, ?, ?, ?, 'fixture', ?, ?, ?, ?, ?)")) {
            s.setObject(1, id);
            s.setObject(2, orgId);
            s.setObject(3, taskId);
            s.setObject(4, runId);
            s.setString(5, sourceEntityId);
            s.setString(6, targetBranch);
            s.setObject(7, openedAt);
            s.setString(8, terminalState);
            s.setObject(9, terminalAt);
            s.execute();
        }
    }

    public static void usage(Connection c, UUID id, UUID orgId, UUID runId, String sourceEntityId,
            OffsetDateTime meteredAt, long costCents) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into usage_record(id, org_id, run_id, source, source_entity_id, metered_at,"
                        + " cost_cents, model_tier) values (?, ?, ?, 'fixture', ?, ?, ?, 'standard')")) {
            s.setObject(1, id);
            s.setObject(2, orgId);
            s.setObject(3, runId);
            s.setString(4, sourceEntityId);
            s.setObject(5, meteredAt);
            s.setLong(6, costCents);
            s.execute();
        }
    }

    /** {@code runId} may be null: a denial always names a task, and names a run only when known. */
    public static void denial(Connection c, UUID id, UUID orgId, UUID taskId, UUID runId,
            String sourceEntityId, String domain, OffsetDateTime occurredAt) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into denial_event(id, org_id, task_id, run_id, source, source_entity_id,"
                        + " domain_raw, domain_normalised, occurred_at)"
                        + " values (?, ?, ?, ?, 'fixture', ?, ?, ?, ?)")) {
            s.setObject(1, id);
            s.setObject(2, orgId);
            s.setObject(3, taskId);
            s.setObject(4, runId);
            s.setString(5, sourceEntityId);
            s.setString(6, domain);
            s.setString(7, normalisedDomain(domain));
            s.setObject(8, occurredAt);
            s.execute();
        }
    }

    /**
     * Contract 6.4 normalisation: lowercase and trailing dot stripped. {@code domain_raw} keeps
     * what the source reported. Locale.ROOT rather than the default locale, so a Turkish-locale
     * JVM cannot lowercase {@code I} to a dotless {@code i} and split one domain into two.
     *
     * <p>This lives here because the friction rule counts distinct <em>normalised</em> domains: if
     * fixtures stored {@code Registry.Corp.} unnormalised, that rule would see two domains where
     * the contract sees one, and its sample gate would never be reached.
     */
    static String normalisedDomain(String domain) {
        String lowercased = domain.toLowerCase(Locale.ROOT);
        return lowercased.endsWith(".")
                ? lowercased.substring(0, lowercased.length() - 1)
                : lowercased;
    }
}
