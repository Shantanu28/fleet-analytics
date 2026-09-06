# 05 — Testing specification

> **Status: DRAFT. No test in this document exists, and none has passed.** Everything below is planned.
> `01-metrics-contract.md` is authoritative for expected values, `02-requirements.md` for behaviour, `04-technical-spec.md` for the stack and commands.

## 1. What we need confidence in

Five things, in rough order of how expensive a mistake is:

| Concern | Biggest risk | Covered by |
|---|---|---|
| **Correct metrics** | a wrong number that looks plausible | §2 unit tests against the hand-checkable fixture |
| **Correct SQL** | duplicate counting, or filters that quietly change a population | §3 integration tests on real PostgreSQL |
| **Secure API** | cross-tenant data, or a leaked denied domain | §3 authentication, isolation and redaction tests |
| **Usable UI** | a `null` rendered as zero; stale data after a filter change | §4 component tests, §5 browser journeys |
| **Reliable setup** | a seeder that destroys data or produces a different dataset | §6 seed integrity |

Two principles apply throughout.

**Expected values are stated independently.** Every fixture assertion transcribes a literal from contract §8, derived by hand there and re-checkable by a reviewer. Snapshot or golden-file capture of the implementation's own output is not used for metric tests — it would only prove the code agrees with itself.

**Determinism.** Tests fix `dataThrough` and the generator seed; none reads the wall clock.

**Where each level draws the line.** PostgreSQL integration tests go from **raw fixture rows to correct populations** — counts, sums, time-window membership and distinct counts, including eligibility and the timestamp basis, which are metric decisions that SQL implements. Pure Java unit tests go from **independently specified aggregate inputs to derived results** — values, states, deltas, gates, thresholds and ranking. Population selection is not reimplemented in Java to make it unit-testable, and derivation is not pushed into SQL to make it integration-testable.

## 2. Backend unit tests

The metrics module is pure: records in, values and states out. These run without a database or HTTP.

**Arithmetic against the contract §7 fixture**, asserting the worked values in contract §8:

| Area | Expected values |
|---|---|
| Merged PRs | `1` and `2`; relative delta `−50.0%` |
| Terminal merge rate | `100.0%` (1/1) and `66.7%` (2/3); delta **+33.3 pp**, suppressed by the gate |
| Completion rate | `50.0%` (1/2) and `80.0%` (4/5) |
| Spend | code-change `2300¢` / `4700¢`; total `2300¢` / `4900¢` |
| Cost per merged PR | `$23.00` and `$23.50`; T-PAY `null` with "no merged PRs in this period" |
| Active seats | `2` and `3`; utilisation `33.3%` / `50.0%`; `unavailable_for_scope` when filtered |
| Funnel | `4/1/1/1`, failed 1, cancelled 1, in progress 1, and `1+1+1+1 = 4` |
| Pooling | team `(0+1)/(0+2)` and repository `(0+1)/(1+1)` both `50.0%`; cents pool to `2300` either way |

Unit tests take **already-selected populations** as input. Which rows are eligible and which window they fall in is SQL's job and is tested in §3; rendered copy is tested in §4.

**Null and zero distinctions**, which the UI depends on being different: `0/n` → `zero_outcome` at `0.0%`; `0/0` → `no_denominator` with `null`; positive spend with zero merged PRs → `null` plus its own reason, never "no activity"; a covered range with no rows → defined zeros, not `missing_data`; a missing source → `missing_data` including counts; an uncovered baseline → `no_baseline` for comparisons and `not_evaluated` for rules. Comparison reasons resolve in the contract's seven-step precedence order.

**Deltas.** Rate delta valid from a `0%` baseline (`0% → 20% = +20.0 pp`); `10 → 0 = −100%`; `0 → 10` relative `null` while the absolute delta is `+10`; each card shows exactly one comparison type and explains an unavailable one rather than substituting another.

**Sample gates.** Suppression flips at 14 versus 15 terminal PRs, 19 versus 20 tasks, and 14 versus 15 merged PRs, applied per metric and to both periods.

**Alert thresholds**, each tested at, just below and just above the line:

