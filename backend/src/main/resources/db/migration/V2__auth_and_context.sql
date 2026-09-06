-- M2: identity, tenancy and the context endpoint's metadata.
-- Only the tables this milestone needs. Task/run/PR/usage tables arrive with M3.

CREATE TABLE team (
    id               UUID PRIMARY KEY,
    org_id           UUID NOT NULL REFERENCES organisation (id),
    source           TEXT NOT NULL,
    source_entity_id TEXT NOT NULL,
    source_version   BIGINT NOT NULL DEFAULT 0,
    name             TEXT NOT NULL,
    UNIQUE (org_id, source, source_entity_id),
    UNIQUE (org_id, id)                              -- referenced by composite tenant-safe FKs
);

CREATE TABLE repository (
    id               UUID PRIMARY KEY,
    org_id           UUID NOT NULL REFERENCES organisation (id),
    source           TEXT NOT NULL,
    source_entity_id TEXT NOT NULL,
    source_version   BIGINT NOT NULL DEFAULT 0,
    name             TEXT NOT NULL,
    default_branch   TEXT NOT NULL,
    UNIQUE (org_id, source, source_entity_id),
    UNIQUE (org_id, id)
);

CREATE TABLE app_user (
    id               UUID PRIMARY KEY,
    org_id           UUID NOT NULL REFERENCES organisation (id),
    team_id          UUID NOT NULL,
    source           TEXT NOT NULL,
    source_entity_id TEXT NOT NULL,
    source_version   BIGINT NOT NULL DEFAULT 0,
    username         TEXT NOT NULL,                  -- stored already normalised (trim + lowercase)
    display_name     TEXT NOT NULL,
    password_hash    TEXT NOT NULL,                  -- DelegatingPasswordEncoder form, prefix retained
    role             TEXT NOT NULL,
    is_demo_account  BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (org_id, source, source_entity_id),
    UNIQUE (org_id, id),
    CONSTRAINT app_user_username_key UNIQUE (username),   -- globally unique, not per organisation
    CONSTRAINT app_user_role_check CHECK (role IN ('ADMIN', 'VIEWER')),
    CONSTRAINT app_user_team_same_org FOREIGN KEY (org_id, team_id) REFERENCES team (org_id, id)
);

CREATE TABLE seat_licence (
    id               UUID PRIMARY KEY,
    org_id           UUID NOT NULL REFERENCES organisation (id),
    source           TEXT NOT NULL,
    source_entity_id TEXT NOT NULL,
    source_version   BIGINT NOT NULL DEFAULT 0,
    user_id          UUID NULL,
    assigned_at      TIMESTAMPTZ NULL,
    UNIQUE (org_id, source, source_entity_id),
    CONSTRAINT seat_licence_assignment_consistent CHECK ((user_id IS NULL) = (assigned_at IS NULL)),
    CONSTRAINT seat_licence_user_same_org FOREIGN KEY (org_id, user_id) REFERENCES app_user (org_id, id)
);

-- one seat per assigned user; unassigned seats may repeat
CREATE UNIQUE INDEX seat_licence_one_per_user ON seat_licence (org_id, user_id) WHERE user_id IS NOT NULL;

-- What interval does this organisation publish? (04 Appendix A.5)
CREATE TABLE dataset_publication (
    org_id              UUID PRIMARY KEY REFERENCES organisation (id),
    data_available_from TIMESTAMPTZ NOT NULL,
    data_through        TIMESTAMPTZ NOT NULL,
    revision            TEXT NOT NULL
);

-- Is a given logical source complete for a given day? (04 Appendix A.5)
CREATE TABLE source_day_coverage (
    org_id         UUID NOT NULL REFERENCES organisation (id),
    logical_source TEXT NOT NULL,
    day            DATE NOT NULL,
    is_complete    BOOLEAN NOT NULL,
    PRIMARY KEY (org_id, logical_source, day)
);

CREATE INDEX app_user_org_idx ON app_user (org_id);
CREATE INDEX team_org_idx ON team (org_id);
CREATE INDEX repository_org_idx ON repository (org_id);
