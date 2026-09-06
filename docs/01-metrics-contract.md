# 01 — Metrics contract

> **Purpose:** the normative definitions for every number the P0 dashboard displays, precise enough to implement and to test.
> Scope is frozen in `00-research.md` §7 and is not reopened here. This document decides *precisely how*; it chooses no stack, storage, route or authentication mechanism.

## 1. Scope, conventions and demo limitations

### 1.1 Conventions

| Convention | Rule |
|---|---|
| Time zone | UTC throughout. |
| Intervals | Internal intervals are half-open `[startInclusive, endExclusive)`. UI dates are inclusive; the UI's last selected day `D` maps to `endExclusive = D + 1 day` at `00:00:00Z`. |
| `dataThrough` | A fixed UTC-midnight instant, **exclusive**. The demo reports complete UTC days only. Every selected period must satisfy `endExclusive ≤ dataThrough`. An event **at** `dataThrough` is outside the reported data: events are included only when `occurred < dataThrough`. |
| Coverage | The dataset declares `[dataAvailableFrom, dataThrough)` (§1.5). Absence of records **inside** coverage is zero activity; absence **outside** coverage is unknown and is never read as zero. |
| Previous period | `[start − (end − start), start)` — the immediately preceding block of equal length. |
| Money | Integer **USD cents**. No floating-point accumulation, no intermediate rounding. |
| Rounding | All arithmetic on unrounded values; rounding happens only at display (§8.6). |
| Ratios | Organisation and any aggregate ratio is computed from **pooled counts**, never as a mean of member percentages. |
| Threshold comparison | Rule triggers are evaluated **exactly**, not on binary floating-point percentage points. Compare with integer cross-multiplication on the underlying counts. `29/50 − 8/16` is exactly `8.0` pp, but in IEEE-754 doubles it evaluates to `7.999999999999993` and a naive `>= 8` test **fails to trigger**. Test M2 (§8.5) exists to catch this. |
| Zero denominator | Returns `null`. Never `0`, never `Infinity`. |
| Rate deltas | Percentage points. Valid even when the previous rate is `0`. |
| Relative deltas | Counts and money only; `null` when the previous value is `0` or either value is `null`. |

### 1.2 Result envelope

Every metric returns a value state and, independently, a comparison state. A raw value may be present while its comparison is unavailable.

**Value states**
- `ok` — computed.
- `zero_outcome` — computed and equal to `0` because the numerator is `0` while the denominator is `> 0`. A real result, not an absence.
- `no_denominator` — denominator is `0`; value is `null`. Copy names the specific reason per metric.
- `unavailable_for_scope` — not defined under the active filters (§5.3).
- `missing_data` — a required source is absent. Value is `null` and **is never coerced to `0`**.

A complete dataset containing no matching records yields `0` for counts and spend — that is `ok`, not `missing_data`.

**Comparison states** (the same set the precedence list below resolves over)
- `ok` — delta computed.
- `unavailable_for_scope` — the comparison is not defined under the active filters.
- `missing_data` — a required source for the current period is absent, or that period is not fully covered.
- `no_baseline` — **the baseline window is not fully covered, or a required baseline source is missing.** Nothing else produces this state.
- `no_denominator` — the current or baseline value is mathematically undefined (a zero denominator).
- `insufficient_sample` — one or both populations fall below the metric's gate (§5.4).
- `undefined_relative` — a relative change was requested and the previous value is `0` or `null`.

**Comparison-reason precedence.** When more than one reason applies, report the first that matches:

1. `unavailable_for_scope` — not defined under the active filters.
2. `missing_data` — a required source for the **current** period is absent, or that period is not fully covered.
3. `no_baseline` — the baseline window is not fully covered, or a required baseline source is missing.
4. `no_denominator` — the current or baseline value is mathematically undefined.
5. `insufficient_sample` — a §5.4 gate is unmet by one or both populations.
6. `undefined_relative` — a relative change was requested and the previous value is `0` or `null`.
7. `ok` — computed.

**A covered baseline containing zero matching records is valid data, never `no_baseline`.** Which state it produces depends on the metric:

| Covered baseline | Comparison result |
|---|---|
| Count baseline `= 0` | absolute delta computed; **relative** change → `undefined_relative` |
| Rate with denominator `= 0` | `no_denominator` — the baseline rate does not exist |
| Rate defined and equal to `0%` | **valid percentage-point comparison** when both gates qualify (`0% → 20% = +20.0 pp`) |

**Absolute deltas require both values to be defined.** An absolute delta is computed only when the current and previous values are both non-`null`; otherwise the comparison takes the first applicable state above. "Always" is never a property of a delta — only of the attempt to compute one.

**An unavailable comparison must never render as a good result.** Suppressed comparisons carry an explanation string; they are not drawn as `0`, as "flat", or as a neutral badge that reads like health.

### 1.3 Demo limitations (explicit)

These are properties of the prototype's data, not claims about production.

1. Exactly **one run per task**, and **at most one PR per task**. Task cost is defined as the sum over the task's runs so the formula is production-shaped; in the demo that sum has one term.
2. **Task status follows its sole run.** This contract does **not** define production task status. Production lifecycle and retry orchestration need an explicit state machine, specified in `03-architecture.md`.
3. `created_at == started_at`; **no queue is modelled**.
4. Only **completed** tasks may have a PR. `pr.opened_at ≥ task.terminal_at`, and `pr.merged_at ≥ pr.opened_at`.
5. **Reopened PRs are excluded.** A PR has at most one terminal transition.
6. **Licensed-seat population and capacity are fixed** across the whole demo history. Historical seat changes are a production concern.
7. **No duplicate source records.** Fixtures assert unique IDs and valid relationships; this is a fixture property and is **not** a claim that production ingestion is idempotent. Production event deduplication belongs to `03-architecture.md`.
8. Only `dataThrough`-complete UTC days are reported; no partial-day results exist.
9. **Every modelled PR targets its repository's designated default branch.** This is a fixture invariant and an eligibility condition (§1.4). Historical branch renaming is **outside the demo model** and is not simulated.
10. The dataset declares a coverage interval and contains no records outside it (§1.5).

