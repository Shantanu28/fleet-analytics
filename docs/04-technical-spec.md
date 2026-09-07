# 04 — Technical specification

> Prototype implementation guide. Foundation, authentication/context API and React session code exist; the M3 dashboard API and M4 seeding are implemented. Frontend dashboard rendering remains separate work. This documentation edit did not rerun builds or tests.
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
| RS256 JWT + Argon2id | Approved prototype authentication choices (§5.1) |

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

Maven builds the backend; npm builds the frontend. The Makefile sequences them. Generate jOOQ sources from the migrated database; do not commit generated types. Some packages above are planned, not existing modules.

## 3. Data model and lifecycle

Tasks hold owner and creation-time team/repository attribution. Runs belong to tasks; usage belongs to runs; PRs link to their task and run; denial events always name a task and may name a run. Parent joins supply eligibility and attribution rather than duplicated child fields.

Task status and run status are separate. The schema permits retries, but demo data has one run and at most one PR per task. Only completed demo tasks have PRs. PR approval/reopen history is outside P0.

A PR's `terminal_state` and `terminal_at` are both null while open. For merged or closed-unmerged PRs, `terminal_at` means the contract's `merged_at` or `closed_at`, respectively. This null means non-terminal, not missing-source data.

The [schema reference](reference/prototype-schema.md) preserves column definitions, tenant-safe foreign keys, lifecycle constraints, source dependencies and index guidance. Migrations remain the evidence of what is actually implemented; remaining target tables must land through later migrations.

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

The [OpenAPI document](../contracts/openapi.yaml) defines the implemented login, context and dashboard endpoints.

| Endpoint | Purpose / status |
|---|---|
| `POST /api/v1/auth/login` | Credentials → access token and identity; implemented |
| `GET /api/v1/analytics/context` | Organisation, filters, coverage, seats and role; implemented |
| `GET /api/v1/analytics/dashboard` | All dashboard sections for one selection; implemented in M3 |

Dashboard parameters: `from`, `to` (inclusive UTC dates), `teamId`, `repositoryId`, and `grouping` (teams/repositories). Default: last 30 complete days. Any custom range inside publication coverage is valid; 90 days is not a maximum.

The response carries publication coverage/revision, per-source completeness for the windows actually used, resolved filters and all sections. Metric values include value/comparison states and explanatory reasons; findings include evaluation limits. The frontend must not infer missing states from a nullable number.

