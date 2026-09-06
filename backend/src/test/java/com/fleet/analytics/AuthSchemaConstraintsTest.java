package com.fleet.analytics;

import static com.fleet.analytics.support.ConstraintViolations.CHECK_VIOLATION;
import static com.fleet.analytics.support.ConstraintViolations.FOREIGN_KEY_VIOLATION;
import static com.fleet.analytics.support.ConstraintViolations.UNIQUE_VIOLATION;
import static com.fleet.analytics.support.ConstraintViolations.assertViolates;

import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M2 schema: tenant-safe relationships and identity uniqueness.
 *
 * <p>Each test creates its own organisations and parents, so none depends on another having run
 * first, and every assertion names the SQLSTATE and the constraint that must reject the row. A
 * valid control row goes in before each invalid one, proving the insert fails for the stated reason
 * and not because the fixture was unusable.
 */
class AuthSchemaConstraintsTest extends PostgresTestBase {

    private static final String HASH = "{noop}x";

    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void createOrganisations() throws SQLException {
        orgA = UUID.randomUUID();
        orgB = UUID.randomUUID();
        try (Connection c = connection()) {
            Fixtures.organisation(c, orgA, "Org A");
            Fixtures.organisation(c, orgB, "Org B");
        }
    }

    @Test
    void teamMustReferenceAnExistingOrganisation() throws SQLException {
        try (Connection c = connection()) {
            Fixtures.team(c, UUID.randomUUID(), orgA, "team-ok", "Platform");   // control: valid

            assertViolates(() -> Fixtures.team(c, UUID.randomUUID(), UUID.randomUUID(), "team-bad", "Ghost"),
                    FOREIGN_KEY_VIOLATION, "team_org_id_fkey");
        }
    }

    @Test
    void userCannotJoinAnotherOrganisationsTeam() throws SQLException {
        try (Connection c = connection()) {
            UUID teamA = UUID.randomUUID();
            UUID teamB = UUID.randomUUID();
            Fixtures.team(c, teamA, orgA, "team-a", "Core A");
            Fixtures.team(c, teamB, orgB, "team-b", "Payments B");

            // Control: the same insert succeeds when the team belongs to the user's own organisation.
            Fixtures.user(c, UUID.randomUUID(), orgA, teamA, "u-ok", "own.team", "Own", HASH, "VIEWER", false);

            // Both organisations exist, so the only reason left to reject is the cross-tenant team.
            assertViolates(() -> Fixtures.user(c, UUID.randomUUID(), orgA, teamB, "u-bad",
                            "cross.tenant", "X", HASH, "VIEWER", false),
                    FOREIGN_KEY_VIOLATION, "app_user_team_same_org");
        }
    }

    @Test
    void usernameIsUniqueAcrossOrganisations() throws SQLException {
        try (Connection c = connection()) {
            UUID teamA = UUID.randomUUID();
            UUID teamB = UUID.randomUUID();
            Fixtures.team(c, teamA, orgA, "team-a", "Core");
            Fixtures.team(c, teamB, orgB, "team-b", "Core B");

            String shared = "shared-" + UUID.randomUUID();
            Fixtures.user(c, UUID.randomUUID(), orgA, teamA, "u-1", shared, "A", HASH, "ADMIN", false);

            assertViolates(() -> Fixtures.user(c, UUID.randomUUID(), orgB, teamB, "u-2",
                            shared, "B", HASH, "VIEWER", false),
                    UNIQUE_VIOLATION, "app_user_username_key");
        }
    }

    @Test
    void roleIsRestrictedToAdminOrViewer() throws SQLException {
        try (Connection c = connection()) {
            UUID team = UUID.randomUUID();
            Fixtures.team(c, team, orgA, "team-a", "Ops");
            Fixtures.user(c, UUID.randomUUID(), orgA, team, "u-ok", "ops.admin", "Ops", HASH, "ADMIN", false);

            assertViolates(() -> Fixtures.user(c, UUID.randomUUID(), orgA, team, "u-bad",
                            "ops.super", "R", HASH, "SUPERUSER", false),
                    CHECK_VIOLATION, "app_user_role_check");
        }
    }

    @Test
    void seatCannotBeAssignedToAnotherOrganisationsUser() throws SQLException {
        try (Connection c = connection()) {
            UUID teamB = UUID.randomUUID();
            UUID userB = UUID.randomUUID();
            Fixtures.team(c, teamB, orgB, "team-b", "Core B");
            Fixtures.user(c, userB, orgB, teamB, "u-b", "beacon.user-" + userB, "B", HASH, "VIEWER", false);

            UUID teamA = UUID.randomUUID();
            UUID userA = UUID.randomUUID();
            Fixtures.team(c, teamA, orgA, "team-a", "Core A");
            Fixtures.user(c, userA, orgA, teamA, "u-a", "acme.user-" + userA, "A", HASH, "VIEWER", false);
            Fixtures.seat(c, UUID.randomUUID(), orgA, "seat-ok", userA);        // control: same tenant

            assertViolates(() -> Fixtures.seat(c, UUID.randomUUID(), orgA, "seat-bad", userB),
                    FOREIGN_KEY_VIOLATION, "seat_licence_user_same_org");
        }
    }

    @Test
    void seatAssignmentAndTimestampMustAgree() throws SQLException {
        try (Connection c = connection()) {
            UUID team = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            Fixtures.team(c, team, orgA, "team-a", "Core A");
            Fixtures.user(c, user, orgA, team, "u-a", "seat.user-" + user, "A", HASH, "VIEWER", false);
            Fixtures.seat(c, UUID.randomUUID(), orgA, "seat-assigned", user);   // control: both set

            // a user with no assignment time
            assertViolates(() -> Fixtures.seat(c, UUID.randomUUID(), orgA, "seat-half-a", user, false),
                    CHECK_VIOLATION, "seat_licence_assignment_consistent");

            // an assignment time with no user
            assertViolates(() -> Fixtures.seat(c, UUID.randomUUID(), orgA, "seat-half-b", null, true),
                    CHECK_VIOLATION, "seat_licence_assignment_consistent");
        }
    }

    @Test
    void oneAssignedSeatPerUserButUnassignedSeatsMayRepeat() throws SQLException {
        try (Connection c = connection()) {
            UUID team = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            Fixtures.team(c, team, orgA, "team-a", "Core A");
            Fixtures.user(c, user, orgA, team, "u-a", "dup.user-" + user, "A", HASH, "VIEWER", false);

            // Unassigned seats carry a NULL user_id, which the partial index deliberately ignores.
            Fixtures.seat(c, UUID.randomUUID(), orgA, "seat-free-1", null);
            Fixtures.seat(c, UUID.randomUUID(), orgA, "seat-free-2", null);

            Fixtures.seat(c, UUID.randomUUID(), orgA, "seat-assigned", user);

            assertViolates(() -> Fixtures.seat(c, UUID.randomUUID(), orgA, "seat-duplicate", user),
                    UNIQUE_VIOLATION, "seat_licence_one_per_user");
        }
    }
}
