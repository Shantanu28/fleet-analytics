package com.fleet.analytics.seed;

import static com.fleet.analytics.data.jooq.Tables.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;

/** Fixed synthetic populations; no metrics or dashboard responses are stored here. */
final class DemoDataset {
    static final String DATASET = "fleet-demo";
    static final String VERSION = "1";
    static final long SEED = 20260907L;
    static final OffsetDateTime THROUGH = OffsetDateTime.parse("2026-09-01T00:00:00Z");
    static final OffsetDateTime FROM = THROUGH.minusDays(180);
    static final List<Table<?>> TABLES = List.of(ORGANISATION, TEAM, REPOSITORY, APP_USER,
            SEAT_LICENCE, BUDGET, TASK, RUN, PULL_REQUEST, USAGE_RECORD, DENIAL_EVENT,
            SOURCE_DAY_COVERAGE);
    static final List<String> SOURCES = List.of("tasks", "runs", "pull_requests", "repositories",
            "usage", "denials", "budgets", "seats");
    private static final DSLContext RECORDS = DSL.using(SQLDialect.POSTGRES);
    private final Map<Table<?>, List<Record>> rows = new LinkedHashMap<>();

    private DemoDataset() {
        TABLES.forEach(t -> rows.put(t, new ArrayList<>()));
    }

    static DemoDataset generate() {
        var data = new DemoDataset();
        data.tenant("a", 7, 8, 14, 20000);
        data.tenant("b", 2, 5, 20, 2000);
        return data;
    }

