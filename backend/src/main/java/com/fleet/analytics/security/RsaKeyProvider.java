package com.fleet.analytics.security;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;

/**
 * Holds the RS256 key pair.
 *
 * <p>Configured PEM keys take precedence everywhere. An ephemeral pair is generated only where
 * {@link SecurityConfig} permits it — an allowed development profile with generation explicitly
 * enabled — and never as a fallback for key configuration that failed to load. Private key material
 * is never logged, persisted or included in a failure message.
 */
public final class RsaKeyProvider {

    /** Smallest modulus accepted for RS256, whether generated or configured. */
    public static final int MINIMUM_KEY_SIZE_BITS = 2048;

    private final KeyPair keyPair;

    private RsaKeyProvider(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static RsaKeyProvider ephemeral() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MINIMUM_KEY_SIZE_BITS);
            return new RsaKeyProvider(generator.generateKeyPair());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot generate development RSA key pair", e);
        }
    }

    public static RsaKeyProvider of(KeyPair keyPair) {
        return new RsaKeyProvider(keyPair);
    }

    /**
     * Loads a PKCS#8 private key and an X.509 public key from PEM resources, then proves they are
     * one pair. Missing, unreadable, malformed, undersized and mismatched inputs all raise
     * {@link KeyConfigurationException}; none of them falls back to generation.
     */
    public static RsaKeyProvider fromPem(Resource privateKeyPem, Resource publicKeyPem) {
        RSAPrivateKey privateKey = read(privateKeyPem, "private", RsaKeyConverters.pkcs8());
        RSAPublicKey publicKey = read(publicKeyPem, "public", RsaKeyConverters.x509());

        if (publicKey.getModulus().bitLength() < MINIMUM_KEY_SIZE_BITS) {
            throw new KeyConfigurationException(
                    "The configured RSA key is smaller than " + MINIMUM_KEY_SIZE_BITS + " bits.");
        }
        if (!privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new KeyConfigurationException(
                    "The configured private and public keys are not a matching pair.");
        }
        return new RsaKeyProvider(new KeyPair(publicKey, privateKey));
    }

    private static <T> T read(Resource pem, String which,
            Converter<InputStream, T> converter) {
        if (pem == null || !pem.exists()) {
            throw new KeyConfigurationException(
                    "The configured " + which + " key resource does not exist.");
        }
        try (InputStream in = pem.getInputStream()) {
            T key = converter.convert(in);
            if (key == null) {
                throw new KeyConfigurationException(
                        "The configured " + which + " key could not be parsed.");
            }
            return key;
        } catch (IOException e) {
            throw new KeyConfigurationException(
                    "The configured " + which + " key resource could not be read.", e);
        } catch (IllegalArgumentException e) {
            // RsaKeyConverters reports malformed PEM this way; the cause may quote key bytes,
            // so it is deliberately not propagated into the message.
            throw new KeyConfigurationException(
                    "The configured " + which + " key is not a valid PEM RSA key.");
        }
    }

    public RSAPublicKey publicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    public RSAPrivateKey privateKey() {
        return (RSAPrivateKey) keyPair.getPrivate();
    }

    /** Startup failure for key configuration. Messages name the problem, never the key material. */
    public static final class KeyConfigurationException extends IllegalStateException {

        public KeyConfigurationException(String message) {
            super(message);
        }

        public KeyConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
