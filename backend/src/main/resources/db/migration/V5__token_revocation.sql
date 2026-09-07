-- Only signed token IDs are stored, never bearer tokens. Runtime security state, not seed data.
CREATE TABLE revoked_token (
    token_id uuid PRIMARY KEY,
    retain_until timestamptz NOT NULL
);

CREATE INDEX revoked_token_retention_idx ON revoked_token (retain_until);