### 1.4 Eligibility

**Eligible code-change task** — a task whose type is one of `bugfix`, `feature`, `refactor`, `tests`, `dependency_update`.

**Non-code tasks** (`repo_question`, `research`) are excluded from outcome metrics, the funnel and the cost-per-merged-PR numerator. They **are** included in total spend, the spend trend, active seats, and **network-policy friction** (§6.4) — friction is an operational rule about sandbox policy, not a code-outcome metric (`00-research.md` §3).

**Eligible PR** — a PR that targets its repository's **designated default branch**. Every PR metric in §3 and §4 carries this condition, because "merged" means merged to the default branch (`00-research.md` §4). PRs targeting any other branch are counted by no PR metric. In the demo all PRs qualify, by fixture invariant (§1.3.9).

### 1.5 Data coverage

The dataset declares a coverage interval `[dataAvailableFrom, dataThrough)`.

- **Inside coverage,** absence of matching records means **zero activity**: counts and spend are `0`, state `ok`.
- **Outside coverage,** absence means **unknown**, never zero.
- A **selected period must be fully covered.** A request that is not fully covered is rejected, not answered with a partial number.
- A **previous period or alert baseline that is not fully covered** yields `no_baseline` (comparisons) or `not_evaluated` (rules), each carrying its reason. Neither is rendered as healthy or flat.
- If a **required source is missing** for a covered period, every dependent metric — **including counts** — is `missing_data` with a `null` value. Metrics that do not depend on that source remain available.

## 2. Lifecycle and timestamp basis

**Demo lifecycle.** `created(=started) → { completed | failed | cancelled | in_progress }`. Only `completed` continues: `→ pr_opened → { merged | closed_unmerged | open }`.

**Completed** means the agent finished its assigned execution successfully. It does **not** imply human acceptance, review, or that required checks passed (`00-research.md` §3, §4).

| Metric | Timestamp that places it in a period |
|---|---|
| Merged agent PRs | `pr.merged_at` |
| Terminal PR merge rate | the PR's terminal-transition timestamp (`merged_at` or `closed_at`) |
| Task completion rate | `task.terminal_at` |
| Spend (all spend metrics, budgets) | `usage_record.metered_at` |
| Active seats | `task.created_at` |
| Funnel cohort membership | `task.created_at`; stages then observed for events occurring `< dataThrough` (§4) |
| Network-policy friction | `denial_event.occurred_at` |

A usage record is recognised **whole** at `metered_at` and is never prorated across days or periods. A record may therefore fall in a different period from its task's terminal timestamp; that is intended and is what makes period spend reconcile to the metered ledger.

## 3. The five KPI cards

Each card shows a value, a previous-period comparison, and a short definition. No sub-lines (`00-research.md` §7).

### 3.1 Merged agent PRs

- **Population:** PRs of eligible code-change tasks with `merged_at ∈ [start, end)`.
- **Value:** `count(distinct pr_id)`.
- **Exclusions:** non-code tasks; PRs merged outside the window regardless of when their task ran.
- **Nulls:** inside coverage, an empty match set is `0` (`ok`). If the PR source is missing for a covered period, the card is `missing_data` with a `null` value — **never** `0` (§1.5). This overrides any reading of this card as null-free.
- **Comparison:** absolute delta whenever **both** period counts are defined; relative delta `null` when the previous count is `0` (`undefined_relative`). No sample gate.
- **Limitation:** a count of merges, an acceptance proxy only (`00-research.md` §3).

### 3.2 Terminal PR merge rate

- **Population:** PRs of eligible code-change tasks whose terminal transition falls in `[start, end)`.
- **Value:** `merged ÷ (merged + closed_unmerged)`.
- **Exclusions:** open PRs; non-code tasks.
- **Nulls:** denominator `0` → `no_denominator`, copy "no PRs reached a terminal state in this period".
- **Comparison:** percentage points. Gate: **≥ 15 terminal PRs in each period** (§5.4).
- **Limitation:** merges may be automated; this is not evidence of human review.

### 3.3 Blended cost per merged PR

- **Numerator:** metered cost of **eligible code-change tasks** with `metered_at ∈ [start, end)`, **regardless of task outcome** — completed, failed, cancelled and in-progress all count. Cost of failure is part of unit cost.
- **Denominator:** merged agent PRs for the same period (§3.1).
- **Value:** `numerator_cents ÷ denominator`.
- **Nulls:** denominator `0` → `no_denominator`, copy **"no merged PRs in this period"**. This is *not* "no activity": positive spend with zero merged PRs is a real and important state and must stay visible alongside the spend figure.
- **Comparison:** absolute and relative. Gate: **≥ 15 merged PRs in each period**.
- **Limitation:** a **period flow metric** — the numerator is period spend, not the acquisition cost of those specific PRs. The two populations are deliberately unmatched, and at low volume the number is unstable.

### 3.4 Task completion rate

