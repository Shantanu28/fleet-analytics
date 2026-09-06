package com.fleet.analytics.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.support.TestKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Key selection under real Spring property binding and real profile activation — not direct calls
 * to the {@code @Bean} method, which would prove nothing about how the application actually starts.
 */
class KeyConfigurationTest {

    @TempDir
    Path keys;

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(KeyConfiguration.class);

    @Test
    void loadsConfiguredKeyPair() throws IOException {
        KeyPair pair = TestKeys.generate(2048);
        Path privatePem = TestKeys.writePrivatePem(keys, "private.pem", pair);
        Path publicPem = TestKeys.writePublicPem(keys, "public.pem", pair);

        runner.withPropertyValues(
                        "fleet.jwt.private-key-location=" + TestKeys.location(privatePem),
                        "fleet.jwt.public-key-location=" + TestKeys.location(publicPem))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RsaKeyProvider.class).publicKey().getModulus())
                            .isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus());
                });
    }

    /** Configured keys win, and a failure to load them is never softened into generation. */
    @Test
    void configuredKeysTakePrecedenceOverDevelopmentGeneration() throws IOException {
        KeyPair pair = TestKeys.generate(2048);
        Path privatePem = TestKeys.writePrivatePem(keys, "private.pem", pair);
        Path publicPem = TestKeys.writePublicPem(keys, "public.pem", pair);

        runner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "fleet.jwt.dev-keys-enabled=true",
                        "fleet.jwt.private-key-location=" + TestKeys.location(privatePem),
                        "fleet.jwt.public-key-location=" + TestKeys.location(publicPem))
                .run(context -> assertThat(context.getBean(RsaKeyProvider.class).publicKey().getModulus())
                        .isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus()));
    }

    @Test
    void missingKeyResourceFailsStartup() {
        runner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "fleet.jwt.dev-keys-enabled=true",
                        "fleet.jwt.private-key-location=file:" + keys.resolve("absent.pem"),
                        "fleet.jwt.public-key-location=file:" + keys.resolve("absent-public.pem"))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(RsaKeyProvider.KeyConfigurationException.class)
                        .hasMessageContaining("does not exist"));
    }

    @Test
    void malformedKeyFailsStartupWithoutQuotingKeyMaterial() throws IOException {
        KeyPair pair = TestKeys.generate(2048);
        Path garbage = TestKeys.writeGarbage(keys, "broken.pem");
        Path publicPem = TestKeys.writePublicPem(keys, "public.pem", pair);

        runner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "fleet.jwt.dev-keys-enabled=true",
                        "fleet.jwt.private-key-location=" + TestKeys.location(garbage),
                        "fleet.jwt.public-key-location=" + TestKeys.location(publicPem))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(RsaKeyProvider.KeyConfigurationException.class)
                        .hasMessageContaining("not a valid PEM RSA key"));
    }

    @Test
    void mismatchedKeyPairFailsStartup() throws IOException {
        Path privatePem = TestKeys.writePrivatePem(keys, "private.pem", TestKeys.generate(2048));
        Path publicPem = TestKeys.writePublicPem(keys, "public.pem", TestKeys.generate(2048));

        runner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "fleet.jwt.dev-keys-enabled=true",
                        "fleet.jwt.private-key-location=" + TestKeys.location(privatePem),
                        "fleet.jwt.public-key-location=" + TestKeys.location(publicPem))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(RsaKeyProvider.KeyConfigurationException.class)
                        .hasMessageContaining("not a matching pair"));
    }

    @Test
    void undersizedConfiguredKeyFailsStartup() throws IOException {
        KeyPair small = TestKeys.generate(1024);
        Path privatePem = TestKeys.writePrivatePem(keys, "private.pem", small);
        Path publicPem = TestKeys.writePublicPem(keys, "public.pem", small);

        runner.withPropertyValues(
                        "fleet.jwt.private-key-location=" + TestKeys.location(privatePem),
                        "fleet.jwt.public-key-location=" + TestKeys.location(publicPem))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("smaller than 2048 bits"));
    }

    @Test
    void halfConfiguredKeyPairFailsStartup() throws IOException {
        Path privatePem = TestKeys.writePrivatePem(keys, "private.pem", TestKeys.generate(2048));

        runner.withPropertyValues("fleet.jwt.private-key-location=" + TestKeys.location(privatePem))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("must be set together"));
    }

    /** The flag alone is not enough: outside an allowed profile it must not generate anything. */
    @Test
    void developmentFlagWithoutAnAllowedProfileFailsStartup() {
        runner.withPropertyValues("fleet.jwt.dev-keys-enabled=true", "spring.profiles.active=production")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(RsaKeyProvider.KeyConfigurationException.class)
                        .hasMessageContaining("No JWT signing keys configured"));
    }

    /** And the profile alone is not enough either. */
    @Test
    void allowedProfileWithoutTheFlagFailsStartup() {
        runner.withPropertyValues("spring.profiles.active=dev", "fleet.jwt.dev-keys-enabled=false")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(RsaKeyProvider.KeyConfigurationException.class)
                        .hasMessageContaining("No JWT signing keys configured"));
    }

    @Test
    void noConfigurationAtAllFailsStartup() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(RsaKeyProvider.KeyConfigurationException.class));
    }

    @Test
    void generatesEphemeralKeysUnderAnAllowedProfile() {
        for (String profile : KeyConfiguration.KEY_GENERATION_PROFILES) {
            runner.withPropertyValues(
                            "spring.profiles.active=" + profile,
                            "fleet.jwt.dev-keys-enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(RsaKeyProvider.class).publicKey().getModulus().bitLength())
                                .isGreaterThanOrEqualTo(2048);
                    });
        }
    }

    @Test
    void keyMaterialIsNeverWrittenIntoTheRepository() throws IOException {
        try (var paths = Files.walk(Path.of("../"), 4)) {
            assertThat(paths.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> p.toString().endsWith(".pem") || p.toString().endsWith(".key")))
                    .as("no key material checked in").isEmpty();
        }
    }
}
