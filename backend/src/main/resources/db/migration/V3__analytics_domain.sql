-- M3 analytics domain: budgets and the task/run/PR/usage/denial graph the metrics contract reads.
-- Columns and constraints follow docs/reference/prototype-schema.md A.2-A.4. V1 and V2 are not
-- modified. `seed_manifest` belongs to M4 and is deliberately absent here.
--
-- Two rules recur below and are load-bearing for tenant safety:
--   * every parent reference is composite and carries org_id, so a child can never point at
--     another organisation's row;
--   * `UNIQUE (org_id, source, source_entity_id)` makes upstream identity unique per tenant. That
--     is a database guarantee. It is NOT a claim that production ingestion is idempotent
--     (contract 1.3.7), which remains a property of the fixtures only.

-- Monthly spend allowance. team_id NULL means the organisation scope.
CREATE TABLE budget (
    id               UUID PRIMARY KEY,
    org_id           UUID NOT NULL REFERENCES organisation (id),
    team_id          UUID NULL,
    source           TEXT NOT NULL,
    source_entity_id TEXT NOT NULL,
    source_version   BIGINT NOT NULL DEFAULT 0,
    period_month     DATE NOT NULL,
    amount_cents     BIGINT NOT NULL,
    UNIQUE (org_id, source, source_entity_id),
    CONSTRAINT budget_team_same_org FOREIGN KEY (org_id, team_id) REFERENCES team (org_id, id),
    -- A calendar-month key, never a mid-month date.
    CONSTRAINT budget_month_is_canonical CHECK (EXTRACT(DAY FROM period_month) = 1),
    -- NULLS NOT DISTINCT is required: the default treats NULL team_id as distinct and would
    -- accept duplicate organisation-scope budgets for the same month.
    CONSTRAINT budget_scope_month_key UNIQUE NULLS NOT DISTINCT (org_id, team_id, period_month)
    -- Deliberately no positivity check on amount_cents: contract 6.1 reports budget <= 0 as
    -- invalid_budget_configuration, which requires the row to exist and be readable.
);

-- A delegated unit of work. team_id and repo_id are attribution captured AT CREATION and are
-- never reconciled against the owner's current team: a reorganisation must not restate history.
CREATE TABLE task (
    id               UUID PRIMARY KEY,
    org_id           UUID NOT NULL REFERENCES organisation (id),
    team_id          UUID NOT NULL,
    repo_id          UUID NOT NULL,
    user_id          UUID NOT NULL,
    source           TEXT NOT NULL,
    source_entity_id TEXT NOT NULL,
    source_version   BIGINT NOT NULL DEFAULT 0,
    task_type        TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    terminal_status  TEXT NULL,
    terminal_at      TIMESTAMPTZ NULL,
    UNIQUE (org_id, source, source_entity_id),
    UNIQUE (org_id, id),                             -- referenced by run, pull_request, denial_event
    CONSTRAINT task_team_same_org FOREIGN KEY (org_id, team_id) REFERENCES team (org_id, id),
    CONSTRAINT task_repo_same_org FOREIGN KEY (org_id, repo_id) REFERENCES repository (org_id, id),
    CONSTRAINT task_user_same_org FOREIGN KEY (org_id, user_id) REFERENCES app_user (org_id, id),
    CONSTRAINT task_type_check CHECK (task_type IN (
        'bugfix', 'feature', 'refactor', 'tests', 'dependency_update', 'repo_question', 'research')),
    CONSTRAINT task_terminal_status_check CHECK (
        terminal_status IS NULL OR terminal_status IN ('completed', 'failed', 'cancelled')),
    -- A status without a time, or a time without a status, is not a representable state.
    CONSTRAINT task_status_time_agree CHECK ((terminal_status IS NULL) = (terminal_at IS NULL)),
    CONSTRAINT task_terminal_not_before_created CHECK (terminal_at IS NULL OR terminal_at >= created_at)
);

-- One execution attempt. The schema keeps retries representable even though demo fixtures
-- generate exactly one run per task -- that limit is a fixture property, not a constraint.
CREATE TABLE run (
    id               UUID PRIMARY KEY,
    org_id           UUID NOT NULL REFERENCES organisation (id),
    task_id          UUID NOT NULL,
    source           TEXT NOT NULL,
    source_entity_id TEXT NOT NULL,
    source_version   BIGINT NOT NULL DEFAULT 0,
    attempt_no       INT NOT NULL,
    started_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ NULL,
    run_status       TEXT NOT NULL,
    failure_reason   TEXT NULL,
    UNIQUE (org_id, source, source_entity_id),
    UNIQUE (org_id, id),                             -- referenced by usage_record
    -- Referenced by pull_request and denial_event so a named run must belong to the named task.
    CONSTRAINT run_task_and_id_key UNIQUE (org_id, task_id, id),
    CONSTRAINT run_attempt_key UNIQUE (org_id, task_id, attempt_no),
    CONSTRAINT run_task_same_org FOREIGN KEY (org_id, task_id) REFERENCES task (org_id, id),
    CONSTRAINT run_attempt_positive CHECK (attempt_no >= 1),
    CONSTRAINT run_status_check CHECK (run_status IN ('running', 'completed', 'failed', 'cancelled')),
    CONSTRAINT run_running_has_no_end CHECK ((run_status = 'running') = (ended_at IS NULL)),
    CONSTRAINT run_end_not_before_start CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT run_failure_reason_presence CHECK ((run_status = 'failed') = (failure_reason IS NOT NULL)),
    CONSTRAINT run_failure_reason_check CHECK (failure_reason IS NULL OR failure_reason IN (
        'agent_gave_up', 'tests_failed', 'timeout', 'internal_error', 'rate_limited', 'sandbox_denied'))
);

