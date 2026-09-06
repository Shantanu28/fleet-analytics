# 04 — Technical specification

> **Status: DRAFT.** **M1 (foundation) is implemented and verified**: build, migration, jOOQ generation, backend compile, one passing PostgreSQL integration smoke test, and a passing frontend type-check and production build. Everything else — login, the analytics API, the demo dataset, the dashboard — is still planned.
> `01-metrics-contract.md` remains authoritative for every calculation and state; this document says how they are stored, queried and served, and does not restate formulas.
> **Authentication is an approved scope change** superseding the earlier "no login" prototype exclusions in `02-requirements.md` §7.2 and `03-architecture.md`. §8 lists the documents needing a synchronisation pass.

## 1. Implementation overview and stack

The prototype consists of a **React frontend, a Spring Boot API and PostgreSQL**. Production packaging is not chosen here.

**Real:** the HTTP API, authentication with signed JWTs, tenant scoping, role-based redaction, the database and its migrations, and every metric calculation. Filters, comparisons, sample gates, findings and unavailable states are computed from stored rows — no per-chart canned responses.

**Synthetic:** the domain records themselves. A deterministic seeder installs a demo dataset (§6) in place of the upstream orchestrator, VCS, billing and policy sources described in `03-architecture.md`. No agent execution and no billing integration.

**Why this stack.** The metrics are relational aggregates over a few million rows at most, so PostgreSQL alone answers every query in the contract; nothing in P0 justifies a queue, cache or columnar store. jOOQ gives typed SQL without an ORM's object-graph machinery, which suits a read-only analytics surface with no write model. Flyway owning DDL keeps the schema reviewable as plain SQL. React with URL-encoded state matches a single bookmarkable page.

**Backend versions are Spring Boot-managed** — declared without version elements and resolved from `spring-boot-dependencies:4.1.1`. No overrides.

| Component | Version | Note |
|---|---|---|
| Java | 25 (LTS) | within Spring Boot 4.1.1's tested range (17 min, 26 max tested). `starter-parent` defaults `java.version` to 17, so the root POM sets it to 25 |
| Spring Boot | 4.1.1 | Spring Framework 7.0.9+; Maven 3.6.3+ |
| PostgreSQL | 18.6 | `UNIQUE NULLS NOT DISTINCT` and foreign-key `MATCH` semantics verified against the PostgreSQL 18 manual. Image pinned to `postgres:18.6-alpine` in Compose **and** in tests |
| jOOQ (Open Source) | **3.21.7** | Boot-managed |
| Flyway | **12.4.0** | Boot-managed; `flyway-database-postgresql` is a separate artifact, also added to the Maven plugin's own dependencies |
| PostgreSQL JDBC | 42.7.13 | Boot-managed |
| JUnit Jupiter | 6.0.3 | Boot-managed |
| Testcontainers | 2.0.5 | Boot-managed via `testcontainers-bom`. **2.x renamed the artifacts** — `testcontainers-postgresql`, `testcontainers-junit-jupiter`; the old `org.testcontainers:postgresql` no longer resolves. Container class is `org.testcontainers.postgresql.PostgreSQLContainer` |
| React / TypeScript | 19.2.8 / 7.0.2 | resolved by npm |
| Vite / `@vitejs/plugin-react` | 8.2.2 / 6.1.1 | Vite 8 requires Node `^20.19 \|\| >=22.12` |

**Maven plugin versions are inherited, not pinned.** `spring-boot-dependencies` manages 30 plugins in `pluginManagement`, including `flyway-maven-plugin` (`${flyway.version}`) and `jooq-codegen-maven` (`${jooq.version}`). Confirmed from the **effective POM**, which resolves them to 12.4.0 and 3.21.7 with no version declared by us.

**Verified by an executed build on 6 Sep 2026** (M1): jOOQ 3.21.7 generation and Flyway 12.4.0 migration both against PostgreSQL **18.6**, plus the TypeScript 7.0.2 / Vite 8.2.2 / Node 26 frontend toolchain. The PostgreSQL 17 fallback is no longer needed. PostgreSQL 18 moves the image volume to `/var/lib/postgresql` (`PGDATA=/var/lib/postgresql/18/docker`), which `docker-compose.yml` follows.

