package com.fleet.analytics.data;

import java.util.Objects;
import java.util.UUID;

/** An identified, named tenant entity — a team or a repository — as served to the client. */
public record NamedEntity(UUID id, String name) {

    public NamedEntity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