| Rule | Cases |
|---|---|
| Budget | 18% MEDIUM · 25% HIGH · 10% no finding · **exactly 20% stays MEDIUM** · fewer than 3 elapsed days → insufficient history · month boundary |
| Failure spike | 12/24 = 50.0% vs baseline 16/40 = 40.0% → **+10.0 pp**, fires · baseline 17/40 = 42.5% → 7.5 pp, no finding · 19-task baseline → `not_evaluated`, not healthy |
| Merge decline | 8/16 = 50.0% vs 12/20 = 60.0% → −10.0 pp, fires · previous 29/50 = 58.0% → **exactly 8.0 pp**, fires at `≥ 8` |
| Network friction | 6 tasks/4 users fires · 6 tasks/2 users does not · two domains stay two findings · 12 denials from one task count as one task and one user · non-code tasks count · event at `end` excluded |

**Exact threshold comparison** deserves its own test: `29/50 − 8/16` is exactly `8.0` pp, but in IEEE-754 doubles it evaluates to `7.999999999999993`. A float implementation must fail this test.

**Findings.** Ranking order and the three-finding cap; tie-break totality when a team and a repository share an id; the five panel states; an evaluation-limit notice that does not consume the cap; a budget finding surviving an otherwise empty period; the friction rerank in both outcomes — still in the top three, and no longer present.

## 3. PostgreSQL and API integration tests

Against **real PostgreSQL 18.6 via Testcontainers**. H2 is not used at any level: the metrics depend on PostgreSQL date semantics, partial indexes and `REPEATABLE READ` behaviour, and an in-memory substitute would prove the wrong thing.

**Schema and migrations.** Flyway migrates from empty to head on a clean container; jOOQ's generated types match the migrated schema.

**Constraints**, each asserted by attempting the violation:

- composite foreign keys reject a `run`, `usage_record` or `denial_event` whose parent belongs to another organisation;
- a `pull_request` whose `run_id` belongs to a different task is rejected;
- two organisation-scope budgets for the same month are rejected, while a team budget and an organisation budget for that month coexist;
- `amount_cents ≤ 0` is storable and reported as `invalid_budget_configuration`;
- assigning one user to a second seat is rejected; unassigned seats may repeat;
- terminal status and timestamp must agree, and `terminal_at` cannot precede `created_at` or `opened_at`;
- a task with several runs inserts successfully — no constraint enforces the demo's one-run simplification;
- `username` uniqueness is global: the same normalised username in two organisations is rejected, and a username differing only by surrounding whitespace or letter case resolves to the same single account.

**Eligibility and time windows**, from raw fixture rows to correct populations: half-open intervals, with a record exactly at `end` excluded and an event exactly at `dataThrough` excluded; the inclusive UI date mapped to `end + 1 day`; the cross-period merge, where a PR merged after `end` but before `dataThrough` joins the funnel's merged stage and not the period's KPI count; non-code tasks absent from outcome metrics, the funnel and the cost numerator while present in total spend, active seats and network friction.

**Parent relationships and fan-out.** A fixture task with **three runs and five usage records** must contribute exactly once to every task-grain count, produce the correct summed spend, and appear once per funnel stage. A second task carrying both runs and a PR proves the two one-to-many branches are never descended together. Assertions are on totals, not on how many statements produced them. Code-change spend joins up to `task` for `task_type`, so non-code usage is excluded from the cost numerator while remaining in total spend.

**Filters and benchmark.** Team ∩ repository at SQL level; a covered period with no rows yielding zeros; the grouping switch changing grouping only; deterministic ordering repeated across runs. The **benchmark drops the team predicate and keeps the repository predicate** — asserted by running the same request with and without a team filter and confirming the benchmark figures are unchanged, then with a repository filter and confirming they move. Trend series are zero-filled for every day in range.

**Snapshot consistency and source availability.** One dashboard request runs in a single `REPEATABLE READ` transaction, with a concurrent write invisible mid-request. A source marked incomplete makes only its dependents `missing_data` while unrelated sections still render.

**API validation.** Envelope shape and defaults; the last-30-complete-days default; filter echoing; malformed, reversed and uncovered ranges rejected with the right problem type; a custom range **longer than 90 days** inside coverage accepted, since presets are not a maximum; a valid range whose comparison period falls outside coverage answered with `no_baseline` rather than an error; unknown filter ids rejected.

**OpenAPI conformance.** The contract lints clean; every response validates against its declared schema including problem responses; no undocumented endpoint or field is served and no documented field is missing.

**Relationship and lifecycle rejection**, each asserted by attempting the violation and expecting failure:

