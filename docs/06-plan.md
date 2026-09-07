# 06 — Execution plan and verification record

The project was built in six milestones, with tests alongside each change.
This page records that sequence; the [requirements](02-requirements.md) and
[metrics contract](01-metrics-contract.md) remain the behavioural specifications.

## Milestones

| Milestone | Delivered | Evidence / status                                                                |
|---|---|----------------------------------------------------------------------------------|
| M1 — Foundation | Maven/npm setup, PostgreSQL, Flyway, jOOQ and smoke test | Committed: `6d54607`                                                             |
| M2 — Authentication and tenancy | Username login, JWT checks, tenant context and React session | Committed: `c946c5f`                                                             |
| M3 — Analytics backend | Domain schema, dashboard API, exact metric calculations and findings | Committed: `22ec70c`, `8c229ef`                                                  |
| M4 — Demo data | Deterministic two-tenant dataset and safe installer | Committed: `74a22f0`                                                             |
| M5 — Dashboard | Filters, cards, charts, funnel, comparisons, findings and styled login | Committed: `8ac9296`                                                             |
| M6 — Delivery | Chromium journeys, isolated runner, CI configuration and documentation | Complete — implementated and human review in pending; plus awaiting human commit |

The human reviews each milestone and stages/commits manually. Completion of implementation does
not imply a deployed service, successful remote CI or production readiness.

## Checks and evidence

Review run on 7 September 2026:

| Check | Evidence |
|---|---|
| Frontend type-check and component tests | Independently rerun after corrections: 202 tests passed |
| Production frontend build | Passed within the browser runner |
| Full Chromium suite | Passed after review fixes: 27 journeys |
| Runner cleanup regression | Failed before the fix; passed after it. A child ignoring SIGTERM is stopped even after its parent exits |
| Diagnostics regression | Deliberately failed browser assertion; public console/summary excludes the synthetic restricted marker |
| Backend suite | Independently rerun: 435 tests passed, 0 failures, 0 errors, 0 skipped |
| GitHub CI | Configured, not yet run/verified remotely |
| Clean checkout | Claude reported a working-tree copy with isolated Compose ports; not a post-commit git clone |

Re-run checks after changes. Historical counts are evidence from specific runs, not permanent
coverage guarantees.

Commands, from the repository root:

```bash
make test
(cd frontend && npx playwright install chromium) # --with-deps on Linux
node --test scripts/e2e*.test.mjs
make e2e
```

`make test` needs the migrated build database. Integration tests create separate Testcontainers.
For a non-default build database, supply matching Maven properties and application environment
variables as explained in [local development](04-technical-spec.md#7-local-development).
The browser runner supplies both channels automatically.

## What review changed

- A real 375px browser check exposed page overflow from a visually hidden element. Its containing
  block is now explicit.
- Cleanup previously forgot surviving descendants when their parent exited. Ownership now lasts
  until the process group is gone; the regression exercises escalation.
- Browser tests now switch organisations in the same React session and require a fresh request
  when revisiting a network-friction finding.
- CI publishes a status-only summary. Disabling traces alone did not make screenshots or raw
  assertion reports safe to publish.
- The README browser-install command now returns to the repository root before `make e2e`.

## Remaining release checks

- Human commit of M6 and these corrections.
- First successful GitHub CI run and a true clean-clone run after commit.
- Separate performance follow-up after the M6 commit: collect PostgreSQL planner statistics
  after successful demo seeding, then verify on a fresh database and remeasure dashboard latency.
  Diagnostics confirmed missing statistics caused a poor funnel-query plan; the earlier
  10.6-second cold request was not reproduced and remains unexplained.
- A production deployment and security/accessibility/capacity audits remain outside this assignment.

## Scope boundary

Do not expand this milestone with ingestion, Kafka, ClickHouse, Redis, rollups, refresh/revocation,
SSO, per-user analytics, exports or a custom dashboard builder.
[Deferred product work](00-research.md#10-deferred-and-cut) and
[production validation](03-architecture.md#10-production-validation-before-launch) explain the next steps.

The images in [requirements](02-requirements.md#2-page-structure-and-visual-reference) are design
references from before implementation, not screenshots or test evidence of the current application.
