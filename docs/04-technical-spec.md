# 04 — Technical specification

> Prototype implementation guide. The dashboard, authentication, analytics API, deterministic seeding and browser test runner are implemented. See the [execution record](06-plan.md) for verification and outstanding release checks.
> The [metrics contract](01-metrics-contract.md) owns calculations, [requirements](02-requirements.md) own behaviour, [architecture](03-architecture.md) owns production design, and the [testing strategy](05-testing-spec.md) owns verification. This document records implementation choices, not every test or development update.

## 1. Implementation and stack

React + TypeScript calls a Spring Boot API backed by PostgreSQL. Authentication, tenant-scoped queries and metric calculations are real; an explicit deterministic seeder supplies synthetic domain records. Kafka, ClickHouse, external ingestion and production reporting jobs are not part of this prototype.

| Choice | Why |
|---|---|
| Java 25 + Spring Boot 4.1.1 | Backend framework, validation and Spring Security |
| PostgreSQL 18.6 | Relational queries and transaction consistency; performance must be measured |
| jOOQ + Flyway | Typed SQL generated from migrated DDL; no JPA/Hibernate |
| React + TypeScript + Vite | Single-page UI with typed API integration |
| TanStack Query | Server-state caching, cancellation and identity-aware isolation |
| JUnit/Testcontainers; Vitest/React Testing Library | Backend/database and frontend testing |
| RS256 JWT + Argon2id | Approved prototype authentication choices ([Authentication and redaction](#51-authentication-and-redaction)) |

Exact dependencies belong to [Maven configuration](../pom.xml), the [backend POM](../backend/pom.xml), [package.json](../frontend/package.json) and its [lockfile](../frontend/package-lock.json), not a duplicated version catalogue here. Spring Boot manages backend versions except explicitly pinned unmanaged dependencies (including BouncyCastle and the test OpenAPI validator). New dependencies require approval.

Use Java 25, a Docker-compatible runtime with Compose, and a Node version supported by the installed tooling. The current Vitest dependency excludes Node 20; the recorded toolchain used Node 26. Compatibility fixes and prior build evidence belong in development history rather than this specification.

## 2. Structure and ownership

| Location / package | Owns |
|---|---|
| `backend/.../web` | HTTP validation, serialization, errors and redaction; no metric formulas |
| `backend/.../security` | Authentication and verified principal → organisation/role |
| `backend/.../data` | jOOQ selection, eligibility, joins and population totals |
| `backend/.../metrics` | Derived values, gates, states, deltas and ranking; no SQL or HTTP |
| `backend/.../seed` | Explicit dataset generation/installation; never normal startup |
| `backend/src/main/resources/db/migration/` | Versioned DDL |
| `frontend/src/` | Views, typed fetch client, session and URL state |
| `contracts/openapi.yaml` | Authoritative HTTP contract for implemented endpoints |

Maven builds the backend; npm builds the frontend. The Makefile sequences them. Generate jOOQ sources from the migrated database; do not commit generated types. These packages are implemented.

The main code paths are small enough to follow directly:

```text
backend/src/main/java/com/fleet/analytics/
  security/          verified identity and token handling
  web/               controllers, request/response types and orchestration
  data/              tenant-scoped SQL
  metrics/           calculations and finding rules
  seed/              deterministic data and safe installation
frontend/src/
  App.tsx            login/dashboard boundary
  auth/session.tsx   in-memory identity and sign-out
  api/               typed HTTP client and response types
  dashboard/         selection state, query hook and display sections
contracts/openapi.yaml
scripts/             local launchers and isolated browser runner
```

In React, `DashboardPage` coordinates the page. The selection hook reads and updates the URL;
the query hook fetches the selected dashboard; section components render the response.
TanStack Query is not a database or a metric engine. It manages request state and cached responses.
In Java, follow the dashboard controller/service into `data` for SQL and `metrics` for arithmetic.
The controller does not calculate percentages and React does not repeat backend formulas.

## 3. Data model and lifecycle

Tasks hold owner and creation-time team/repository attribution. Runs belong to tasks; usage belongs to runs; PRs link to their task and run; denial events always name a task and may name a run. Parent joins supply eligibility and attribution rather than duplicated child fields.

Task status and run status are separate. The schema permits retries, but demo data has one run and at most one PR per task. Only completed demo tasks have PRs. PR approval/reopen history is outside P0.

A PR's `terminal_state` and `terminal_at` are both null while open. For merged or closed-unmerged PRs, `terminal_at` means the contract's `merged_at` or `closed_at`, respectively. This null means non-terminal, not missing-source data.

The [schema reference](reference/prototype-schema.md) preserves column definitions, tenant-safe foreign keys, lifecycle constraints, source dependencies and index guidance. Migrations V1–V4 implement the business schema; V5 adds token revocation. The reference explains constraints without replacing executable DDL.

## 4. Request and calculation flow

Verified identity → authorised filters → tenant-scoped SQL in one `REPEATABLE READ` transaction → Java metric derivation → role-redacted response.

SQL selects populations and returns counts/sums; Java performs division, comparisons, gates and ranking. Both follow the metrics contract. Each aggregate uses one grain: never join independent one-to-many branches and then sum usage. Failure-reason evidence is separate from task-grain failure counts.

| Population | Timestamp / key |
|---|---|
| PR outcomes | `pull_request.terminal_at`, interpreted using terminal state |
| Completion | `task.terminal_at` |
| Spend | `usage_record.metered_at`, through run → task |
| Active seats | `task.created_at`, distinct owners restricted to licensed users |
| Funnel | Tasks created in range; linked stages observed before `dataThrough` |
| Network friction | `denial_event.occurred_at`, distinct tasks and owners |

PR eligibility also checks code-change task type and repository default branch. Unit-cost spend includes all execution outcomes for eligible tasks. The benchmark drops only the team predicate; budget uses its own calendar-month window. Zero-fill confirmed empty trend days, never incomplete days.

Use exact integer/decimal arithmetic and round only for display. PostgreSQL `sum(bigint)` returns `numeric`; do not narrow totals prematurely. Compare thresholds by exact cross-multiplication.

## 5. API contract

The [OpenAPI document](../contracts/openapi.yaml) defines the implemented login, logout, context and dashboard endpoints.

| Endpoint | Purpose / status |
|---|---|
| `POST /api/v1/auth/login` | Credentials → access token and identity; implemented |
| `POST /api/v1/auth/logout` | Revoke the presented token; 204 after commit, no body; implemented |
| `GET /api/v1/analytics/context` | Organisation, filters, coverage, seats and role; implemented |
| `GET /api/v1/analytics/dashboard` | All dashboard sections for one selection; implemented |

Dashboard parameters: `from`, `to` (inclusive UTC dates), `teamId`, `repositoryId`, and `grouping` (teams/repositories). Default: last 30 complete days. Any custom range inside publication coverage is valid; 90 days is not a maximum.

The response carries publication coverage/revision, per-source completeness for the windows actually used, resolved filters and all sections. Metric values include value/comparison states and explanatory reasons; findings include evaluation limits. The frontend must not infer missing states from a nullable number.

Publication interval comes from `dataset_publication`, not event min/max. `source_day_coverage` determines completeness independently for current, baseline, budget and funnel-observation windows. Missing data affects dependents only; baseline gaps suppress comparisons rather than current values. Details: [A.5](reference/prototype-schema.md#a5-coverage-and-revision).

Use sanitised `application/problem+json` errors: invalid dates or unknown/foreign filters → 400; invalid/missing authentication → 401; insufficient permission → 403. Foreign and nonexistent filter IDs are indistinguishable. Never expose stack traces, secrets or internal domain-bearing IDs.

### 5.1 Authentication and redaction

- Normalise globally unique usernames by trimming/lowercasing. Login accepts username/password, not organisation. Resolve tenant and role from the verified identity; team filters are analytical filters, not permission boundaries.
- Issue 15-minute RS256 JWTs. Spring Security restricts the algorithm and validates signature, issuer, audience, timestamps and required `jti/sub/org/role/iss/aud/iat/exp` claims. Token ID, subject and organisation must be UUIDs; roles are ADMIN/VIEWER. Issuer/audience are identifiers, not OIDC discovery URLs.
- Use the configured 60-second clock-skew tolerance and an injected clock for deterministic tests.
- Hash passwords with Argon2id through `DelegatingPasswordEncoder` and the Spring Security v5.8 defaults: 16-byte salt, 32-byte hash, parallelism 1, 16384 KiB memory, 2 iterations. Keep the encoding prefix and random salt.
- Configured PEM keys (`fleet.jwt.private-key-location`/`public-key-location`, loaded from any Spring resource location) take precedence and are never replaced on failure: missing, unreadable, malformed, undersized or mismatched material fails startup under every profile. Ephemeral RSA-2048 generation requires **both** `fleet.jwt.dev-keys-enabled` and an allowed profile (`dev` or `test`) — the flag alone does nothing elsewhere. Generated keys are per application context, so restarts invalidate tokens and instances cannot share them. The `demo` profile is the sole switch permitting accounts marked `is_demo_account` to log in. Without it they are rejected; ordinary accounts are unaffected. Never activate `demo` in production. Development keys alone do not enable demo logins.
- Tokens stay in browser memory. Each login has a random `jti`, so separate logins receive distinct tokens. Sign-out calls the logout API and immediately clears the local token, requests and protected caches. A network/server failure shows a warning; it never restores the old session or changes a newer login. Reload loses the local token but does not call logout.
- Logout stores only the verified `jti` in PostgreSQL, never the bearer token. Revocation follows that signed identity, not the token's text encoding. Every authenticated request validates the signature/claims first, then checks that shared store without an acceptance cache. Logout returns 204 only after commit; token reuse (including another logout) returns 401. Other logins remain valid. Requests authenticated before revocation may finish.
- Revoked IDs remain through token expiry plus the 60-second clock-skew allowance. Successful logout transactions also remove records strictly older than that boundary. Records may linger when no one logs out; no scheduler or new infrastructure is required. Store-read failure denies access with a sanitised 503; revocation-write failure returns a sanitised 500, never a false success. Shared keys and the same database are required across API instances.
- Refresh tokens, logout-all-sessions, registration/reset, SSO, rate limiting and production key rotation are outside this prototype.
- VIEWER responses omit raw denied domains and internal domain-bearing strings throughout the serialized body; evidence counts survive. Finding identity stays inside the rule engine for deduplication and ranking; the API returns no finding ID. Navigation applies the supplied filters and shows fresh results, without tracking or highlighting an individual finding across responses.

### 5.2 Frontend integration

URLSearchParams and browser history own date/filter/grouping state; no router library is required. TanStack Query owns authenticated server state through a typed fetch client.

Query keys include user, organisation, role, session generation, endpoint and all request parameters—never the token. Forward AbortSignal to fetch. Loading a new selection must not display the previous selection's results; stale responses must not replace the current view.

Logout, expiry and identity changes cancel protected work and clear caches. Disable automatic retry for 400/401 and background refetch by default. Render API states directly; do not recalculate metrics.

Reload requires sign-in again, after which URL state is restored. A different organisation's account must not reuse foreign team/repository IDs: handle them through the explicit `unknown-filter` behaviour, not silent dropping. Tenant-independent date/grouping state survives.

## 6. Demo data

Two organisations are installed together, atomically, by an explicit Spring Boot seed-profile command.

| Configuration | Organisation A | Organisation B |
|---|---|---|
| Engineers / assigned seats | 56 / 56 | 10 / 10 |
| Teams | 7 × 8 engineers | 2 × 5 engineers |
| Repositories | 14 | 20 |
| Tasks | 20,000 | 2,000 |
| Interval | 180 complete UTC days | Same interval |
| Accounts | One ADMIN, one VIEWER | One ADMIN, one VIEWER |

Demo accounts are existing engineers, not extra seats. Repositories may be shared across teams. Generate distinct per-tenant activity, not a rescaled copy. Keep the ten-task/five-PR contract fixture separate.

The fixed dates below allow at least three complete budget-month days. Coverage supports the latest presets and baselines, not every historical custom-range comparison.

Required scenarios: low samples, an empty filter combination, zero outcomes, cross-period merges, triggering/non-triggering budgets, qualifying network friction and an unevaluated rule. Preserve the browser investigation target: a qualifying `repo-api` failure spike within `Payments`, visible under starting filters with a budget finding ranked above it.

| Existing state | Installer outcome |
|---|---|
| Empty tables, no manifest | Install |
| Matching manifest and validated data | No-op, exit 0 |
| Rows without manifest | Refuse without changes |
| Incompatible/incomplete/inconsistent dataset | Refuse without changes |

Take a transaction-scoped advisory lock before inspecting state. One transaction installs both tenants; no truncate, repair or ordinary-startup seeding. One manifest records dataset version, seed and per-tenant expected counts; validate these before the canonical business-data checksum.

Derive stable IDs from dataset/seed, organisation, entity type and source ID. Include relationships in deterministic checksumming; exclude password hashes/salts, install timestamps and publication rows. Each organisation's publication revision copies the resulting checksum. Never make password hashing deterministic. The README publishes credentials only for these synthetic demo accounts.

### Dataset configuration

Implemented dataset `fleet-demo`, version `1`, seed `20260907`, with
`dataAvailableFrom=2026-03-05T00:00:00Z` and exclusive `dataThrough=2026-09-01T00:00:00Z`.
August has 31 complete budget days. These are fixed synthetic dates, not a live data feed.

- A: 20,000 tasks/runs/usage records, 12,370 PRs, 430 denial events, 7 budget rows.
  B: 2,000 tasks/runs/usage records, 600 PRs, 98 denial events, 3 budget rows.
  Each has 1,440 complete source-day rows and one publication; remaining counts match the table above.
- August budgets: A organisation 2,000,000 cents; Payments 140,000; other configured A teams
  300,000 each; Labs intentionally unconfigured. B organisation 500,000; both teams 300,000 each.
  Payments MTD/forecast is 173,224 cents, a 23.7314…% overrun (`HIGH`). All other configured
  scopes are below budget. No floating-point accumulation is used.
- Investigation starting filters: inclusive **2026-08-02–2026-08-31**, team **Payments**, no
  repository restriction. `repo-api`: **96/240 failed** versus **23/224** in the preceding 28 days,
  a **29.7321… percentage-point rise**. Both samples qualify. The HIGH Payments budget must rank
  above this MEDIUM failure finding. `SeedDashboardApiTest` verifies this ordering through the M3 endpoint.
- Latest presets and their comparison/failure windows are covered. A failure finding is required
  at the stated starting filters, not under every preset. Historical custom comparisons can lack baselines.
- Payments + `repo-retired` is empty. Labs + `repo-experimental` has six failed tasks (low sample,
  zero completion); `repo-retired` has 40 closed-unmerged PRs and positive spend (zero merge rate).
  The dataset also contains cross-period merges, non-code spend/denials, historical team attribution,
  and repeated denials qualifying by distinct tasks and owners. March 5 is complete and known-empty.
- Exactly two published logins per tenant are drawn from the engineer population. As clarified
  during M4, other engineers have VIEWER roles and undisclosed random passwords. Every seeded user
  retains the existing demo-account safeguard. Public credentials and safe commands are in the [README](../README.md#try-it-locally).

Seed-owned API cases exercise the real authenticated
filter chain, OpenAPI validation, latest presets against each tenant's ledger, finding link patches
and destination responses, empty/sparse/zero-outcome states, missing historical baselines, tenant
isolation and VIEWER redaction with unchanged counts. Separate component and browser suites verify history and rendering.
The seed launcher needs no JWT keys. Finding calculations require no secret configuration.

Installer checks actual per-tenant counts and canonical business rows, then publication and password
validity. It excludes random hashes/salts, installation timestamps and publication rows from the
checksum; source-day coverage is included. Each publication copies the checksum. There is no repair path.

## 7. Local development

| Command | Current behaviour |
|---|---|
| `make setup` | PostgreSQL readiness → Flyway migration → jOOQ generation → npm dependencies |
| `make test` | Maven verification, frontend tests, type-check and production build |
| `make seed` | Explicit [Demo data](#6-demo-data) installer; requires an intentional `DB_URL` |
| `make dev` | Backend (`dev` profile, generated keys) and the Vite dev server, started and stopped together |
| `make e2e` | Browser journeys against an isolated database, servers and ports the runner owns |

Run setup before compiling: code generation needs a migrated database, not compiled application code. Integration tests use separate ephemeral Testcontainers databases. The Makefile propagates failures and does not delete data. Its Colima socket adaptation is specific to that runtime, not universal Docker configuration.

Two configuration channels exist and both must be set to the same target. Flyway and jOOQ read the Maven properties `db.url`/`db.user`/`db.password`; the application and the seed launcher read the environment variables `DB_URL`/`DB_USER`/`DB_PASSWORD`. Setting only one migrates one database and runs against another. `spring-boot:run` forks the lifecycle through code generation, so it needs both. `make e2e` derives both from one resolved value, creates and removes its own PostgreSQL container, refuses to reuse or stop a server already listening on its ports, and never signals a process by name or by the port it holds.

Configure datasource, JWT keys and issuer/audience through environment/profiles; activate the `demo` profile only for synthetic demo logins. Never commit real credentials or private keys. Synthetic local database credentials are not production secrets. Use the [README quickstart](../README.md#try-it-locally) and [testing strategy](05-testing-spec.md) for commands; the [execution record](06-plan.md) distinguishes local results from unverified CI.

### If port 5432 is already in use

Use a new, deliberately named local container instead of stopping another checkout's database.
These commands do not remove existing data. Choose another unused name if this one already exists.

```bash
docker run --detach --name fleet-demo-postgres \
  -e POSTGRES_USER=fleet -e POSTGRES_PASSWORD=fleet -e POSTGRES_DB=fleet \
  -p 127.0.0.1::5432 postgres:18.6-alpine
docker exec fleet-demo-postgres pg_isready -U fleet -d fleet
```

Wait until the readiness check succeeds, then use the assigned port:

```bash
export DB_URL="jdbc:postgresql://$(docker port fleet-demo-postgres 5432/tcp)/fleet"
export DB_USER=fleet DB_PASSWORD=fleet
export MAVEN_ARGS="-Ddb.url=$DB_URL -Ddb.user=$DB_USER -Ddb.password=$DB_PASSWORD"
./mvnw -pl backend flyway:migrate generate-sources
(cd frontend && npm ci)
make seed
```

Continue with the backend and frontend launch commands in the README, in this same environment.
`MAVEN_ARGS` supplies the build-time database properties; `DB_URL` and companions supply runtime
configuration. Do not run `make setup` for this alternative: its Compose target uses port 5432.
`make dev` alone does not enable demo accounts; the README's explicit `dev,demo` launch does.

## 8. Decisions and remaining configuration

These are choices for a working assignment prototype, not benchmark results or a claim of
production capacity. The alternatives below explain the decision; they were not all implemented
and experimentally compared.

### One endpoint or several?

**Chosen: one dashboard endpoint.** A filter change asks for one coordinated answer containing the
cards, trends, funnel, comparisons and findings. The frontend handles one request lifecycle, and
the backend controls the reporting revision. One endpoint alone does not guarantee consistency.

**Alternative: one endpoint per section.** Sections could load, retry and be cached independently.
The frontend would need to coordinate filters and reporting revisions across responses, or clearly
show that sections represent different snapshots. This becomes attractive when sections have
different refresh needs or one expensive section should not delay the rest.

The current response waits for all sections. That is a latency coupling, not a reason to accept
poor performance. Splitting the endpoint is an option, not the first assumed fix.

### Sequential queries or parallel queries?

**Chosen: sequential SQL inside one read-only repeatable-read transaction.** A merge arriving
between the KPI and trend queries cannot make those sections disagree. PostgreSQL keeps the
transaction's reads on the same snapshot. The [concurrent-write test](../backend/src/test/java/com/fleet/analytics/DashboardSnapshotConsistencyTest.java)
checks this behaviour. [PostgreSQL isolation reference](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-REPEATABLE-READ).

| Alternative | Benefit | Additional work or limitation |
|---|---|---|
| Consolidate compatible aggregates into fewer SQL statements | Fewer round trips and potentially fewer repeated scans | Preserve each metric's grain; avoid joins that multiply spend |
| Run independent queries concurrently on separate connections | Can reduce elapsed time when database capacity permits | Extra connections/load; independent transactions need explicit snapshot coordination |
| Read a versioned, precomputed reporting dataset | Fast, reusable reads with a shared reporting version | Build refresh, correction, publication and backfill logic |

Parallel reads are not inherently inconsistent: PostgreSQL supports exporting/importing snapshots
between transactions. That coordination is not implemented here. A JDBC connection is not treated
as a pool of independent parallel queries. [Snapshot synchronization](https://www.postgresql.org/docs/18/functions-admin.html#FUNCTIONS-SNAPSHOT-SYNCHRONIZATION).

Sequential execution was selected as a small, testable consistency boundary. Its cost is cumulative
query latency and a connection retained for the request. It is not claimed to be the fastest option.

### What changes with millions of records?

Total record count alone is not the sizing input: measure rows scanned for one tenant/range,
largest-tenant size, request concurrency, cold/warm latency and database resource use.

1. **Profile the existing request.** Time its queries, inspect plans and consolidate repeated work.
   Compare against an agreed latency target; do not blame a cold request on JIT or sequential SQL
   without evidence.
2. **Reduce repeated calculation.** Precompute frequently used counts/sums where useful. Preserve
   detailed task/PR links for funnels and exact distinct-user calculations; daily ratios or distinct
   counts cannot simply be added together.
3. **Use the proposed analytical read path when justified.** The production design separates
   PostgreSQL metadata from ClickHouse analytical facts and selective aggregates. It requires a
   tested publication protocol so all requested sections read a coherent version.
4. **Add bounded concurrency and caching where measurements support them.** Keep cache keys scoped
   to tenant, permissions, filters and reporting revision. Concurrency limits protect the database;
   more threads do not create more database capacity.
5. **Keep or split the HTTP response based on the user experience.** The current endpoint can
   assemble optimised reads. Separate section endpoints are useful if independent rendering and
   failure recovery become more valuable than a single response.

This is the [production direction](03-architecture.md), not infrastructure already implemented.
The reported cold request remains an open performance investigation in the
[execution record](06-plan.md#remaining-release-checks); consistency does not justify leaving it slow.

### Other settled choices

| Choice | Alternative and accepted cost |
|---|---|
| Normalised parent attribution | Duplicated analytical dimensions can reduce joins but require consistent updates |
| Generated jOOQ types | Handwritten SQL avoids database-backed code generation but loses its generated schema checks |
| Short-lived in-memory JWTs with PostgreSQL revocation | Opaque server sessions are another option; retaining JWTs means a shared token-ID lookup on every authenticated request, so authentication is no longer stateless |
| TanStack Query | Manual fetch/state handling avoids a dependency but requires custom caching and cancellation logic |

RS256, Argon2id, the [dataset configuration](#dataset-configuration), Vitest/React Testing Library
and Playwright Chromium are settled. Node is pinned by `.nvmrc`. New dependencies require approval.
Further browsers, accessibility-audit tooling, test sharding and numeric coverage gates are not added.

Older UI screenshots predate login; written acceptance criteria take precedence. Production
architecture and storage choices remain separate from this prototype.

## Appendix A — Schema reference

Detailed A.1–A.6 tables were moved to [Prototype schema reference](reference/prototype-schema.md), alongside the executable migrations.

### A.5 Coverage and revision

The coverage model referenced by migration comments is documented in [schema reference A.5](reference/prototype-schema.md#a5-coverage-and-revision).