| Behaviour | Scenario |
|---|---|
| Cross-tenant parents rejected | a `run`, `usage_record`, `pull_request` or `denial_event` whose parent belongs to organisation B |
| Wrong-task child rejected | a PR, and a denial event, whose `run_id` is a valid same-organisation run **belonging to another task** |
| Optional denial run valid when absent | a denial with `run_id` NULL is accepted, **and** its task foreign key is still enforced — a denial naming a foreign task is rejected whether or not a run is present |
| Uniqueness | two PRs on one task; two runs with the same `attempt_no`; two organisation budgets for one month; a user assigned to two seats; a duplicate normalised `username` across organisations |
| Lifecycle | terminal status without timestamp and the reverse; `terminal_at` before `created_at` or `opened_at`; `run_status = 'running'` with an `ended_at`; a failed run with no `failure_reason`; a mid-month `period_month`; negative `cost_cents` |
| Retries representable | a task with three runs inserts successfully — no constraint enforces the demo's one-run rule |

**Aggregation correctness.**

| Behaviour | Scenario |
|---|---|
| Child rows do not inflate parents | a task with three runs and five usage records contributes **once** to every task-grain count and to the funnel, while its spend sums all five records |
| Two branches never descended together | a task carrying both runs and a PR is counted once in task-grain metrics |
| Default branch excluded | a PR targeting a non-default branch is absent from merged-PR counts, merge rate and the funnel's merged stage, while its task still appears in earlier stages |
| Adoption cannot be inflated | a task owned by a user with **no** `seat_licence` row does not raise the active-seat count |
| Reason evidence | failed-task reasons resolve to agent, platform and policy groups from the failed run, without altering the task-grain failure count |
| Results match the contract | aggregate outputs equal the independently stated expectations in contract §8 for the same fixture |

**Coverage states**, driven by `source_day_coverage` rows rather than by deleting data:

| Behaviour | Scenario |
|---|---|
| Covered and empty | days marked complete with no business rows → defined zeros, state `ok` |
| Current window incomplete | a required source incomplete inside the selected range → `missing_data` for its dependents only, including counts, while unrelated sections render |
| Baseline unavailable | current window complete, baseline days missing → `no_baseline` for comparisons and `not_evaluated` for rules, with reasons |
| Absent metadata | a day with no coverage row is treated as not covered, never as no activity |
| Budget independence | the budget month is evaluated on its own days regardless of the selected range |

**Authentication.** Valid credentials issue a 15-minute signed token. Rejected with 401: no token, expired, malformed, tampered payload, signed by a different key, wrong issuer, wrong audience. Login normalises the username before lookup, and invalid credentials fail with a sanitised message identical for an unknown username and a wrong password. Demo accounts cannot log in outside demo configuration.

**Tenant isolation is asserted on returned data, not only on rejected ids.** With both demo organisations installed, a token for A returns A's organisation name, teams, repositories, seats, findings and metric values — and **no entity belonging to B**. The check is on **disjoint identifiers and names**, which the generator guarantees are distinct, not on numeric inequality: if A and B coincidentally share a completion rate that is not a failure. Populations must never combine, which is asserted by confirming A's totals equal A's independently known expectations rather than A+B. Foreign filter ids return `unknown-filter`, indistinguishable from nonexistent ids, in both directions; no endpoint accepts an organisation parameter.

**Redaction across the full response.** `ADMIN` sees the denied domain; `VIEWER` does not, while both see identical counts. The viewer assertions are a **recursive scan over the entire serialized body** — every field at any depth, generated finding text, link targets and query parameters — so a field added later cannot leak a raw domain or an internal domain-bearing identity string without failing. No secret, key or password hash appears in any response or log line.

**Keyed pseudonymous finding ids.** The public id is a keyed HMAC (`04-technical-spec.md` §5), so the tests assert stability and separation rather than unlinkability:

| Behaviour | Scenario |
|---|---|
| Stable across change | the id is unchanged when the finding's evidence, counts, severity or rank change, including after a link is followed and findings are reranked for a narrower scope |
| Distinct findings differ | two findings in one scope — including **two denied domains in the same team** — receive different ids |
| Organisation namespacing | the same internal identity under two organisations yields different ids |
| No leakage | a viewer response contains no raw domain and no internal identity string; the id itself reveals neither |
| Both navigation outcomes | after following a friction finding, the originating id is either still present in the recomputed top three, or absent — and the UI distinguishes the two (AC-06.7) |
| Not a capability | a finding id grants no access: presenting one with a token for another organisation, or with an insufficient role, changes nothing about what is returned |

