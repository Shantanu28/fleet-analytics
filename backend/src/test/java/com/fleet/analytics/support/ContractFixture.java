package com.fleet.analytics.support;

import com.fleet.analytics.metrics.model.LogicalSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Installs the metrics contract's normative fixture (contract 7): ten tasks, one run each, five
 * PRs, ten usage records, two teams, two repositories and six licensed seats.
 *
 * <p>Every timestamp is fixed and absolute — nothing here reads a clock, so the same dataset is
 * installed in January as in July and the contract's worked figures stay meaningful. It is
 * deliberately independent of the M4 demo generator: this is the small hand-checkable dataset, and
 * a generator change must never silently move a contract expectation.
 *
 * <p>Identifiers are derived from the organisation and a stable key, so the same fixture can be
 * installed for two tenants at once without collision — which is what makes tenant isolation
 * testable on returned data rather than only on rejected filter ids.
 *
 * <p>The boundary cases the contract deliberately builds in are all present: T10 created at exactly
 * the previous period's first instant, T8 created inside the period but metered outside it, PR-4
 * merged after the period ends but before {@code dataThrough}, PR-3 merged inside the period from a
 * task created before it, T9 non-code, T5 cancelled and T8 still running.
 */
public final class ContractFixture {

    public static final OffsetDateTime DATA_AVAILABLE_FROM = utc(2025, 12, 1, 0, 0);
    public static final OffsetDateTime DATA_THROUGH = utc(2026, 2, 4, 0, 0);
    public static final String REVISION = "contract-fixture-1";

    /** Contract 7: the organisation's February 2026 budget, $2,000.00. */
    public static final long ORGANISATION_BUDGET_CENTS = 200000;

    /** The selected period P of contract 7: 16 January to 31 January 2026 inclusive. */
    public static final LocalDate PERIOD_FROM = LocalDate.of(2026, 1, 16);
    public static final LocalDate PERIOD_TO = LocalDate.of(2026, 1, 31);

    private final UUID organisationId;
    private final Map<String, UUID> identifiers;

    private ContractFixture(UUID organisationId, Map<String, UUID> identifiers) {
        this.organisationId = organisationId;
        this.identifiers = Map.copyOf(identifiers);
    }

    public UUID organisationId() {
        return organisationId;
    }

    /** A stable, organisation-scoped id for a fixture key such as {@code T-PLAT} or {@code PR-3}. */
    public UUID id(String key) {
        UUID id = identifiers.get(key);
        if (id == null) {
            throw new IllegalArgumentException("no fixture entity named " + key);
        }
        return id;
    }