## 2. Project structure

```
fleet-analytics/
├── Makefile                      proposed thin wrapper (§7)
├── docker-compose.yml            PostgreSQL 18
├── mvnw · mvnw.cmd · .mvn/       Maven wrapper
├── pom.xml                       parent, packaging=pom, module: backend
├── contracts/openapi.yaml        authoritative HTTP contract
├── docs/                         00–05 specifications
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/…           web · security · metrics · data · seed
│       ├── main/resources/db/migration/   Flyway V*__*.sql
│       └── test/java/…           unit + integration (Testcontainers)
└── frontend/
    ├── package.json
    ├── src/                      components, hooks, API client
    ├── src/**/*.test.tsx         component tests (Vitest + RTL)
    └── e2e/                      browser journeys (Playwright)
```

The frontend is **not** a Maven module; wrapping npm in Maven would earn nothing at this size. Backend packages divide by concern:

| Package | Owns | Must not |
|---|---|---|
| `web` | HTTP, validation, problem responses, serialization, redaction | contain metric logic |
| `security` | JWT validation, principal → organisation + role | be bypassable by another package |
| `metrics` | every formula, gate, state, threshold and ranking (contract §3–§6) | know about HTTP or SQL |
| `data` | jOOQ queries behind the retrieval port (`03-architecture.md` §3.3) | contain metric semantics |
| `seed` | deterministic dataset generation and installation | run during ordinary startup |

## 3. Data model and lifecycle

Records form a **star around the task**, not a chain:

```
task ──< run ──< usage_record
  │       └──< denial_event (also references task directly)
  └──< pull_request  (0..1 in v1)
```

A task has one or more runs; a run has many usage records. A task has at most one pull request. Denial events belong to a task, optionally naming a run. Relationships are carried by explicit IDs and never inferred.

**Attribution is captured at task creation.** `task.team_id` and `task.repo_id` are written once and never updated, so a reorganisation cannot restate history. Children inherit attribution **by join**, not by stored copy.

**Eligibility.** `task.task_type` decides code-change (`bugfix`, `feature`, `refactor`, `tests`, `dependency_update`) versus non-code (`repo_question`, `research`). Outcome metrics, the funnel and the cost-per-merged-PR numerator take code-change tasks only; total spend, the spend trend, active seats and network-policy friction take all types (contract §1.4). Because `task_type` lives only on `task`, spend must join up to it — see §4.

**Task status versus run status.** A *run* is one execution attempt with its own status. A *task* is the engineering intent, and **its status is what every metric uses** — completion rate and the failure-spike rule count failed *tasks*, never failed attempts. The schema keeps the two separate so retries stay representable; the demo's one-run-per-task simplification is a seed rule, not a constraint. Production task lifecycle and retry ownership are proposed in `03-architecture.md` §7 and are not implemented here.

**Failure reasons for evidence.** The failure *rate* is task-grain. The *reason* shown in a failure-spike finding lives on `run.failure_reason`, grouped agent / platform / policy (contract §6.4). In the demo a failed task has exactly one run, so the reason is unambiguous. Reading it is a **separate aggregate over the runs of failed tasks**, never a join added to the task-grain count — that would descend a one-to-many branch while counting tasks. Choosing a reason when a production task has several failed attempts needs an explicit rule that does not exist yet.

**Pull-request states** — three, distinguished by two columns:

| State | `terminal_state` | `terminal_at` |
|---|---|---|
| Open | `NULL` | `NULL` |
| Merged | `merged` | present |
| Closed unmerged | `closed_unmerged` | present |

`NULL` here means **non-terminal**, not missing source data. A missing source is a separate condition that makes dependent metrics `missing_data` (contract §1.5). The two columns always agree, enforced by a check constraint (Appendix A).

**Mapping to the contract's terminology.** Contract §2 speaks of `merged_at` and `closed_at`; this schema stores one `terminal_at` whose meaning is given by `terminal_state`. `terminal_at` **is** `merged_at` when the state is `merged`, and **is** `closed_at` when it is `closed_unmerged`. One column, because a PR reaches exactly one terminal state and the contract never needs both timestamps at once.

