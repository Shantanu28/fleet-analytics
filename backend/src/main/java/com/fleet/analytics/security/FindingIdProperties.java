package com.fleet.analytics.security;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The dedicated secret behind public finding identifiers, under {@code fleet.findings.*}.
 *
 * <p><b>Deliberately separate from every JWT setting.</b> Reusing the signing key would tie two
 * unrelated lifetimes together: rotating the token key would silently change every finding id, and a
 * finding id — which is handed to clients and may be logged or bookmarked — would become a value
 * derived from the key that authenticates requests.
 *
 * <p><b>Fail closed, in every profile.</b> There is no generated fallback, not even in development.
 * An ephemeral key would produce ids that change on every restart, quietly breaking the stability
 * the contract promises while every test still passed. Missing or malformed configuration stops
 * startup instead.
 */
@ConfigurationProperties(prefix = "fleet.findings")
public record FindingIdProperties(String idSecret) {

    /**
     * HMAC-SHA-256's block-equivalent strength. A shorter key would still produce output, so the
     * length has to be checked rather than assumed.
     */
    static final int MINIMUM_KEY_BYTES = 32;

    public FindingIdProperties {
        // An unset environment variable expands to the empty string, so blank genuinely means
        // "not configured" rather than "configured as nothing".
        if (idSecret == null || idSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "fleet.findings.id-secret must be configured; finding identifiers have no "
                            + "generated fallback in any profile");
        }
    }

    /**
     * @return the decoded key material.
     * @throws IllegalArgumentException if the value is not Base64 or is too short to be a key.
     */
    public byte[] keyMaterial() {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(idSecret.trim());
        } catch (IllegalArgumentException e) {
            // The value itself is never included: it is a secret, and a decoding failure says
            // nothing useful about it beyond the fact that it is wrong.
            throw new IllegalArgumentException(
                    "fleet.findings.id-secret must be valid Base64", e);
        }
        if (decoded.length < MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException("fleet.findings.id-secret must decode to at least "
                    + MINIMUM_KEY_BYTES + " bytes, but decoded to " + decoded.length);
        }
        return decoded;
    }
}
