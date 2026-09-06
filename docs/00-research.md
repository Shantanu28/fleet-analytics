# 00 — Research & product framing

> **Purpose:** decide *who* this dashboard is for, *what one question* it answers, and *which metrics* earn a place on it.
> **§7 is the authoritative frozen scope for the prototype (P0).** §6 stays the broader catalogue: every item there is classified **[P0 visible]**, **[P0 supporting]** or **[Deferred]**.
> Exact formulas, timestamp bases, exclusions, minimum samples, benchmark scope and filter semantics live in `01-metrics-contract.md`. This page decides *what* and *why*; the contract decides *precisely how*.

## 1. The product we are analysing

Fleet is an imaginary platform in the mould of Claude Code on the web: an engineer connects a GitHub repo, describes a task, and an agent runs it in an isolated cloud sandbox (network and filesystem restricted, git through a proxy limited to authorised repos). Sessions run in parallel, can be steered mid-run, and finish by opening a pull request with a change summary. Admins toggle access per account and configure which domains the sandbox may reach; cloud sessions share rate limits with other usage.

### 1.1 Entity model

| Entity | Definition |
|---|---|
| **Org → Team → User** | the customer, its teams, and enabled users; the org has a number of **licensed seats** |
| **Repository** | where the agent is pointed |
| **Task** | the engineering intent a user submits; has one type, one owner, one team, one repo |
| **Run** | one execution attempt of a task; carries duration, model usage, cost and a terminal status. A task has one or more runs |
| **Steering event** | a human message sent after a run began (excludes the original prompt and system messages) |
| **Pull request** | the reviewable outcome; for v1 a task produces zero or one PR. Carries review events and, at merge, whether configured required checks passed |
| **Usage record** | metered compute per run, by model tier |
| **Governance event** | sandbox network denial, rate-limit hit, access change |

Attribution is **explicit, not inferred**: Fleet creates the branch and PR itself, so `task_id` and `run_id` are embedded in branch and PR metadata. No heuristic matching.

The table describes the **broader product model**, not the prototype's data requirement: fields and entities that exist only to serve deferred metrics — review events, steering events, required-check outcomes, separate rate-limit events — are not automatically required by the P0 implementation. What the mock actually carries is settled in `03-architecture.md` and `04-technical-spec.md`.

**Prototype constraint:** mock data generates one run per task. Cost and duration are still recorded at run level so the schema is production-shaped; only the generator is simplified. Definitions are written for a task with one or more runs.

### 1.2 Commercial assumption

Fleet uses hybrid pricing: organisations pay for **enabled seats** plus **metered agent compute**. Seat utilisation measures the licence component; spend and budget metrics use the billed metered ledger. Token counts are diagnostic inputs, not the customer's billed amount.

## 2. Who is it for

**P1 — Engineering leader (VP Engineering / Director) — PRIMARY**
Owns the budget and the renewal decision. Visits weekly, or before planning and budget meetings.
Needs: adoption trend, value and unit-economics signals, cost trajectory, one-glance health.
Tolerance for detail: **low** — headlines, with the option to drill down.

