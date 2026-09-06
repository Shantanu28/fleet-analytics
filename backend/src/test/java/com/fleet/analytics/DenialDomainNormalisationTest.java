package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.PostgresTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The one property of {@code denial_event} that no CHECK constraint can state: {@code
 * domain_normalised} is the contract 6.4 normal form of {@code domain_raw} — lowercased and with a
 * trailing dot stripped — while the raw value is preserved exactly as reported.
 *
 * <p>This is a fixture-helper obligation, not a database guarantee, which is why it is not in
 * {@link AnalyticsSchemaConstraintsTest}. It is worth a test because the network-policy-friction
 * rule counts <em>distinct normalised domains</em>: an unnormalised fixture would present
 * {@code Registry.Corp.} and {@code registry.corp} as two domains, splitting one population in two
 * and silently keeping the rule below its 5-task / 3-user gate.
 */
class DenialDomainNormalisationTest extends PostgresTestBase {

    private static final OffsetDateTime CREATED =
            OffsetDateTime.of(2026, 1, 5, 9, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void mixedCaseAndTrailingDotNormaliseToOneDomainWhileRawIsPreserved() throws SQLException {
        UUID org = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        UUID repo = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        UUID mixedCase = UUID.randomUUID();
        UUID alreadyNormal = UUID.randomUUID();

        try (Connection c = connection()) {
            Fixtures.organisation(c, org, "Denial Org");
            Fixtures.team(c, team, org, "t-" + team, "Platform");
            Fixtures.repository(c, repo, org, "r-" + repo, "repo-api");
            Fixtures.user(c, user, org, team, "u-" + user, "user-" + user, "User",
                    "{noop}x", "ADMIN", false);
            Fixtures.task(c, task, org, team, repo, user, "task-" + task, "feature", CREATED,
                    "failed", CREATED);

            // The same domain as two sources would report it: one mixed-case and fully qualified
            // with the root label's trailing dot, one already in normal form.
            Fixtures.denial(c, mixedCase, org, task, null, "d-mixed", "Registry.Corp.", CREATED);
            Fixtures.denial(c, alreadyNormal, org, task, null, "d-plain", "registry.corp", CREATED);

            assertThat(domainRaw(c, mixedCase))
                    .as("domain_raw must keep exactly what the source reported")
                    .isEqualTo("Registry.Corp.");

            assertThat(distinctNormalisedDomains(c, org))
                    .as("contract 6.4 normalisation must collapse these to one domain")
                    .containsExactly("registry.corp");
        }
    }

    private static String domainRaw(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s =
                c.prepareStatement("select domain_raw from denial_event where id = ?")) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static List<String> distinctNormalisedDomains(Connection c, UUID org)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "select distinct domain_normalised from denial_event where org_id = ?"
                        + " order by domain_normalised")) {
            s.setObject(1, org);
            try (ResultSet rs = s.executeQuery()) {
                List<String> domains = new ArrayList<>();
                while (rs.next()) {
                    domains.add(rs.getString(1));
                }
                return domains;
            }
        }
    }
}
