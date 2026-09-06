# Fleet Analytics

Organization-level analytics dashboard for **Fleet**, an imaginary platform where engineers delegate coding tasks to agents that run in isolated cloud sandboxes and open pull requests. A take-home assignment for a developer-tools company, built spec-first with an AI-first workflow.

> **Status: M1 (foundation) and M2 (authentication and tenancy) implemented.** Username/password login issues short-lived RS256 JWTs, and a tenant-scoped context endpoint serves the organisation name, role, teams, repositories, licensed seats and coverage. A minimal React UI signs in, shows the organisation and signs out. **69 backend and 28 frontend tests pass.** There is **no analytics dashboard, metrics API or demo dataset yet**; M3–M6 are planned. Research, the metrics contract and the requirements are landed; the architecture, technical, testing and plan documents are drafts under review.

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
| 03 | [Architecture](docs/03-architecture.md) | system boundaries, and what the prototype mocks | draft |
| 04 | [Technical spec](docs/04-technical-spec.md) | stack, structure, schema, API contracts | draft |
| 05 | [Testing spec](docs/05-testing-spec.md) | how every acceptance criterion is proven | draft |
| 06 | [Plan](docs/06-plan.md) | milestones and the cut line | draft |
| 07 | AI workflow | a log kept during development: how Claude Code was driven, and where it was wrong | not started |

Documents 03–06 are drafts under review, not settled decisions. Decision records (ADRs) are added alongside significant decisions as they are made; none exists yet.

## Planned stack

**React + TypeScript** frontend using **TanStack Query** for server state, **Java 25 + Spring Boot** API, **PostgreSQL** with **Flyway** migrations and **jOOQ** queries (no JPA/Hibernate). Sign-in is username and password with short-lived JWTs, and the dashboard shows one organisation at a time, scoped from the verified identity.

Vite and the M1 dependency versions are **settled and in use**, and **TanStack Query is integrated** — brought forward to M2 for the context query. Settled in M2: RS256, Argon2id, Vitest with React Testing Library, and a test-scoped OpenAPI response validator. Still pending: the browser-testing tool and dependencies belonging to later milestones — see `docs/04-technical-spec.md` §8.

Then implementation: one milestone per pull request, tests first.

## Run it

**M1/M2 quickstart.** This verifies the foundation and the sign-in slice — it does not start a dashboard, because there isn't one yet.

Requires **Java 25**, **Node 22.12+ (or 24+, or 26+)** — the component-test tooling no longer supports Node 20 — and a running Docker-compatible runtime (Docker Desktop, Colima, or similar) with Compose.

```bash
make setup   # start PostgreSQL, wait for readiness, migrate, generate jOOQ types, install npm deps
make test    # backend tests (incl. real PostgreSQL), React component tests, type-check and build
```

To run the backend and the Vite dev server together under the `dev` profile:

```bash
make dev
```

Ctrl-C stops both. It seeds nothing, changes no migrations and deletes no data — run `make setup` first.

The API will not start without JWT signing keys. Either configure a real PEM key pair:

```bash
FLEET_JWT_PRIVATE_KEY=file:/path/to/private.pem FLEET_JWT_PUBLIC_KEY=file:/path/to/public.pem ./mvnw -pl backend spring-boot:run
```

or opt into generated development keys, which need **both** the flag and the `dev` (or `test`) profile:

```bash
FLEET_DEV_KEYS=true ./mvnw -pl backend spring-boot:run -Dspring-boot.run.profiles=dev
```

The flag on its own, under any other profile, does nothing: startup fails rather than falling back to an insecure default, and configured keys that are missing, unreadable, malformed or mismatched fail startup too rather than being quietly replaced by generated ones. Development keys are regenerated on every restart, so tokens do not survive a restart and are not shared between instances. Enabling them does **not** enable demo accounts — those need both the `demo` profile and `FLEET_DEMO_ACCOUNTS=true`. No key material is committed to this repository.

`make setup` is safe to repeat: it re-validates the migration rather than reapplying it, and never drops data.

What passing means: the schema, code generation, build pipeline, login, token validation, tenant scoping and the context endpoint all work. It does **not** mean the dashboard works — the analytics API, metrics, demo dataset and dashboard arrive in later milestones. Demo account credentials will be published here once the seeded demo dataset exists.

## Architecture

![Fleet Analytics proposed production architecture](docs/production-architecture.png)

Proposed production design—not the infrastructure implemented by this prototype. The prototype targets React, Spring Boot, and PostgreSQL with synthetic domain data; current implementation status is described above.

See [Architecture](docs/03-architecture.md) for component responsibilities, trade-offs, and prototype boundaries.
