package com.fleet.analytics.web.error;

import java.net.URI;

/** The stable {@code type} values the API contract declares. */
public final class ProblemTypes {

    public static final URI UNAUTHENTICATED = URI.create("urn:fleet:problem:unauthenticated");
    public static final URI INVALID_CREDENTIALS = URI.create("urn:fleet:problem:invalid-credentials");
    public static final URI FORBIDDEN = URI.create("urn:fleet:problem:forbidden");
    public static final URI INVALID_REQUEST = URI.create("urn:fleet:problem:invalid-request");
    public static final URI METHOD_NOT_ALLOWED = URI.create("urn:fleet:problem:method-not-allowed");
    public static final URI UNSUPPORTED_MEDIA_TYPE = URI.create("urn:fleet:problem:unsupported-media-type");
    public static final URI NOT_ACCEPTABLE = URI.create("urn:fleet:problem:not-acceptable");
    public static final URI CONTEXT_UNAVAILABLE = URI.create("urn:fleet:problem:context-unavailable");

    // Dashboard selection failures. Deliberately distinct types rather than one "bad request": the
    // client keys a controlled message on them, and telling a user their range is reversed is
    // actionable where "invalid input" is not.
    public static final URI INVALID_DATE_FORMAT = URI.create("urn:fleet:problem:invalid-date-format");
    public static final URI REVERSED_DATE_RANGE = URI.create("urn:fleet:problem:reversed-date-range");
    public static final URI INCOMPLETE_DATE_RANGE =
            URI.create("urn:fleet:problem:incomplete-date-range");
    public static final URI RANGE_OUTSIDE_COVERAGE =
            URI.create("urn:fleet:problem:range-outside-coverage");
    /** Unknown, malformed and foreign identifiers all report this, and must stay indistinguishable. */
    public static final URI UNKNOWN_FILTER = URI.create("urn:fleet:problem:unknown-filter");
    public static final URI INVALID_GROUPING = URI.create("urn:fleet:problem:invalid-grouping");
    public static final URI INTERNAL_ERROR = URI.create("urn:fleet:problem:internal-error");

    private ProblemTypes() {}
}
