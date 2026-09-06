# Fleet Analytics

Organization-level analytics dashboard for **Fleet**, an imaginary platform where engineers delegate coding tasks to agents that run in isolated cloud sandboxes and open pull requests. A take-home assignment for a developer-tools company, built spec-first with an AI-first workflow.

> **Status:** specifications written, **no application code and no executable tests yet**. Research, the metrics contract and the requirements are landed; architecture, the technical spec, the testing spec and the execution plan are drafts still under review. This README grows with the repo, and the git log is the build diary.

## What this is

A customer-facing dashboard that lets an engineering organization understand its use of cloud coding agents — adoption, outcomes, cost and policy friction — at the org, team and repository level, never the individual engineer.

It answers one question: **"Is Fleet producing accepted code changes at a sustainable cost — and where should we act this week?"**

The frozen prototype is five KPI cards, an outcome funnel, two trends, one team/repository comparison table against an organizational benchmark, and a needs-attention panel showing at most three computed findings — all under global date, team and repository filters. Full scope, the broader metric catalogue and what was deferred: [docs/00-research.md](docs/00-research.md).

## How it's being built

Spec-driven, one commit per document, so the history shows each decision as it was made:

| # | Document | Owns | Status |
|---|---|---|---|
| 00 | [Research & product framing](docs/00-research.md) | product scope: personas, the one question, the frozen P0 | landed |
| 01 | [Metrics contract](docs/01-metrics-contract.md) | calculations: formulas, timestamps, exclusions, thresholds | landed |
| 02 | [Requirements](docs/02-requirements.md) | acceptance criteria | landed |
| 03 | Architecture | system boundaries, and what the prototype mocks | local draft — not published |
| 04 | [Technical spec](docs/04-technical-spec.md) | stack, structure, schema, API contracts | draft |
| 05 | [Testing spec](docs/05-testing-spec.md) | how every acceptance criterion is proven | draft |
| 06 | [Plan](docs/06-plan.md) | milestones and the cut line | draft |
| 07 | AI workflow | a log kept during development: how Claude Code was driven, and where it was wrong | not started |

Documents 03–06 are drafts under review, not settled decisions. Decision records (ADRs) are added alongside significant decisions as they are made; none exists yet.

## Planned stack

**React + TypeScript** frontend using **TanStack Query** for server state, **Java 25 + Spring Boot** API, **PostgreSQL** with **Flyway** migrations and **jOOQ** queries (no JPA/Hibernate). Sign-in is username and password with short-lived JWTs, and the dashboard shows one organisation at a time, scoped from the verified identity.

The JWT algorithm, password-hashing algorithm, frontend build and test libraries, and all dependency versions are **not yet decided** — see `docs/04-technical-spec.md` §8.

Then implementation: one milestone per pull request, tests first.

## Run it

Nothing to run yet — there is no implementation. Setup instructions, and the public credentials for the synthetic demo accounts, land with the first code milestone.
