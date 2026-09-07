package com.fleet.analytics.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.jooq.DSLContext;

/** Independent SQL checks of contract 1.3, 6 and approved populations; no M3 code is called. */
final class SeedPopulationAssertions {
    private SeedPopulationAssertions() {}

    static void verify(DSLContext db) {
        assertThat(db.fetch("select o.name, count(distinct u.id) users, count(distinct s.id) seats, count(distinct t.id) teams, count(distinct r.id) repos from organisation o join app_user u on u.org_id=o.id join seat_licence s on s.user_id=u.id join team t on t.org_id=o.id join repository r on r.org_id=o.id group by o.name order by o.name")
                .map(r -> r.get("name") + ":" + r.get("users") + ":" + r.get("seats") + ":" + r.get("teams") + ":" + r.get("repos")))
                .containsExactly("Harbor Labs:10:10:2:20", "Northstar Engineering:56:56:7:14");
        assertThat(db.fetch("select count(*) n from app_user group by org_id, team_id").getValues("n", Integer.class))
                .containsExactlyInAnyOrder(8, 8, 8, 8, 8, 8, 8, 5, 5);
        zero(db, "select count(*) from (select t.id from task t left join run r on r.task_id=t.id group by t.id having count(r.id)<>1) x");
        zero(db, "select count(*) from task t join run r on r.task_id=t.id where t.org_id<>r.org_id or t.created_at<>r.started_at or t.terminal_at is distinct from r.ended_at or coalesce(t.terminal_status,'running')<>r.run_status or r.attempt_no<>1");
        zero(db, "select count(*) from pull_request p join task t on t.id=p.task_id join run r on r.id=p.run_id join repository repo on repo.id=t.repo_id where t.terminal_status<>'completed' or p.opened_at<t.terminal_at or p.target_branch<>repo.default_branch or r.task_id<>t.id or p.org_id<>t.org_id or p.org_id<>r.org_id or p.terminal_at<p.opened_at or t.task_type in ('research','repo_question')");
        zero(db, "select count(*) from task t left join seat_licence s on s.org_id=t.org_id and s.user_id=t.user_id where s.id is null");
        zero(db, "select count(*) from seat_licence where assigned_at<>'2026-03-05T00:00Z' or user_id is null");
        zero(db, "select count(*) from app_user where not is_demo_account");
        assertThat(number(db, "select count(*) from app_user where role='ADMIN'")).isEqualTo(2);
        assertThat(number(db, "select count(*) from task t join app_user u on u.id=t.user_id where t.team_id<>u.team_id")).isPositive();
        zero(db, "select count(*) from denial_event d join task t on t.id=d.task_id left join run r on r.id=d.run_id where d.org_id<>t.org_id or (d.run_id is not null and (r.task_id<>t.id or r.org_id<>d.org_id)) or d.domain_normalised<>lower(rtrim(d.domain_raw,'.'))");
        zero(db, "select count(*) from usage_record u join run r on r.id=u.run_id where u.org_id<>r.org_id or u.cost_cents<0");
        for (String timestamp : new String[] {"task.created_at", "task.terminal_at", "run.started_at", "run.ended_at", "pull_request.opened_at", "pull_request.terminal_at", "usage_record.metered_at", "denial_event.occurred_at"}) {
            String[] names = timestamp.split("\\.");
            zero(db, "select count(*) from " + names[0] + " where " + names[1] + " < '2026-03-05T00:00Z' or " + names[1] + " >= '2026-09-01T00:00Z'");
        }
        assertThat(number(db, "select count(*) from source_day_coverage where is_complete")).isEqualTo(2880);
        assertThat(number(db, "select count(*) from source_day_coverage where day='2026-03-05' and is_complete")).isEqualTo(16);
        zero(db, "select count(*) from task where created_at<'2026-03-06T00:00Z'");
        zero(db, "select count(*) from task t join team tm on tm.id=t.team_id join repository r on r.id=t.repo_id where tm.name='Payments' and r.name='repo-retired'");
        assertThat(number(db, "select count(*) from task t join repository r on r.id=t.repo_id where r.name='repo-experimental' and t.terminal_status='failed'")).isEqualTo(6);
        assertThat(number(db, "select count(*) from pull_request p join task t on t.id=p.task_id join repository r on r.id=t.repo_id where r.name='repo-retired' and p.terminal_state='closed_unmerged'")).isEqualTo(40);
        zero(db, "select count(*) from pull_request p join task t on t.id=p.task_id join repository r on r.id=t.repo_id where r.name='repo-retired' and p.terminal_state='merged'");
        assertThat(number(db, "select count(*) from pull_request p join task t on t.id=p.task_id where p.terminal_state='merged' and t.created_at<'2026-08-02T00:00Z' and p.terminal_at>='2026-08-02T00:00Z'")).isPositive();
        assertThat(number(db, "select count(*) from usage_record u join run r on r.id=u.run_id join task t on t.id=r.task_id where t.task_type in ('research','repo_question') and u.cost_cents>0")).isPositive();
        assertThat(number(db, "select count(*) from denial_event d join task t on t.id=d.task_id where t.task_type in ('research','repo_question')")).isPositive();
        // Published coverage contains each latest preset, equal-length comparison and trailing
        // failure baseline. This makes no claim about all older custom comparisons.
        for (int days : new int[] {7, 30, 90}) {
            assertThat(DemoDataset.THROUGH.minusDays(days * 2L)).isAfterOrEqualTo(DemoDataset.FROM);
            assertThat(DemoDataset.THROUGH.minusDays(days + 28L)).isAfterOrEqualTo(DemoDataset.FROM);
            var counts = db.fetchOne("""
                select count(*) filter(where t.terminal_at>=?::timestamptz) current_n,
                       count(*) filter(where t.terminal_at>=?::timestamptz and t.terminal_status='failed') current_failed,
                       count(*) filter(where t.terminal_at<?::timestamptz) baseline_n,
                       count(*) filter(where t.terminal_at<?::timestamptz and t.terminal_status='failed') baseline_failed
                  from task t join team tm on tm.id=t.team_id join repository r on r.id=t.repo_id
                 where tm.name='Payments' and r.name='repo-api' and t.task_type in ('bugfix','feature','refactor','tests','dependency_update')
                   and t.terminal_status in ('completed','failed') and t.terminal_at>=?::timestamptz and t.terminal_at<?::timestamptz
                """, DemoDataset.THROUGH.minusDays(days), DemoDataset.THROUGH.minusDays(days), DemoDataset.THROUGH.minusDays(days), DemoDataset.THROUGH.minusDays(days), DemoDataset.THROUGH.minusDays(days + 28L), DemoDataset.THROUGH);
            long n = counts.get("current_n", Long.class), failed = counts.get("current_failed", Long.class);
            long b = counts.get("baseline_n", Long.class), baselineFailed = counts.get("baseline_failed", Long.class);
            assertThat(n).isGreaterThanOrEqualTo(20);
            assertThat(b).isGreaterThanOrEqualTo(20);
            if (days == 30) {
                assertThat(n).isEqualTo(240);
                assertThat(failed).isEqualTo(96);
                assertThat(b).isEqualTo(224);
                assertThat(baselineFailed).isEqualTo(23);
                assertThat(100 * (failed * b - baselineFailed * n))
                        .as("repo-api failure rise at the documented starting filters")
                        .isGreaterThanOrEqualTo(8 * n * b);
            }
        }
        var friction = db.fetchOne("select count(*) events, count(distinct d.task_id) tasks, count(distinct t.user_id) users from denial_event d join task t on t.id=d.task_id join team tm on tm.id=t.team_id join repository r on r.id=t.repo_id where tm.name='Payments' and r.name='repo-api' and d.occurred_at>='2026-08-02T00:00Z' group by d.domain_normalised");
        assertThat(friction.get("tasks", Long.class)).isGreaterThanOrEqualTo(5);
        assertThat(friction.get("users", Long.class)).isGreaterThanOrEqualTo(3);
        assertThat(friction.get("events", Long.class)).isGreaterThan(friction.get("tasks", Long.class));
        var budgets = db.fetch("""
            select coalesce(tm.name, o.name) scope, b.amount_cents,
                coalesce((select sum(u.cost_cents) from usage_record u join run r on r.id=u.run_id join task t on t.id=r.task_id
                 where t.org_id=b.org_id and (b.team_id is null or t.team_id=b.team_id)
                 and u.metered_at>='2026-08-01T00:00Z' and u.metered_at<'2026-09-01T00:00Z'),0) mtd
            from budget b join organisation o on o.id=b.org_id left join team tm on tm.id=b.team_id
            """);
        for (var budget : budgets) {
            // 31 elapsed days / 31 days in August: forecast equals actual integer MTD spend.
            var spend = budget.get("mtd", BigDecimal.class);
            var limit = budget.get("amount_cents", BigDecimal.class);
            if (budget.get("scope").equals("Payments")) {
                assertThat(spend.multiply(BigDecimal.valueOf(100))).isGreaterThan(limit.multiply(BigDecimal.valueOf(120)));
            } else assertThat(spend).isLessThan(limit);
        }
        zero(db, "select count(*) from budget b join team t on t.id=b.team_id where t.name='Labs'");
    }

    private static long number(DSLContext db, String sql) {
        return db.fetchOne(sql).get(0, Long.class);
    }
    private static void zero(DSLContext db, String sql) {
        assertThat(number(db, sql)).as(sql).isZero();
    }
}