## 4. React component tests

**New tooling recommendation, not yet approved: Vitest + React Testing Library.** These sit alongside the browser journeys and cover behaviour that is expensive to reach end to end.

Tests exercise **accessible, user-facing behaviour** — roles, labels and visible text — rather than internal component state. The **HTTP boundary is mocked** so a component can be driven through states the backend would take effort to produce; component internals are not mocked. **Backend metric arithmetic is not reimplemented here**: fixtures supply already-computed values and states, and the assertions are about rendering.

Server state uses TanStack Query (`04-technical-spec.md` §5.2). Each test constructs its **own `QueryClient`**, so no cache leaks between tests. The assertions are about **observable behaviour at the HTTP boundary** — which requests go out, and what the user sees — never about the library's internals.

| Area | Covered |
|---|---|
| Metric rendering | value plus exactly one comparison; an unavailable comparison shows its reason and never a substituted type, a zero or a neutral badge; `0.0%` and `—` are distinguishable; monetary zero keeps currency formatting |
| Labels that carry meaning | the funnel's cohort label names the selected period and the observation cutoff; its maturity copy avoids a numeric threshold; the benchmark label states it includes the selected team and may include teams not displayed; the header shows the organisation name |
| Unavailable PR stages | when PR data is unavailable the two PR funnel stages render as unavailable with a reason, never as `0` |
| Filters | preset and custom range selection, team and repository choices, reset; invalid and reversed ranges surface a specific message; unknown ids show a notice rather than unfiltered data |
| Loading, error, retry | pending sections show loading instead of the previous selection's values; a recoverable failure offers retry and keeps filters |
| Attention panel | at most three findings in ranked order; the five panel states; an evaluation-limit notice rendered as a notice, not a finding |
| Login and logout | the login form, an authentication failure message, and sign-out clearing the in-memory token together with all cached protected data |
| Distinct filters, distinct requests | changing the range, team, repository or grouping issues a **different request** and renders that selection's result; two selections never share a cache entry |
| Superseded response | a response for an earlier selection that arrives **last** is not rendered, and the previous selection's values are never shown while the new one is loading |
| Pending work across a switch | logging out, or signing in as a different account or role, while requests are in flight leaves no stale render — the earlier principal's response never repopulates the UI |
| No cross-identity leakage | after a switch, no data belonging to the previous identity or organisation appears |
| Failures do not retry-loop | a 401 and a 400 surface to the user without entering automatic retries; a recoverable failure offers retry and keeps filters |

## 5. Browser journeys

A small number of Playwright journeys, each asserting several behaviours as it goes. **Assertions are not journeys** — one session covers many.

| Journey | Asserts along the way |
|---|---|
| **Login and role-appropriate display** | log in as each role **within one organisation**; the organisation name, demo-data indicator and reporting cutoff are visible; `ADMIN` sees a denied domain in a friction finding and `VIEWER` sees the redacted form with identical counts; a username entered with different casing or surrounding spaces signs in as the same account |
| **Two organisations** | signing in to A shows A's name, seat total, teams, repositories and its independently known metric values; signing out and in to B shows B's corresponding values; neither view contains the other's entities, and no figure reflects a combined population. A's filter ids are rejected while holding a B token, and the reverse |
| **Filters, URL and history** | presets and a custom range; team ∩ repository; the URL encodes range, filters and grouping; refresh and back/forward restore the same view; reset returns to defaults |
| **Finding investigation and return** | at most three ranked findings with inline evidence; follow the budget finding to the month-to-date spend view with the repository restriction cleared and the period change explained; return to the starting view; follow the `repo-api` failure spike, confirming the date range and other-dimension filter are preserved, the table switches to the repository grouping and the affected row is in view; back again |
| **Empty, unavailable and error recovery** | a valid but empty filter combination showing zeros with an explanation and reset; a filtered view showing the seat count with utilisation unavailable; a gated comparison naming its gate while the value stays visible; a recoverable failure retried with filters intact |
| **Logout, expiry and identity change** | sign-out returns to login and clears cached protected data; an expired token returns to login; signing in as a different role, and as a different organisation's account, shows the new principal's data with **no cached remnant and no late response** from the previous one — a response for the old tenant arriving after the switch is discarded rather than rendered; switching organisations does not carry the previous tenant's team or repository ids into the new session |

