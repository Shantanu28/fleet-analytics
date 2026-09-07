package com.fleet.analytics.metrics.model;

import java.util.Objects;

/**
 * Why a value or comparison is not shown: a stable machine-readable code and the sentence the user
 * reads. Both travel together because an unavailable result must never render as a good one — not
 * as 0, not as "flat", not as a neutral badge (contract 1.2).
 *
 * <p>The text names the actual threshold and the observed population rather than saying "not enough
 * data", so a reader can tell how far short the sample fell without opening another view.
 */
public record Explanation(String code, String text) {

    public Explanation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(text, "text");
        if (code.isBlank() || text.isBlank()) {
            throw new IllegalArgumentException("an explanation needs both a code and text");
        }
    }
}