Publication interval comes from `dataset_publication`, not event min/max. `source_day_coverage` determines completeness independently for current, baseline, budget and funnel-observation windows. Missing data affects dependents only; baseline gaps suppress comparisons rather than current values. Details: [A.5](reference/prototype-schema.md#a5-coverage-and-revision).

Use sanitised `application/problem+json` errors: invalid dates or unknown/foreign filters → 400; invalid/missing authentication → 401; insufficient permission → 403. Foreign and nonexistent filter IDs are indistinguishable. Never expose stack traces, secrets or internal domain-bearing IDs.

### 5.1 Authentication and redaction

- Normalise globally unique usernames by trimming/lowercasing. Login accepts username/password, not organisation. Resolve tenant and role from the verified identity; team filters are analytical filters, not permission boundaries.
- Issue 15-minute RS256 JWTs. Spring Security restricts the algorithm and validates signature, issuer, audience, timestamps and required `sub/org/role/iss/aud/iat/exp` claims. Subject/organisation must be UUIDs; roles are ADMIN/VIEWER. Issuer/audience are identifiers, not OIDC discovery URLs.
- Use the configured 60-second clock-skew tolerance and an injected clock for deterministic tests.
- Hash passwords with Argon2id through `DelegatingPasswordEncoder` and the Spring Security v5.8 defaults: 16-byte salt, 32-byte hash, parallelism 1, 16384 KiB memory, 2 iterations. Keep the encoding prefix and random salt.
- Configured PEM keys (`fleet.jwt.private-key-location`/`public-key-location`, loaded from any Spring resource location) take precedence and are never replaced on failure: missing, unreadable, malformed, undersized or mismatched material fails startup under every profile. Ephemeral RSA-2048 generation requires **both** `fleet.jwt.dev-keys-enabled` and an allowed profile (`dev` or `test`) — the flag alone does nothing elsewhere. Generated keys are per application context, so restarts invalidate tokens and instances cannot share them. Demo accounts likewise require **both** the `demo` profile and `fleet.demo.accounts-enabled`; enabling development keys never enables them.
- Tokens stay in browser memory; no refresh/revocation. Logout clears local state but does not invalidate an issued token before expiry. Registration/reset, SSO, rate limiting and production key rotation are outside this prototype.
- VIEWER responses omit raw denied domains and internal domain-bearing strings throughout the serialized body; evidence counts survive. Public finding IDs use HMAC-SHA-256 with a dedicated secret over a canonical purpose/version prefix, organisation and internal identity. Exclude changing evidence/rank. IDs grant no access; stable correlation is accepted.

### 5.2 Frontend integration

URLSearchParams and browser history own date/filter/grouping state; no router library is required. TanStack Query owns authenticated server state through a typed fetch client.

Query keys include user, organisation, role, endpoint and all request parameters—never the token. Forward AbortSignal to fetch. Loading a new selection must not display the previous selection's results; stale responses must not replace the current view.

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

Fix `dataThrough` and the seed; exact calendar dates are selected with the generator and must allow at least three complete budget-month days. Coverage supports the latest presets and baselines, not every historical custom-range comparison.

Required scenarios: low samples, an empty filter combination, zero outcomes, cross-period merges, triggering/non-triggering budgets, qualifying network friction and an unevaluated rule. Preserve the browser investigation target: a qualifying `repo-api` failure spike within `Payments`, visible under starting filters with a budget finding ranked above it.

| Existing state | Installer outcome |
|---|---|
| Empty tables, no manifest | Install |
| Matching manifest and validated data | No-op, exit 0 |
| Rows without manifest | Refuse without changes |
| Incompatible/incomplete/inconsistent dataset | Refuse without changes |

Take a transaction-scoped advisory lock before inspecting state. One transaction installs both tenants; no truncate, repair or ordinary-startup seeding. One manifest records dataset version, seed and per-tenant expected counts; validate these before the canonical business-data checksum.

Derive stable IDs from dataset/seed, organisation, entity type and source ID. Include relationships in deterministic checksumming; exclude password hashes/salts, install timestamps and publication rows. Each organisation's publication revision copies the resulting checksum. Never make password hashing deterministic. Publish synthetic demo credentials only when seeding exists.

### M4 generator configuration and verification

Implemented dataset `fleet-demo`, version `1`, seed `20260907`, with
`dataAvailableFrom=2026-03-05T00:00:00Z` and exclusive `dataThrough=2026-09-01T00:00:00Z`.
August has 31 complete budget days. These fixed synthetic values are generator choices under §6.

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
  retains the existing demo-account safeguard. Public credentials and safe commands are in README.

M4 is integrated with M3 commit `8c229ef`. Eight seed-owned API cases exercise the real authenticated
filter chain, OpenAPI validation, latest presets against each tenant's ledger, finding link patches
and destination responses, empty/sparse/zero-outcome states, missing historical baselines, tenant
isolation and VIEWER redaction with unchanged counts. Browser history and rendering remain M5–M6.
The seed launcher still requires no JWT/HMAC material; the web API retains M3's mandatory persistent
`FLEET_FINDINGS_ID_SECRET` configuration.

Installer checks actual per-tenant counts and canonical business rows, then publication and password
validity. It excludes random hashes/salts, installation timestamps and publication rows from the
checksum; source-day coverage is included. Each publication copies the checksum. There is no repair path.

## 7. Local development

| Command | Current behaviour |
|---|---|
| `make setup` | PostgreSQL readiness → Flyway migration → jOOQ generation → npm dependencies |
| `make test` | Maven verification, frontend tests, type-check and production build |
| `make seed` | Explicit §6 installer; requires an intentional `DB_URL` |
| `make dev` | Placeholder for coordinated backend/frontend startup |
| `make e2e` | Placeholder for browser journeys |

Run setup before compiling: code generation needs a migrated database, not compiled application code. Integration tests use separate ephemeral Testcontainers databases. The Makefile propagates failures and does not delete data. Its Colima socket adaptation is specific to that runtime, not universal Docker configuration.

Configure datasource, JWT keys, issuer/audience, HMAC secret and demo flags through environment/profiles. Never commit real credentials or private keys. Synthetic local database credentials are not production secrets. Refer to manifests and the testing strategy for executable commands; this edit does not establish passing build evidence.

## 8. Decisions and remaining configuration

| Decision | Accepted cost |
|---|---|
| Atomic records; query-time computation | More query work than rollups; benchmark realistic data before optimising |
| One repeatable-read transaction | Holds a transaction across section queries |
| Normalised parent joins | Additional SQL joins, without duplicated attribution |
| Generated jOOQ types | Clean builds require database setup/codegen |
| Short-lived in-memory JWTs | Reload requires login; logout cannot revoke |
| TanStack Query | Additional dependency in exchange for cache/cancellation management |

Settled: RS256, Argon2id, publication/source-day metadata, TanStack Query, Vitest and React Testing Library. Remaining milestone configuration: browser runner (Playwright proposed), exact dataset dates, team budget values and denial volume. Do not silently change those choices or add dependencies.

Older UI screenshots predate login; written acceptance criteria take precedence until screenshots are regenerated. Production architecture and storage choices remain separate from this prototype.

## Appendix A — Schema reference

Detailed A.1–A.6 tables were moved to [Prototype schema reference](reference/prototype-schema.md), preserving planned constraints that are not yet expressed in migrations.

### A.5 Coverage and revision

The coverage model referenced by migration comments is documented in [schema reference A.5](reference/prototype-schema.md#a5-coverage-and-revision).
