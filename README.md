# Fleet Analytics

Organization-level analytics dashboard for **Fleet**, an imaginary platform where engineers delegate coding tasks to agents that run in isolated cloud sandboxes and open pull requests. A take-home assignment for a developer-tools company, built spec-first with an AI-first workflow.

> **Status: M1 (foundation) implemented.** The build chain works — Flyway migration, jOOQ generation, backend compile, **one passing PostgreSQL integration smoke test**, and a passing frontend type-check and production build. There is **no login, analytics API, demo dataset or dashboard yet**, and no frontend component tests; M2–M6 are planned. Research, the metrics contract and the requirements are landed; the architecture, technical, testing and plan documents are drafts under review.

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

Vite and the M1 dependency versions are **settled and in use**. **TanStack Query is approved but not yet integrated** — it arrives with the dashboard. Still pending: the JWT signature algorithm, the password-hashing algorithm, the component- and browser-testing tools, and dependencies belonging to later milestones — see `docs/04-technical-spec.md` §8.

Then implementation: one milestone per pull request, tests first.

## Run it

**M1 quickstart.** This verifies the foundation — it does not start a dashboard, because there isn't one yet.

Requires **Java 25**, **Node 20.19+ or 22.12+**, and a running Docker-compatible runtime (Docker Desktop, Colima, or similar) with Compose.

```bash
make setup   # start PostgreSQL, wait for readiness, migrate, generate jOOQ types, install npm deps
make test    # backend compile + PostgreSQL integration smoke test, then frontend type-check and build
```

`make setup` is safe to repeat: it re-validates the migration rather than reapplying it, and never drops data.

What passing means: the schema, code generation, compilation and build pipeline all work. It does **not** mean any product behaviour works. Login, the analytics API, the demo dataset and the dashboard arrive in later milestones, and the demo account credentials will be published here once login and seeding exist.