- **Population:** eligible code-change tasks with `terminal_at ∈ [start, end)` and status `completed` or `failed`.
- **Value:** `completed ÷ (completed + failed)`.
- **Exclusions:** cancelled tasks (a human decision, not a platform failure) and in-progress tasks (no outcome yet). Cancelled tasks remain visible as funnel side exits (§4).
- **Nulls:** denominator `0` → `no_denominator`. Numerator `0` with denominator `> 0` → value `0`, state `zero_outcome` (e.g. `0/10 = 0.0%`).
- **Comparison:** percentage points. Gate: **≥ 20 completed + failed tasks in each period**.
- **Limitation:** measures agent execution, not acceptance.

### 3.5 Active seats / licensed seats

- **Active:** distinct users with **≥ 1 task of any type** (code-change or non-code) with `created_at ∈ [start, end)`. Active users must belong to the licensed population.
- **Licensed:** the fixed demo seat capacity.
- **Value:** active count, with utilisation `active ÷ licensed` as the derived ratio.
- **Filtered scope:** under a **team or repository filter**, show the filtered active count and set utilisation to `unavailable_for_scope`, with visible copy that seat allocation is not defined for that scope. Team and repository seat allocations are never fabricated.
- **Comparison:** active count compared between periods **under the same filters**; absolute delta whenever both counts are defined, relative `null` when previous is `0`. Utilisation compared in percentage points only when both periods have a defined ratio. No sample gate.
- **Limitation:** seat capacity is fixed across demo history (§1.3.6).

## 4. Cohort funnel and residuals

- **Cohort:** eligible code-change tasks with `created_at ∈ [start, end)`.
- **Observation:** each stage counts **distinct tasks in that cohort** whose stage event occurred at `< dataThrough` — including after `end`.
- **Stages:** `started` → `completed` → `pr_opened` → `pr_merged`. Nesting is guaranteed by the lifecycle (§2): `merged ≤ pr_opened ≤ completed ≤ started`.
- **Side exits** (terminal, mutually exclusive with `completed`): `failed`, `cancelled`.
- **Residual** (non-terminal): `in_progress`. Shown as a residual, **never as a side exit**.
- **Identity:** `completed + failed + cancelled + in_progress = started`.
- **Label (required):** *"Tasks started in the selected period; outcomes observed through `<dataThrough − 1 day>`."*
- **Maturity:** recent cohorts have had less time to reach merge. This is explained in copy; **no numerical maturity threshold is defined**.
- **No previous-period funnel comparison** is computed for P0.

**The funnel and the KPI cards do not reconcile, by design.** A PR merged after `end` but before `dataThrough` counts in the cohort's merged stage and **not** in that period's merged-PR KPI. Conversely a PR merged inside the period whose task was created earlier counts in the KPI and **not** in the cohort. Worked in §8.3.

## 5. Trends, filters, benchmarks and gates

### 5.1 Trends

Two series, bucketed by complete UTC day over the selected range:
- **Merged agent PRs over time** — bucket by `pr.merged_at`.
- **Agent spend over time** — bucket by `usage_record.metered_at`, **all task types** (`00-research.md` §3).

Days with no matching records are `0`, not gaps.

### 5.2 Global filters

Date range, team and repository. A task carries `team_id` and `repo_id` captured at creation; PRs and usage records inherit attribution through their task (§7).

### 5.3 Filter exceptions (must be visible on screen)

| Metric | Under team filter | Under repository filter |
|---|---|---|
| Seat utilisation | `unavailable_for_scope` | `unavailable_for_scope` |
| Budget risk | evaluated against that team's budget | `unavailable_for_scope` — no repository allocation exists and none is invented |
| Budget period | always calendar month-to-date, independent of the selected date range (§6.1) | same |

### 5.4 Benchmark and sample gates

**Benchmark scope.** The organisational benchmark uses the **same date range and the same explicit repository filter**, and **ignores the team filter**. The selected team's own contribution **is included**: this is an *inclusive organisational benchmark*, not "everyone else". Selecting a repository **row** in the table is not a global repository filter and does not change the benchmark.

**Gates** (product heuristics, not tests of statistical significance — `00-research.md` §3):

| Comparison | Gate |
|---|---|
| Task completion rate | ≥ 20 completed + failed eligible tasks |
| Terminal PR merge rate | ≥ 15 terminal eligible PRs |
| Cost per merged PR | ≥ 15 eligible merged PRs |

The cost gate of 15 is an approved product heuristic. Reusing the number 15 gives it **no** statistical justification.

**Application.**
- Gates apply **per metric**, never to a whole row: one column may show a comparison while another in the same row does not.
- Row-vs-benchmark: **both populations** must qualify.
- Period-over-period rate and cost deltas: **both periods** must qualify.
- Counts (merged PRs, active seats) have no gate.
- A raw value stays visible whenever it is mathematically defined. Only the **comparison** is suppressed, with an explanation.

## 6. Attention rules

Four rule types. Every finding is computed. Severity is `HIGH` or `MEDIUM`; only budget risk can reach `HIGH`. **A rule that cannot be evaluated produces no finding and is never reported as healthy.**

### 6.0 Filter application

For the three non-budget rules: apply the explicit **team and repository filters together** to build the current population, and apply the **identical filters** to the baseline population. Then, for each evaluated team or repository scope, **intersect that scope with the global filters**; a scope whose intersection is empty produces no finding. Findings from scopes unrelated to the active filters are never reported.

Budget scoping follows §6.1. Only the **benchmark** calculation (§5.4) deliberately ignores the team filter — nothing in §6 does.

### 6.1 Budget risk

