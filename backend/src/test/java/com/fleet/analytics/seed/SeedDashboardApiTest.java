package com.fleet.analytics.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fleet.analytics.FleetAnalyticsApplication;
import com.fleet.analytics.support.OpenApiContract;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real M3 HTTP/filter-chain integration over M4's dataset, in a dedicated database. */
@SpringBootTest(classes = FleetAnalyticsApplication.class)
@ActiveProfiles({"test", "demo"})
@DirtiesContext
@Testcontainers
class SeedDashboardApiTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        POSTGRES.start();
        var source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        try {
            new SeedInstaller(source).install();
        } catch (SQLException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private WebApplicationContext context;
    @Autowired private DataSource source;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void http() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 30, 90})
    void latestPresetsReconcileWithEachTenantsLedgerAndPublishCompleteWindows(int days) throws Exception {
        for (String tenant : List.of("a", "b")) {
            String username = tenant.equals("a") ? "admin" : "admin123";
            String password = tenant.equals("a") ? "123456" : "1234567";
            String token = token(username, password);
            JsonNode identity = successful(getResult(token, "/api/v1/analytics/context", Map.of()));
            assertThat(identity.path("licensedSeats").asInt()).isEqualTo(tenant.equals("a") ? 56 : 10);
            assertThat(identity.path("teams")).hasSize(tenant.equals("a") ? 7 : 2);
            assertThat(identity.path("repositories")).hasSize(tenant.equals("a") ? 14 : 20);
            var selection = Map.of("from", DemoDataset.THROUGH.minusDays(days).toLocalDate().toString(),
                    "to", "2026-08-31");
            JsonNode response = dashboard(token, selection);
            for (JsonNode window : response.path("coverageWindows")) {
                assertThat(window.path("incompleteSources")).isEmpty();
            }
            // OpenAPI permits any RFC 3339 offset; verify the UTC instant, not its spelling.
            assertThat(OffsetDateTime.parse(response.at("/coverage/dataThrough").asString()).toInstant())
                    .isEqualTo(DemoDataset.THROUGH.toInstant());
            var db = DSL.using(source, SQLDialect.POSTGRES);
            long spend = db.fetchOne("""
                    select sum(u.cost_cents) from usage_record u join run r on r.id=u.run_id
                    join task t on t.id=r.task_id where t.org_id=? and u.metered_at>=?::timestamptz
                    and u.metered_at<?::timestamptz
                    """, DemoDataset.id(tenant, "organisation", "root"),
                    DemoDataset.THROUGH.minusDays(days), DemoDataset.THROUGH).get(0, Long.class);
            long trendSpend = 0;
            for (JsonNode point : response.at("/trends/spendPerDay/points")) {
                trendSpend += point.path("spendCents").asLong();
            }
            assertThat(trendSpend).isEqualTo(spend);
            assertThat(response.at("/trends/spendPerDay/points")).hasSize(days);
            long merged = db.fetchOne("""
                    select count(*) from pull_request p join task t on t.id=p.task_id
                    join repository r on r.id=t.repo_id where t.org_id=?
                    and t.task_type in ('bugfix','feature','refactor','tests','dependency_update')
                    and p.target_branch=r.default_branch and p.terminal_state='merged'
                    and p.terminal_at>=?::timestamptz and p.terminal_at<?::timestamptz
                    """, DemoDataset.id(tenant, "organisation", "root"),
                    DemoDataset.THROUGH.minusDays(days), DemoDataset.THROUGH).get(0, Long.class);
            assertThat(response.at("/kpis/mergedPrs/display/value").asString()).isEqualTo(Long.toString(merged));
            String foreign = tenant.equals("a") ? "b" : "a";
            for (int team = 0; team < (foreign.equals("a") ? 7 : 2); team++) {
                assertThat(response.toString()).doesNotContain(DemoDataset.id(foreign, "team", "" + team).toString());
            }
        }
    }

    @Test
    void paymentsBudgetRanksAboveRepoApiFailureAndBothLinksLeadToTheEvaluatedPopulation() throws Exception {
        String token = token("admin", "123456");
        Map<String, String> starting = Map.of("teamId", id("a", "team", 0));
        JsonNode response = dashboard(token, starting);
        assertThat(response.at("/selection/from").asString()).isEqualTo("2026-08-02");
        JsonNode findings = response.at("/attention/findings");
        assertThat(findings.size()).isLessThanOrEqualTo(3);
        JsonNode budget = findings.get(0);
        assertThat(budget.path("ruleType").asString()).isEqualTo("budget_risk");
        assertThat(budget.path("severity").asString()).isEqualTo("HIGH");
        assertThat(budget.at("/evidence/monthToDateSpendCents").asLong()).isEqualTo(173224);
        assertThat(budget.at("/evidence/budgetCents").asLong()).isEqualTo(140000);
        JsonNode failure = find(findings, "task_failure_spike", id("a", "repository", 0));
        assertThat(failure.at("/evidence/failedTasks").asLong()).isEqualTo(96);
        assertThat(failure.at("/evidence/currentRate/value").asString()).isEqualTo("40.0");
        assertThat(failure.at("/evidence/baselineRate/value").asString()).isEqualTo("10.3");
        assertThat(failure.at("/evidence/failureReasons/agent").asLong()).isEqualTo(96);

        JsonNode budgetLink = budget.path("link");
        assertThat(budgetLink.path("section").asString()).isEqualTo("spendTrend");
        assertThat(budgetLink.has("repositoryId")).isTrue();
        assertThat(budgetLink.path("repositoryId").isNull()).isTrue();
        JsonNode budgetView = dashboard(token, apply(response.path("selection"), budgetLink));
        assertThat(budgetView.at("/selection/from").asString()).isEqualTo("2026-08-01");
        assertThat(budgetView.at("/selection/teamId").asString()).isEqualTo(id("a", "team", 0));
        assertThat(budgetView.at("/attention/findings/0/evidence/monthToDateSpendCents").asLong()).isEqualTo(173224);

        // A non-budget patch preserves the other dimension and dates by omitting their keys.
        JsonNode link = failure.path("link");
        assertThat(link.has("from")).isFalse();
        assertThat(link.has("to")).isFalse();
        assertThat(link.has("teamId")).isFalse();
        assertThat(link.path("focusRowId").asString()).isEqualTo(id("a", "repository", 0));
        JsonNode narrowed = dashboard(token, apply(response.path("selection"), link));
        assertThat(narrowed.at("/selection/teamId").asString()).isEqualTo(id("a", "team", 0));
        assertThat(narrowed.at("/selection/from")).isEqualTo(response.at("/selection/from"));
        assertThat(narrowed.at("/comparison/grouping").asString()).isEqualTo("repositories");
        assertThat(narrowed.at("/comparison/rows")).hasSize(1);
        assertThat(narrowed.at("/comparison/rows/0/scopeId").asString()).isEqualTo(id("a", "repository", 0));
        assertThat(narrowed.at("/comparison/rows/0/terminalTaskCount").asLong()).isEqualTo(240);
        assertThat(dashboard(token, starting)).isEqualTo(response);
    }

    @ParameterizedTest
    @CsvSource({"a,admin,123456,viewer,demo-viewer-a", "b,admin123,1234567,viewer123,demo-viewer-b"})
    void viewerGetsTheSameFindingsAndCountsWithoutAnyDomainInTheBody(
            String tenant, String admin, String adminPassword, String viewer, String viewerPassword) throws Exception {
        Map<String, String> filters = tenant.equals("a")
                ? Map.of("teamId", id("a", "team", 0), "repositoryId", id("a", "repository", 0))
                : Map.of();
        JsonNode privileged = dashboard(token(admin, adminPassword), filters);
        JsonNode redacted = dashboard(token(viewer, viewerPassword), filters);
        var expected = privileged.deepCopy();
        int frictionCount = 0;
        for (JsonNode finding : expected.at("/attention/findings")) {
            if (finding.path("ruleType").asString().equals("network_policy_friction")) {
                assertThat(finding.at("/evidence/distinctTasks").asLong()).isGreaterThanOrEqualTo(5);
                assertThat(finding.at("/evidence/distinctUsers").asLong()).isGreaterThanOrEqualTo(3);
                assertThat(finding.at("/evidence/domain").asString()).endsWith(".example");
                ((ObjectNode) finding.path("evidence")).remove("domain");
                frictionCount++;
            }
        }
        assertThat(frictionCount).isPositive();
        assertThat(redacted).isEqualTo(expected);
        assertThat(redacted.toString()).doesNotContain("packages.northstar.example", "registry.harbor.example",
                "Packages.Northstar.Example.", "Registry.Harbor.Example.", "\"domain\"");
    }

    @Test
    void emptyLowSampleZeroOutcomeAndUnknownBaselineStatesRemainDistinct() throws Exception {
        String token = token("admin", "123456");
        JsonNode empty = dashboard(token, Map.of("teamId", id("a", "team", 0), "repositoryId", id("a", "repository", 13)));
        assertThat(empty.at("/kpis/mergedPrs/display/value").asString()).isEqualTo("0");
        assertThat(empty.at("/kpis/taskCompletionRate/state").asString()).isEqualTo("no_denominator");
        JsonNode sparse = dashboard(token, Map.of("repositoryId", id("a", "repository", 12)));
        assertThat(sparse.at("/kpis/taskCompletionRate/state").asString()).isEqualTo("zero_outcome");
        assertThat(sparse.at("/comparison/rows/0/taskCompletionRate/comparison/state").asString()).isEqualTo("insufficient_sample");
        JsonNode retired = dashboard(token, Map.of("repositoryId", id("a", "repository", 13)));
        assertThat(retired.at("/kpis/terminalMergeRate/state").asString()).isEqualTo("zero_outcome");
        assertThat(retired.at("/kpis/costPerMergedPr/state").asString()).isEqualTo("no_denominator");
        JsonNode firstDay = dashboard(token, Map.of("from", "2026-03-05", "to", "2026-03-05", "teamId", id("a", "team", 0)));
        assertThat(firstDay.at("/trends/spendPerDay/points/0/spendCents").asLong()).isZero();
        assertThat(firstDay.at("/kpis/mergedPrs/comparison/state").asString()).isEqualTo("no_baseline");
        assertThat(firstDay.at("/attention/findings/0/ruleType").asString()).isEqualTo("budget_risk");
        JsonNode labs = dashboard(token, Map.of("teamId", id("a", "team", 6)));
        JsonNode limit = find(labs.at("/attention/limits"), "budget_risk", id("a", "team", 6));
        assertThat(limit.path("state").asString()).isEqualTo("not_evaluated");
    }

    @Test
    void foreignFiltersAreRejectedWithoutLeakingTenantIdentifiers() throws Exception {
        String token = token("admin", "123456");
        MvcResult foreign = getResult(token, "/api/v1/analytics/dashboard", Map.of("teamId", id("b", "team", 0)));
        MvcResult unknown = getResult(token, "/api/v1/analytics/dashboard", Map.of("teamId", UUID.randomUUID().toString()));
        assertThat(foreign.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        OpenApiContract.assertResponseValid(foreign);
        assertThat(foreign.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
    }

    private String token(String username, String password) throws Exception {
        var login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password)))).andReturn();
        return successful(login).path("accessToken").asString();
    }

    private JsonNode dashboard(String token, Map<String, String> filters) throws Exception {
        return successful(getResult(token, "/api/v1/analytics/dashboard", filters));
    }

    private MvcResult getResult(String token, String path, Map<String, String> filters) throws Exception {
        var request = get(path).header("Authorization", "Bearer " + token);
        filters.forEach(request::param);
        return mvc.perform(request).andReturn();
    }

    private JsonNode successful(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        OpenApiContract.assertValid(result);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode find(JsonNode items, String rule, String scope) {
        for (JsonNode item : items) {
            if (item.path("ruleType").asString().equals(rule) && item.path("scopeId").asString().equals(scope)) return item;
        }
        throw new AssertionError("Missing " + rule + " for " + scope);
    }

    private static String id(String tenant, String entity, int sourceId) {
        return DemoDataset.id(tenant, entity, Integer.toString(sourceId)).toString();
    }

    private static Map<String, String> apply(JsonNode selection, JsonNode link) {
        Map<String, String> filters = new LinkedHashMap<>();
        for (String key : List.of("from", "to", "teamId", "repositoryId", "grouping")) {
            JsonNode value = link.has(key) ? link.get(key) : selection.get(key);
            if (value != null && !value.isNull()) filters.put(key, value.asString());
        }
        return filters;
    }
}