PR **approval is not a lifecycle state** P0 needs: acceptance is "merged into the default branch" (contract §3.1). Reopening and review history remain excluded (contract §1.3.5); supporting them would require transition history and a contract revision, not a schema tweak.

## 4. Request and calculation flow

```
JWT verified → organisation + role resolved → filters validated against that organisation
   → tenant-scoped queries in one REPEATABLE READ transaction
   → metrics module derives values, states, gates, findings
   → response with coverage envelope, redacted for role
```

**Responsibility split.** SQL selects and aggregates populations — eligibility, filters, the window, and the sum or count at that population's own grain. **These are metric decisions too**: which rows are eligible and which timestamp places a row in a period come straight from contract §1.4 and §2, and SQL implements them. What SQL does *not* do is derive: division, deltas, comparison states, sample gates, thresholds, severity and ranking belong to the metrics module (contract §1.2, §3–§6). The line is between **selecting and totalling rows** and **deriving results from those totals**, and neither side reimplements the other.

**Snapshot consistency.** All queries for one dashboard response run in a single `REPEATABLE READ` transaction, so sections cannot disagree because a write landed between them.

| Metric | Population and joins | Timestamp | Grain |
|---|---|---|---|
| Merged agent PRs | `pull_request` → `task` (eligibility, filters) → `repository` (default branch) | `pull_request.terminal_at` | PR |
| Terminal merge rate | same, both terminal states | `pull_request.terminal_at` | PR |
| Cost numerator | `usage_record` → `run` → `task` (code-change only) | `usage_record.metered_at` | usage record |
| Total spend / trend | `usage_record` → `run` → `task` (filters only, all types) | `usage_record.metered_at` | usage record, by day |
| Completion rate | `task` (`terminal_status` in completed/failed) | `task.terminal_at` | task |
| Active seats | `task` semi-joined to `seat_licence` on the owner | `task.created_at` | distinct licensed `user_id` |
| Funnel | `task` LEFT JOIN `pull_request` → `repository` | `created_at`; stages `< dataThrough` | task |
| Failure reasons (evidence) | `run` of failed tasks | `task.terminal_at` | run |
| Network friction | `denial_event` → `task` (owner, scope; all types) | `denial_event.occurred_at` | distinct task, distinct owner |

Three of these carry requirements that are easy to miss:

- **PR eligibility is two conditions, not one.** A PR counts only if its task is an eligible code-change task **and** its `target_branch` equals its repository's `default_branch` (contract §1.4). That needs the `repository` hop, so PR metrics join three tables up.
- **Active seats counts licensed owners.** A task owner outside the licensed population must not inflate adoption (contract §3.5), so the count is `COUNT(DISTINCT task.user_id)` restricted by a semi-join to `seat_licence`. Under a team or repository filter the count still renders and utilisation becomes `unavailable_for_scope`.
- **Spend covers every outcome.** Eligible tasks that failed, were cancelled or are still running contribute their metered cost (contract §3.3); only `task_type` filters the cost numerator.
- **Funnel stages do not share one dependency.** *Tasks started*, *completed* and the side exits and residual need only task data. *PR opened* and *PR merged* additionally need pull-request and repository data. If PR data is unavailable, those two stages are **unavailable**, not zero — a zero would read as "the agent produced nothing", which is a different and much worse claim (Appendix A.5).

**Joins and aggregation.** Joining a child up to its parents (`usage_record → run → task`) is many-to-one at each hop and returns exactly one row per child; this is how eligibility and attribution are read. Fan-out comes from descending **two independent one-to-many branches in one query** — a task's runs *and* its PRs, for example — or from aggregating two grains in one `SELECT`. So: each aggregate covers one population at one grain, descending at most one branch. Cost per merged PR sums usage and counts PRs as two independent aggregates, divided by the metrics module; the two populations are never joined to each other. Integration tests assert the resulting totals against a task carrying several runs and usage records.

**Benchmark and row queries are the same query with a different filter set** (contract §5.4): the benchmark drops the team predicate and keeps the repository predicate. The team predicate is a parameter, never hard-coded.

