package com.fleet.analytics.data;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * The organisation's published reporting interval {@code [dataAvailableFrom, dataThrough)}
 * (04 Appendix A.5). Never derived from the earliest or latest event: empty days are valid data.
 */
public record Coverage(OffsetDateTime dataAvailableFrom, OffsetDateTime dataThrough, String revision) {

    public Coverage {
        Objects.requireNonNull(dataAvailableFrom, "dataAvailableFrom");
        Objects.requireNonNull(dataThrough, "dataThrough");
        Objects.requireNonNull(revision, "revision");
        if (!dataThrough.isAfter(dataAvailableFrom)) {
            throw new IllegalArgumentException("dataThrough must follow dataAvailableFrom");
        }
    }
}
