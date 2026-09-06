# 05 — Testing strategy

> This document defines test layers and release checks, not a catalogue of individual cases. Expected calculations belong to the [metrics contract](01-metrics-contract.md); acceptance criteria belong to [requirements](02-requirements.md); tooling and implementation belong to the [technical spec](04-technical-spec.md).
> Foundation, authentication/context API and React session tests exist. Metric, dashboard and browser coverage remains planned. Consult executable suites and test reports for current counts and results; no tests were rerun for this documentation edit.

## 1. Approach

Use many fast unit tests, focused real-database/API tests, and a small set of customer journeys. Write a failing test first for new behaviour where practical; every bug fix should add a regression test.

Assert observable results using independently specified expectations—not snapshots copied from the implementation's output. Reference relevant acceptance-criterion IDs in test names or comments; keep detailed cases in executable tests and contract §9 rather than duplicating them here. Update this strategy when the approach changes, not whenever a test is added.

## 2. Backend unit tests

**JUnit; no database or HTTP.** Test metric derivation from independently specified aggregate inputs: ratios, deltas, comparison states, sample gates, exact thresholds and finding ranking. Test isolated validation/security helpers where useful.

SQL population selection is tested separately in §3, not reimplemented in Java. Prioritise undefined versus zero results and exact threshold boundaries, including the contract's floating-point regression example.

## 3. Backend integration and API/functional tests

**Spring Boot + real PostgreSQL through Testcontainers.** Use the same database version as the application; no H2 substitute.

| Area | What tests prove |
|---|---|
| Database integration | Flyway migrations, constraints, tenant-safe relationships and jOOQ queries work against PostgreSQL |
| Query correctness | Eligibility, timestamp windows, filters, pooled totals, cohort membership and reporting coverage match the metrics contract |
| Read consistency | Concurrent writes do not produce conflicting sections within one dashboard response |
| API/functional | Login, authenticated context, dashboard requests, defaults, validation and error responses behave as specified |
| Security | Invalid tokens fail; tenant data cannot mix; VIEWER responses omit restricted domains and internal identifiers across the entire body |
| HTTP contract | Success and problem responses conform to the authoritative OpenAPI document |

High-risk fixtures include a task with multiple runs/usage records that must not multiply costs, a merge outside the task-creation period, incomplete source data that must not become zero, and two organisations with disjoint identities. Verify isolation on returned data, not only rejected filter IDs.

API/functional coverage may live in integration suites; it does not require another framework or duplicate tests.

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

**Planned end-to-end coverage:** use the real frontend, API and seeded PostgreSQL database. Playwright is the proposed browser runner; confirm approval/configuration in the technical spec before installation.

- **Access and identity:** sign in as ADMIN/VIEWER, verify role-appropriate evidence, switch organisations, and check logout/expiry without stale tenant data.
- **Filtering:** change date/team/repository/grouping, reset, and restore URL state through browser history and reload. With an in-memory token, reload requires sign-in before restoring the selected view.
- **Investigation:** follow budget and repository-failure findings, verify the intended filter changes, and return to the starting view.
- **Recovery and accessibility:** exercise empty/unavailable states and a controlled recoverable failure; verify keyboard use, chart text alternatives and the 375px layout.

Keep rare edge cases in faster suites. Browser assertions verify customer behaviour rather than chart-library internals.

## 6. Test data and setup safety

Use the small hand-checkable contract fixture for correctness and the larger demo dataset for realistic journeys. Fix clocks, seeds, identity and ordering. Expected values must be independent of the implementation.

Verify seed repeatability, per-tenant totals, relationships and reachable scenarios. The installer must be atomic, safe under concurrent invocation, a no-op for matching validated data, and refuse incompatible or unmanaged data without changing it. Ordinary startup must not seed. Exclude random password salts/hashes and installation timestamps from deterministic business-data comparisons; never weaken password hashing for repeatability.

## 7. Scope

This strategy covers the assignment prototype. Kafka delivery/replay, ClickHouse correctness, source reconciliation, archival, production load and disaster recovery are not demonstrated by these tests. Their validation belongs to [architecture §10](03-architecture.md#10-production-validation-before-launch).

Measure query/API performance on the seeded dataset and record the environment and results before making latency claims. Build success is not evidence of product correctness, security or production capacity.

## 8. Execution and completion

| Command | Current meaning |
|---|---|
| `make setup` | Starts PostgreSQL, applies migrations, generates jOOQ types and installs frontend dependencies |
| `make test` | Runs Maven verification and installed frontend tests, type-checking and production build |
| `make seed` | Currently an unimplemented placeholder; intended to install demo data |
| `make e2e` | Currently an unimplemented placeholder; intended to run browser journeys |

**Planned CI:** build/static checks, backend unit/integration/API and OpenAPI checks, frontend unit/component tests, then browser journeys once implemented. Checks must propagate failures; optional future lint/coverage tooling is not claimed to exist.

A milestone is complete when its applicable acceptance criteria have executable evidence and relevant checks pass. Record commands, scope and results in the development handoff/CI, not a running tally here. Review uncovered business branches; no numeric coverage percentage is an approved gate, and coverage alone is not proof of correctness.
