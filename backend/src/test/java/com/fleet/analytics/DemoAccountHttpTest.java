package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fleet.analytics.security.PasswordEncoderFactory;
import com.fleet.analytics.support.Fixtures;
import com.fleet.analytics.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The running application under the {@code test} profile: development keys are on, the demo profile
 * is not active, so demo accounts must stay inert while ordinary accounts sign in normally. The
 * profile cases are covered in {@code DemoAccountPolicyTest} and
 * {@code AuthenticationServiceTest}.
 */
class DemoAccountHttpTest extends IntegrationTestBase {

    private static final UUID ORG = UUID.fromString("0c000000-0000-0000-0000-00000000000c");

    @BeforeAll
    static void fixtures() throws SQLException {
        String hash = PasswordEncoderFactory.create().encode("demo-pass");
        try (Connection c = connection()) {
            Fixtures.organisation(c, ORG, "Demo Org");
            UUID team = UUID.fromString("1c000000-0000-0000-0000-000000000001");
            Fixtures.team(c, team, ORG, "demo-t", "Demo Team");
            Fixtures.user(c, UUID.fromString("3c000000-0000-0000-0000-000000000001"), ORG, team,
                    "demo-u", "demo.admin", "Demo Admin", hash, "ADMIN", true);
            Fixtures.user(c, UUID.fromString("3c000000-0000-0000-0000-000000000002"), ORG, team,
                    "real-u", "real.admin", "Real Admin", hash, "ADMIN", false);
        }
    }

    private int login(String username) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"demo-pass\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void developmentKeyGenerationDoesNotEnableDemoAccounts() throws Exception {
        assertThat(login("demo.admin")).as("demo account without the demo profile").isEqualTo(401);
        assertThat(login("real.admin")).as("ordinary account").isEqualTo(200);
    }
}
