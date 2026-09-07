package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.fleet.analytics.data.analytics.CoverageQueries;
import com.fleet.analytics.security.AuthenticatedTenant;
import com.fleet.analytics.support.ContractFixture;
import com.fleet.analytics.support.IntegrationTestBase;
import com.fleet.analytics.web.dashboard.DashboardResponse;
import com.fleet.analytics.web.dashboard.DashboardService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proves the dashboard's sections come from one database snapshot, using a real concurrent commit.
 *
 * <p>Asserting that {@code @Transactional} is present would prove nothing: the annotation is
 * inert on a self-invoked method, silently ignored on a non-public one, and defeated entirely by any
 * query that borrows a different connection. The only convincing evidence is that a commit landing
 * <em>mid-request</em> is invisible to the rest of that request and visible to the next one.
 *
 * <p>Coordination is by latches with bounded waits and cleanup, never timing sleeps — a sleep would
 * make this test flaky in exactly the direction that hides a real failure.
 */
class DashboardSnapshotConsistencyTest extends IntegrationTestBase {

    private static final UUID ORG = UUID.fromString("dd000000-0000-0000-0000-0000000000d1");
    private static final long ORIGINAL_T7_CENTS = 1800;
    private static final long REPLACEMENT_T7_CENTS = 9999;
    /** Contract fixture spend inside the selected period: 150 + 350 + 1800. */
    private static final long ORIGINAL_PERIOD_CENTS = 2300;

    private static ContractFixture fixture;

    /**
     * A spy, not a stub. Every call still reaches the real query — the wrapper only opens a
     * deterministic pause after the transaction's first statement has established its snapshot.
     */
    @MockitoSpyBean private CoverageQueries coverageQueries;

    @Autowired private DashboardService service;

    @BeforeAll
    static void install() throws SQLException {
        try (Connection c = connection()) {
            fixture = ContractFixture.install(c, ORG);
        }
    }

    private static Map<String, String> contractPeriod() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("from", ContractFixture.PERIOD_FROM.toString());
        parameters.put("to", ContractFixture.PERIOD_TO.toString());
        return parameters;
    }

    private DashboardResponse dashboard() {
        return service.dashboard(new AuthenticatedTenant(UUID.randomUUID(), ORG, "ADMIN"),
                contractPeriod());
    }

    private static long periodSpendCents(DashboardResponse response) {
        return response.trends().spendPerDay().points().stream()
                .mapToLong(DashboardResponse.TrendsResponse.SpendPointResponse::spendCents)
                .sum();
    }

    /** Committed from a separate connection, so it is genuinely another transaction. */
    private static void commitConcurrentChange() throws SQLException {
        try (Connection writer = connection()) {
            writer.setAutoCommit(false);
            try (var spend = writer.prepareStatement(
                    "update usage_record set cost_cents = ? where org_id = ? and id = ?")) {
                spend.setLong(1, REPLACEMENT_T7_CENTS);
                spend.setObject(2, ORG);
                spend.setObject(3, fixture.id("usage-T7"));
                assertThat(spend.executeUpdate()).isEqualTo(1);
            }
            try (var revision = writer.prepareStatement(
                    "update dataset_publication set revision = ? where org_id = ?")) {
                revision.setString(1, "revision-after-concurrent-write");
                revision.setObject(2, ORG);
                assertThat(revision.executeUpdate()).isEqualTo(1);
            }
            writer.commit();
        }
    }

    private static void restoreOriginalState() throws SQLException {
        try (Connection writer = connection()) {
            try (var spend = writer.prepareStatement(
                    "update usage_record set cost_cents = ? where org_id = ? and id = ?")) {
                spend.setLong(1, ORIGINAL_T7_CENTS);
                spend.setObject(2, ORG);
                spend.setObject(3, fixture.id("usage-T7"));
                spend.executeUpdate();
            }
            try (var revision = writer.prepareStatement(
                    "update dataset_publication set revision = ? where org_id = ?")) {
                revision.setString(1, ContractFixture.REVISION);
                revision.setObject(2, ORG);
                revision.executeUpdate();
            }
        }
    }

    @Test
    void aCommitDuringOneRequestIsInvisibleToItAndVisibleToTheNext() throws Exception {
        CountDownLatch snapshotEstablished = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicBoolean firstRequest = new AtomicBoolean(true);
        ExecutorService requests = Executors.newSingleThreadExecutor();

        try {
            // The pause sits after the publication read, so the snapshot already exists when the
            // writer commits. Only the first request waits; the verification request runs freely.
            doAnswer(invocation -> {
                if (firstRequest.compareAndSet(true, false)) {
                    snapshotEstablished.countDown();
                    assertThat(writerCommitted.await(30, TimeUnit.SECONDS))
                            .as("the writer must commit before the request resumes").isTrue();
                }
                return invocation.callRealMethod();
            }).when(coverageQueries).sourceDays(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

            Future<DashboardResponse> inFlight = requests.submit(this::dashboard);

            assertThat(snapshotEstablished.await(30, TimeUnit.SECONDS))
                    .as("the request must reach its pause").isTrue();
            commitConcurrentChange();
            writerCommitted.countDown();

            DashboardResponse duringWrite = inFlight.get(30, TimeUnit.SECONDS);

            // Every section of this response predates the commit, and the revision it reports is
            // true of all of them.
            assertThat(duringWrite.coverage().revision()).isEqualTo(ContractFixture.REVISION);
            assertThat(periodSpendCents(duringWrite)).isEqualTo(ORIGINAL_PERIOD_CENTS);
            assertThat(duringWrite.kpis().costPerMergedPr().display().value()).isEqualTo("23.00");

            // A fresh request opens a new snapshot and sees the committed change, which is what
            // proves the first request's consistency was isolation rather than caching.
            DashboardResponse afterWrite = dashboard();
            assertThat(afterWrite.coverage().revision())
                    .isEqualTo("revision-after-concurrent-write");
            assertThat(periodSpendCents(afterWrite))
                    .isEqualTo(ORIGINAL_PERIOD_CENTS - ORIGINAL_T7_CENTS + REPLACEMENT_T7_CENTS);
        } finally {
            // Release any waiter before shutting down, so a failed assertion cannot hang the suite.
            writerCommitted.countDown();
            requests.shutdownNow();
            assertThat(requests.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            restoreOriginalState();
        }
    }

    /**
     * Sections within one response agree with each other. The spend trend and the cost-per-merged-PR
     * numerator are separate queries over the same population, so a mid-request commit would make
     * them disagree — the exact inconsistency the shared snapshot prevents.
     */
    @Test
    void sectionsWithinOneResponseAgreeWithEachOther() {
        DashboardResponse response = dashboard();

        long trendTotal = periodSpendCents(response);
        long evidenceSpend = response.kpis().costPerMergedPr().evidence().codeChangeSpendCents();

        assertThat(trendTotal).isEqualTo(ORIGINAL_PERIOD_CENTS);
        assertThat(evidenceSpend).isEqualTo(ORIGINAL_PERIOD_CENTS);
        assertThat(response.comparison().benchmark().codeChangeSpend().evidence()
                .codeChangeSpendCents()).isEqualTo(ORIGINAL_PERIOD_CENTS);
    }
}
