-- M4: one manifest for the atomic two-tenant demo installation. Never used by normal startup.
CREATE TABLE seed_manifest (
    dataset_id        TEXT PRIMARY KEY,
    dataset_version   TEXT NOT NULL,
    seed              BIGINT NOT NULL,
    expected_counts   JSONB NOT NULL,
    business_checksum TEXT NOT NULL,
    installed_at      TIMESTAMPTZ NOT NULL
);
