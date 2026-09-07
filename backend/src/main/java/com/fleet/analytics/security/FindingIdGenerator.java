package com.fleet.analytics.security;

import com.fleet.analytics.metrics.model.FindingIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Turns a finding's internal identity into an opaque public identifier.
 *
 * <p>The identity itself cannot be published: it contains the denied domain for network-policy
 * findings, which research 9 restricts to admins. An HMAC over it gives clients something stable to
 * correlate on while revealing nothing — the identifier grants no access, and carries no domain.
 *
 * <p><b>Stability is the contract.</b> The same identity and key produce the same id across
 * restarts and instances, because the input is only the identity and the organisation — evidence,
 * severity, magnitude and rank are excluded. That is what lets a finding recomputed for a narrower
 * scope be recognised as the same finding rather than appearing to be a new one (AC-06.7).
 *
 * <p><b>Length-prefixed components.</b> Each part is written as its byte length followed by its
 * bytes, so no combination of values can be reinterpreted as another. Plain concatenation would let
 * {@code ("ab", "c")} and {@code ("a", "bc")} collide, which for a domain-bearing component is a
 * cross-scope collision rather than a cosmetic one.
 *
 * <p>{@link Mac} is stateful and not thread-safe, so one is created per call rather than shared
 * across concurrent requests.
 */
@Component
@EnableConfigurationProperties(FindingIdProperties.class)
public class FindingIdGenerator {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Binds the output to this purpose and this construction. If the canonical form ever changes,
     * the version changes with it, so old and new ids cannot be mistaken for each other.
     */
    private static final String PURPOSE = "fleet.findings.id.v1";

    private final byte[] keyMaterial;

    /** Validation happens here, at startup: a bad key must not first surface mid-request. */
    public FindingIdGenerator(FindingIdProperties properties) {
        this.keyMaterial = properties.keyMaterial();
        try {
            Mac.getInstance(ALGORITHM).init(new SecretKeySpec(keyMaterial, ALGORITHM));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("finding identifiers cannot be generated", e);
        }
    }

    /**
     * @param organisationId scopes the identifier to its tenant, so two organisations with
     *     structurally identical findings never share an id.
     */
    public String publicId(UUID organisationId, FindingIdentity identity) {
        List<String> components = List.of(
                PURPOSE,
                organisationId.toString(),
                identity.ruleType().wireName(),
                identity.scopeType().wireName(),
                identity.scopeId() == null ? "" : identity.scopeId().toString(),
                identity.evaluationPeriodKey(),
                identity.normalisedDomain() == null ? "" : identity.normalisedDomain());

        Mac mac = newMac();
        for (String component : components) {
            byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
            mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            mac.update(bytes);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(keyMaterial, ALGORITHM));
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("finding identifiers cannot be generated", e);
        }
    }
}