- **Evaluation period:** the calendar month containing `dataThrough − 1 day`, **independent of the selected dashboard date range**. The finding carries this month explicitly.
- **Elapsed days:** complete UTC days of that month before `dataThrough`.
- **Forecast:** `mtd_spend ÷ elapsed_days × days_in_month`.
- **Insufficient history:** `elapsed_days < 3` → `insufficient_history`, no forecast, no finding. At `dataThrough = 2026-02-01T00:00:00Z` the last complete day is 31 Jan, so the evaluated month is **January** with `elapsed_days = 31` — the month-boundary case.
- **Scopes and filters:** with no team or repository filter, evaluate the organisation and every team with a configured budget. With a **team filter**, evaluate only that team's budget. With a **repository filter**, budget evaluation is `unavailable_for_scope` — **including when a team filter is also applied**.
- **Budget configuration states**, none of which renders as healthy:
  - **no configured budget** → `not_evaluated`, reason "no budget configured for this scope";
  - **budget ≤ 0** → `invalid_budget_configuration`; no percentage forecast comparison and no finding;
  - **budget > 0** → normal evaluation.
- **Trigger:** `overrun = forecast ÷ budget − 1`; finding when `overrun > 0.10`.
- **Severity:** `MEDIUM` when `overrun > 0.10`; `HIGH` when `overrun > 0.20`.
- **Magnitude:** `overrun`.

### 6.2 Task failure spike

- **Current window:** the selected range. **Baseline:** the 28 complete UTC days immediately before `start` — `[start − 28d, start)`, **non-overlapping**.
- **Rate:** `failed ÷ (completed + failed)` on eligible code-change tasks, by `terminal_at`.
- **Sample:** **each** window independently needs ≥ 20 terminal (completed + failed) tasks. Otherwise `not_evaluated`.
- **Trigger:** `current_rate − baseline_rate ≥ 8` percentage points.
- **Scopes:** each team and each repository. A repository finding is **not** suppressed because its team also triggered — repositories are shared and no parent-team ownership is assumed.
- **Severity:** `MEDIUM`. **Magnitude:** the rise in percentage points.

### 6.3 Terminal merge-rate decline

- **Current window:** the selected range. **Baseline:** the immediately preceding equal-length period.
- **Rate:** §3.2, by terminal transition timestamp.
- **Sample:** each window independently needs ≥ 15 terminal PRs. Otherwise `not_evaluated`.
- **Trigger:** `baseline_rate − current_rate ≥ 8` percentage points.
- **Scopes:** each team and each repository.
- **Severity:** `MEDIUM`. **Magnitude:** the decline in percentage points.

### 6.4 Network-policy friction

- **Window:** the selected range, anchored on the denial event's **`occurred_at`**, using `[start, end)`. An event at exactly `end` is excluded.
- **Task scope:** **all task types**, code-change and non-code (§1.4). Friction is an operational rule about sandbox policy, not a code-outcome metric.
- **Attribution:** every denial event belongs to a task; that task supplies team, repository and owner (§7).
- **Counting:** per normalised domain, count **distinct affected `task_id`** and **distinct task-owner `user_id`**. Repeated denial events from one task inflate neither count.
- **Trigger:** `distinct_tasks ≥ 5` **and** `distinct_users ≥ 3`. Both required.
- **Normalisation:** lowercase, trailing dot stripped. Distinct normalised domains are distinct findings and are never collapsed.
- **Scopes:** evaluated per team and per repository from task attribution, after the §6.0 filter intersection.
- **Severity:** `MEDIUM`. **Magnitude:** distinct affected task count.
- **Privacy:** the domain is carried in a field marked admin-only (`00-research.md` §9). Non-admin rendering shows a redacted evidence string that keeps the counts. **This contract does not define who is an admin or how the restriction is enforced** — access behaviour is `02-requirements.md`, the trust boundary `03-architecture.md`, enforcement `04-technical-spec.md`.

### 6.5 Identity, deduplication and selection

**Finding identity:** `rule_id : scope_type : scope_id : evaluation_period_key [ : normalised_domain ]`.
Budget findings use **their own month** as the period key, never the dashboard date range. Only findings with identical identity are deduplicated; nothing is merged across rules, scopes or domains.

**Deterministic ranking**, then take the top three:

1. Severity — `HIGH` before `MEDIUM`.
2. Rule order — budget risk, task failure spike, terminal merge-rate decline, network-policy friction.
3. Within-rule magnitude, descending (§6.1–§6.4).
4. Stable identifier ascending — `scope_type`, then `scope_id`, then `normalised_domain` where applicable.
5. **Full finding identity string** ascending, as the final tie-breaker.

Step 5 makes the order **total** even when two entity types share an id — a team and a repository both named `payments` cannot tie. The same inputs always produce the same three findings in the same order.

## 7. Fixture

**Coverage** `[dataAvailableFrom = 2025-12-01T00:00:00Z, dataThrough = 2026-02-04T00:00:00Z)` — chosen to fully cover the selected period, the previous period, **and** the 28-day failure baseline `[2025-12-19T00:00:00Z, 2026-01-16T00:00:00Z)`. Last complete day: 3 Feb 2026. All PRs target their repository's default branch (§1.3.9).
**Selected period** `P = [2026-01-16T00:00:00Z, 2026-02-01T00:00:00Z)` — 16 days.
**Previous period** `P₋₁ = [2025-12-31T00:00:00Z, 2026-01-16T00:00:00Z)` — 16 days.

**Seats:** licensed capacity **6** (`u1…u6`), fixed. **Teams:** `T-PLAT` = u1, u2, u5; `T-PAY` = u3, u4, u6. **Repos:** `R-API`, `R-WEB`.
**Budgets:** organisation, February 2026 = **200000 cents ($2,000.00)**. No team budgets are configured in the fixture, so team budget scopes are `not_evaluated` with reason "no budget configured" (§6.1).

