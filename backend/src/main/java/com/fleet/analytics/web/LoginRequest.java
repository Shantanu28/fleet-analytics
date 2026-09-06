package com.fleet.analytics.web;

/**
 * Login inputs. Both fields are nullable here on purpose: a malformed body must produce the same
 * sanitised failure as a wrong password, not a binding error that distinguishes the two.
 */
public record LoginRequest(String username, String password) {

    @Override
    public String toString() {
        return "LoginRequest[username=<redacted>, password=<redacted>]";
    }
}