    public static UUID idFor(UUID organisationId, String key) {
        return UUID.nameUUIDFromBytes(
                (organisationId + ":contract-fixture:" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime utc(int year, int month, int day, int hour, int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC);
    }

    public static ContractFixture install(Connection c, UUID organisationId) throws SQLException {
        Map<String, UUID> ids = new HashMap<>();
        Installer installer = new Installer(c, organisationId, ids);
        installer.organisationAndScopes();
        installer.usersAndSeats();
        installer.tasksRunsAndUsage();
        installer.pullRequests();
        installer.coverage();
        return new ContractFixture(organisationId, ids);
    }

    /** Groups the insert order so the fixture reads like the contract's own tables. */
    private static final class Installer {

        private final Connection c;
        private final UUID org;
        private final Map<String, UUID> ids;

        Installer(Connection c, UUID org, Map<String, UUID> ids) {
            this.c = c;
            this.org = org;
            this.ids = ids;
        }

        private UUID id(String key) {
            return ids.computeIfAbsent(key, k -> idFor(org, k));
        }

        void organisationAndScopes() throws SQLException {
            Fixtures.organisation(c, org, "Contract Fixture Org");
            Fixtures.team(c, id("T-PLAT"), org, "T-PLAT", "Platform");
            Fixtures.team(c, id("T-PAY"), org, "T-PAY", "Payments");
            Fixtures.repository(c, id("R-API"), org, "R-API", "repo-api");
            Fixtures.repository(c, id("R-WEB"), org, "R-WEB", "repo-web");

            // Contract 7 declares one budget: the organisation's February 2026 allowance of
            // 200000 cents. No team budget exists, which is why both team scopes report
            // "no budget configured" rather than a forecast (contract 6.1, case B6). Omitting this
            // row would silently drop the one budget evaluation the approved accounting counts.
            Fixtures.budget(c, id("budget-org-2026-02"), org, null, "budget-org-2026-02",
                    LocalDate.of(2026, 2, 1), ORGANISATION_BUDGET_CENTS);
        }

        /** Contract 7: T-PLAT is u1, u2, u5; T-PAY is u3, u4, u6. Capacity is a fixed six seats. */
        void usersAndSeats() throws SQLException {
            user("u1", "T-PLAT");
            user("u2", "T-PLAT");
            user("u5", "T-PLAT");
            user("u3", "T-PAY");
            user("u4", "T-PAY");
            user("u6", "T-PAY");
            for (String user : new String[] {"u1", "u2", "u3", "u4", "u5", "u6"}) {
                Fixtures.seat(c, id("seat-" + user), org, "seat-" + user, id(user));
            }
        }

        private void user(String key, String teamKey) throws SQLException {
            Fixtures.user(c, id(key), org, id(teamKey), key, key + "." + org, key,
                    "{noop}fixture", "ADMIN", false);
        }

        /**
         * Contract 7.1 and 7.3. Task status follows its sole run (contract 1.3.2), so each run
         * mirrors its task and a running task has no end timestamp.
         */
        void tasksRunsAndUsage() throws SQLException {
            task("T10", "u1", "T-PLAT", "R-API", "feature", utc(2025, 12, 31, 0, 0),
                    "completed", utc(2026, 1, 1, 0, 30), null, utc(2026, 1, 1, 0, 30), 700);
            task("T1", "u1", "T-PLAT", "R-API", "bugfix", utc(2026, 1, 5, 9, 0),
                    "completed", utc(2026, 1, 5, 9, 40), null, utc(2026, 1, 5, 9, 40), 1200);
            task("T2", "u1", "T-PLAT", "R-API", "feature", utc(2026, 1, 8, 11, 0),
                    "completed", utc(2026, 1, 8, 11, 30), null, utc(2026, 1, 8, 11, 30), 900);
            task("T3", "u2", "T-PLAT", "R-WEB", "tests", utc(2026, 1, 10, 14, 0),
                    "failed", utc(2026, 1, 10, 14, 20), "tests_failed", utc(2026, 1, 10, 14, 20), 500);
            task("T9", "u5", "T-PLAT", "R-API", "research", utc(2026, 1, 12, 9, 0),
                    "completed", utc(2026, 1, 12, 9, 15), null, utc(2026, 1, 12, 9, 15), 200);
            task("T4", "u2", "T-PLAT", "R-WEB", "refactor", utc(2026, 1, 15, 10, 0),
                    "completed", utc(2026, 1, 15, 10, 50), null, utc(2026, 1, 15, 10, 50), 1400);
            task("T5", "u3", "T-PAY", "R-API", "feature", utc(2026, 1, 18, 8, 0),
                    "cancelled", utc(2026, 1, 18, 8, 10), null, utc(2026, 1, 18, 8, 10), 150);
            task("T6", "u3", "T-PAY", "R-API", "bugfix", utc(2026, 1, 22, 13, 0),
                    "failed", utc(2026, 1, 22, 13, 25), "sandbox_denied",
                    utc(2026, 1, 22, 13, 25), 350);
            task("T7", "u4", "T-PAY", "R-WEB", "feature", utc(2026, 1, 30, 16, 0),
                    "completed", utc(2026, 1, 30, 16, 45), null, utc(2026, 1, 30, 16, 45), 1800);
            // T8 is still running, and its 400c is metered on 2 February -- inside the dataset but
            // outside the selected period. That gap is the point of the boundary case.
            task("T8", "u4", "T-PAY", "R-WEB", "feature", utc(2026, 1, 31, 23, 30),
                    null, null, null, utc(2026, 2, 2, 10, 0), 400);
        }

        private void task(String key, String userKey, String teamKey, String repoKey, String type,
                OffsetDateTime createdAt, String terminalStatus, OffsetDateTime terminalAt,
                String failureReason, OffsetDateTime meteredAt, long cents) throws SQLException {
            Fixtures.task(c, id(key), org, id(teamKey), id(repoKey), id(userKey), key, type,
                    createdAt, terminalStatus, terminalAt);

            String runStatus = terminalStatus == null ? "running" : terminalStatus;
            Fixtures.run(c, id("run-" + key), org, id(key), "run-" + key, 1, createdAt,
                    terminalAt, runStatus, failureReason);
            Fixtures.usage(c, id("usage-" + key), org, id("run-" + key), "usage-" + key,
                    meteredAt, cents);
        }

        /** Contract 7.2. Every PR targets its repository's default branch (contract 1.3.9). */
        void pullRequests() throws SQLException {
            pullRequest("PR-5", "T10", utc(2026, 1, 1, 1, 0), "merged", utc(2026, 1, 2, 12, 0));
            pullRequest("PR-1", "T1", utc(2026, 1, 5, 9, 45), "merged", utc(2026, 1, 7, 10, 0));
            pullRequest("PR-2", "T2", utc(2026, 1, 8, 11, 35), "closed_unmerged",
                    utc(2026, 1, 12, 8, 0));
            pullRequest("PR-3", "T4", utc(2026, 1, 15, 11, 0), "merged", utc(2026, 1, 20, 9, 0));
            // Merged after the period ends but before dataThrough: counted by the funnel's cohort,
            // not by the period's merged-PR card (contract 4).
            pullRequest("PR-4", "T7", utc(2026, 1, 30, 17, 0), "merged", utc(2026, 2, 3, 9, 0));
        }

        private void pullRequest(String key, String taskKey, OffsetDateTime openedAt,
                String terminalState, OffsetDateTime terminalAt) throws SQLException {
            Fixtures.pullRequest(c, id(key), org, id(taskKey), id("run-" + taskKey), key, "main",
                    openedAt, terminalState, terminalAt);
        }

        /**
         * Publication plus a complete row for every source on every day of the interval. Without
         * these rows every metric would correctly report unavailable, so they are as much a part of
         * the fixture as the business data.
         */
        void coverage() throws SQLException {
            Fixtures.publication(c, org, DATA_AVAILABLE_FROM, DATA_THROUGH, REVISION);
            for (LocalDate day = DATA_AVAILABLE_FROM.toLocalDate();
                    day.isBefore(DATA_THROUGH.toLocalDate()); day = day.plusDays(1)) {
                for (LogicalSource source : LogicalSource.values()) {
                    Fixtures.sourceDay(c, org, source.wireName(), day, true);
                }
            }
        }
    }
}
