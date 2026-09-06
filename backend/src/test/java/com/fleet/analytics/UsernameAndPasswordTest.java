package com.fleet.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.security.PasswordEncoderFactory;
import com.fleet.analytics.security.Usernames;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class UsernameAndPasswordTest {

    @Test
    void normalisationTrimsAndLowercases() {
        assertThat(Usernames.normalise("  Ada@Example  ")).isEqualTo("ada@example");
        assertThat(Usernames.normalise("ADMIN")).isEqualTo("admin");
        assertThat(Usernames.normalise("admin")).isEqualTo("admin");
    }

    @Test
    void normalisationRejectsBlank() {
        assertThat(Usernames.normaliseOrNull("   ")).isNull();
        assertThat(Usernames.normaliseOrNull(null)).isNull();
    }

    @Test
    void encoderUsesArgon2WithRetainedPrefixAndRandomSalt() {
        PasswordEncoder encoder = PasswordEncoderFactory.create();
        String first = encoder.encode("correct horse");
        String second = encoder.encode("correct horse");

        assertThat(first).startsWith("{argon2@SpringSecurity_v5_8}");
        assertThat(first).isNotEqualTo(second);          // per-password random salt
        assertThat(encoder.matches("correct horse", first)).isTrue();
        assertThat(encoder.matches("wrong", first)).isFalse();
    }
}
