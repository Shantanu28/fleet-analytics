package com.fleet.analytics.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
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
}
