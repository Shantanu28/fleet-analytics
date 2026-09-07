package com.fleet.analytics.security;

import org.springframework.security.core.AuthenticationException;

/** Fail closed without telling the browser that an infrastructure outage means token expiry. */
public final class RevocationUnavailableException extends AuthenticationException {
    public RevocationUnavailableException(Throwable cause) {
        super("Token status is unavailable", cause);
    }
}
