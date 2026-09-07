package com.fleet.analytics.seed;

/** Refusal is non-destructive: the caller must select a fresh database, never repair in place. */
public final class SeedRefusedException extends RuntimeException {
    public SeedRefusedException(String reason) {
        super("Demo installation refused: " + reason + ". No data was changed.");
    }
}
