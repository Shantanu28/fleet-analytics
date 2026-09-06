-- M1 baseline: the smallest real table that proves Flyway -> jOOQ -> compile.
-- The rest of the schema (docs/04-technical-spec.md Appendix A) lands in later milestones.
CREATE TABLE organisation (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
