package com.fleet.analytics.security;

import java.util.Map;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Argon2id via DelegatingPasswordEncoder. The {argon2@SpringSecurity_v5_8} prefix stays in the
 * stored hash so the encoding is self-describing. Argon2PasswordEncoder requires BouncyCastle.
 */
public final class PasswordEncoderFactory {

    public static final String ENCODING_ID = "argon2@SpringSecurity_v5_8";

    private PasswordEncoderFactory() {}

    public static PasswordEncoder create() {
        Argon2PasswordEncoder argon2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        return new DelegatingPasswordEncoder(ENCODING_ID, Map.of(ENCODING_ID, argon2));
    }
}
