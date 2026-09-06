# 06 — Execution plan

> **Status: DRAFT, awaiting review.** An execution guide, not a specification. Scope is `00-research.md` §7 · calculations `01` · acceptance criteria `02` · boundaries `03` · implementation `04` · tests `05`. Nothing is copied from them here.
> **M1 is complete and verified; M2–M6 are planned.** For any milestone not yet executed, planned commands are not evidence of passing tests.

## How to work the plan

Small test-first steps. Tests accompany **every** milestone — M6 completes verification, it is not where testing begins. **Stop after each milestone for human review.** No automatic commits: propose changes and commit messages; **the human stages and commits** (`CLAUDE.md`).

## Prerequisites — open decisions that block a milestone

TanStack Query is approved. Nothing else below is; none is resolved here.

**M1's tooling and compatibility checks are settled** — the build tool and every dependency version are confirmed and in use, recorded in `04-technical-spec.md` §1.

| Blocks | Still open |
|---|---|
| M2 | JWT signature algorithm (RS256 recommended); password hashing (Argon2id recommended); **coverage metadata tables** (`04` A.5), needed here because the context response carries coverage; component test stack (Vitest + React Testing Library recommended), needed here because M2 creates the first UI |
| M3 | `cost_cents >= 0` |
| M4 | demo calendar dates and `dataAvailableFrom`; per-team budget values; denial-event volume; seed-derived namespaced ids; advisory-lock seeding |
| M6 | browser test tool (Playwright recommended); coverage thresholds |

## Milestones

### M1 — Foundation ✔ complete
**Deliverable.** A clean clone builds: PostgreSQL running, Flyway migrations at head, jOOQ types generated, backend and frontend skeletons compiling.
**Touches.** Root `pom.xml`, Maven wrapper, `docker-compose.yml`, `Makefile`, `backend/pom.xml`, `backend/src/main/resources/db/migration/`, `frontend/`.
**Done when.** `make setup` succeeds from a clean clone in the order fixed by `04` §7, **and then** the backend and frontend compile and a PostgreSQL integration smoke test passes against a Testcontainers instance. Setup completing is not proof on its own — the compile and the smoke test are what show the generated types and the test path actually work.
**Result.** `make setup` and `make test` pass from a clean environment; the PostgreSQL smoke test reports 1 test, 0 failures, 0 errors, 0 skipped; frontend type-check and build pass. `make setup` re-runs without reapplying the migration or dropping data.
**Review gate.** Reviewed.

### M2 — Authentication and tenancy
**Deliverable.** Username/password login, signed JWT validation, a protected `analytics/context`, and a minimal UI that signs in and shows the authenticated organisation name.
**Touches.** Backend `security` and `web` packages; migrations for organisation, team, **repository**, user and seat tables plus the **publication and source-coverage metadata** the context response returns; `contracts/openapi.yaml` for the login and context endpoints; a login view and context fetch in `frontend/src`.
**Data.** Minimal **explicit two-tenant fixtures**, written for these tests only. M2 does **not** depend on M4's demo generator and adds **no startup seeding**.
**Done when.** For the login and context slice only: valid credentials issue a token and load the context; invalid credentials, and missing, expired, malformed, tampered, wrongly-signed, wrong-issuer and wrong-audience tokens are all rejected; the context returns the organisation name, and only that organisation's teams, repositories, coverage and seat count, resolved from the token alone; a foreign identifier is indistinguishable from a nonexistent one; demo accounts are inert outside demo configuration; the UI displays the organisation name. Dashboard-response tenant isolation and `ADMIN`/`VIEWER` redaction are verified in **M3**; their rendered behaviour, cache clearing and reload/restore in **M5–M6**. No acceptance criterion is dropped — they land in the milestone that can actually exercise them.
**Review gate.** Stop, report changes and verification results, and wait for human review. Do not stage or commit.

### M3 — Analytics backend
**Deliverable.** The metrics module and its SQL over the hand-checkable contract fixture: five KPIs, funnel, trends, comparison with benchmark, and the four findings; `analytics/dashboard` serving them; OpenAPI conformance.
**Touches.** Backend `metrics` and `data` packages, remaining migrations, `contracts/openapi.yaml` extended with the dashboard endpoint, backend tests.
**Done when.** Fixture results equal the independently stated values in `01` §8; fan-out, coverage-state, gate and threshold tests pass (`05` §2–§3); every response validates against the OpenAPI contract.
**Review gate.** Stop, report changes and verification results, and wait for human review. Do not stage or commit.

### M4 — Demo dataset
**Deliverable.** Deterministic generation of both organisations as configured in `04` §6, and the installer with its four outcomes.
**Touches.** Backend `seed` package and its tests.
**Done when.** Two installs produce identical canonical business data; per-tenant counts match the manifest; required scenarios exist, including the failure spike the investigation journey needs; install, no-op and both refusals behave; concurrent invocation does not double-install.
**Review gate.** Stop, report changes and verification results, and wait for human review. Do not stage or commit.

### M5 — Dashboard
**Deliverable.** The full P0 page: TanStack Query integration, URL-driven filters, five KPI cards, two trends, the cohort funnel, the comparison table and the findings panel, with every state.
**Touches.** `frontend/src` and its component tests.
**Done when.** `02` AC-01 through AC-07 render from the API with no metric computed client-side; loading, empty, unavailable and insufficient-sample states are distinct; query keys, cancellation and cache clearing behave per `04` §5.2; component tests in `05` §4 pass. Basic keyboard and 375px behaviour is built here, not deferred.
**Review gate.** Stop, report changes and verification results, and wait for human review. Do not stage or commit.

### M6 — Delivery
**Deliverable.** Browser journeys, accessibility and responsive verification, CI, and a clean-clone check.
**Touches.** `frontend/e2e`, CI configuration, README run instructions.
**Done when.** The journeys in `05` §5 pass, including two-organisation isolation and tenant switching; `02` AC-08 passes by keyboard and at 375px; CI gates run in the documented order; a clean clone reproduces everything from `make setup` to `make e2e`.
**Review gate.** Stop, report changes and verification results, and wait for human review. Do not stage or commit.

## Cut line

Excluded, and already deferred upstream: ingestion pipelines, rollups, queue/cache/columnar stores, refresh tokens, rotation and revocation, registration and password reset, an organisation switcher, per-user analytics, exports, custom dashboards, and every deferred metric in `00-research.md` §10.1.

If time runs short, cut **polish inside M5** — visual refinement, optional affordances. Never cut a metric's correctness, a coverage or unavailable state, or a tenancy, redaction or authorisation control. Those are the product.

## Note on the visual references

The screenshots in `02` §2 predate authentication: no login page, and a header showing a viewer role rather than the organisation name AC-09.13 requires. The written criteria govern until they are regenerated, which is a follow-up after M5 and blocks nothing.