**P2 — Team lead / Engineering manager — SECONDARY**
Responsible for their team's effective use of agents. Visits weekly.
Needs: their team versus the org; where agents succeed or fail for their repos; signals worth investigating (a low completion rate can point at task scoping, repo readiness, model fit or platform reliability — the dashboard narrows the search, it doesn't name the cause).
Tolerance for detail: **medium** — one team, several repos.

**P3 — Platform / DevProd admin — SECONDARY**
Operates the integration: sandbox allowlist, per-team budgets, access. Visits when something looks wrong.
Needs: failure breakdowns by reason, alerts on cost and error movements, per-repo health, network-policy friction. The prototype serves this need through the needs-attention panel only; the breakdown views are deferred (§10.1).
Tolerance for detail: **high**, but only for the thing that broke.

### One page, three entry points
- **P1** reads the KPI row, unfiltered, and stops. Everything below exists for when a number surprises them.
- **P2** opens the same page with the **team filter** applied (filters live in the URL, so the view is bookmarkable). The comparison table carries a scoped organisational benchmark.
- **P3** starts at the **needs-attention panel**. The prototype has no separate health section: a finding states the evidence that triggered it and links to a relevant filtered investigation view (§7).

**Grain stops at the team and repository.** There is no engineer-level view. Individual analytics is a different product with different consent, audit and retention requirements; putting it behind an admin toggle would not make it safe.

## 3. Measurement scope and boundaries

Fleet supports code-change tasks (bugfix, feature, refactor, tests, dependency update) and non-code tasks (repository questions and research). The boundaries below are stated at business level; the exact rules are normative in `01-metrics-contract.md`.

- **Outcome metrics and task completion rate cover code-change tasks.** Non-code tasks are excluded from the outcome funnel and from merge, conversion and cost-per-outcome metrics — otherwise a valuable question session would lower PR conversion and inflate cost per merged PR.
- **Task completion rate is completed eligible code-change tasks ÷ (completed + failed eligible code-change tasks).** Cancelled and in-progress tasks are excluded from that denominator: a cancellation is a human decision rather than a platform failure, and an unfinished task has no outcome yet. Cancelled tasks remain visible as funnel side exits (§6.2).
- **Total spend and adoption may include non-code tasks.** Seats and the spend trend cover all agent usage, so the cost on the page is not quietly smaller than the bill.
- **Blended cost per merged PR uses code-change metered spend.** It is a **unit-cost indicator, not a claim of ROI** (§10.2).
- **A merged agent PR is an acceptance proxy.** It records that a change was let into the default branch. It is not proof of production quality, review depth or time saved, and the prototype does not claim a human approved a PR: merges can be automated, and review analytics are deferred (§10.1).
- **The funnel follows a task cohort; the KPI cards are period-based metrics.** The funnel walks one set of tasks through four stages; each headline metric is computed on its own defined reporting-period basis. The two totals may therefore differ, for reasons `01-metrics-contract.md` documents and the UI explains where they sit together. Differing for a documented reason is not licence for inconsistent calculation: each metric has exactly one defined basis, and it is applied the same way everywhere it appears.
- **Budget evaluation is always calendar month-to-date**, independent of the selected date range, because budgets are monthly. This is a deliberate exception to the global filters and is labelled on screen wherever a budget number appears.
- **Minimum-sample thresholds are product heuristics, not tests of statistical significance.** They exist to stop a five-task repository being ranked against the org; they do not make a comparison significant.
- **The mock generates one run per task** (§1.1). Definitions are written for a task with one or more runs; only the generator is simplified.

## 4. The one question, and the four underneath it

> **"Is Fleet producing accepted code changes at a sustainable cost — and where should we act this week?"**

"Accepted" is deliberately narrow: the pull request was merged into the default branch. That is an acceptance proxy, not a claim that the change was reviewed by a human, deployed, or stayed in production (§3). Post-merge quality (revert rate) is the right guardrail but needs deploy and revert events the mock does not model (§10.1).

| | Question | Mainly for |
|---|---|---|
| **Q1** | Are people using it, and are we paying for the right number of seats? | P1 |
| **Q2** | Is the work it produces accepted, and where? | P1, P2 |
| **Q3** | What does it cost, and is cost per accepted outcome improving? | P1, P3 |
| **Q4** | Is it healthy and safe — what's failing, and why? | P3, P2 |

Every element on the page serves the headline or one of Q1–Q4, or it is cut.

## 5. Landscape and product emphasis

| Category | Pattern observed | Limitation | Fleet decision |
|---|---|---|---|
| Vendor analytics for AI coding tools — [Claude Code analytics](https://code.claude.com/docs/en/analytics), [GitHub Copilot metrics data](https://docs.github.com/en/copilot/reference/metrics-data) | adoption, acceptance-style trust metrics, spend and PR-level counts, reported down to the individual: Claude Code analytics publishes *suggestion accept rate*, *lines of code accepted*, *PRs with CC* and a top-10 *leaderboard*; GitHub documents a Copilot activity report listing per-user login and most-recent-activity timestamps | individual-user rows are a first-class output; and where AI involvement in a PR is reported, it can be **inferred** — Claude Code analytics attributes by matching session activity against code in merged pull requests | explicit task→PR attribution (§1.1); team and repository grain only; no leaderboard and no per-user rows |
| Engineering-intelligence tools (Swarmia, DX, LinearB) — *design inspiration* | outcome funnels, cycle time, period-over-period comparison, team-versus-org baselines | *risk Fleet intends to avoid:* widget overload, and comparisons that read as diagnoses | one-page narrative; comparisons shown as differences, not causes |
| FinOps dashboards (AWS Cost Explorer, Vercel and Datadog usage views) — *design inspiration* | budget versus burn versus forecast, attribution by team, callouts for what moved | *risk Fleet intends to avoid:* cost reported detached from outcome | cost per merged PR; a month-end budget forecast feeds the attention panel |

**On these sources.** Row 1 was verified against the two linked primary pages on 6 Sep 2026; the specifics named there come from those pages. Rows 2 and 3 are **design inspiration**, not verified findings: the patterns are common in those categories, and the middle column states **risks Fleet intends to avoid**, not established facts about any named product. They were compiled with AI assistance and not verified against primary sources, so they should not be quoted as vendor claims. No claim is made about how any vendor other than Claude Code analytics attributes AI involvement, and none about minimum-cohort suppression in any product — the pages consulted do not state one.

**Fleet's chosen emphasis.** Merged-PR counts and acceptance rates are table stakes in vendor dashboards. Fleet's emphasis is connecting **task execution, PR acceptance, cost and sandbox policy friction in one actionable view**, at team and repository grain — a deliberate trade-off, not a claim that no other product does this. The place that emphasis becomes concrete is the **needs-attention panel** (§8), which turns the same aggregates into "what changed, for whom, what to look at first."

## 6. Metrics — what earns a place

Definitions here are business-level; the normative formulas are in `01-metrics-contract.md`.

**Classification.** **[P0 visible]** is displayed in the prototype. **[P0 supporting]** is not displayed on its own but is required to compute or explain a named P0 metric or rule — the consumer is named in every case. **[Deferred]** is a retained research candidate, held back from this prototype and *not* rejected; §10.2 holds what was rejected on product grounds.

### 6.0 Headline layer — five KPI cards

Chosen so P1's renewal question — usage, accepted output, cost, reliability — is answered above the fold. Each card shows its value, its change against the equal-length previous period, and a short definition. **Cards carry no sub-lines:** the secondary metrics once attached to them are deferred (§10.1), so the row stays readable at a glance.

| Card | Role | Definition shown on the card |
|---|---|---|
| **Merged agent PRs** [P0 visible] | north star — accepted output | agent PRs merged into the default branch in the period |
| **Terminal PR merge rate** [P0 visible] | guardrail: acceptance | merged as a share of agent PRs that reached a terminal state |
| **Blended cost per merged PR** [P0 visible] | guardrail: efficiency | metered code-change spend divided by merged PRs |
| **Task completion rate** [P0 visible] | guardrail: reliability | completed ÷ (completed + failed) eligible code-change tasks; cancelled and in-progress excluded (§3) |
| **Active seats / licensed seats** [P0 visible] | adoption | users active in the period against licensed seats |

Card wording above is indicative; the normative definitions are in `01-metrics-contract.md`.

Why three guardrails: there are three ways to buy a high north star cheaply — flood PRs nobody accepts, spend without limit, or hide failed runs behind the ones that worked. Merge rate, cost per merged PR and completion rate close those three doors. Whether required checks passed would close a fourth; it is deferred (§10.1), so the prototype's acceptance signal stops at "merged" (§3).

*Considered and rejected as north star:* tasks completed (an agent can complete a task nobody merges), tokens or spend (an input), lines of code (rewards verbosity). Merged PRs are closer to accepted value than any of these, while still influenced by PR size, task splitting and team workflow — hence the guardrails.

### 6.1 Adoption (Q1)

- Active seats and seat utilisation **[P0 visible — card 5]**.
- Per-user task counts, used only to decide who counts as active **[P0 supporting → card 5]**.
- Weekly active users trend **[Deferred]**.
- Tasks created, and task-type mix **[Deferred]**. The code-change / non-code classification itself is **[P0 supporting → scope boundaries in §3, funnel]**; only the mix chart is deferred.
- Adoption by team against the org row **[Deferred]** — the P0 comparison table carries outcome and cost columns, not adoption.

### 6.2 Outcomes (Q2) — code-change tasks

**Outcome funnel** (the primary visual) **[P0 visible]**: code-change tasks started → completed → PR opened → PR merged, with failed and cancelled tasks shown as **side exits** rather than as extra headline numbers. Failure and cancellation counts are retained for those side exits **[P0 supporting → funnel]**. Checks-passed share is **not** a funnel stage — deferred (§10.1).

- Task completion rate **[P0 visible — card 4 and comparison table]** · terminal PR merge rate **[P0 visible — card 2 and comparison table]**.
- Merged agent PRs over time **[P0 visible — trend]**.
- Team and repository rows against a scoped organisational benchmark, on completion rate, merge rate and cost per merged PR **[P0 visible — comparison table]**.
- PR conversion as a standalone metric **[Deferred]** — the funnel already shows the completed → PR-opened step.
- **First-pass approval rate** (merged with ≥ 1 human review and no changes-requested before first approval ÷ merged with ≥ 1 human review) **[Deferred]**.
- Median time-to-PR and time-to-merge **[Deferred]**.
- **Steering rate** (tasks with ≥ 1 steering event ÷ tasks started; messages per steered task as drill-down) **[Deferred]**.

*Interpretation guidance for deferred metrics — retained for when they land, and not applicable to the prototype, which displays neither:* a falling time-to-merge indicates faster flow, read alongside task mix and review outcomes; a high steering rate indicates a more collaborative pattern and may warrant a look at task clarity, agent behaviour or a deliberate team workflow.

### 6.3 Cost (Q3)

- Spend over time, against the previous period **[P0 visible — trend]**.
- Blended cost per merged PR — all metered cost of code-change tasks ÷ merged PRs; a flow metric, most meaningful read over a month or more **[P0 visible — card 3 and comparison table]**.
- Spend by team and repository **[P0 supporting → cost-per-merged-PR column, budget-risk rule]**; a standalone showback view is deferred.
- Budget burn and a **simple month-end forecast — always calendar month-to-date, independent of the global date filter** (§3) **[P0 supporting → budget-risk rule]**. Anything more elaborate than a straightforward projection is deferred (§10.1).
- Model mix **[Deferred]**.

### 6.4 Health, reliability & governance (Q4)

- Team and repository failure rates **[P0 supporting → task-failure-spike rule]**.
- Failure reasons, grouped as agent (`agent_gave_up`, `tests_failed`), platform (`timeout`, `internal_error`, `rate_limited`) and policy (`sandbox_denied`) **[P0 supporting → the evidence line on a failure-spike finding]**. The grouping is the finding worth keeping: it separates what the agent did wrong from what the platform did wrong from what policy blocked. A standalone failure-reason chart is deferred (§10.1).
- Sandbox network denials, by domain **[P0 supporting → network-policy-friction rule]**; a standalone denied-domain chart is deferred, and §9 governs who may see the domain itself.
- Repositories with elevated failure rate versus the org rate, minimum 20 terminal tasks **[Deferred as a rule type]** — see §8.
- Task duration p50/p95 **[Deferred]**.
- Rate-limit hits as separate governance events, with their own counts, charts and alerts **[Deferred]** — the rate-limit rule type is deferred (§8), so no P0 metric or rule consumes them. This does not touch the terminal failure reason `rate_limited`, which stays a valid value in the platform group above and may appear in failure-spike evidence.

Alerts are **deterministic rules** (§8); statistical anomaly detection is deferred (§10.1).

## 7. Prototype P0 — frozen

The authoritative scope for the prototype. It answers the §4 question unchanged. Anything not listed here is not built; §6 records where each catalogue item went.

**KPI row — exactly five cards.** Merged agent PRs · terminal PR merge rate · blended cost per merged PR · task completion rate · active seats / licensed seats. Each card shows its value, its previous-period delta, and a short definition. No sub-lines.

**Outcome funnel.** Code-change tasks started → completed → PR opened → PR merged, with failed and cancelled tasks as side exits. Side exits are not additional headline KPIs.

**Trends.** Merged agent PRs over time · agent spend over time.

**Comparison table.** One table with team and repository views, and three columns — task completion rate, terminal PR merge rate, cost per merged PR — each against a scoped organisational benchmark. A row's comparison is shown only when the row has enough tasks or PRs to support one; below that the row still appears and the comparison does not (§3).

**Needs-attention panel.** Four rule types — budget risk · task failure spike · terminal merge-rate decline · network-policy friction. **At most three findings are displayed.** Every finding is computed, never hand-written; it states the evidence that triggered it and links to a relevant filtered investigation view.

**Global filters.** Date range · team · repository, all reflected in the URL so a view is bookmarkable. **One labelled exception:** budget evaluation is always calendar month-to-date (§3) and says so on screen. Any other metric-specific exception must be visibly explained on the page and defined in `01-metrics-contract.md`.

**Page order.** KPI row · outcome funnel · merged-PR and spend trends · needs-attention panel · comparison table. P1 reads the first row and stops; P2 applies the team filter; P3 starts at the panel and follows a finding into a filtered investigation view.

Formulas, timestamp rules, sample thresholds, benchmark scope, seat and repository filter semantics, and attention-rule evaluation are normative in `01-metrics-contract.md`, not here.

## 8. The needs-attention panel

Each finding states: severity · what changed · the affected team or repository · the evidence and the threshold it crossed · a link to a relevant filtered investigation view. Findings are computed from the same aggregates as the charts — never hand-written — and they report a difference, not a cause. The triggering evidence lives in the finding itself: P0 has no dedicated denied-domain or failure-reason view to link to (§6.4), so the link opens a relevant filtered view rather than a chart of the evidence.

| Signal | Deterministic rule | Prototype |
|---|---|---|
| Budget risk | month-end forecast exceeds the monthly budget by > 10% (high if > 20%) | **P0** |
| Task failure spike | team or repo failure rate up ≥ 8 points vs the trailing 28 days, with ≥ 20 terminal tasks | **P0** |
| Terminal merge-rate decline | terminal merge rate down ≥ 8 points vs the previous period, with ≥ 15 terminal PRs | **P0** |
| Network-policy friction | the same denied domain affected ≥ 5 tasks across ≥ 3 users in the period | **P0** |
| Repository outlier | repo failure rate ≥ 2× org rate, with ≥ 20 terminal tasks | Deferred (§10.1) |
| Rate-limit impact | ≥ 5% of runs hit a rate limit | Deferred (§10.1) |

Four rule types, **at most three findings displayed**. Which three are shown when more fire — severity ordering and tie-breaking — is normative in `01-metrics-contract.md`, as is the full severity taxonomy beyond the one threshold recorded above. Deferring the repository-outlier *rule* does not defer per-repository failure *rates*: the failure-spike rule is defined over team or repository (§6.4).

Example finding: *"MEDIUM — Payments is projected to spend $11,800 against a $10,000 monthly budget, an 18% overrun. → Inspect Payments spend."*

The example is deliberately flat. Budget risk is evaluated against the calendar month to date, independent of the selected date range (§3). 18% crosses the > 10% threshold but not the > 20% one, so it is not HIGH. The finding gives the projection, the budget it is measured against and the threshold it crossed, then stops: it attributes no cause and recommends no action the data does not support. How the projection is computed, the data-through timestamp it carries, and what date range the link opens belong to `01-metrics-contract.md` and `02-requirements.md`.

## 9. Privacy principle

Fleet analytics collects the operational metadata needed for adoption, cost, reliability and outcome measurement. It does not collect prompt text, model responses or source-code contents. Grain is org → team → repository; no individual view exists, in the prototype or in v1 (§10.2). Denied-domain detail is restricted to platform admins, because internal hostnames can reveal architecture; in the prototype it appears only inside a network-policy-friction finding. This document decides only that the restriction exists. Who may see what is an access requirement (`02-requirements.md`); the trust boundary it sits on belongs to `03-architecture.md`; how it is enforced belongs to `04-technical-spec.md`. No authentication mechanism is chosen here.

## 10. Deferred, and cut

These are different things, and the difference is the point: §10.1 is scope management for one prototype, §10.2 is a product judgement. Nothing moves from §10.1 to §10.2 without a reason being written down.

### 10.1 Deferred for the prototype — research candidates

Retained above with their reasoning; not implemented. The case for each still stands.

- **Metrics.** Checks-passed share · open-PR ageing · weekly-active trend · first-pass approval rate · median time-to-PR and time-to-merge · steering analytics · duration distributions · model mix · task-type mix · tasks created and adoption-by-team views · PR conversion as a standalone metric · standalone cancellation-rate, failure-reason and denied-domain charts.
- **Rule types.** Repository outlier · rate-limit impact.
- **Capabilities.** Forecasting beyond a simple month-end projection · statistical anomaly detection · CSV export.
- **Post-merge revert rate.** The right guardrail on the wrong day — it needs deploy and revert events the mock does not model yet. Deferred on data availability, not on merit.

### 10.2 Cut on product grounds

Decided against, with the reason recorded. These are not scope deferrals.

- **Estimated hours saved.** It would be `merged PRs × an average invented today`; the first time finance asks how it was calculated, the honest numbers next to it lose credibility too. Cost per merged PR is a **unit-cost indicator, not an ROI claim** — true ROI needs customer-specific baselines, work-type context and qualitative evidence (a quarterly developer pulse is the roadmap data source).
- **Engineer-level breakdown and individual analytics.** Removed entirely, not hidden behind a role. It is a different product with different consent, audit and retention requirements (§2, §9).
- **Tokens as a headline; lines of code.** Activity, not value.
- **Finance persona.** P1 owns the budget conversation.
- **Real-time streaming.** Weekly decisions; minutes-fresh is enough. The freshness decision will be recorded in an ADR alongside the technical spec.
- **Custom dashboard builder.** Opinionated defaults are the product.

## 11. What "good" looks like for a reviewer

Every fact in both sentences is available on the P0 page (§7).

**P1:** *"Fleet produced 212 merged PRs last month, up 18%, at a blended $41 each. Terminal merge rate held at 71%, task completion rate 88%, and 46 of 60 seats were active. Payments is projected 18% over its monthly budget — one thing to raise with the platform team."*

**P2:** *"Our task completion rate is 6 points below the org benchmark and our terminal merge rate 8 points below, on enough tasks for the comparison to be shown. Before deciding anything I'll look at repo setup and how we're scoping tasks."*

Actionable, and neither sentence pretends the dashboard already knows the cause. Those two sentences are the acceptance test for the prototype.
