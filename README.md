# Fleet Analytics

Organization-level analytics dashboard for **Fleet**, an imaginary platform where engineers delegate coding tasks to agents that run in isolated cloud sandboxes and open pull requests. A take-home assignment for a developer-tools company, built spec-first with an AI-first workflow.

> **Status:** research complete, prototype scope (P0) frozen. The metrics contract is next; there is no application code yet. This README grows with the repo, and the git log is the build diary.

## What this is

A customer-facing dashboard that lets an engineering organization understand its use of cloud coding agents — adoption, outcomes, cost and policy friction — at the org, team and repository level, never the individual engineer.

It answers one question: **"Is Fleet producing accepted code changes at a sustainable cost — and where should we act this week?"**

The frozen prototype is five KPI cards, an outcome funnel, two trends, one team/repository comparison table against an organizational benchmark, and a needs-attention panel showing at most three computed findings — all under global date, team and repository filters. Full scope, the broader metric catalogue and what was deferred: [docs/00-research.md](docs/00-research.md).

## How it's being built

Spec-driven, one commit per document, so the history shows each decision as it was made:

| # | Document | Owns |
|---|---|---|
| 00 | [Research & product framing](docs/00-research.md) | product scope: personas, the one question, the frozen P0 |
| 01 | Metrics contract | calculations: formulas, timestamps, exclusions, thresholds |
| 02 | Requirements | acceptance criteria |
| 03 | Architecture | system boundaries, and what the prototype mocks |
| 04 | Technical spec | stack, structure, API contracts |
| 05 | Testing spec | how every acceptance criterion is proven |
| 06 | Plan | milestones and the cut line |
| 07 | AI workflow | a log kept during development: how Claude Code was driven, and where it was wrong |

Decision records (ADRs) are added alongside significant decisions as they are made. Only document 00 exists so far; the rest are linked here as they land.

Then implementation: one milestone per pull request, tests first.

## Run it

Nothing to run yet. Instructions land with the first code milestone.
