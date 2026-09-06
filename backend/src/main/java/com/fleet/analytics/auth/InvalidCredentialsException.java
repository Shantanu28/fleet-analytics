package com.fleet.analytics.auth;

/**
 * A rejected sign-in. Carries no username, no reason and no hash detail, because an unknown
 * account and a wrong password must be indistinguishable to the caller.
 */
public final class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid username or password.");
    }
}
