# Prototype schema reference

> Detailed prototype schema design retained from technical spec Appendix A. [Migrations](../../backend/src/main/resources/db/migration/) define implemented DDL: V1/V2 cover foundation, identity and context metadata; task/run/PR/usage/denial/budget and seed-manifest work remains planned. The tables below describe the complete target, not a claim that all constraints already exist.
> Bare §3–§6 references refer to the [technical spec](../04-technical-spec.md); “contract” refers to the [metrics contract](../01-metrics-contract.md). A.1–A.6 are local sections. Production event schemas are [separate](event-schemas.md).

### A.1 Table classes

Four classes have different identity rules: `organisation` does not reference itself, and metadata tables do not carry upstream-source identity.

| Class | Tables | Primary key | Upstream source identity |
|---|---|---|---|
| **Organisation root** | `organisation` | `id UUID` | no `org_id` column — it *is* the tenant |
| **Tenant-owned domain** | `team`, `repository`, `app_user`, `seat_licence`, `budget`, `task`, `run`, `pull_request`, `usage_record`, `denial_event` | `id UUID` | yes: `source`, `source_entity_id`, `source_version`, with `UNIQUE (org_id, source, source_entity_id)` |
| **Dataset metadata** | `dataset_publication`, `source_day_coverage` | natural keys (below) | no — describes the dataset, not an upstream record |
| **Seed manifest** | `seed_manifest` | `dataset_id TEXT` | no |

`source` is the **provider label** (`demo-seed` throughout the prototype). It is not the same thing as a **logical source** — `tasks`, `runs`, `pull_requests`, `repositories`, `usage`, `denials`, `budgets`, `seats` — which is what coverage and `missing_data` reason about. Both organisations are installed by the same provider, so the provider label does not distinguish tenants; `org_id` does.

### A.2 Columns

| Table | Columns beyond `id` and identity |
|---|---|
| `organisation` | `name TEXT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL` |
| `team` | `org_id`, `name TEXT NOT NULL` |
| `repository` | `org_id`, `name TEXT NOT NULL`, `default_branch TEXT NOT NULL` |
| `app_user` | `org_id`, `team_id UUID NOT NULL`, `username TEXT NOT NULL` (normalised), `display_name TEXT NOT NULL`, `password_hash TEXT NOT NULL`, `role TEXT NOT NULL`, `is_demo_account BOOLEAN NOT NULL DEFAULT false` |
| `seat_licence` | `org_id`, `user_id UUID NULL`, `assigned_at TIMESTAMPTZ NULL` |
| `budget` | `org_id`, `team_id UUID NULL` (NULL = organisation scope), `period_month DATE NOT NULL`, `amount_cents BIGINT NOT NULL` |
| `task` | `org_id`, `team_id`, `repo_id`, `user_id` `UUID NOT NULL`; `task_type TEXT NOT NULL`; `created_at TIMESTAMPTZ NOT NULL`; `terminal_status TEXT NULL`; `terminal_at TIMESTAMPTZ NULL` |
| `run` | `org_id`, `task_id UUID NOT NULL`, `attempt_no INT NOT NULL`, `started_at TIMESTAMPTZ NOT NULL`, `ended_at TIMESTAMPTZ NULL`, `run_status TEXT NOT NULL`, `failure_reason TEXT NULL` |
| `pull_request` | `org_id`, `task_id UUID NOT NULL`, `run_id UUID NOT NULL`, `target_branch TEXT NOT NULL`, `opened_at TIMESTAMPTZ NOT NULL`, `terminal_state TEXT NULL`, `terminal_at TIMESTAMPTZ NULL` |
| `usage_record` | `org_id`, `run_id UUID NOT NULL`, `metered_at TIMESTAMPTZ NOT NULL`, `cost_cents BIGINT NOT NULL`, `model_tier TEXT NOT NULL` |
| `denial_event` | `org_id`, `task_id UUID NOT NULL`, `run_id UUID NULL`, `domain_raw TEXT NOT NULL`, `domain_normalised TEXT NOT NULL`, `occurred_at TIMESTAMPTZ NOT NULL` |
| `dataset_publication` | PK `org_id`; `data_available_from`, `data_through` `TIMESTAMPTZ NOT NULL`; `revision TEXT NOT NULL` |
| `source_day_coverage` | PK `(org_id, logical_source, day)`; `day DATE NOT NULL`, `is_complete BOOLEAN NOT NULL` |
| `seed_manifest` | PK `dataset_id`; `dataset_version TEXT NOT NULL`, `seed BIGINT NOT NULL`, `expected_counts JSONB NOT NULL`, `business_checksum TEXT NOT NULL`, `installed_at TIMESTAMPTZ NOT NULL` |

All timestamps are `TIMESTAMPTZ` in UTC; all money is `BIGINT` USD cents (contract §1.1).

### A.3 Referenced unique keys, and the foreign keys that need them