-- At most one PR per task (a v1 product rule). terminal_state and terminal_at are both NULL while
-- the PR is open: that NULL means non-terminal, never missing-source data.
CREATE TABLE pull_request (
    id               UUID PRIMARY KEY,
    org_id           UUID NOT NULL REFERENCES organisation (id),
    task_id          UUID NOT NULL,
    run_id           UUID NOT NULL,
    source           TEXT NOT NULL,
    source_entity_id TEXT NOT NULL,
    source_version   BIGINT NOT NULL DEFAULT 0,
    target_branch    TEXT NOT NULL,
    opened_at        TIMESTAMPTZ NOT NULL,
    terminal_state   TEXT NULL,
    terminal_at      TIMESTAMPTZ NULL,
    UNIQUE (org_id, source, source_entity_id),
    CONSTRAINT pull_request_one_per_task UNIQUE (org_id, task_id),
    CONSTRAINT pull_request_task_same_org FOREIGN KEY (org_id, task_id) REFERENCES task (org_id, id),
    -- The PR's run must belong to the PR's own task, not merely to the same organisation.
    CONSTRAINT pull_request_run_same_task FOREIGN KEY (org_id, task_id, run_id)
        REFERENCES run (org_id, task_id, id),
    CONSTRAINT pull_request_state_check CHECK (
        terminal_state IS NULL OR terminal_state IN ('merged', 'closed_unmerged')),
    CONSTRAINT pull_request_state_time_agree CHECK ((terminal_state IS NULL) = (terminal_at IS NULL)),
    CONSTRAINT pull_request_terminal_not_before_opened CHECK (
        terminal_at IS NULL OR terminal_at >= opened_at)
);

-- Metered cost, recognised whole at metered_at and never prorated across days (contract 2).
CREATE TABLE usage_record (
    id               UUID PRIMARY KEY,
    org_id           UUID NOT NULL REFERENCES organisation (id),
    run_id           UUID NOT NULL,
    source           TEXT NOT NULL,
    source_entity_id TEXT NOT NULL,
    source_version   BIGINT NOT NULL DEFAULT 0,
    metered_at       TIMESTAMPTZ NOT NULL,
    cost_cents       BIGINT NOT NULL,
    model_tier       TEXT NOT NULL,
    UNIQUE (org_id, source, source_entity_id),
    CONSTRAINT usage_record_run_same_org FOREIGN KEY (org_id, run_id) REFERENCES run (org_id, id),
    -- Refunds and credits are not modelled; negative cost would be a scope change (approved D-1).
    CONSTRAINT usage_record_cost_non_negative CHECK (cost_cents >= 0)
);

-- A sandbox network denial. Always attributable to a task; a run is named only when known.
CREATE TABLE denial_event (
    id                UUID PRIMARY KEY,
    org_id            UUID NOT NULL REFERENCES organisation (id),
    task_id           UUID NOT NULL,
    run_id            UUID NULL,
    source            TEXT NOT NULL,
    source_entity_id  TEXT NOT NULL,
    source_version    BIGINT NOT NULL DEFAULT 0,
    domain_raw        TEXT NOT NULL,
    domain_normalised TEXT NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,
    UNIQUE (org_id, source, source_entity_id),
    -- Two separate foreign keys, deliberately. PostgreSQL's default MATCH SIMPLE skips a composite
    -- constraint entirely when ANY referencing column is NULL, so a single
    -- (org_id, task_id, run_id) key would stop checking the task whenever run_id is NULL --
    -- silently losing tenant safety on exactly the rows that have no run. MATCH FULL is not the
    -- answer either: it would reject a NULL run_id beside a non-null task_id, a legitimate row.
    CONSTRAINT denial_event_task_same_org FOREIGN KEY (org_id, task_id) REFERENCES task (org_id, id),
    CONSTRAINT denial_event_run_same_task FOREIGN KEY (org_id, task_id, run_id)
        REFERENCES run (org_id, task_id, id)
);

-- Indexes: the timestamp each metric family filters on, org_id leading so tenant scope is the
-- first predicate, plus the foreign keys behind the parent joins. Starting points only -- no
-- claim is made that any of these makes a query faster until EXPLAIN (ANALYZE, BUFFERS) has been
-- run against the demo dataset. Columns already led by a unique constraint are not repeated:
-- run (org_id, task_id, ...), pull_request (org_id, task_id) and the budget scope key.
CREATE INDEX task_org_created_idx ON task (org_id, created_at);
CREATE INDEX task_org_terminal_idx ON task (org_id, terminal_at);
CREATE INDEX task_org_team_idx ON task (org_id, team_id);
CREATE INDEX task_org_repo_idx ON task (org_id, repo_id);
CREATE INDEX pull_request_org_terminal_idx ON pull_request (org_id, terminal_at);
CREATE INDEX usage_record_org_metered_idx ON usage_record (org_id, metered_at);
CREATE INDEX usage_record_org_run_idx ON usage_record (org_id, run_id);
CREATE INDEX denial_event_org_occurred_idx ON denial_event (org_id, occurred_at);
CREATE INDEX denial_event_org_task_idx ON denial_event (org_id, task_id);
