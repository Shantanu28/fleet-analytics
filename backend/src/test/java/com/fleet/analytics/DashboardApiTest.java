package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fleet.analytics.security.PasswordEncoderFactory;
import com.fleet.analytics.support.ContractFixture;
import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.IntegrationTestBase;
import com.fleet.analytics.support.OpenApiContract;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The dashboard endpoint over the real filter chain, database and OpenAPI validator.
 *
 * <p>Expected values are the metrics contract's worked figures (8.1 to 8.4), asserted on the
 * serialized body — so this proves the whole path, not just that the calculators agree with
 * themselves.
 */
class DashboardApiTest extends IntegrationTestBase {

    private static final UUID ORG_A = UUID.fromString("da000000-0000-0000-0000-0000000000a1");
    private static final UUID ORG_B = UUID.fromString("db000000-0000-0000-0000-0000000000b1");

    private static ContractFixture fixtureA;
    private static ContractFixture fixtureB;

    private final ObjectMapper json = new ObjectMapper();

    /** Two tenants holding the same fixture, so isolation is provable on returned data. */
    @BeforeAll
    static void install() throws SQLException {
        String hash = PasswordEncoderFactory.create().encode("pw");
        try (Connection c = connection()) {
            fixtureA = ContractFixture.install(c, ORG_A);
            fixtureB = ContractFixture.install(c, ORG_B);
            Fixtures.user(c, UUID.randomUUID(), ORG_A, fixtureA.id("T-PLAT"), "admin-a",
                    "admin.a", "Admin A", hash, "ADMIN", false);
            Fixtures.user(c, UUID.randomUUID(), ORG_B, fixtureB.id("T-PLAT"), "admin-b",
                    "admin.b", "Admin B", hash, "ADMIN", false);
        }
    }

