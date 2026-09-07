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
| M6 — Delivery | Chromium journeys, isolated runner, CI configuration and documentation | Committed: `a992b12`; keyboard-readiness fix: `96419ba` |

The human reviews each milestone and stages/commits manually. Completion of implementation does
not imply a deployed service, successful remote CI or production readiness.

## Checks and evidence

M6 review run on 7 September 2026 (before the logout follow-up):

| Check | Evidence |
|---|---|
| Frontend type-check and component tests | Independently rerun after corrections: 202 tests passed |
| Production frontend build | Passed within the browser runner |
| Full Chromium suite | Passed after review fixes: 27 journeys |
| Runner cleanup regression | Failed before the fix; passed after it. A child ignoring SIGTERM is stopped even after its parent exits |
| Diagnostics regression | Deliberately failed browser assertion; public console/summary excludes the synthetic restricted marker |
| Backend suite | Independently rerun: 435 tests passed, 0 failures, 0 errors, 0 skipped |
| GitHub CI | Initial run failed at keyboard navigation. Readiness regression fixed in `96419ba`; 27 journeys passed against Linux Chromium locally. Remote rerun not yet verified |
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

## Logout follow-up

Single-token server logout replaces browser-only sign-out. PostgreSQL stores the signed token
ID; the browser clears protected state immediately and warns if server logout cannot be confirmed.
Other logins are unaffected. See [authentication](04-technical-spec.md#51-authentication-and-redaction).

Review caught a real bypass in the first draft: hashing the JWT text treated alternate valid
signature encodings as different tokens. An HTTP regression reproduced both variants returning
200 after logout. Required signed UUID token IDs now make both return 401. The boundary cleanup
test also uses a different token to trigger deletion, so reinsertion cannot hide an expiry bug.

Final local verification on 7 September 2026:

| Check | Result |
|---|---|
| `make test` against a freshly migrated isolated build database | 441 backend tests and 210 frontend tests passed; type-check and production build passed |
| Fixed-clock token uniqueness assertion added to `JwtSecurityTest` | All 7 tests in that class rerun and passed |
| `make e2e` on a fresh disposable database | All 27 Chromium journeys passed, including real logout and rejected token replay; runner cleaned up its resources |
| Independent review and document checks | Encoding bypass corrected and re-reviewed; no remaining actionable findings; diff whitespace and local link targets checked |

No legacy-token fallback, new dependency, refresh flow or second authentication mechanism was added.
This follow-up is not yet committed or verified in remote CI.

## Remaining release checks

- A production deployment and security/accessibility/capacity audits remain outside this assignment.

## Scope boundary

Single-token server logout is an approved post-M6 extension. Do not expand it with
ingestion, Kafka, ClickHouse, Redis, rollups, refresh tokens, logout-all-sessions,
SSO, per-user analytics, exports or a custom dashboard builder.
[Deferred product work](00-research.md#10-deferred-and-cut) and
[production validation](03-architecture.md#10-production-validation-before-launch) explain the next steps.

The images in [requirements](02-requirements.md#2-page-structure-and-visual-reference) are design
references from before implementation, not screenshots or test evidence of the current application.
