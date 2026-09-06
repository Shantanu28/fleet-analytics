package com.fleet.analytics.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Generates RSA key material for tests and writes it as PEM into a temporary directory.
 *
 * <p>Keys are produced per run and never checked in: no signing key, real or otherwise, belongs in
 * the repository.
 */
public final class TestKeys {

    private TestKeys() {}

    public static KeyPair generate(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PKCS#8, which is what {@code RsaKeyConverters.pkcs8()} reads. */
    public static Path writePrivatePem(Path directory, String name, KeyPair pair) throws IOException {
        return write(directory, name, "PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    /** X.509 SubjectPublicKeyInfo, which is what {@code RsaKeyConverters.x509()} reads. */
    public static Path writePublicPem(Path directory, String name, KeyPair pair) throws IOException {
        return write(directory, name, "PUBLIC KEY", pair.getPublic().getEncoded());
    }

    public static Path writeGarbage(Path directory, String name) throws IOException {
        return Files.writeString(directory.resolve(name), "-----BEGIN PRIVATE KEY-----\nnot base64\n");
    }

    private static Path write(Path directory, String name, String label, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        String pem = "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
        return Files.writeString(directory.resolve(name), pem);
    }

    public static String location(Path file) {
        return "file:" + file.toAbsolutePath();
    }
}