    private String token(String username) throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}")).andReturn();
        return json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private MvcResult request(String username, String... params) throws Exception {
        var builder = get("/api/v1/analytics/dashboard")
                .header("Authorization", "Bearer " + token(username));
        for (int i = 0; i < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return mvc.perform(builder).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode contractPeriod(String... extra) throws Exception {
        String[] params = new String[extra.length + 4];
        params[0] = "from";
        params[1] = ContractFixture.PERIOD_FROM.toString();
        params[2] = "to";
        params[3] = ContractFixture.PERIOD_TO.toString();
        System.arraycopy(extra, 0, params, 4, extra.length);
        MvcResult result = request("admin.a", params);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        OpenApiContract.assertValid(result);
        return body(result);
    }

    // --- Successful requests -----------------------------------------------------------------------

    @Test
    void theDefaultRequestReturnsEveryDocumentedSection() throws Exception {
        MvcResult result = request("admin.a");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        OpenApiContract.assertValid(result);
        assertThat(body(result).propertyNames()).contains(
                "coverage", "selection", "coverageWindows", "kpis", "funnel", "trends",
                "comparison", "attention");
    }

    /** With no dates, the last 30 complete days ending at dataThrough. */
    @Test
    void theDefaultSelectionIsTheLastThirtyCompleteDays() throws Exception {
        JsonNode selection = body(request("admin.a")).get("selection");

        assertThat(selection.get("from").asString()).isEqualTo("2026-01-05");
        assertThat(selection.get("to").asString()).isEqualTo("2026-02-03");
        assertThat(selection.get("observationCutoff").asString()).isEqualTo("2026-02-03");
        // Required and nullable: present, carrying null, so no client has to guess.
        assertThat(selection.get("teamId").isNull()).isTrue();
        assertThat(selection.get("repositoryId").isNull()).isTrue();
    }

    @Test
    void theContractFixtureRangeReproducesTheWorkedKpis() throws Exception {
        JsonNode kpis = contractPeriod().get("kpis");

        assertThat(kpis.get("mergedPrs").get("display").get("value").asString()).isEqualTo("1");
        assertThat(kpis.get("mergedPrs").get("comparison").get("display").get("value").asString())
                .isEqualTo("-50.0");
        assertThat(kpis.get("terminalMergeRate").get("display").get("value").asString())
                .isEqualTo("100.0");
        assertThat(kpis.get("costPerMergedPr").get("display").get("value").asString())
                .isEqualTo("23.00");
        assertThat(kpis.get("taskCompletionRate").get("display").get("value").asString())
                .isEqualTo("50.0");
        assertThat(kpis.get("seats").get("display").get("value").asString()).isEqualTo("2");
        assertThat(kpis.get("seats").get("licensedSeats").asLong()).isEqualTo(6);
        assertThat(kpis.get("seats").get("utilisation").get("display").get("value").asString())
                .isEqualTo("33.3");
    }

    /** An unavailable display is omitted, and its state and reason are present in its place. */
    @Test
    void aSuppressedComparisonOmitsItsDisplayAndStatesItsReason() throws Exception {
        JsonNode comparison =
                contractPeriod().get("kpis").get("terminalMergeRate").get("comparison");

        assertThat(comparison.has("display")).isFalse();
        assertThat(comparison.get("state").asString()).isEqualTo("insufficient_sample");
        assertThat(comparison.get("reasonCode").asString()).isEqualTo("gate_terminal_prs_15");
        assertThat(comparison.get("reason").asString()).contains("this period had 1");
    }

    @Test
    void theFunnelAndTrendsReproduceTheWorkedFigures() throws Exception {
        JsonNode body = contractPeriod();
        JsonNode funnel = body.get("funnel");

        assertThat(funnel.get("observationCutoff").asString()).isEqualTo("2026-02-03");
        assertThat(funnel.get("stages").get("started").get("display").get("value").asString())
                .isEqualTo("4");
        assertThat(funnel.get("stages").get("prMerged").get("display").get("value").asString())
                .isEqualTo("1");
        assertThat(funnel.get("residual").get("inProgress").get("display").get("value").asString())
                .isEqualTo("1");

        assertThat(body.get("trends").get("mergedPrsPerDay").get("points")).hasSize(16);
        assertThat(body.get("trends").get("spendPerDay").get("points")).hasSize(16);
        long spend = 0;
        for (JsonNode point : body.get("trends").get("spendPerDay").get("points")) {
            spend += point.get("spendCents").asLong();
        }
        assertThat(spend).isEqualTo(2300);
    }

    @Test
    void theTeamTableReproducesTheWorkedRowsAndBenchmark() throws Exception {
        JsonNode comparison = contractPeriod().get("comparison");

        assertThat(comparison.get("rows").get(0).get("scopeName").asString()).isEqualTo("Payments");
        assertThat(comparison.get("rows").get(1).get("scopeName").asString()).isEqualTo("Platform");
        assertThat(comparison.get("rows").get(0).get("codeChangeSpend").get("display")
                .get("value").asString()).isEqualTo("23");
        assertThat(comparison.get("benchmark").get("costPerMergedPr").get("display")
                .get("value").asString()).isEqualTo("23.00");
        assertThat(comparison.get("benchmark").get("scope").get("mayIncludeUndisplayedTeams")
                .asBoolean()).isFalse();
    }

    @Test
    void theRepositoryGroupingPoolsToTheSameBenchmark() throws Exception {
        JsonNode comparison = contractPeriod("grouping", "repositories").get("comparison");

        assertThat(comparison.get("grouping").asString()).isEqualTo("repositories");
        assertThat(comparison.get("benchmark").get("taskCompletionRate").get("display")
                .get("value").asString()).isEqualTo("50.0");
        assertThat(comparison.get("benchmark").get("costPerMergedPr").get("display")
                .get("value").asString()).isEqualTo("23.00");
    }

    /** Contract 6.0: the two filters intersect, and both are echoed in the resolved selection. */
    @Test
    void teamAndRepositoryFiltersIntersect() throws Exception {
        JsonNode body = contractPeriod(
                "teamId", fixtureA.id("T-PAY").toString(),
                "repositoryId", fixtureA.id("R-API").toString());

        assertThat(body.get("selection").get("teamId").asString())
                .isEqualTo(fixtureA.id("T-PAY").toString());
        assertThat(body.get("selection").get("repositoryId").asString())
                .isEqualTo(fixtureA.id("R-API").toString());
        // repo-api under Payments: T6 failed, T5 cancelled. One terminal task, none completed.
        assertThat(body.get("kpis").get("taskCompletionRate").get("display").get("value").asString())
                .isEqualTo("0.0");
        // Contract 5.3: utilisation is not defined for a filtered scope.
        assertThat(body.get("kpis").get("seats").get("utilisation").get("state").asString())
                .isEqualTo("unavailable_for_scope");
        assertThat(body.get("kpis").get("seats").get("utilisation").has("display")).isFalse();
    }

    /** Contract 5.3: a repository filter leaves no budget window to report. */
    @Test
    void aRepositoryFilterOmitsTheBudgetWindow() throws Exception {
        JsonNode windows = contractPeriod(
                "repositoryId", fixtureA.id("R-API").toString()).get("coverageWindows");

        assertThat(windows.has("budgetMonthToDate")).isFalse();
        assertThat(windows.has("current")).isTrue();
        assertThat(windows.has("funnelObservation")).isTrue();
    }

    @Test
    void theAttentionPanelReportsTheApprovedFixtureAccounting() throws Exception {
        JsonNode attention = contractPeriod().get("attention");

        assertThat(attention.get("findings")).isEmpty();
        assertThat(attention.get("evaluationsCompleted").asInt()).isEqualTo(5);
        assertThat(attention.get("limits")).hasSize(10);
    }

    // --- Rejected selections -----------------------------------------------------------------------

    /**
     * Each cause keeps its own problem type. Collapsing them would degrade every one of them to
     * "invalid input" in the client's controlled message.
     */
    @ParameterizedTest(name = "from={0} to={1} -> {2}")
    @CsvSource({
        "2026-02-30, 2026-03-05, urn:fleet:problem:invalid-date-format",
        "'',         2026-01-31, urn:fleet:problem:invalid-date-format",
        "2026-01-31, 2026-01-16, urn:fleet:problem:reversed-date-range",
        "2025-11-01, 2026-01-31, urn:fleet:problem:range-outside-coverage",
    })
    void malformedSelectionsAreRejectedWithTheirOwnProblemType(
            String from, String to, String expectedType) throws Exception {
        MvcResult result = request("admin.a", "from", from, "to", to);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("type").asString()).isEqualTo(expectedType);
        OpenApiContract.assertResponseValid(result);
    }

    @Test
    void aLoneDateIsAnIncompleteRange() throws Exception {
        MvcResult result = request("admin.a", "from", "2026-01-16");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("type").asString())
                .isEqualTo("urn:fleet:problem:incomplete-date-range");
    }

    @Test
    void anUnrecognisedGroupingIsRejected() throws Exception {
        MvcResult result = request("admin.a", "grouping", "nonsense");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("type").asString())
                .isEqualTo("urn:fleet:problem:invalid-grouping");
    }

    /**
     * The tenancy control that matters. A foreign team id, a nonexistent one and a malformed one
     * must produce byte-identical responses, or the parameter becomes an oracle for discovering
     * another organisation's scopes.
     */
    @Test
    void foreignUnknownAndMalformedFiltersAreIndistinguishable() throws Exception {
        String foreign = request("admin.a", "teamId", fixtureB.id("T-PAY").toString())
                .getResponse().getContentAsString();
        String unknown = request("admin.a", "teamId", UUID.randomUUID().toString())
                .getResponse().getContentAsString();
        String malformed = request("admin.a", "teamId", "not-a-uuid")
                .getResponse().getContentAsString();

        assertThat(foreign).isEqualTo(unknown).isEqualTo(malformed);
        assertThat(json.readTree(foreign).get("type").asString())
                .isEqualTo("urn:fleet:problem:unknown-filter");
        // The rejected identifier is never echoed back.
        assertThat(foreign).doesNotContain(fixtureB.id("T-PAY").toString());
    }

    /** A foreign filter must never be silently dropped and answered organisation-wide. */
    @Test
    void aForeignFilterIsRejectedRatherThanIgnored() throws Exception {
        MvcResult result = request("admin.a", "teamId", fixtureB.id("T-PAY").toString());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    // --- Authentication ------------------------------------------------------------------------------

    @Test
    void anUnauthenticatedRequestIsRejected() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/dashboard")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        OpenApiContract.assertResponseValid(result);
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/analytics/dashboard")
                .header("Authorization", "Bearer " + token("admin.a") + "x")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    // --- Tenant isolation on returned data --------------------------------------------------------------

    /**
     * Both tenants hold an identical fixture, so equal numbers prove nothing on their own. What
     * proves isolation is that the scope identifiers and names each tenant receives are its own.
     */
    @Test
    void eachTenantReceivesOnlyItsOwnScopes() throws Exception {
        JsonNode a = contractPeriod();
        MvcResult resultB = request("admin.b",
                "from", ContractFixture.PERIOD_FROM.toString(),
                "to", ContractFixture.PERIOD_TO.toString());
        JsonNode b = body(resultB);
        OpenApiContract.assertValid(resultB);

        assertThat(a.get("comparison").get("rows").get(0).get("scopeId").asString())
                .isEqualTo(fixtureA.id("T-PAY").toString())
                .isNotEqualTo(fixtureB.id("T-PAY").toString());
        assertThat(b.get("comparison").get("rows").get(0).get("scopeId").asString())
                .isEqualTo(fixtureB.id("T-PAY").toString());

        String bodyA = a.toString();
        assertThat(bodyA).doesNotContain(fixtureB.id("T-PAY").toString());
        assertThat(bodyA).doesNotContain(fixtureB.id("R-API").toString());
    }

    // --- Coverage-dependent behaviour ------------------------------------------------------------------

    /**
     * One withdrawn day of pull-request coverage must silence exactly its dependants, through the
     * whole HTTP path — and none of them may become zero.
     */
    @Test
    void aMissingSourceAffectsOnlyItsDependentSections() throws Exception {
        UUID org = UUID.randomUUID();
        String hash = PasswordEncoderFactory.create().encode("pw");
        ContractFixture local;
        try (Connection c = connection()) {
            local = ContractFixture.install(c, org);
            Fixtures.user(c, UUID.randomUUID(), org, local.id("T-PLAT"), "admin-gap",
                    "admin.gap", "Admin Gap", hash, "ADMIN", false);
            Fixtures.sourceDay(c, org, "pull_requests", LocalDate.of(2026, 1, 20), false);
        }

        MvcResult result = request("admin.gap",
                "from", ContractFixture.PERIOD_FROM.toString(),
                "to", ContractFixture.PERIOD_TO.toString());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        OpenApiContract.assertValid(result);
        JsonNode body = body(result);

        JsonNode mergedPrs = body.get("kpis").get("mergedPrs");
        assertThat(mergedPrs.get("state").asString()).isEqualTo("missing_data");
        assertThat(mergedPrs.has("display")).isFalse();
        assertThat(mergedPrs.get("reason").asString()).contains("pull_requests");

        // Unaffected sections keep their real values rather than dropping to zero.
        assertThat(body.get("kpis").get("taskCompletionRate").get("display").get("value").asString())
                .isEqualTo("50.0");
        assertThat(body.get("kpis").get("seats").get("display").get("value").asString())
                .isEqualTo("2");

        // The merge series is unavailable with no points; spend still renders all sixteen days.
        assertThat(body.get("trends").get("mergedPrsPerDay").get("state").asString())
                .isEqualTo("missing_data");
        assertThat(body.get("trends").get("mergedPrsPerDay").get("points")).isEmpty();
        assertThat(body.get("trends").get("spendPerDay").get("points")).hasSize(16);

        // Funnel task stages survive; only the two PR stages become unavailable.
        assertThat(body.get("funnel").get("stages").get("started").get("display")
                .get("value").asString()).isEqualTo("4");
        assertThat(body.get("funnel").get("stages").get("prMerged").get("state").asString())
                .isEqualTo("missing_data");
    }

    /**
     * Evidence must be as unavailable as the value it explains. A population read over an uncovered
     * window is zero because there was nothing to read, and publishing that zero as evidence would
     * present a gap as a precise measurement — the confusion {@code missing_data} exists to avoid.
     *
     * <p>Gating is per source group, so the spend that <em>is</em> known survives alongside an
     * unavailable PR count. That is what keeps AC-01.5's "positive spend, nothing merged" readable.
     */
    @Test
    void unavailableEvidenceIsOmittedWhileIndependentlyKnownEvidenceSurvives() throws Exception {
        UUID org = UUID.randomUUID();
        String hash = PasswordEncoderFactory.create().encode("pw");
        ContractFixture local;
        try (Connection c = connection()) {
            local = ContractFixture.install(c, org);
            Fixtures.user(c, UUID.randomUUID(), org, local.id("T-PLAT"), "admin-ev",
                    "admin.ev", "Admin Ev", hash, "ADMIN", false);
            Fixtures.sourceDay(c, org, "pull_requests", LocalDate.of(2026, 1, 20), false);
        }

        MvcResult result = request("admin.ev",
                "from", ContractFixture.PERIOD_FROM.toString(),
                "to", ContractFixture.PERIOD_TO.toString());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        OpenApiContract.assertValid(result);
        JsonNode body = body(result);

        // PR counts are unknown, so no count is published as though it were measured.
        JsonNode mergeRate = body.get("kpis").get("terminalMergeRate");
        assertThat(mergeRate.get("state").asString()).isEqualTo("missing_data");
        assertThat(mergeRate.has("evidence")).isFalse();

        // The unit cost depends on both, and only the half that is known appears.
        JsonNode cost = body.get("kpis").get("costPerMergedPr");
        assertThat(cost.get("evidence").has("mergedPrs")).isFalse();
        assertThat(cost.get("evidence").get("codeChangeSpendCents").asLong()).isEqualTo(2300);

        // Task evidence depends on neither and is untouched.
        JsonNode completion = body.get("kpis").get("taskCompletionRate");
        assertThat(completion.get("evidence").get("completedTasks").asLong()).isEqualTo(1);
        assertThat(completion.get("evidence").get("failedTasks").asLong()).isEqualTo(1);

        // The same gating reaches rows and the benchmark, which are current-period only.
        JsonNode row = body.get("comparison").get("rows").get(0);
        assertThat(row.get("terminalMergeRate").has("evidence")).isFalse();
        assertThat(row.get("codeChangeSpend").get("evidence").has("codeChangeSpendCents")).isTrue();
        assertThat(body.get("comparison").get("benchmark").get("terminalMergeRate").has("evidence"))
                .isFalse();
        assertThat(body.get("comparison").get("benchmark").get("taskCompletionRate")
                .get("evidence").get("completedTasks").asLong()).isEqualTo(1);
    }

    /**
     * A previous-period gap withdraws only the previous-period evidence. The current values and
     * their evidence are unaffected, and an insufficient sample is never a reason to withhold a
     * count that was genuinely measured.
     */
    @Test
    void aPreviousPeriodGapWithdrawsOnlyPreviousEvidence() throws Exception {
        UUID org = UUID.randomUUID();
        String hash = PasswordEncoderFactory.create().encode("pw");
        ContractFixture local;
        try (Connection c = connection()) {
            local = ContractFixture.install(c, org);
            Fixtures.user(c, UUID.randomUUID(), org, local.id("T-PLAT"), "admin-prev",
                    "admin.prev", "Admin Prev", hash, "ADMIN", false);
            Fixtures.sourceDay(c, org, "pull_requests", LocalDate.of(2026, 1, 5), false);
        }

        JsonNode kpis = body(request("admin.prev",
                "from", ContractFixture.PERIOD_FROM.toString(),
                "to", ContractFixture.PERIOD_TO.toString())).get("kpis");

        JsonNode mergeRate = kpis.get("terminalMergeRate");
        assertThat(mergeRate.get("display").get("value").asString()).isEqualTo("100.0");
        assertThat(mergeRate.get("evidence").get("terminalPrs").asLong()).isEqualTo(1);
        assertThat(mergeRate.get("comparison").get("state").asString()).isEqualTo("no_baseline");
        assertThat(mergeRate.get("comparison").has("evidence")).isFalse();
    }

    /** An insufficient sample suppresses a comparison; it must not withdraw measured counts. */
    @Test
    void anInsufficientSampleKeepsBothPeriodsEvidence() throws Exception {
        JsonNode mergeRate = contractPeriod().get("kpis").get("terminalMergeRate");

        assertThat(mergeRate.get("comparison").get("state").asString())
                .isEqualTo("insufficient_sample");
        assertThat(mergeRate.get("evidence").get("mergedPrs").asLong()).isEqualTo(1);
        assertThat(mergeRate.get("evidence").get("terminalPrs").asLong()).isEqualTo(1);
        assertThat(mergeRate.get("comparison").get("evidence").get("previousTerminalPrs").asLong())
                .isEqualTo(3);
    }

    /** Contract 1.2: an uncovered baseline suppresses comparisons, never the current values. */
    @Test
    void aMissingBaselinePreservesCurrentValues() throws Exception {
        UUID org = UUID.randomUUID();
        String hash = PasswordEncoderFactory.create().encode("pw");
        ContractFixture local;
        try (Connection c = connection()) {
            local = ContractFixture.install(c, org);
            Fixtures.user(c, UUID.randomUUID(), org, local.id("T-PLAT"), "admin-base",
                    "admin.base", "Admin Base", hash, "ADMIN", false);
            Fixtures.sourceDay(c, org, "tasks", LocalDate.of(2026, 1, 3), false);
        }

        JsonNode body = body(request("admin.base",
                "from", ContractFixture.PERIOD_FROM.toString(),
                "to", ContractFixture.PERIOD_TO.toString()));

        JsonNode completion = body.get("kpis").get("taskCompletionRate");
        assertThat(completion.get("display").get("value").asString()).isEqualTo("50.0");
        assertThat(completion.get("comparison").get("state").asString()).isEqualTo("no_baseline");
        assertThat(completion.get("comparison").has("display")).isFalse();
    }
}
