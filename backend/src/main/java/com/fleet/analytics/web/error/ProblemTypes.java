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
    public static final URI INTERNAL_ERROR = URI.create("urn:fleet:problem:internal-error");

    private ProblemTypes() {}
}