### 7.1 Tasks (10; one run each)

| Task | User | Team | Repo | Type | `created_at` | Status | `terminal_at` |
|---|---|---|---|---|---|---|---|
| T10 | u1 | T-PLAT | R-API | feature | **2025-12-31T00:00Z** | completed | 2026-01-01T00:30Z |
| T1 | u1 | T-PLAT | R-API | bugfix | 2026-01-05T09:00Z | completed | 2026-01-05T09:40Z |
| T2 | u1 | T-PLAT | R-API | feature | 2026-01-08T11:00Z | completed | 2026-01-08T11:30Z |
| T3 | u2 | T-PLAT | R-WEB | tests | 2026-01-10T14:00Z | failed (`tests_failed`) | 2026-01-10T14:20Z |
| T9 | u5 | T-PLAT | R-API | **research (non-code)** | 2026-01-12T09:00Z | completed | 2026-01-12T09:15Z |
| T4 | u2 | T-PLAT | R-WEB | refactor | 2026-01-15T10:00Z | completed | 2026-01-15T10:50Z |
| T5 | u3 | T-PAY | R-API | feature | 2026-01-18T08:00Z | cancelled | 2026-01-18T08:10Z |
| T6 | u3 | T-PAY | R-API | bugfix | 2026-01-22T13:00Z | failed (`sandbox_denied`) | 2026-01-22T13:25Z |
| T7 | u4 | T-PAY | R-WEB | feature | 2026-01-30T16:00Z | completed | 2026-01-30T16:45Z |
| T8 | u4 | T-PAY | R-WEB | feature | 2026-01-31T23:30Z | **in_progress** | — |

### 7.2 Pull requests (5)

| PR | Task | `opened_at` | Terminal state | Terminal timestamp |
|---|---|---|---|---|
| PR-5 | T10 | 2026-01-01T01:00Z | merged | 2026-01-02T12:00Z |
| PR-1 | T1 | 2026-01-05T09:45Z | merged | 2026-01-07T10:00Z |
| PR-2 | T2 | 2026-01-08T11:35Z | closed_unmerged | 2026-01-12T08:00Z |
| PR-3 | T4 | 2026-01-15T11:00Z | merged | 2026-01-20T09:00Z |
| PR-4 | T7 | 2026-01-30T17:00Z | merged | **2026-02-03T09:00Z** |

### 7.3 Usage records (one per run)

| Task | `metered_at` | Cents |
|---|---|---|
| T10 | 2026-01-01T00:30Z | 700 |
| T1 | 2026-01-05T09:40Z | 1200 |
| T2 | 2026-01-08T11:30Z | 900 |
| T3 | 2026-01-10T14:20Z | 500 |
| T9 | 2026-01-12T09:15Z | 200 |
| T4 | 2026-01-15T10:50Z | 1400 |
| T5 | 2026-01-18T08:10Z | 150 |
| T6 | 2026-01-22T13:25Z | 350 |
| T7 | 2026-01-30T16:45Z | 1800 |
| T8 | **2026-02-02T10:00Z** | 400 |

**Boundary cases deliberately present:** T10 created at **exactly** `P₋₁`'s first instant `2025-12-31T00:00:00Z` (included by `[startInclusive, …)`); T8 created inside `P` but metered **outside** it; PR-4 merged after `end` but before `dataThrough`; PR-3 merged inside `P` from a task created before `P`; T9 non-code; T5 cancelled; T8 in-progress.

## 8. Worked calculations

Each result is derived independently from §7 by applying the timestamp basis in §2.

### 8.1 KPI cards, period `P`

| Card | `P` | `P₋₁` | Delta |
|---|---|---|---|
| Merged agent PRs | **1** | **2** | −1 (**−50.0%**) |
| Terminal PR merge rate | **100.0%** (1/1) | **66.7%** (2/3) | **+33.3 pp**, *suppressed* — `insufficient_sample` |
| Blended cost per merged PR | **$23.00** (2300¢/1) | **$23.50** (4700¢/2) | *suppressed* — `insufficient_sample` |
| Task completion rate | **50.0%** (1/2) | **80.0%** (4/5) | *suppressed* — `insufficient_sample` |
| Active seats / licensed | **2** / 6 = 33.3% | **3** / 6 = 50.0% | −1 (**−33.3%**); utilisation **−16.7 pp** |

**Derivations.**