Composite foreign keys require a matching unique constraint on the parent (PostgreSQL 18 manual). These parent keys exist **for that purpose**:

`organisation (id)` · `team (org_id, id)` · `repository (org_id, id)` · `app_user (org_id, id)` · `task (org_id, id)` · `run (org_id, id)` · `run (org_id, task_id, id)`

Every tenant-safe foreign key, in full:

| Child | Foreign key | Parent key |
|---|---|---|
| every tenant-owned table | `(org_id)` | `organisation (id)` |
| `app_user` | `(org_id, team_id)` | `team (org_id, id)` |
| `task` | `(org_id, team_id)` | `team (org_id, id)` |
| `task` | `(org_id, repo_id)` | `repository (org_id, id)` |
| `task` | `(org_id, user_id)` | `app_user (org_id, id)` |
| `run` | `(org_id, task_id)` | `task (org_id, id)` |
| `usage_record` | `(org_id, run_id)` | `run (org_id, id)` |
| `pull_request` | `(org_id, task_id)` | `task (org_id, id)` |
| `pull_request` | `(org_id, task_id, run_id)` | `run (org_id, task_id, id)` — the PR's run belongs to the PR's own task |
| `denial_event` | `(org_id, task_id)` | `task (org_id, id)` — **always enforced** |
| `denial_event` | `(org_id, task_id, run_id)` | `run (org_id, task_id, id)` — checked only when `run_id` is present |
| `seat_licence` | `(org_id, user_id)` | `app_user (org_id, id)`, optional |
| `budget` | `(org_id, team_id)` | `team (org_id, id)`, optional |
| `source_day_coverage`, `dataset_publication` | `(org_id)` | `organisation (id)` |

**Why `denial_event` needs two separate foreign keys.** PostgreSQL's default `MATCH SIMPLE` means *"a referencing row need not satisfy the foreign key constraint if any of its referencing columns are null"*. A single `(org_id, task_id, run_id)` key would therefore be skipped entirely whenever `run_id` is NULL — taking the task check with it. Splitting them keeps the task reference always enforced, while the run reference is validated only when a run is named. `MATCH FULL` is not the answer here: it would reject a NULL `run_id` alongside a non-null `task_id`, which is a legitimate row.

**Task attribution is historical.** `task.team_id` records the team **at creation** and is never validated against `app_user.team_id`, which is the owner's *current* team. A reorganisation must not restate history.

**Repositories are shared.** No exclusive team ownership and no team–repository mapping table: a repository belongs to the organisation, and any team's tasks may target it.

### A.4 Lifecycle and value constraints

| Rule | Mechanism | Enforced by |
|---|---|---|
| Enumerated values | `CHECK`: `task_type IN ('bugfix','feature','refactor','tests','dependency_update','repo_question','research')`; `terminal_status IN ('completed','failed','cancelled')`; `run_status IN ('running','completed','failed','cancelled')`; `terminal_state IN ('merged','closed_unmerged')`; `failure_reason IN ('agent_gave_up','tests_failed','timeout','internal_error','rate_limited','sandbox_denied')`; `role IN ('ADMIN','VIEWER')` | DB |
| Task status/time agree | `CHECK ((terminal_status IS NULL) = (terminal_at IS NULL))`, `CHECK (terminal_at IS NULL OR terminal_at >= created_at)` | DB |
| PR status/time agree | `CHECK ((terminal_state IS NULL) = (terminal_at IS NULL))`, `CHECK (terminal_at IS NULL OR terminal_at >= opened_at)` | DB |
| One PR per task | `pull_request UNIQUE (org_id, task_id)` — a v1 product rule (`00-research.md` §1.1) | DB |
| Run attempts | `run UNIQUE (org_id, task_id, attempt_no)`, `CHECK (attempt_no >= 1)` | DB |
| Run status/end time | `CHECK ((run_status = 'running') = (ended_at IS NULL))`, `CHECK (ended_at IS NULL OR ended_at >= started_at)` | DB |
| Failure reason presence | `CHECK ((run_status = 'failed') = (failure_reason IS NOT NULL))` | DB |
| Budget month is canonical | `CHECK (EXTRACT(DAY FROM period_month) = 1)` — a calendar-month key, never a mid-month date | DB |
| Budget uniqueness with NULL team | `UNIQUE NULLS NOT DISTINCT (org_id, team_id, period_month)`; the default treats NULLs as distinct, which would accept duplicate organisation budgets | DB |
| `amount_cents` may be ≤ 0 | deliberately **no** positivity constraint: contract §6.1 treats `budget ≤ 0` as a reportable `invalid_budget_configuration` | DB (deliberate absence) |
| Usage cost values | `CHECK (cost_cents >= 0)`. Refunds and credits are **not modelled**; introducing negative cost is a scope change, not a data detail | DB |
| Seat assignment consistency | `CHECK ((user_id IS NULL) = (assigned_at IS NULL))`; partial unique index `(org_id, user_id) WHERE user_id IS NOT NULL` gives one seat per assigned user | DB |
| Login identity | `username` globally `UNIQUE`, not per organisation; stored already normalised (trimmed, lowercased) so the constraint and the lookup agree. **No plaintext password column exists** | DB |
| Fixed licence population | each organisation's seat count (§6), all assigned, unchanging across demo history; no seat-history model exists | seed |
| Exactly one run per task | deliberately **not** a constraint — the schema keeps retries representable | seed |
| PRs only on completed tasks; `opened_at ≥ task.terminal_at`; `target_branch = repository.default_branch` | cross-row invariants spanning tables, which a `CHECK` cannot express | seed, asserted by integration tests |

