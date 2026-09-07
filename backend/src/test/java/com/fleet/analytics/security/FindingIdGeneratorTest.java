package com.fleet.analytics.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleet.analytics.metrics.model.FindingIdentity;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.RuleType;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Public finding identifiers: stable, tenant-separated, and revealing nothing about the identity
 * they are derived from.
 */
class FindingIdGeneratorTest {

    private static final UUID ORG_A = UUID.fromString("a0000000-0000-0000-0000-00000000000a");
    private static final UUID ORG_B = UUID.fromString("b0000000-0000-0000-0000-00000000000b");
    private static final UUID TEAM = UUID.fromString("2a1f0c64-1d3b-4f7a-9c02-5e8b7a10d002");

    private static FindingIdGenerator generatorWith(byte fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        return new FindingIdGenerator(
                new FindingIdProperties(Base64.getEncoder().encodeToString(key)));
    }

    private static FindingIdentity budgetFinding() {
        return FindingIdentity.of(
                RuleType.BUDGET_RISK, RuleScope.team(TEAM, "Payments"), "2026-02");
    }

    /**
     * The property the contract promises: a finding keeps its id across restarts and instances. A
     * separately constructed generator with the same configured key must agree.
     */
    @Test
    void theSameIdentityAndKeyAlwaysProduceTheSameIdentifier() {
        assertThat(generatorWith((byte) 0x2A).publicId(ORG_A, budgetFinding()))
                .isEqualTo(generatorWith((byte) 0x2A).publicId(ORG_A, budgetFinding()));
    }

    @Test
    void differentOrganisationsNeverShareAnIdentifier() {
        assertThat(generatorWith((byte) 0x2A).publicId(ORG_A, budgetFinding()))
                .isNotEqualTo(generatorWith((byte) 0x2A).publicId(ORG_B, budgetFinding()));
    }

    @Test
    void aDifferentKeyProducesADifferentIdentifier() {
        assertThat(generatorWith((byte) 0x2A).publicId(ORG_A, budgetFinding()))
                .isNotEqualTo(generatorWith((byte) 0x3B).publicId(ORG_A, budgetFinding()));
    }

    /**
     * Every identity component must change the output, or two genuinely different findings would
     * collide and the panel would silently show one where there are two.
     */
    @Test
    void everyIdentityComponentChangesTheIdentifier() {
        FindingIdGenerator generator = generatorWith((byte) 0x2A);
        String base = generator.publicId(ORG_A, budgetFinding());
        RuleScope team = RuleScope.team(TEAM, "Payments");

        assertThat(generator.publicId(ORG_A,
                        FindingIdentity.of(RuleType.TASK_FAILURE_SPIKE, team, "2026-02")))
                .isNotEqualTo(base);
        assertThat(generator.publicId(ORG_A,
                        FindingIdentity.of(RuleType.BUDGET_RISK, team, "2026-01")))
                .isNotEqualTo(base);
        assertThat(generator.publicId(ORG_A, FindingIdentity.of(RuleType.BUDGET_RISK,
                        RuleScope.team(UUID.randomUUID(), "Payments"), "2026-02")))
                .isNotEqualTo(base);
        assertThat(generator.publicId(ORG_A, FindingIdentity.of(RuleType.BUDGET_RISK,
                        RuleScope.repository(TEAM, "repo-api"), "2026-02")))
                .isNotEqualTo(base);
    }

    /**
     * Components are length-prefixed, so no rearrangement of the same characters across adjacent
     * fields can collide. Plain concatenation would make these two identities produce one id, and
     * for a domain-bearing component that is a cross-scope collision, not a cosmetic one.
     */
    @Test
    void adjacentComponentsCannotBeReinterpretedIntoEachOther() {
        FindingIdGenerator generator = generatorWith((byte) 0x2A);
        RuleScope team = RuleScope.team(TEAM, "Payments");

        String first = generator.publicId(ORG_A, FindingIdentity.ofDomain(
                RuleType.NETWORK_POLICY_FRICTION, team, "2026-01-16/2026-01-31", "ab"));
        String second = generator.publicId(ORG_A, FindingIdentity.ofDomain(
                RuleType.NETWORK_POLICY_FRICTION, team, "2026-01-16/2026-01-31a", "b"));

        assertThat(first).isNotEqualTo(second);
    }

    /**
     * Evidence, severity, magnitude and rank are excluded from the input, so a finding recomputed
     * for a narrower scope keeps its id (AC-06.7). The identity carries none of them, and this test
     * is what stops one being added to it later without the consequence being noticed.
     */
    @Test
    void theIdentifierDependsOnNothingThatChangesWithTheData() {
        FindingIdentity identity = FindingIdentity.of(
                RuleType.TASK_FAILURE_SPIKE, RuleScope.team(TEAM, "Payments"), "2026-01-16/2026-01-31");
        FindingIdentity sameFindingRenamedScope = FindingIdentity.of(
                RuleType.TASK_FAILURE_SPIKE,
                RuleScope.team(TEAM, "Payments, renamed"), "2026-01-16/2026-01-31");

        FindingIdGenerator generator = generatorWith((byte) 0x2A);
        assertThat(generator.publicId(ORG_A, identity))
                .isEqualTo(generator.publicId(ORG_A, sameFindingRenamedScope));
    }

    /** The identifier must not leak the domain it was derived from. */
    @Test
    void theIdentifierRevealsNoDomain() {
        String id = generatorWith((byte) 0x2A).publicId(ORG_A, FindingIdentity.ofDomain(
                RuleType.NETWORK_POLICY_FRICTION, RuleScope.team(TEAM, "Payments"),
                "2026-01-16/2026-01-31", "internal-registry.corp"));

        assertThat(id).doesNotContain("internal", "registry", "corp");
        assertThat(Base64.getUrlDecoder().decode(id)).hasSize(32);
    }

    // --- Fail-closed configuration ------------------------------------------------------------

    @Test
    void anAbsentSecretIsRejectedRatherThanGenerated() {
        assertThatThrownBy(() -> new FindingIdProperties(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no generated fallback");
        assertThatThrownBy(() -> new FindingIdProperties(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FindingIdProperties("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMalformedOrUndersizedSecretIsRejected() {
        assertThatThrownBy(() -> new FindingIdProperties("not base64!!!").keyMaterial())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Base64");

        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new FindingIdProperties(tooShort).keyMaterial())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    /** A rejection must not echo the secret it rejected. */
    @Test
    void aRejectionNeverEchoesTheConfiguredValue() {
        String secret = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new FindingIdProperties(secret).keyMaterial())
                .hasMessageNotContaining(secret);
    }
}