- *Merged PRs, `P`:* `merged_at ∈ [01-16, 02-01)` → PR-3 (01-20). PR-4 merged 02-03 is outside. = **1**.
- *Merged PRs, `P₋₁`:* PR-5 (01-02), PR-1 (01-07). = **2**. Delta −1; relative −1/2 = **−50.0%**.
- *Merge rate, `P`:* terminal transitions in `P` → PR-3 merged. 1 merged / 1 terminal = **100.0%**. Gate needs 15; 1 < 15.
- *Merge rate, `P₋₁`:* PR-5 merged, PR-1 merged, PR-2 closed → 2/3 = **66.7%**. 3 < 15. The change is `100.0% − 66.7% = ` **+33.3 pp** (an *improvement*), and it is **not displayed** because both periods fail the 15-PR gate.
- *Cost/PR, `P`:* code-change cents with `metered_at ∈ P` → T5 150 + T6 350 + T7 1800 = **2300¢**. T8's 400¢ is metered 02-02, outside `P`. ÷ 1 merged PR = **2300¢ = $23.00**.
- *Cost/PR, `P₋₁`:* T10 700 + T1 1200 + T2 900 + T3 500 + T4 1400 = **4700¢** (T9's 200¢ is non-code, excluded from this numerator). ÷ 2 = **2350¢ = $23.50**. Gate needs 15 merged PRs; 1 and 2 both fail.
- *Completion, `P`:* `terminal_at ∈ P`, code-change, completed|failed → T6 failed, T7 completed. T5 cancelled and T8 in-progress excluded. 1/2 = **50.0%**. 2 < 20.
- *Completion, `P₋₁`:* T10, T1, T2, T4 completed; T3 failed; T9 non-code excluded. 4/5 = **80.0%**. 5 < 20.
- *Active seats, `P`:* `created_at ∈ P` → T5,T6 (u3), T7,T8 (u4) → {u3,u4} = **2**. Utilisation 2/6 = 33.333…% → **33.3%**.
- *Active seats, `P₋₁`:* T10,T1,T2 (u1), T3,T4 (u2), T9 (u5) → {u1,u2,u5} = **3**. 3/6 = **50.0%**. Delta −1; relative −1/3 = **−33.3%**; utilisation 33.333… − 50 = **−16.667 pp → −16.7 pp**.

### 8.2 Total spend (all task types)

`P` = 150 + 350 + 1800 = **2300¢ = $23.00**. `P₋₁` = 700 + 1200 + 900 + 500 + 200 + 1400 = **4900¢ = $49.00**. Delta −2600¢ (**−53.1%**; −2600/4900 = −53.061%).

### 8.3 Cohort funnel, period `P`

Cohort = eligible code-change tasks with `created_at ∈ P` = **T5, T6, T7, T8**.

| Stage / exit | Count | Members |
|---|---|---|
| Tasks started | **4** | T5, T6, T7, T8 |
| Completed | **1** | T7 |
| PR opened | **1** | T7 → PR-4 |
| PR merged | **1** | T7 → PR-4, merged 2026-02-03T09:00Z (`< dataThrough`) |
| Side exit: failed | **1** | T6 |
| Side exit: cancelled | **1** | T5 |
| Residual: in progress | **1** | T8 |

Identity: `1 + 1 + 1 + 1 = 4` ✓. Nesting: `1 ≤ 1 ≤ 1 ≤ 4` ✓.
Label: *"Tasks started in the selected period; outcomes observed through 3 February 2026."*

**Non-reconciliation, demonstrated.** Funnel merged = 1 (**PR-4**, from T7). KPI merged = 1 (**PR-3**, from T4, created before `P`). Equal counts, **disjoint sets**. Neither is wrong; they answer different questions (§4).

### 8.4 Comparison table, period `P`

Team view:

| Row | Completion rate | Merge rate | Cost per merged PR |
|---|---|---|---|
| T-PLAT | `no_denominator` — no terminal code-change tasks | 100.0% (1/1) | **$0.00** (0¢ / 1) |
| T-PAY | 50.0% (1/2) | `no_denominator` — no terminal PRs | `null` — **no merged PRs in this period** (spend $23.00) |
| **Org benchmark** | **50.0%** (1/2) | **100.0%** (1/1) | **$23.00** (2300¢/1) |

Repository view:

| Row | Completion rate | Merge rate | Cost per merged PR |
|---|---|---|---|
| R-API | **0.0%** (0/1) — `zero_outcome` | `no_denominator` | `null` — no merged PRs (spend $5.00) |
| R-WEB | 100.0% (1/1) | 100.0% (1/1) | **$18.00** (1800¢/1) |
| **Org benchmark** | **50.0%** (1/2) | **100.0%** (1/1) | **$23.00** (2300¢/1) |

**Row-vs-benchmark comparison states**, resolved by the §1.2 precedence — not all of them are `insufficient_sample`:

| State | Fixture examples | Why |
|---|---|---|
| `no_denominator` | T-PLAT completion (`0/0`); T-PAY merge rate (`0/0`); T-PAY and R-API cost per merged PR (0 merged PRs) | the **row's own ratio is undefined**, so there is nothing to compare; `no_denominator` outranks `insufficient_sample` |
| `insufficient_sample` | R-API completion `0.0%` vs benchmark `50.0%` (1 < 20); T-PAY completion `50.0%` vs `50.0%` (2 < 20); T-PLAT merge rate `100.0%` vs `100.0%` (1 < 15); T-PLAT cost `$0.00` vs `$23.00` (1 < 15) | **both** ratios are defined, but the gate is unmet |

Every suppressed comparison carries its own explanation; none is drawn as flat or healthy.

**Three things this table proves.**
1. **Pooling, shown separately.** Team view: `(0 + 1) / (0 + 2) = 50.0%`. Repository view: `(0 + 1) / (1 + 1) = 50.0%`. Two different partitions, one pooled org figure. A mean of member percentages is undefined here — T-PLAT's completion is `null` — so pooled counts are mandatory, not merely preferred.
2. **Zero outcomes ≠ no activity.** R-API is `0.0%` from `0/1`; T-PLAT completion is `null` from `0/0`. Different states, different copy.
3. **Positive spend, no merged PRs.** T-PAY spent **$23.00** and merged nothing: cost per merged PR is `null` with copy "no merged PRs in this period" — **never** "no activity", and the spend stays on screen. T-PLAT's `$0.00` is the mirror image and is a true consequence of the flow-metric definition (§3.3): its merged PR-3 was produced by spend recognised in `P₋₁`.

### 8.5 Budget, and boundary examples the fixture cannot reach

*From the fixture:* evaluated month = February 2026 (contains `dataThrough − 1 day` = 3 Feb); `elapsed_days = 3` (1, 2, 3 Feb) — exactly the minimum, so it evaluates. MTD spend = **400¢ = $4.00** (T8 only). Forecast = `400 ÷ 3 × 28` = **3733.33¢ = $37.33**. Against the declared organisation budget of **200000¢ ($2,000.00)** the overrun is `3733.33 ÷ 200000 − 1 = −98.1%` — far below the `> 10%` trigger → **no finding**. The two team scopes have no configured budget → `not_evaluated`, reason "no budget configured" (B6).

*Separate boundary examples (independent figures, not from the fixture):*

| # | Scenario | Inputs | Expected |
|---|---|---|---|
| B1 | Budget MEDIUM | Feb 2026, budget $10,000, `elapsed = 14`, MTD $5,900 | forecast `5900/14×28` = **$11,800**; overrun `11800/10000−1` = **18.0%** → `MEDIUM` |
| B2 | Budget HIGH | same, MTD $6,250 | forecast **$12,500**; overrun **25.0%** → `HIGH` |
| B3 | Budget just below trigger | same, MTD $5,500 | forecast **$11,000**; overrun **10.0%**, not `> 10%` → **no finding** |
| B4 | Insufficient history | `dataThrough = 2026-02-03T00:00Z`, `elapsed = 2` | `insufficient_history`, no forecast, no finding |
| B5 | Month boundary | `dataThrough = 2026-02-01T00:00Z` | evaluated month = **January**, `elapsed = 31` (full month) |
| F1 | Failure spike | current 24 terminal / 12 failed = 50.0%; baseline `[start−28d, start)` 40 terminal / 16 failed = 40.0% | rise **10.0 pp ≥ 8** → `MEDIUM`, magnitude 10.0 |
| F2 | Failure spike, near miss | baseline 40 terminal / 17 failed = 42.5% | rise **7.5 pp** → no finding |
| F3 | Failure spike, small baseline | baseline 19 terminal | `not_evaluated` — **not** reported healthy |
| M1 | Merge decline | current 16 terminal / 8 merged = 50.0%; previous 20 terminal / 12 merged = 60.0% | decline **10.0 pp ≥ 8** → `MEDIUM` |
| M2 | Merge decline, exact threshold | previous 50 terminal / 29 merged = 58.0%; current 16 terminal / 8 merged = 50.0% | decline **exactly 8.0 pp** → triggers (`≥ 8`). Float arithmetic yields `7.999999999999993` and wrongly does not trigger — see §1.1 *Threshold comparison* |
| N1 | Network friction | `internal-registry.corp` → 6 tasks, 4 users | ≥5 and ≥3 → `MEDIUM`, magnitude 6 |
| N2 | Network friction, user shortfall | same domain → 6 tasks, 2 users | no finding |
| N3 | Distinct domains | `a.corp` 5 tasks/3 users; `b.corp` 5 tasks/3 users | **two** findings, never collapsed |
| B6 | No configured budget | team has no budget row | `not_evaluated`, reason "no budget configured"; **not** healthy |
| B7 | Invalid budget | budget `$0` (or negative) | `invalid_budget_configuration`; no percentage comparison, no finding |
| B8 | Overrun exactly 20% | budget $10,000, `elapsed = 14`, MTD $6,000 | forecast **$12,000**; overrun **exactly 20.0%** → `MEDIUM` (HIGH needs `> 20%`) |
| N4 | Repeated denials, one task | 12 denial events, all task `TX`, owner `uA` | distinct tasks **1**, distinct users **1** → **no finding** |
| N5 | Non-code tasks count | 5 tasks (3 `research`, 2 `feature`), 3 distinct owners | ≥5 and ≥3 → `MEDIUM`, magnitude **5** |
| N6 | Exact trigger boundary | 5 tasks / 3 users → fires; 4 tasks / 3 users → no; 5 tasks / 2 users → no | `≥` on **both** conditions |
| N7 | Window boundary | denial `occurred_at == end` | excluded by `[start, end)` |
| S1 | Selection | B2 (`HIGH`), F1 (10.0 pp), M1 (10.0 pp), N1 | ordered B2, F1, M1 → **N1 not displayed** |

### 8.6 Display formatting

| Kind | Format | Example |
|---|---|---|
| Spend totals | whole dollars, thousands-separated | `$49` |
| Positive spend rounding to zero | sub-dollar indicator | `<$1` |
| Cost per merged PR | two decimals | `$23.00` |
| Rates | one decimal | `50.0%` |
| Rate deltas | one decimal, percentage points | `−16.7 pp` |
| Counts | separated integers | `1,204` |
| Suppressed comparison | explanation, never a neutral or zero badge | "Comparison needs 15 terminal PRs in both periods" |

## 9. Test checklist

**Boundaries** — task at the first instant of a period (T10 at `2025-12-31T00:00:00Z`); task at the last instant; `endExclusive` excludes a record at exactly `end`; **an event at exactly `dataThrough` is excluded**; UI inclusive date maps to `end + 1 day`; period ending exactly at `dataThrough`; PR merged after `end` but `< dataThrough`; usage metered outside its task's period (T8); denial event at exactly `end` excluded (N7).

**Exclusions** — non-code tasks absent from completion rate, funnel, merge rate and the cost numerator, present in total spend, active seats **and network-policy friction** (N5); PRs not targeting the default branch counted by no PR metric; cancelled excluded from the completion denominator and present as a side exit; in-progress excluded from completion and present as a residual, never a side exit; open PRs excluded from merge rate; reopened PRs rejected by fixture validation.

**Zero and null** — `0/10 = 0.0%` (`zero_outcome`); `0/0 = null` (`no_denominator`); positive spend ÷ 0 merged PRs = `null` with the merged-PR explanation, spend still displayed; no `Infinity`, no `NaN` anywhere.

**Coverage** — these are four distinct scenarios with four distinct outcomes, and no scenario carries two of them:
1. *Covered period, no matching records* → `0`, state `ok`.
2. *Selected period not fully covered* → the request is **rejected**; no partial number is returned.
3. *Covered period, required source missing* → `missing_data` with a `null` value, **including counts** (§3.1); unrelated metrics stay available.
4. *Baseline window not fully covered, or its required source missing* → `no_baseline` for comparisons and `not_evaluated` for rules, each with a reason; neither renders as healthy or flat.

Also: a **covered baseline with zero records** is valid, and resolves per the §1.2 table — count `0` → `undefined_relative` for relative change; rate denominator `0` → `no_denominator`; rate `0%` → valid pp delta when gates qualify. Comparison-reason precedence returns the first matching reason (§1.2).

**Deltas** — rate delta valid from a zero previous rate (`0% → 20% = +20.0 pp`) when both samples qualify; a complete zero-count baseline is distinguished from missing history; count relative delta `10 → 0 = −100%`; `0 → 10` relative = `null` while the absolute delta is `+10`; relative delta `null` when either side is `null`.

**Filters** — team and repository filters change the population but not the timestamp basis; **rule populations and their baselines receive the identical filter intersection** (§6.0), and a scope disjoint from the filters produces no finding; seat utilisation `unavailable_for_scope` under both; budget `unavailable_for_scope` under a repository filter; budget month unchanged by the dashboard date range; benchmark ignores the team filter, honours the repository filter, and includes the selected team; a table row selection does not alter the benchmark.

**Pooled ratios** — org completion identical in the team and repository views (50.0%); pooled ≠ mean of member rates; a `null` member row does not poison the pooled result.

**Sample gates** — comparison suppressed at 14 and shown at 15 terminal PRs; at 19 and 20 terminal tasks; at 14 and 15 merged PRs; gates applied per metric so one column may compare while another does not; raw values remain visible in every suppressed case.

**Attention rules** — each trigger at, just below and just above threshold (F1–F3, M1–M2, N1–N7, B1–B8); non-overlapping failure baseline; `not_evaluated` never renders as healthy; a repository finding is not suppressed when its team also fires; distinct domains produce distinct findings; **repeated denials from one task inflate neither count** (N4); identical identity deduplicates; budget finding carries its own month; **no budget** and **zero/negative budget** are distinct non-findings (B6, B7); **overrun of exactly 20% is `MEDIUM`, above 20% is `HIGH`** (B8, B2); budget `unavailable_for_scope` under a repository filter even with a team filter present; ranking is stable across runs, total under shared ids, and caps at three (S1).

**Determinism** — the fixture with a fixed `dataThrough` produces byte-identical results across runs; §8.1, §8.3 and §8.4 values are asserted exactly.

## 10. Approved decisions, limitations, and open questions

### 10.1 Approved decisions — closed

- Sample gates apply to KPI rate and cost deltas as well as to table comparisons (§5.4).
- The budget month is the calendar month containing `dataThrough − 1 day`, labelled explicitly wherever a budget figure or finding appears (§6.1).
- `$0.00` cost per merged PR is a valid result under the flow definition (§3.3, §8.4).
- The fixture stays small; larger populations come from the demo generator.
- Threshold comparisons are exact, never floating-point percentage points (§1.1).

### 10.2 Limitations of this contract and its demo

These are properties of the prototype, not open questions.

- Sample gates are **product heuristics**, not tests of statistical significance (§5.4).
- **A large headline volume does not by itself guarantee a comparison qualifies.** Every gate is two-sided: the **previous period** must qualify as well as the current one, and each metric's own denominator must meet its own gate. A period with many merged PRs can still show no merge-rate delta.
- No budget finding arises from the fixture — February MTD is **$4.00** — so budget behaviour is proven only by B1–B8 (§8.5).
- Per-team budget amounts are not fixed here; the demo values belong to the fixture generator and `03-architecture.md`.
- Severity is two-valued, with only budget risk escalating (§6).
- The non-admin redacted friction string is described, not specified; the exact copy belongs to `02-requirements.md`.
- Production task lifecycle, retry orchestration, event deduplication, historical seat changes and branch renaming are all explicitly outside this contract (§1.3).

### 10.3 Dataset coverage — settled

The shipped demo dataset covers the **latest date presets** and, for each, its **equal-length comparison period**, its **28-day failure-spike baseline**, and the **evaluated budget month-to-date** (§6.1). Dataset size, calendar dates and `dataAvailableFrom` are configured in `04-technical-spec.md` §6 and are not repeated here.

A **custom range lying wholly inside the published coverage is valid**, even when its comparison period or failure baseline falls outside that coverage. Those are not errors: the comparison resolves to `no_baseline` and the affected rules to `not_evaluated` (§1.2, §1.5, §6), each carrying its reason and neither rendered as healthy.

The earlier requirement that coverage span the baseline of *every* selectable historical range is **withdrawn**. It cannot hold for a range beginning at `dataAvailableFrom`, and it is unnecessary — the unavailable-baseline states exist precisely for that case.

### 10.4 Open questions

1. **Per-team budget values and denial-event volume** the generator should produce so the panel has findings to display. This contract defines the rules, not the data.