**Reload semantics.** The token lives in memory only, so reloading ends the session and the login page appears. After logging in again, the **URL-selected dashboard state is restored** — the range, filters and grouping in the address bar survive, because they were never held in the token or in memory. The journey asserts this explicitly: apply a non-default selection, reload, sign in again, and confirm the same view returns without re-applying filters by hand.

**Textual equivalents.** Both trend charts and the funnel expose the same information as text or a table, asserted by reading the tabular form and comparing it with the rendered series rather than by inspecting the chart's internals.

**Keyboard and mobile** are checked inside these journeys rather than separately: focus order and visible focus across presets, range inputs, selectors, reset, the grouping switch and finding links; accessible names matching visible labels; at 375px, no page-level horizontal scrolling with wide tables scrolling inside a labelled region; and severity, delta direction and unavailability conveyed by text or shape as well as colour.

## 6. Seed integrity and determinism

- **Determinism** — the same seed produces identical **canonical business data** across two installs. The comparison excludes `password_hash` and its per-password random salt, `installed_at`, and surrogate ids not derived from the seed. Hashing is never made deterministic to satisfy this test.
- **Volumes** — both organisations match the configuration in `04-technical-spec.md` §6, asserted per tenant rather than in total, with each organisation's demo accounts inside its own engineer count and consuming no extra seat. Coverage spans the 90-day preset and its comparison period for each.
- **Namespaced identifiers** — both tenants may hold the same local `source_entity_id` (for example `task-1`) without collision, and the derived ids differ.
- **Per-tenant publication** — each organisation has its own publication row and source-day coverage; one manifest covers the whole installation, with per-tenant expected counts.
- **Fixture invariants** — one run per task, at most one PR per task, PRs only on completed tasks, `opened_at ≥ terminal_at`, `merged_at ≥ opened_at`, every PR on its repository's default branch.
- **Installation outcomes** — empty tables with no manifest installs; a matching manifest with validated data is a no-op exiting 0; rows without a manifest refuse; an incompatible manifest, incomplete dataset or inconsistent data refuses. Refusals change nothing. A failure mid-install leaves the database untouched. Ordinary application startup installs nothing.
- **Validation order** — `dataset_version`, `seed` and `expected_counts` first, then the canonical business-data checksum.
- **Relationships are validated, not just scalars** — a dataset whose rows are individually correct but whose foreign key points at the wrong parent fails validation, because identifiers are seed-derived and therefore inside the checksum.
- **Conflicting installs** — a manifest for a different `dataset_version` or `seed`, and business rows present with no manifest, are both refused with the database unchanged. Two seeders started concurrently do not both install: the second waits on the advisory lock and then observes the first result.
- **Reachable scenarios** — normal volumes, a low-sample team, an empty combination, a `zero_outcome` row, a cross-period merge, budgets that do and do not trigger, a qualifying friction domain, and at least one `not_evaluated` rule. The `repo-api` / `Payments` failure spike is present, **visible under the investigation journey's starting filters**, and survives the three-finding cap with a budget finding ranked above it.
- **Separation** — the contract §7 fixture stays hand-checkable and is not the demo dataset.

## 7. Authentication acceptance criteria

The authentication and authorisation criteria previously held here as provisional `PA-n` items are now **`US-09`, criteria `AC-09.1`–`AC-09.16`, in `02-requirements.md`**. They are not duplicated in this document; the tests that cover them are in §3 (authentication, tenant isolation, redaction), §4 (login, logout, cache clearing) and §5 (browser journeys).

## 8. Running tests and completion criteria

Commands match `04-technical-spec.md` §7. `make setup` is required first; `make e2e` also needs `make seed`.

```
make test      # backend unit, PostgreSQL integration, API, OpenAPI, frontend component
make e2e       # browser journeys against a built frontend and seeded database
```

Underneath these are Maven and npm; the Makefile only sequences them and propagates failures.

**Proposed CI gates**, each blocking in order: build and type-check → backend unit → OpenAPI lint and conformance → PostgreSQL integration and API → security → frontend component → browser journeys.

**Proposed coverage thresholds — a proposal, not an approved standard and not evidence of correctness:** ≥ 95% branch coverage in the `metrics` package, where a missed branch is a wrong number on someone's screen; ≥ 80% line coverage elsewhere in the backend; none on generated jOOQ code. The independently stated fixture expectations in §2 are the real proof.

**Nothing here has been executed.** No test file exists, no command has been run, and no result in this document should be read as passing.