**Trend zero-fill.** Days inside the selected range with no matching rows must render as `0`, not as gaps (contract §5.1). Aggregation returns only days that have rows, so the missing days are filled — by a generated date series in SQL or in the metrics module. Either is acceptable; leaving the gap is not.

**Aggregate types and exact arithmetic.** In PostgreSQL `sum(bigint)` returns **`numeric`** and `count()` returns `bigint`, so a cents total arrives in Java as an arbitrary-precision decimal, not a `long` — narrowing it early would risk silent loss. Division for cost per merged PR is performed on exact decimal types, never floating point, and is rounded only for display (contract §1.1). Rule thresholds are compared by integer cross-multiplication on the underlying counts.

## 5. API and security

`contracts/openapi.yaml` is authoritative; backend and frontend are both checked against it.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/auth/login` | credentials → access token + display identity |
| `GET /api/v1/analytics/context` | **organisation name**, teams, repositories, coverage, licensed seats, role — populates the header, filters and the cutoff label |
| `GET /api/v1/analytics/dashboard` | every section for one filter selection |

Health endpoints are separate (Actuator; exposure is a configuration choice).

**Dashboard parameters:** `from`, `to` (inclusive UTC dates mapped to a half-open interval, contract §1.1), `teamId`, `repositoryId`, `grouping` (`teams` \| `repositories`). Absent dates mean the last 30 complete days.

**Response envelope** — coverage travels with every response so no section can disagree with another:

```
{ coverage: { dataAvailableFrom, dataThrough, revision },
  sources:  [ { name, complete } ],
  filters:  { …echoed, resolved… },
  sections: { kpis, funnel, trends, findings, comparison } }
```

Each metric carries more than a nullable scalar, because the UI must explain itself:

```
{ value, valueState, explanation,          // why a value is null, zero or capped
  sample: { n, gate },                      // for "needs 15 terminal PRs" copy
  comparison: { delta, comparisonState, reason } }