A `CHECK` constraint sees only its own row. Every rule above that compares two tables is therefore a **seed validation**, not a database guarantee, and is listed as such rather than implied to be enforced.

### A.5 Coverage and revision

Two questions, deliberately stored apart:

- **What interval does this organisation publish?** `dataset_publication` — **one row per organisation**, giving `data_available_from`, `data_through` and `revision`. These are the envelope's values (§5), resolved for the caller's own tenant. Both demo organisations publish the same 180-day interval but keep independent rows, so they can diverge without a schema change. `revision` is copied from the seed manifest's `business_checksum` (§6).
- **Is a given logical source complete for a given day?** `source_day_coverage`, keyed `(org_id, logical_source, day)`. A window is complete for a source when every day it spans has a row with `is_complete = true`.

Day grain is the smallest representation that answers the required cases, because a whole-dataset boolean cannot say "complete now, incomplete in the baseline". It supports: a covered period with no activity (rows present, `is_complete = true`, no matching business rows → defined zeros); a current period missing a required source (`missing_data`, including counts); an available current period with an unavailable baseline (`no_baseline` for comparisons, `not_evaluated` for rules); a budget month-to-date outside the selected range, evaluated on its own days; and funnel observation continuing to `dataThrough`. At the published interval × 8 logical sources × 2 organisations this is a few thousand rows — metadata, not an ingestion system.

**Two different failures, two different responses.**

| Situation | Response |
|---|---|
| Requested range falls outside the organisation's published interval | **reject the request** (contract §1.5) — no partial answer |
| Range is inside the interval, but a source-day row is missing or incomplete | **the dependent metrics are unavailable**; everything else still renders. This is never a whole-request rejection |

**Absent source-day metadata inside the published interval means not covered** for that source — unknown, never "no activity". Coverage is never derived from the earliest or latest event, because empty days are valid data.

Baseline windows and the budget month are evaluated **independently** against the same metadata: a complete current window with an incomplete baseline yields `no_baseline` and `not_evaluated`, and the budget month is judged on its own days regardless of the selected range.

**Required sources per metric family** — a missing dependency degrades exactly these and nothing else:

| Metric family | Required logical sources |
|---|---|
| Merged PRs, merge rate | `pull_requests`, `tasks` (eligibility), `repositories` (default branch) |
| Cost per merged PR | the above plus `usage`, `runs` |
| Task completion rate | `tasks` |
| Funnel — started, completed, failed, cancelled, in progress | `tasks` |
| Funnel — PR opened, PR merged | `tasks`, `pull_requests`, `repositories` |
| Spend and spend trend | `usage`, `runs`, `tasks` |
| Active seats | `tasks`, `seats` |
| Budget findings | `usage`, `runs`, `tasks`, `budgets` |
| Failure spike | `tasks`, and `runs` for the reason shown as evidence |
| Merge decline | `pull_requests`, `tasks`, `repositories` |
| Network friction | `denials`, `tasks` |

Completion rate and the funnel are listed apart on purpose: they are not one dependency. The completion rate needs only tasks, and so do the funnel's task-only stages — but the two PR stages need pull-request and repository data as well, and become **unavailable** without it rather than dropping to zero.

The publication and source-day tables are implemented in migration V2. Population and coverage-query behaviour is still part of the planned analytics work.

### A.6 Indexing strategy

Index the timestamp each metric filters on (contract §2), with `org_id` leading so tenant scope is the first predicate, plus the foreign keys behind the parent joins in §4. Partial indexes suit the merged-PR and terminal-PR predicates, and the cohort and completion queries split naturally by `created_at` versus `terminal_at`. **These are starting points, not measured results** — no claim is made that any index makes a query faster or index-only until `EXPLAIN (ANALYZE, BUFFERS)` has been run against the demo dataset (§6).

Correctness-enforcing uniqueness is not a tuning choice and is listed above: `(org_id, source, source_entity_id)`, the parent keys in A.3, `pull_request (org_id, task_id)`, `run (org_id, task_id, attempt_no)`, the budget `NULLS NOT DISTINCT` key, the partial seat index, and `username`.
