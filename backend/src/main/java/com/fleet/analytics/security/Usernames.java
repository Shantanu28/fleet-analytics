package com.fleet.analytics.security;

import java.util.Locale;

/** Login identity is a globally unique normalised username (04 §5.1). */
public final class Usernames {

    private Usernames() {}

    /** Trim, then lowercase with the root locale. Applied at account creation and at login. */
    public static String normalise(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Null for absent or blank input, so callers never look up an empty username. */
    public static String normaliseOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalised = normalise(raw);
        return normalised.isEmpty() ? null : normalised;
    }
}
