# Fleet Analytics

Organization-level analytics dashboard for **Fleet**, an imaginary platform where engineers delegate coding tasks to agents that run in isolated cloud sandboxes and open pull requests. A take-home assignment for a developer-tools company, built spec-first with an AI-first workflow.

> **Status: M1–M4 backend implemented.** Authentication, tenant-scoped context and dashboard APIs, metrics/findings, and the safe two-organisation demo installer are available. M4's seeded dashboard integration is verified against M3. The React UI currently supports sign-in and organisation context; dashboard rendering and browser journeys remain M5–M6. Verification evidence is recorded in [the execution plan](docs/06-plan.md).

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

**Backend quickstart.** This verifies the backend and current sign-in UI. Dashboard rendering remains M5.

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

The API requires JWT signing keys and a separate persistent finding-ID secret. Set
`FLEET_FINDINGS_ID_SECRET` to Base64-encoded key material containing at least 32 bytes; generate
it once (for example with `openssl rand -base64 32`) and retain it outside the repository across
restarts. There is no automatic fallback. For JWT signing, either configure a PEM key pair:

```bash
FLEET_JWT_PRIVATE_KEY=file:/path/to/private.pem FLEET_JWT_PUBLIC_KEY=file:/path/to/public.pem ./mvnw -pl backend spring-boot:run
```

or opt into generated development keys, which need **both** the flag and the `dev` (or `test`) profile:

```bash
FLEET_DEV_KEYS=true ./mvnw -pl backend spring-boot:run -Dspring-boot.run.profiles=dev
```

The flag on its own, under any other profile, does nothing: startup fails rather than falling back to an insecure default, and configured keys that are missing, unreadable, malformed or mismatched fail startup too rather than being quietly replaced by generated ones. Development keys are regenerated on every restart, so tokens do not survive a restart and are not shared between instances. Enabling them does **not** enable demo accounts — those need both the `demo` profile and `FLEET_DEMO_ACCOUNTS=true`. No key material is committed to this repository.

`make setup` is safe to repeat: it re-validates the migration rather than reapplying it, and never drops data.

The checks cover the backend schema, authentication, analytics API, metric calculations and seeded integration. They do not establish browser behavior for the frontend dashboard, which remains M5–M6. M4 seeding is described below.

## Synthetic demo data (M4)

The explicit seed command installs both organisations in one transaction. It returns `INSTALLED`
on an empty database or `UNCHANGED` for matching, validated data. It refuses unmanaged or damaged
data without changing it. It never migrates, resets or repairs a database. Ordinary startup does not seed.

**Use a separate database when another checkout is active.** The existing Compose file uses port
5432 and a shared project/volume identity. For a new isolated review database:

```bash
docker run --detach --name fleet-demo-review \
  -e POSTGRES_DB=fleet_demo -e POSTGRES_USER=fleet -e POSTGRES_PASSWORD=fleet \
  -p 127.0.0.1::5432 postgres:18.6-alpine
docker exec fleet-demo-review pg_isready -U fleet -d fleet_demo  # wait for ready
export DB_URL="jdbc:postgresql://$(docker port fleet-demo-review 5432)/fleet_demo"
./mvnw -pl backend -Ddb.url="$DB_URL" flyway:migrate generate-sources
make seed
```

The container name must be unused; do not substitute another checkout's container. `make seed`
requires `DB_URL`; `DB_USER`/`DB_PASSWORD` default to the synthetic local `fleet` credentials.
The separate seed launcher imports only datasource configuration, so it needs no JWT/HMAC secrets.
To run tests against this build database, use `MAVEN_ARGS="-Ddb.url=$DB_URL" make test`.
Testcontainers still creates independent test databases.

| Organisation | ADMIN username / password | VIEWER username / password |
|---|---|---|
| Northstar Engineering (A) | `admin` / `123456` | `viewer` / `demo-viewer-a` |
| Harbor Labs (B) | `admin123` / `1234567` | `viewer123` / `demo-viewer-b` |

These public credentials access synthetic data only. Each tenant has two published logins drawn
from its engineers. Other engineers have undisclosed random passwords and VIEWER roles; all seeded
users retain `is_demo_account=true`. Login still requires both the `demo` profile and
`FLEET_DEMO_ACCOUNTS=true`, plus the normal JWT and finding-ID configuration above. For local API development:

```bash
FLEET_DEMO_ACCOUNTS=true ./mvnw -pl backend -Ddb.url="$DB_URL" spring-boot:run \
  -Dspring-boot.run.main-class=com.fleet.analytics.FleetAnalyticsApplication \
  -Dspring-boot.run.profiles=dev,demo
```

Dataset `fleet-demo`, version `1`, seed `20260907`: **2026-03-05 through 2026-08-31 inclusive**
(`dataThrough=2026-09-01T00:00:00Z`). A has 56 engineers/seats, 7 teams, 14 repositories and
20,000 tasks; B has 10 engineers/seats, 2 teams, 20 repositories and 2,000 tasks. Each task has
one run. Both tenants publish all eight logical sources for every day, including the empty March 5.
Configuration and scenario evidence are recorded in [technical spec §6](docs/04-technical-spec.md#6-demo-data).

The investigation starts at **2026-08-02–2026-08-31, Payments, no repository filter**. M4 verifies
qualifying populations and budget arithmetic directly in PostgreSQL. Authenticated endpoint tests
also verify finding ranking, link patches and their destination responses, latest presets,
tenant isolation and ADMIN/VIEWER redaction. Browser navigation and rendering remain M5–M6.

## Architecture

![Fleet Analytics proposed production architecture](docs/production-architecture.png)

Proposed production design—not the infrastructure implemented by this prototype. The prototype targets React, Spring Boot, and PostgreSQL with synthetic domain data; current implementation status is described above.

See [Architecture](docs/03-architecture.md) for component responsibilities, trade-offs, and prototype boundaries.
