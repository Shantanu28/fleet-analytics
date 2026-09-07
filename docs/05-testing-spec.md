# 05 — Testing strategy

> This document defines test layers and release checks, not a catalogue of individual cases. Expected calculations belong to the [metrics contract](01-metrics-contract.md); acceptance criteria belong to [requirements](02-requirements.md); tooling and implementation belong to the [technical spec](04-technical-spec.md).
> Foundation, authentication, metric/dashboard API, seed safety/integration, React session and browser journey tests all exist. The [execution record](06-plan.md) records the latest local verification; a workflow file alone does not prove GitHub CI passed.

## 1. Approach

Use many fast unit tests, focused real-database/API tests, and a small set of customer journeys. Write a failing test first for new behaviour where practical; every bug fix should add a regression test.

Assert observable results using independently specified expectations—not snapshots copied from the implementation's output. Reference relevant acceptance-criterion IDs in test names or comments; keep detailed cases in executable tests and [Test checklist](01-metrics-contract.md#9-test-checklist) rather than duplicating them here. Update this strategy when the approach changes, not whenever a test is added.

## 2. Backend unit tests

**JUnit; no database or HTTP.** Test metric derivation from independently specified aggregate inputs: ratios, deltas, comparison states, sample gates, exact thresholds and finding ranking. Test isolated validation/security helpers where useful.