```

The findings section carries the ranked findings **and** an `evaluationLimits` list — the scopes and rules that could not be evaluated, with a reason each — so the panel can render contract §6's five states without inferring them. States and explanations travel to the client; the client never derives one.

**Where the envelope's coverage values come from.** `dataAvailableFrom`, `dataThrough` and `revision` are the organisation's **published reporting interval**, read from one row of dataset publication metadata (Appendix A) — not computed from the earliest or latest event, since empty days are legitimate data. `sources[]` reports, **for the windows this request actually used**, whether each logical source is complete. Publication interval and per-source completeness are different questions and are stored separately.

**Range validity.** 7/30/90 days are presets, **not a maximum**. Any custom range wholly inside `[dataAvailableFrom, dataThrough)` is accepted. A range outside coverage is rejected. A valid range whose comparison period or 28-day baseline falls outside coverage is still answered — the value renders, the comparison reports `no_baseline`, affected rules report `not_evaluated` (contract §1.2, §1.5).

**Errors** are `application/problem+json` with a stable `type`, sanitised detail, and no internal identifiers or stack traces.

| Condition | Status | Type |
|---|---|---|
| Malformed, reversed or uncovered range | 400 | `invalid-date-range`, `range-not-covered` |
| Unknown or foreign team/repository id | 400 | `unknown-filter` |
| Missing, expired, malformed or tampered token | 401 | `unauthenticated` |
| Authenticated but not permitted | 403 | `forbidden` |

A foreign-organisation id returns the same `unknown-filter` as a nonexistent one, so the API cannot be used to probe another tenant's ids.

### 5.1 Authentication

**Login identity is a globally unique username.** `app_user.username` is normalised by **trimming and lowercasing**, applied identically at account creation and at login, and the normalised form is what is stored and compared. The login inputs are **username and password** — nothing else. The account lookup resolves the organisation and role, so **neither login nor any analytics endpoint accepts a client-selected organisation**. There is no registration, no organisation selection and no SSO; a real deployment would resolve a tenant during authentication.

**Flow.** `POST /api/v1/auth/login` takes a username and password, normalises the username, verifies the password hash and issues a JWT with a **15-minute** lifetime, carrying subject, organisation, role, issuer, audience, `iat` and `exp`. Validation uses **Spring Security's JWT resource-server support** — signature, issuer, audience and expiry — not hand-written parsing.

**Tenant scope comes from the verified token.** Every query takes `org_id` from the principal, and `org_id` leads every index.

**Roles.** `ADMIN` and `VIEWER` share one dashboard and see only their own organisation. **An `ADMIN` manages visibility inside its own tenant, not across tenants** — the role widens what is shown (denied domains), never which organisation is queried. Each organisation has one of each account, counted within its own engineer total (§6). **Team filters are analytical filters, not permission boundaries.**

**There is no organisation switcher.** To view another tenant a user logs out and logs in with that organisation's account.

**Redaction.** For `VIEWER`, a response must contain neither a **raw denied domain** nor an **internal domain-bearing identity string** — in any field, in generated finding text, in a link target or in a query parameter. Removal happens server-side at serialization; counts survive. **Stable keyed pseudonymous finding ids are explicitly permitted** and are the mechanism above; this precise rule replaces the earlier blanket prohibition on every domain-derived identifier, which would have ruled out a keyed HMAC that **does not expose the raw domain; stable correlation is accepted**.

**Finding identifiers — approved.** Contract §6.5's internal identity includes the normalised domain and is never serialised. The public id is **HMAC-SHA-256** over a canonical encoding of a **purpose/version prefix, the organisation id, and that internal identity**, keyed by a **dedicated server-side secret held separately from the JWT signing key**. Changing evidence, counts, severity and rank are excluded from the input, so the same finding keeps the same id across recomputation and reranking — which is exactly what AC-06.7 needs once a link is followed and findings are reranked for the narrower scope. Internal identity, deduplication and ranking are unchanged. A finding id **grants no access** and never substitutes for the tenant and role checks; it requires no extra endpoint, persistence table or session store. Repeated ids do let an observer correlate the same finding across responses: that is accepted, not a claim of anonymity, and visible counts, scopes and ordering already permit as much.

**Demo accounts are inert outside demo configuration** — installed by the seeder, enabled only under an explicit demo profile and property. Public demo credentials guard synthetic data only.

**Recommendations, not approved decisions:** RS256 signing (EdDSA a reasonable alternative) and Argon2id password hashing via `DelegatingPasswordEncoder` (bcrypt acceptable; it is adaptive rather than memory-hard). Either hashing choice uses a cryptographically secure random salt per password. **Signing keys and the finding-id HMAC secret come from configuration and stay outside Git; the HMAC secret is a distinct key, never reused JWT material.**

**Honest limitations.** 15-minute tokens with **no refresh** — reload or expiry requires logging in again. The token lives **in memory only**, never `localStorage`. Sign-out clears the token and all cached protected data but **does not revoke an already-issued token**, which stays valid until it expires. None of this is production hardening: no rotation, revocation, rate limiting or session management.

### 5.2 Frontend integration

- **URL is the state** — `URLSearchParams` plus `history.pushState`/`popstate`, no router library for a single page.
- **Server state is managed by TanStack Query** for the two authenticated GETs — `analytics/context` and `analytics/dashboard` — over a typed fetch client. URL parameters remain the source of truth for filters; the query reads them, it does not own them. Java remains responsible for every metric and for API authorisation.
- **Query keys include identity, organisation, role, endpoint and every relevant request parameter** — `(userId, orgId, role, endpoint, range, teamId, repositoryId, grouping)`. Two filter selections never share an entry.
- **Races** — TanStack Query's `AbortSignal` is passed through to `fetch`, so a superseded request is cancelled by the library rather than by a hand-written sequence number. The required behaviour is unchanged: while a new selection is loading, the page must **not** display the previous selection's results, and a late response for a superseded selection must never be rendered.
- **Sign-out, expiry and identity change clear protected caches and stop in-flight work**, so no response for a previous principal can repopulate the UI. Session isolation stays explicit: the cache is cleared on logout and on any change of identity or role, and the query keys above keep two principals' entries apart even before that.
- **JWTs live in memory only** — never in a query key, a persisted cache, or the URL.
- **Retry and refetch are configured deliberately.** Authentication failures (401) and validation failures (400) are **not** retried automatically; they surface to the user. Background refetching is off by default for this dashboard, whose data changes only when the dataset does.
- **Reauthenticating as the same user preserves the URL's filters.** Signing in as a **different organisation's** account does not silently reapply the previous tenant's team or repository ids: those ids do not exist in the new tenant, so they resolve through the existing invalid-filter behaviour (§5, `unknown-filter`) rather than being applied or silently dropped. The date range and grouping, which are tenant-independent, survive.
- **States are rendered, never inferred** — the client displays `valueState` and `comparisonState`; it never decides that `null` means zero.

## 6. Demo data

The shipped demo contains **two organisations, installed together by one explicit atomic seed operation**. This table is the single record of the configuration; the rest of the documentation refers to §6 rather than repeating counts.

| | Organisation A | Organisation B |
|---|---|---|
| Engineers | 56 | 10 |
| Purchased seats, all assigned | 56 | 10 |
| Teams | 7, of 8 engineers | 2, of 5 engineers |
| Repositories | 14, some shared across teams | 20, some shared across teams |
| Tasks | 20,000 | 2,000 |
| Interval | the same 180 complete UTC days | the same 180 complete UTC days |
| Accounts | one `ADMIN`, one `VIEWER` | one `ADMIN`, one `VIEWER` |

The two demo accounts in each organisation are **existing engineers**, not extra users, and consume no extra seats. Activity and spend are generated independently per organisation and are visibly different, so a reviewer switching accounts sees a different dashboard rather than a rescaled copy. Every record obeys contract §1.3 and §1.4 — one run and at most one PR per task.

Public demo credentials are listed once in the README when implementation exists; they are not repeated through these specifications, and no plaintext password is ever stored.

**Fixed clock.** `dataThrough` is a fixed UTC midnight baked into the dataset, not derived from the wall clock, so results are reproducible. Exact calendar dates are a generator setting to fix when the seeder is written; they must leave the evaluated budget month at least three complete days (contract §6.1).

**Coverage.** 180 days spans the latest 90-day preset plus its equal-length comparison period, and the 28-day failure baseline. It does **not** give every custom range a baseline — a range starting at `dataAvailableFrom` has none, and those comparisons report `no_baseline` while affected rules report `not_evaluated`. This matches contract §10.3.

**Separate hand-checkable fixture.** The ten-task, five-PR fixture in contract §7 is unchanged and is used by unit tests for arithmetic a reviewer can verify by hand. It is not the demo dataset, and neither replaces the other.

**Security fixtures are separate and smaller.** Isolation tests build their own minimal two-tenant fixtures; they do not load the full demonstration dataset. Organisation B is part of the shipped demo, not a test-only artefact.

**Required scenarios.** The generator produces, and asserts it has produced: normal volumes; a low-sample team below every gate (contract §5.4); an empty filter combination; a `zero_outcome` row; a cross-period merge (contract §8.3); a budget scope that triggers `MEDIUM` and one that does not; a network-friction domain clearing ≥5 tasks / ≥3 users; and at least one rule that is `not_evaluated`.

It also guarantees the browser journeys are reachable: a **failure-spike finding on `repo-api` within `Payments`**, meeting contract §6.2's ≥ 20 terminal tasks in *both* windows, **visible under the journey's starting filters and surviving the three-finding cap**, with a budget finding ranked above it so both targets are on screen at once.

**Installation is explicit and atomic** — invoked deliberately, never during ordinary startup, in one transaction. Four outcomes, decided before any write:

| Target state | Outcome |
|---|---|
| Tables empty, no manifest | install |
| Manifest matches request and installed data validates | no-op, exit 0 |
| Rows present without a manifest | refuse, change nothing, non-zero exit |
| Manifest incompatible, dataset incomplete, or data inconsistent | refuse, change nothing, non-zero exit |

There is no implicit truncate and no repair path; reinstalling requires a separate explicit drop.

**Concurrent invocations.** Two seeders started together must not both observe empty tables and both install. The installing transaction takes a **transaction-scoped advisory lock** on a fixed key before it inspects anything, so the second invocation waits and then observes the first one's committed result — reaching the no-op or refuse branch rather than racing it. The lock is released with the transaction, including on failure.

**One manifest covers the whole two-organisation installation.** There is a single `seed_manifest` row for the demo dataset; its `expected_counts` are recorded **per tenant**, so a partially installed or mismatched organisation is detected rather than averaged away. Validation compares `dataset_version`, `seed` and those per-tenant counts first — cheap checks that reject most mismatches immediately. Only if those agree does it compute the **canonical business-data checksum**, which reads the business rows of both organisations, serialised deterministically and ordered by `(org, source, source_entity_id)`.

**Manifest and publication revision.** Each organisation has **its own `dataset_publication` row**, carrying its own interval and `revision`. The revision is taken from the manifest's `business_checksum` — a value computed over the installed business data and stored on the manifest, then copied to each publication row. The publication rows are not themselves inputs to that checksum, so there is no self-reference.

**Business identifiers are deterministic and namespaced.** Each row's `id` is derived from **dataset and seed, organisation, entity type, and `source_entity_id`** (a UUIDv5-style derivation over that namespace tuple). The organisation and entity-type namespaces matter: both tenants may legitimately hold a local identifier such as `task-1`, and without them the two would collide. Identifiers are therefore stable across installs and **relationships are covered by the checksum** rather than excluded from it. This matters: a checksum over scalar columns alone would pass while a foreign key pointed at the wrong parent. If a future generator uses random ids instead, references must be canonicalised — each foreign key serialised as the referenced row's `source_entity_id` — before checksumming.

**Determinism covers canonical business data only.** The checksum and the seed-identity assertion exclude `password_hash` and its per-password random salt, and `installed_at`. Password hashing is never made deterministic to satisfy a test.

## 7. Local development

A thin **Makefile** wraps Maven, npm and Compose; those remain the real tools, and every target propagates the exit status of the command it runs. **No target deletes a database or drops data.**

| Target | Does | Prerequisite |
|---|---|---|
| `make setup` | starts PostgreSQL and waits for readiness, applies Flyway migrations, runs jOOQ generation, installs npm dependencies | none — run first |
| `make seed` | installs the demo dataset via the seeder's four-outcome decision (§6) | `setup` |
| `make dev` | runs the backend API and the frontend dev server | `setup`, and `seed` for meaningful data |
| `make test` | non-browser checks: backend unit, PostgreSQL integration and API tests, OpenAPI conformance, frontend component tests | `setup` |
| `make e2e` | browser journeys against a built frontend and a seeded database | `setup`, `seed` |

`setup` is required before anything else: a clean clone cannot compile until jOOQ has generated types, which needs a migrated database. That ordering — **PostgreSQL ready → Flyway → jOOQ → compile → test** — has no circular dependency, because Flyway and jOOQ need only SQL files and a JDBC connection, never compiled application code.

The build/codegen database is a long-lived local container. Integration tests use **ephemeral Testcontainers instances** migrated in test setup, so they never depend on codegen-database state.

The seeder is an ordinary Spring Boot entry point — a `CommandLineRunner` under a `seed` profile that performs the §6 decision and exits with the matching status. It is not a custom Maven plugin.

Configuration comes from environment variables or Spring profiles: datasource URL and credentials, JWT key location, issuer, audience, the finding-id HMAC secret, and the demo flag. **No secret is committed** — not signing keys, not the HMAC secret, not real credentials; the repository ships an example file with placeholders. **Public synthetic-demo credentials are a separate matter and may be documented in the README**, since they guard generated data only; stored account passwords are always hashed.

**M1 status.** `make setup` and `make test` have been executed successfully from a clean environment. Under **Colima** the host socket is not `/var/run/docker.sock`, but Testcontainers' resource reaper must bind-mount the in-VM path; the `Makefile` detects **that specific case** from the active Docker context and sets `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` itself, so no shell profile needs changing. Other VM-backed runtimes are not auto-detected — set those two variables yourself. `make setup` is **idempotent and non-destructive**: a second run reports "Schema is up to date. No migration necessary", regenerates jOOQ, and leaves existing rows intact — verified with a sentinel row that survived. `make seed`, `make dev` and `make e2e` are not implemented yet and exit non-zero with a message naming the milestone that adds them.

## 8. Trade-offs and open decisions

| Decision | Alternative | Accepted limitation | Reconsider when |
|---|---|---|---|
| Compute at query time from atomic rows | nightly rollups | response cost grows with range | a measured p95 on the demo dataset (§6) exceeds an agreed budget |
| One `REPEATABLE READ` transaction per request | per-section reads | holds a transaction for the request | read concurrency becomes a measured problem |
| Normalised schema; parent joins for eligibility and attribution | denormalise `task_type`/`team_id` onto child rows | extra joins per query | a measured plan shows the join dominates; denormalisation would then duplicate state |
| jOOQ generated from a migrated database, not committed | commit generated sources | clean clone needs a database and `make setup` before compiling | offline builds become a requirement |
| 15-minute tokens, no refresh, in memory | refresh tokens with rotation | reload forces re-login; sign-out cannot revoke | the demo is used by people who are not evaluating it |
| TanStack Query for server state | a hand-written fetch hook | one more frontend dependency | the surface simplifies enough that one uncached request without cancellation or race handling would do, or the measured dependency and maintenance cost outweighs the loading, caching, cancellation and race handling it removes |
| No JPA/Hibernate | Spring Data JPA | hand-written SQL for every metric | the app grows write paths, which P0 has none of |

**Settled in M1:** Vite as the frontend build tool, and every dependency version listed in §1, now resolved and exercised by a real build. **TanStack Query is approved but not yet integrated** — it arrives with the dashboard in M5.

**Open decisions:** JWT signature algorithm (RS256 recommended); password hashing (Argon2id recommended); the React component-test stack (Vitest + React Testing Library recommended); the browser-test tool (Playwright recommended); exact dataset calendar dates; and TanStack Query's version, unpinned until it is added.

**Outstanding synchronisation** — not edited here. The status, authentication, tenancy and coverage items previously listed have been applied to `README.md`, `CLAUDE.md`, `00-research.md`, `01-metrics-contract.md` and `02-requirements.md`; what remains is visual:

- **`02-requirements.md` §2 visual references** — `desktop-1440.png` and `mobile-375.png` predate authentication. They show no login page, and a header reading "Viewing as: platform admin (demo)" rather than the organisation name required by AC-09.13. §2's existing rule already settles which wins — follow the written criteria and flag the discrepancy — so the specification is unambiguous; the screenshots need regenerating once a UI exists.
- **`03-architecture.md` diagrams** — both still carry the superseded "immutable in-memory" and "no authentication" annotations, with an adjacent notice saying so. Redrawing them is outstanding.

## Appendix A — Schema reference

Migrations do not exist yet, so these decisions live here.

### A.1 Table classes

Four classes, with different identity rules. Conflating them was a defect in the previous draft: `organisation` does not reference itself, and metadata tables do not carry upstream-source identity.

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

**This coverage model is a proposed correction requiring approval.** It replaces a single `dataset_coverage` row carrying one `is_complete` boolean, which could not distinguish current-window from baseline-window availability — a case contract §1.2 and §1.5 both require.

### A.6 Indexing strategy

Index the timestamp each metric filters on (contract §2), with `org_id` leading so tenant scope is the first predicate, plus the foreign keys behind the parent joins in §4. Partial indexes suit the merged-PR and terminal-PR predicates, and the cohort and completion queries split naturally by `created_at` versus `terminal_at`. **These are starting points, not measured results** — no claim is made that any index makes a query faster or index-only until `EXPLAIN (ANALYZE, BUFFERS)` has been run against the demo dataset (§6).

Correctness-enforcing uniqueness is not a tuning choice and is listed above: `(org_id, source, source_entity_id)`, the parent keys in A.3, `pull_request (org_id, task_id)`, `run (org_id, task_id, attempt_no)`, the budget `NULLS NOT DISTINCT` key, the partial seat index, and `username`.
