package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * One comparison, in exactly one of two shapes: computed and carrying a display, or suppressed and
 * carrying an explanation. The compact constructor enforces that split, which is the same invariant
 * the OpenAPI schema enforces on the wire — availability is never inferred from a missing key.
 *
 * <p>{@code kind} is present on both shapes: a suppressed comparison still knows which comparison it
 * would have been, so the card can say what is missing without substituting another kind (AC-01.8).
 */
public record ComparisonResult(
        ComparisonKind kind, ComparisonState state, DisplayValue display, Explanation explanation) {

    public ComparisonResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        boolean computed = state == ComparisonState.OK;
        if (computed && display == null) {
            throw new IllegalArgumentException("a computed comparison must carry a display");
        }
        if (!computed && display != null) {
            throw new IllegalArgumentException("a suppressed comparison must not carry a display");
        }
        if (!computed && explanation == null) {
            throw new IllegalArgumentException("a suppressed comparison must explain itself");
        }
    }

    public static ComparisonResult computed(ComparisonKind kind, DisplayValue display) {
        return new ComparisonResult(kind, ComparisonState.OK, display, null);
    }

    public static ComparisonResult suppressed(
            ComparisonKind kind, ComparisonState state, Explanation explanation) {
        if (state == ComparisonState.OK) {
            throw new IllegalArgumentException("ok is not a suppression state");
        }
        return new ComparisonResult(kind, state, null, explanation);
    }

    public boolean isComputed() {
        return state == ComparisonState.OK;
    }
}