    static UUID id(String tenant, String entity, String sourceId) {
        return UUID.nameUUIDFromBytes((DATASET + ":" + VERSION + ":" + SEED + ":" + tenant
                + ":" + entity + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
    }

    List<Record> rows(Table<?> table) {
        return List.copyOf(rows.get(table));
    }

    String checksum() {
        return SeedCanonical.checksum(rows);
    }

    String counts() {
        return SeedCanonical.counts(rows);
    }

    private void add(Table<?> table, Record row) {
        rows.get(table).add(row);
    }

    private void tenant(String tenant, int teams, int perTeam, int repositories, int tasks) {
        UUID org = id(tenant, "organisation", "root");
        boolean a = tenant.equals("a");
        add(ORGANISATION, RECORDS.newRecord(ORGANISATION).with(ORGANISATION.ID, org)
                .with(ORGANISATION.NAME, a ? "Northstar Engineering" : "Harbor Labs")
                .with(ORGANISATION.CREATED_AT, FROM));
        String[] names = a ? new String[] {"Payments", "Platform", "Commerce", "Identity", "Data", "Experience", "Labs"}
                : new String[] {"Workbench", "Delivery"};
        for (int t = 0; t < teams; t++) {
            add(TEAM, RECORDS.newRecord(TEAM).with(TEAM.ID, id(tenant, "team", "" + t))
                    .with(TEAM.ORG_ID, org).with(TEAM.SOURCE, "demo-seed")
                    .with(TEAM.SOURCE_ENTITY_ID, "" + t).with(TEAM.SOURCE_VERSION, 0L)
                    .with(TEAM.NAME, names[t]));
        }
        for (int r = 0; r < repositories; r++) {
            String name = a ? switch (r) {
                case 0 -> "repo-api";
                case 1 -> "repo-web";
                case 12 -> "repo-experimental";
                case 13 -> "repo-retired";
                default -> "repo-service-" + r;
            } : "harbor-module-" + r;
            add(REPOSITORY, RECORDS.newRecord(REPOSITORY)
                    .with(REPOSITORY.ID, id(tenant, "repository", "" + r))
                    .with(REPOSITORY.ORG_ID, org).with(REPOSITORY.SOURCE, "demo-seed")
                    .with(REPOSITORY.SOURCE_ENTITY_ID, "" + r).with(REPOSITORY.SOURCE_VERSION, 0L)
                    .with(REPOSITORY.NAME, name).with(REPOSITORY.DEFAULT_BRANCH, "main"));
        }
        for (int u = 0; u < teams * perTeam; u++) {
            UUID user = id(tenant, "app_user", "" + u);
            String username = u == 0 ? (a ? "admin" : "admin123")
                    : u == 1 ? (a ? "viewer" : "viewer123") : "demo-" + tenant + "-engineer-" + u;
            // Hashes are supplied only during installation, with normal random salts.
            add(APP_USER, RECORDS.newRecord(APP_USER).with(APP_USER.ID, user)
                    .with(APP_USER.ORG_ID, org).with(APP_USER.TEAM_ID, id(tenant, "team", "" + (u / perTeam)))
                    .with(APP_USER.SOURCE, "demo-seed").with(APP_USER.SOURCE_ENTITY_ID, "" + u)
                    .with(APP_USER.SOURCE_VERSION, 0L).with(APP_USER.USERNAME, username)
                    .with(APP_USER.DISPLAY_NAME, "Engineer " + tenant.toUpperCase(Locale.ROOT) + " " + (u + 1))
                    .with(APP_USER.ROLE, u == 0 ? "ADMIN" : "VIEWER").with(APP_USER.IS_DEMO_ACCOUNT, true));
            add(SEAT_LICENCE, RECORDS.newRecord(SEAT_LICENCE)
                    .with(SEAT_LICENCE.ID, id(tenant, "seat_licence", "" + u))
                    .with(SEAT_LICENCE.ORG_ID, org).with(SEAT_LICENCE.SOURCE, "demo-seed")
                    .with(SEAT_LICENCE.SOURCE_ENTITY_ID, "" + u).with(SEAT_LICENCE.SOURCE_VERSION, 0L)
                    .with(SEAT_LICENCE.USER_ID, user).with(SEAT_LICENCE.ASSIGNED_AT, FROM));
        }
        // August is complete. Payments intentionally exceeds its allowance; other configured
        // scopes are comfortably below theirs. Labs has no budget: an unevaluated scope.
        for (int t = -1; t < teams; t++) {
            if (a && t == 6) continue;
            add(BUDGET, RECORDS.newRecord(BUDGET).with(BUDGET.ID, id(tenant, "budget", "" + t))
                    .with(BUDGET.ORG_ID, org).with(BUDGET.SOURCE, "demo-seed")
                    .with(BUDGET.SOURCE_ENTITY_ID, "" + t).with(BUDGET.SOURCE_VERSION, 0L)
                    .with(BUDGET.TEAM_ID, t < 0 ? null : id(tenant, "team", "" + t))
                    .with(BUDGET.PERIOD_MONTH, LocalDate.of(2026, 8, 1))
                    .with(BUDGET.AMOUNT_CENTS, a && t == 0 ? 140000L : t < 0 ? (a ? 2000000L : 500000L) : 300000L));
        }
        var random = new SplittableRandom(SEED + (a ? 0 : 1));
        int apiCurrent = 0;
        int apiBaseline = 0;
        for (int i = 0; i < tasks; i++) {
            // Day zero is deliberately known-empty. B is burstier and has different outcomes,
            // repository sharing, spend and non-code mix, rather than scaling A's activity.
            int day = a ? 1 + i % 179 : 1 + (i * 37 % 179);
            int team = a ? (i / 179) % teams : i % teams;
            int repo = a ? (team % 6) * 2 + (i / (179 * teams)) % 2 : (i / 2) % repositories;
            boolean low = a && i >= tasks - 6;
            boolean zero = a && i >= tasks - 46 && !low;
            if (low || zero) {
                team = 6;
                repo = low ? 12 : 13;
                day = 150 + (i % 30);
            }
            int owner = team * perTeam + random.nextInt(perTeam);
            if (day < 90 && i % 101 == 0) owner = ((team + 1) % teams) * perTeam;
            String type = i % (a ? 10 : 4) == 0 ? "research"
                    : i % (a ? 10 : 4) == 1 ? "repo_question" : "feature";
            String status = i % 20 < (a ? 16 : 13) ? "completed"
                    : i % 20 < 18 ? "failed" : i % 20 == 18 ? "cancelled" : null;
            boolean api = a && team == 0 && repo == 0;
            if (api) {
                int population = day >= 150 ? apiCurrent++ : apiBaseline++;
                type = "bugfix";
                status = population % 10 < (day >= 150 ? 4 : 1) ? "failed" : "completed";
                owner = population % perTeam;
            }
            if (low || zero) {
                type = "feature";
                status = low ? "failed" : "completed";
            }
            OffsetDateTime created = FROM.plusDays(day).plusHours(a ? 9 : 15).plusMinutes(i % 45);
            OffsetDateTime ended = status == null ? null : created.plusMinutes(30 + i % 90);
            UUID task = id(tenant, "task", "" + i);
            UUID run = id(tenant, "run", "" + i);
            add(TASK, RECORDS.newRecord(TASK).with(TASK.ID, task).with(TASK.ORG_ID, org)
                    .with(TASK.TEAM_ID, id(tenant, "team", "" + team))
                    .with(TASK.REPO_ID, id(tenant, "repository", "" + repo))
                    .with(TASK.USER_ID, id(tenant, "app_user", "" + owner))
                    .with(TASK.SOURCE, "demo-seed").with(TASK.SOURCE_ENTITY_ID, "" + i)
                    .with(TASK.SOURCE_VERSION, 0L).with(TASK.TASK_TYPE, type)
                    .with(TASK.CREATED_AT, created).with(TASK.TERMINAL_STATUS, status).with(TASK.TERMINAL_AT, ended));
            add(RUN, RECORDS.newRecord(RUN).with(RUN.ID, run).with(RUN.ORG_ID, org)
                    .with(RUN.TASK_ID, task).with(RUN.SOURCE, "demo-seed").with(RUN.SOURCE_ENTITY_ID, "" + i)
                    .with(RUN.SOURCE_VERSION, 0L).with(RUN.ATTEMPT_NO, 1).with(RUN.STARTED_AT, created)
                    .with(RUN.ENDED_AT, ended).with(RUN.RUN_STATUS, status == null ? "running" : status)
                    .with(RUN.FAILURE_REASON, "failed".equals(status) ? (api ? "tests_failed" : "timeout") : null));
            add(USAGE_RECORD, RECORDS.newRecord(USAGE_RECORD)
                    .with(USAGE_RECORD.ID, id(tenant, "usage_record", "" + i)).with(USAGE_RECORD.ORG_ID, org)
                    .with(USAGE_RECORD.RUN_ID, run).with(USAGE_RECORD.SOURCE, "demo-seed")
                    .with(USAGE_RECORD.SOURCE_ENTITY_ID, "" + i).with(USAGE_RECORD.SOURCE_VERSION, 0L)
                    .with(USAGE_RECORD.METERED_AT, created.plusMinutes(10))
                    .with(USAGE_RECORD.COST_CENTS, a ? 100L + random.nextInt(500) : 25L + random.nextInt(1500))
                    .with(USAGE_RECORD.MODEL_TIER, i % 3 == 0 ? "advanced" : "standard"));
            if ("completed".equals(status) && !type.equals("research") && !type.equals("repo_question")) {
                OffsetDateTime opened = ended.plusMinutes(5);
                OffsetDateTime resolved = opened.plusDays(2 + i % (a ? 4 : 9));
                boolean terminal = resolved.isBefore(THROUGH) && i % 11 != 0;
                if (zero) { resolved = opened.plusHours(1); terminal = true; }
                add(PULL_REQUEST, RECORDS.newRecord(PULL_REQUEST)
                        .with(PULL_REQUEST.ID, id(tenant, "pull_request", "" + i)).with(PULL_REQUEST.ORG_ID, org)
                        .with(PULL_REQUEST.TASK_ID, task).with(PULL_REQUEST.RUN_ID, run)
                        .with(PULL_REQUEST.SOURCE, "demo-seed").with(PULL_REQUEST.SOURCE_ENTITY_ID, "" + i)
                        .with(PULL_REQUEST.SOURCE_VERSION, 0L).with(PULL_REQUEST.TARGET_BRANCH, "main")
                        .with(PULL_REQUEST.OPENED_AT, opened)
                        .with(PULL_REQUEST.TERMINAL_STATE, terminal ? (zero || i % 5 == 0 ? "closed_unmerged" : "merged") : null)
                        .with(PULL_REQUEST.TERMINAL_AT, terminal ? resolved : null));
            }
            if ((api && day >= 150 && apiCurrent <= 8) || i % (a ? 97 : 41) == 0) {
                // Two repeated events per task prove raw event volume is not affected-task volume.
                for (int repeat = 0; repeat < 2; repeat++) {
                    String sourceId = i + "-" + repeat;
                    add(DENIAL_EVENT, RECORDS.newRecord(DENIAL_EVENT)
                            .with(DENIAL_EVENT.ID, id(tenant, "denial_event", sourceId)).with(DENIAL_EVENT.ORG_ID, org)
                            .with(DENIAL_EVENT.TASK_ID, task).with(DENIAL_EVENT.RUN_ID, repeat == 0 ? run : null)
                            .with(DENIAL_EVENT.SOURCE, "demo-seed").with(DENIAL_EVENT.SOURCE_ENTITY_ID, sourceId)
                            .with(DENIAL_EVENT.SOURCE_VERSION, 0L)
                            .with(DENIAL_EVENT.DOMAIN_RAW, a ? "Packages.Northstar.Example." : "Registry.Harbor.Example.")
                            .with(DENIAL_EVENT.DOMAIN_NORMALISED, a ? "packages.northstar.example" : "registry.harbor.example")
                            .with(DENIAL_EVENT.OCCURRED_AT, created.plusMinutes(2 + repeat)));
                }
            }
        }
        for (String source : SOURCES) {
            for (int day = 0; day < 180; day++) {
                add(SOURCE_DAY_COVERAGE, RECORDS.newRecord(SOURCE_DAY_COVERAGE)
                        .with(SOURCE_DAY_COVERAGE.ORG_ID, org).with(SOURCE_DAY_COVERAGE.LOGICAL_SOURCE, source)
                        .with(SOURCE_DAY_COVERAGE.DAY, FROM.toLocalDate().plusDays(day))
                        .with(SOURCE_DAY_COVERAGE.IS_COMPLETE, true));
            }
        }
    }
}
