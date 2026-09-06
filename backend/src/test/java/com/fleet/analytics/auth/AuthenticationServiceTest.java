package com.fleet.analytics.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleet.analytics.data.Account;
import com.fleet.analytics.data.UserAccounts;
import com.fleet.analytics.security.DemoAccountPolicy;
import com.fleet.analytics.security.DemoAccountProperties;
import com.fleet.analytics.security.JwtIssuer;
import com.fleet.analytics.security.JwtProperties;
import com.fleet.analytics.security.PasswordEncoderFactory;
import com.fleet.analytics.security.RsaKeyProvider;
import com.fleet.analytics.web.LoginRequest;
import com.fleet.analytics.web.LoginResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Sign-in rules without HTTP: what is compared, how often, and what is refused. */
class AuthenticationServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** Counts calls on a real encoder, so behaviour — not a mock's script — is what is asserted. */
    private static final class CountingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = PasswordEncoderFactory.create();
        final AtomicInteger encodes = new AtomicInteger();
        final AtomicInteger matches = new AtomicInteger();

        @Override
        public String encode(CharSequence rawPassword) {
            encodes.incrementAndGet();
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matches.incrementAndGet();
            return delegate.matches(rawPassword, encodedPassword);
        }
    }

    private static final class StubAccounts extends UserAccounts {
        private final Map<String, Account> accounts;

        StubAccounts(Map<String, Account> accounts) {
            super(null);
            this.accounts = accounts;
        }

        @Override
        public Optional<Account> findByUsername(String normalisedUsername) {
            return Optional.ofNullable(accounts.get(normalisedUsername));
        }
    }

    private final CountingPasswordEncoder encoder = new CountingPasswordEncoder();
    private final JwtProperties properties = new JwtProperties(
            "fleet-analytics", "fleet-analytics-dashboard", Duration.ofMinutes(15), null, null, true);
    private final JwtIssuer issuer = new JwtIssuer(RsaKeyProvider.ephemeral(), properties,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    private Account account(boolean demo) {
        return new Account(USER, ORG, "ADMIN", "Ada Lovelace", encoder.encode("correct horse"), demo);
    }

    private AuthenticationService service(Map<String, Account> accounts, boolean demoEnabled,
            String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        if (profiles.length > 0) {
            environment.setProperty("spring.profiles.active", String.join(",", profiles));
        }
        return new AuthenticationService(new StubAccounts(accounts), encoder, issuer, properties,
                new DemoAccountPolicy(new DemoAccountProperties(demoEnabled), environment));
    }

    @Test
    void issuesATokenAndTheIdentityTheClientNeeds() {
        AuthenticationService service = service(Map.of("ada", account(false)), false);

        LoginResponse response = service.authenticate(new LoginRequest("  Ada  ", "correct horse"));

        assertThat(response.userId()).isEqualTo(USER);
        assertThat(response.organisationId()).isEqualTo(ORG);
        assertThat(response.displayName()).isEqualTo("Ada Lovelace");
        assertThat(response.role()).isEqualTo("ADMIN");
        assertThat(response.expiresInSeconds()).isEqualTo(900);
        assertThat(response.accessToken()).isNotBlank();
    }

    /**
     * The production change this would catch: reinstating {@code account != null && matches(...)},
     * which short-circuits and skips verification whenever the username is unknown.
     */
    @Test
    void verifiesAPasswordEvenWhenNoAccountMatches() {
        AuthenticationService service = service(Map.of("ada", account(false)), false);
        int before = encoder.matches.get();

        assertThatThrownBy(() -> service.authenticate(new LoginRequest("nobody", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(encoder.matches.get() - before).as("password verifications").isEqualTo(1);
    }

    /** The dummy hash is encoded once at construction, never per rejected request. */
    @Test
    void doesNotEncodeAFreshHashPerFailedAttempt() {
        AuthenticationService service = service(Map.of("ada", account(false)), false);
        int after = encoder.encodes.get();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.authenticate(new LoginRequest("nobody", "whatever")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThat(encoder.encodes.get()).as("encode calls after five failures").isEqualTo(after);
    }

    @Test
    void rejectsWrongPasswordBlankAndMissingFieldsIdentically() {
        AuthenticationService service = service(Map.of("ada", account(false)), false);

        assertThatThrownBy(() -> service.authenticate(new LoginRequest("ada", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid username or password.");
        assertThatThrownBy(() -> service.authenticate(new LoginRequest("ada", null)))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate(new LoginRequest(null, "correct horse")))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate(new LoginRequest("   ", "correct horse")))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate(null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void demoAccountNeedsBothProfileAndFlagWhileOrdinaryAccountsAreUnaffected() {
        Map<String, Account> both = Map.of("demo", account(true), "ada", account(false));

        assertThatThrownBy(() -> service(both, true).authenticate(new LoginRequest("demo", "correct horse")))
                .as("flag without the demo profile").isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service(both, false, "demo")
                .authenticate(new LoginRequest("demo", "correct horse")))
                .as("profile without the flag").isInstanceOf(InvalidCredentialsException.class);

        assertThat(service(both, true, "demo").authenticate(new LoginRequest("demo", "correct horse"))
                .accessToken()).isNotBlank();

        // An ordinary account signs in under every one of those configurations.
        assertThat(service(both, false).authenticate(new LoginRequest("ada", "correct horse"))
                .accessToken()).isNotBlank();
        assertThat(service(both, true, "demo").authenticate(new LoginRequest("ada", "correct horse"))
                .accessToken()).isNotBlank();
    }

    /** Credential-bearing objects must not print secrets if anything ever logs them. */
    @Test
    void accountAndRequestDoNotExposeSecretsInToString() {
        assertThat(account(false).toString()).doesNotContain("argon2").contains("<redacted>");
        assertThat(new LoginRequest("ada", "correct horse").toString())
                .doesNotContain("correct horse").doesNotContain("ada");

        LoginResponse response = service(Map.of("ada", account(false)), false)
                .authenticate(new LoginRequest("ada", "correct horse"));
        assertThat(response.toString()).doesNotContain(response.accessToken()).contains("<redacted>");
    }
}