SQL population selection is tested separately in [Backend integration and API/functional tests](#3-backend-integration-and-apifunctional-tests), not reimplemented in Java. Prioritise undefined versus zero results and exact threshold boundaries, including the contract's floating-point regression example.

## 3. Backend integration and API/functional tests

**Spring Boot + real PostgreSQL through Testcontainers.** Use the same database version as the application; no H2 substitute.

| Area | What tests prove |
|---|---|
| Database integration | Flyway migrations, constraints, tenant-safe relationships and jOOQ queries work against PostgreSQL |
| Query correctness | Eligibility, timestamp windows, filters, pooled totals, cohort membership and reporting coverage match the metrics contract |
| Read consistency | Concurrent writes do not produce conflicting sections within one dashboard response |
| API/functional | Login/logout, authenticated context, dashboard requests, defaults, validation and error responses behave as specified |
| Security | Invalid tokens fail; tenant data cannot mix; VIEWER responses omit restricted domains and internal identifiers across the entire body |
| HTTP contract | Success and problem responses conform to the authoritative OpenAPI document |

High-risk fixtures include a task with multiple runs/usage records that must not multiply costs, a merge outside the task-creation period, incomplete source data that must not become zero, and two organisations with disjoint identities. Verify isolation on returned data, not only rejected filter IDs.

API/functional coverage may live in integration suites; it does not require another framework or duplicate tests.

Logout checks use the real database: replay (including alternate signature encodings) is rejected
after revocation, other logins survive, and expiry cleanup respects clock skew. Recreating the service checks shared-store behaviour,
not an actual multi-process restart. An unavailable store denies access; a rejected database
write must not report successful logout. Both errors are sanitised. Browser tests also replay the token after a real logout response; frontend
tests cover network/server failures and late logout responses after another login.

## 4. Frontend unit and component tests

**Vitest + React Testing Library are installed.**

| Layer | What tests prove |
|---|---|
| Unit | Pure helpers correctly format values, parse URL filters and build request parameters, where such helpers exist |
| Component | Login, filters, metric states, loading/error/retry behaviour and finding links render and respond correctly |
| State isolation | Superseded requests cannot overwrite the selected view; logout and identity changes clear protected data and reject late responses |
| Accessibility | Controls have accessible names; unavailable values and severity are understandable without colour alone |

Mock the HTTP boundary, not component internals. Give each test an isolated TanStack Query client. Frontend fixtures contain already-computed metric values; do not duplicate backend formulas or create helpers solely to generate unit tests.

## 5. Browser journeys

**Implemented with Playwright**, Chromium only, one worker, against the production frontend build served by Vite preview, the real API and a seeded PostgreSQL database the runner creates and removes. See the [browser tooling choices](04-technical-spec.md#8-decisions-and-remaining-configuration).

Journeys assert customer-visible outcomes through accessible locators and retrying assertions, never fixed waits, and pair what the page says with what the server was actually asked. Scopes are resolved from the API by identity, because display names are not unique across organisations. Recoverable-failure and unauthorized-response cases are produced by intercepting responses in the browser and are labelled as injected; the application has no test hook, and an injected 401 is not evidence of real token expiry. Accessibility checks here are targeted — keyboard order, accessible names, visible focus, chart text alternatives and the 375px layout — not a conformance audit.

- **Access and identity:** sign in as ADMIN/VIEWER, verify role-appropriate evidence, switch organisations, and check logout without stale tenant data. Replay the logged-out bearer against the API and require 401. Expired-session UI handling uses an injected 401; actual expiry validation belongs to backend tests.
- **Filtering:** change date/team/repository/grouping, reset, and restore URL state through browser history and reload. With an in-memory token, reload requires sign-in before restoring the selected view.
- **Investigation:** follow budget and repository-failure findings, verify the intended filter changes, and return to the starting view.
- **Recovery and accessibility:** exercise empty/unavailable states and a controlled recoverable failure; verify keyboard use, chart text alternatives and the 375px layout.

Keep rare edge cases in faster suites. Browser assertions verify customer behaviour rather than chart-library internals.

## 6. Test data and setup safety

Use the small hand-checkable contract fixture for correctness and the larger demo dataset for realistic journeys. Fix clocks, seeds, identity and ordering. Expected values must be independent of the implementation.

Verify seed repeatability, per-tenant totals, relationships and reachable scenarios. The installer must be atomic, safe under concurrent invocation, a no-op for matching validated data, and refuse incompatible or unmanaged data without changing it. Ordinary startup must not seed. Exclude random password salts/hashes and installation timestamps from deterministic business-data comparisons; never weaken password hashing for repeatability.

## 7. Scope

This strategy covers the assignment prototype. Kafka delivery/replay, ClickHouse correctness, source reconciliation, archival, production load and disaster recovery are not demonstrated by these tests. Their validation belongs to [architecture](03-architecture.md#10-production-validation-before-launch).

Measure query/API performance on the seeded dataset and record the environment and results before making latency claims. Build success is not evidence of product correctness, security or production capacity.

## 8. Execution and completion

| Command | Current meaning |
|---|---|
| `make setup` | Starts PostgreSQL, applies migrations, generates jOOQ types and installs frontend dependencies |
| `make test` | Runs Maven verification and installed frontend tests, type-checking and production build |
| `make seed` | Explicit M4 installer; requires `DB_URL`, validates/no-ops or refuses existing data |
| `make e2e` | Browser journeys: migrate, generate, seed, build, start owned servers, run, then stop and remove them |

`make e2e` owns everything it creates. It resolves one datasource and supplies it to both configuration channels, creates its own PostgreSQL container on an ephemeral loopback port, refuses to reuse or stop a server already answering on its ports, and on success, failure, `SIGINT` or `SIGTERM` stops the process groups it started and removes only its own container within a bounded deadline. A database supplied through `E2E_DB_URL` is migrated and seeded in place, must be confirmed disposable, and is never removed. Chromium must be installed once per checkout (`npx playwright install chromium`, `--with-deps` on Linux).

**CI configuration** runs one job: frontend dependencies, migration and code generation, `make test`, Chromium installation, runner/diagnostics regression tests, then `make e2e`. Failures fail the job; the summary upload runs even after a test failure.

**Published diagnostics are allowlisted.** The custom reporter prints only test file, line and outcome, and CI uploads only `frontend/playwright-report/summary.json`. It does not forward test output, assertion values or attachments. Screenshots, traces and videos are disabled. Raw local `test-results` files can still contain restricted page content: never upload or commit them. A deliberately failing browser test verifies that sensitive sentinel text does not reach the public console or summary. Turning off traces alone is not a privacy guarantee.

The runner also has a real subprocess regression: a child that ignores SIGTERM must be stopped even after its parent exits. No lint or coverage tooling is configured.

A milestone is complete when its applicable acceptance criteria have executable evidence and relevant checks pass. Record commands, scope and results in the development handoff/CI, not a running tally here. Review uncovered business branches; no numeric coverage percentage is an approved gate, and coverage alone is not proof of correctness.
