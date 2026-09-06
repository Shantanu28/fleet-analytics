package com.fleet.analytics.auth;

import com.fleet.analytics.data.Account;
import com.fleet.analytics.data.UserAccounts;
import com.fleet.analytics.security.DemoAccountPolicy;
import com.fleet.analytics.security.JwtIssuer;
import com.fleet.analytics.security.JwtProperties;
import com.fleet.analytics.security.Usernames;
import com.fleet.analytics.web.LoginRequest;
import com.fleet.analytics.web.LoginResponse;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Credential verification and token issue, kept out of the controller so the sign-in rules are
 * testable without HTTP.
 */
@Service
public class AuthenticationService {

    private final UserAccounts accounts;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer issuer;
    private final JwtProperties jwtProperties;
    private final DemoAccountPolicy demoAccountPolicy;

    /**
     * Encoded once at startup, then compared against whenever no account matched. Verifying a real
     * hash is what makes an unknown username cost the same as a wrong password; encoding a fresh
     * hash per failed request would instead hand an attacker an unauthenticated Argon2 workload.
     */
    private final String absentAccountPasswordHash;

    public AuthenticationService(UserAccounts accounts, PasswordEncoder passwordEncoder, JwtIssuer issuer,
            JwtProperties jwtProperties, DemoAccountPolicy demoAccountPolicy) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.issuer = issuer;
        this.jwtProperties = jwtProperties;
        this.demoAccountPolicy = demoAccountPolicy;
        this.absentAccountPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse authenticate(LoginRequest request) {
        String username = request == null ? null : Usernames.normaliseOrNull(request.username());
        String password = request == null || request.password() == null ? "" : request.password();

        Account account = username == null ? null : accounts.findByUsername(username).orElse(null);

        // Runs for every request, matched account or not — the comparison is never short-circuited.
        String encodedPassword = account == null ? absentAccountPasswordHash : account.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password, encodedPassword);

        if (account == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        if (account.demoAccount() && !demoAccountPolicy.demoAccountsEnabled()) {
            throw new InvalidCredentialsException();
        }

        return new LoginResponse(
                issuer.issue(account.id(), account.organisationId(), account.role()),
                jwtProperties.ttl().toSeconds(),
                account.id(),
                account.organisationId(),
                account.displayName(),
                account.role());
    }
}
