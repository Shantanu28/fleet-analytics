# Fleet Analytics

A customer-facing analytics dashboard for an imaginary cloud coding-agent platform.

**“Is Fleet producing accepted code changes at a sustainable cost — and where should we act this week?”**

The dashboard connects adoption, execution, merged pull requests, spend and sandbox-policy friction. It reports at organisation, team and repository level—not individual engineer performance. A merge is an acceptance proxy, not proof of quality or hours saved.

React + TypeScript + TanStack Query on the frontend; Java 25 + Spring Boot, PostgreSQL, Flyway and jOOQ on the backend. Authentication, SQL, calculations and role-based redaction are real. Upstream agent/GitHub/metering integrations are replaced by deterministic synthetic records.

## Try it locally

Requires Java 25, Node (the tested version is pinned in [.nvmrc](.nvmrc)), and Docker with Compose. Commands below start in the repository root. The standard setup uses port 5432; if another checkout owns it, use the [isolated database instructions](docs/04-technical-spec.md#7-local-development).

```bash
make setup
export DB_URL=jdbc:postgresql://127.0.0.1:5432/fleet
make seed

# Generate once for this local environment. Keep it outside Git and retain it across restarts.
export FLEET_FINDINGS_ID_SECRET="$(openssl rand -base64 32)"

FLEET_DEMO_ACCOUNTS=true ./mvnw -pl backend -Ddb.url="$DB_URL" spring-boot:run \
  -Dspring-boot.run.main-class=com.fleet.analytics.FleetAnalyticsApplication \
  -Dspring-boot.run.profiles=dev,demo
```

In a second terminal, from the repository root:

```bash
(cd frontend && npm run dev)
```

Open the URL Vite prints (normally http://localhost:5173). The frontend proxies API requests to port 8080. Keep the API terminal's environment when restarting it.

| Organisation | ADMIN username / password | VIEWER username / password |
|---|---|---|
| Northstar Engineering | `admin` / `123456` | `viewer` / `demo-viewer-a` |
| Harbor Labs | `admin123` / `1234567` | `viewer123` / `demo-viewer-b` |

These are public demo credentials for synthetic data only. Both roles see their organisation's dashboard; only ADMIN receives the denied-domain detail. Sign out, then sign into the other organisation to compare datasets.

Demo accounts require both the `demo` profile and `FLEET_DEMO_ACCOUNTS=true`. The `dev` profile supplies ephemeral JWT keys; configured PEM keys take precedence and invalid configuration fails startup. The separate finding-ID secret is always required. [Authentication details](docs/04-technical-spec.md#51-authentication-and-redaction).

Sign-out calls the logout API to revoke that token and immediately clears the browser's session and cached data. Other logins are unaffected. If server sign-out cannot be confirmed, the login page warns you. Tokens otherwise expire after 15 minutes, plus the configured clock-skew tolerance. Reloading loses the local session without calling logout; login is required again, then the URL's filters are restored.

## What to explore

- Five headline cards: merged PRs, terminal PR merge rate, blended cost per merged PR, task completion rate, and active/licensed seats.
- A task-cohort funnel, two daily trends and a team/repository comparison table.
- At most three computed findings, with evidence and navigation to the relevant view.
- Explicit loading, zero, unavailable, insufficient-sample and error states.

The seed covers **5 March–31 August 2026**, not the current day. Northstar has 56 seats, 7 teams, 14 repositories and 20,000 tasks. Harbor has 10 seats, 2 teams, 20 repositories and 2,000 tasks. Each demo task has one run. [Dataset details](docs/04-technical-spec.md#6-demo-data).

For a short walkthrough: sign in as Northstar ADMIN, select **Payments** over **2–31 August 2026**, inspect its budget finding and the **repo-api** failure spike, then use browser Back. Switch to VIEWER to see redacted policy evidence. The funnel and headline cards intentionally use different time bases, explained on the page.

The seed installs both organisations atomically. Repeating it validates existing data and returns `UNCHANGED`; incompatible or unmanaged data is refused without repair or deletion. Ordinary startup never seeds.

## Run the checks

```bash
make test
(cd frontend && npx playwright install chromium) # add --with-deps on Linux
node --test scripts/e2e*.test.mjs
make e2e
```

`make test` runs backend/database/API tests, React tests, type-checking and the frontend build. `make e2e` creates a separate PostgreSQL container, seeds it, builds the frontend, starts isolated servers on 8091/4183, runs Chromium and cleans up its own resources.

Override occupied test ports with `FLEET_E2E_API_PORT` and `FLEET_E2E_PREVIEW_PORT`. To supply a disposable test database, set `E2E_DB_URL` and `E2E_DB_DISPOSABLE=yes`; that database is migrated and seeded but never removed.

CI is configured in [ci.yml](.github/workflows/ci.yml), but a successful GitHub run has **not yet been verified**. Current local evidence and remaining release checks are in the [execution record](docs/06-plan.md). Browser tests include injected 500/401 responses; those prove recovery handling, not a real outage or token expiry. Accessibility checks are targeted, not a WCAG audit.

Only an allowlisted browser summary is uploaded. Raw local `frontend/test-results/` files can contain restricted display data and must not be shared. [Testing strategy](docs/05-testing-spec.md).

## Decisions worth discussing

| Choice | Alternative | Why this prototype uses it |
|---|---|---|
| One dashboard endpoint | Separate endpoints per section | One coordinated filter/revision response; separate endpoints would allow independent loading and retries |
| Sequential queries in one repeatable-read transaction | Consolidated SQL or parallel queries with a shared snapshot | A straightforward, tested consistency boundary without cross-connection snapshot coordination |
| Query-time metrics over source facts | Precomputed reporting tables | Verify and evolve formulas against real records before adding refresh/backfill logic |
| Java and jOOQ | Node backend or ORM-based persistence | Familiar backend stack and explicit, typed SQL; code generation requires a migrated database |
| In-memory JWT with database-backed logout | Opaque server session | Keeps the signed-token API and supports revocation; requires a token-status lookup per authenticated request and login after reload |

One endpoint does **not** require sequential SQL: the HTTP interface can stay the same while its
query implementation evolves. Today the response waits for all section queries, so latency still
needs measurement and optimisation. The [execution record](docs/06-plan.md#remaining-release-checks)
records the outstanding performance investigation. These alternatives have not been benchmarked
against each other. See [trade-offs and the scale-up path](docs/04-technical-spec.md#8-decisions-and-remaining-configuration).

## Read the project

| Document | Use it for |
|---|---|
| [Research](docs/00-research.md) | Personas, metric choices and deliberate exclusions |
| [Metrics contract](docs/01-metrics-contract.md) | Exact formulas, populations, states and worked examples |
| [Requirements](docs/02-requirements.md) | User behaviour and acceptance criteria |
| [Architecture](docs/03-architecture.md) | Proposed production components and prototype boundaries |
| [Technical guide](docs/04-technical-spec.md) | Code structure, API, authentication and data |
| [Testing strategy](docs/05-testing-spec.md) | What each test layer proves |
| [Execution record](docs/06-plan.md) | Milestones and verification evidence |
| [OpenAPI](contracts/openapi.yaml) | Implemented HTTP interface |

## Production direction and limits

![Proposed production architecture](docs/production-architecture.png)

The diagram describes a **proposed production system**, not deployed infrastructure. Kafka, ClickHouse, archive/reconciliation workers and optional Redis/rollups are not implemented in the prototype. Production also needs SSO/session lifecycle, rate limiting, operational safeguards, provider integrations and measured capacity. [Production validation](docs/03-architecture.md#10-production-validation-before-launch).

Next priorities: verify CI and a post-commit clean clone; profile the slow request path; harden authentication and deployment; then integrate upstream events and add selective aggregates where measurements justify them.
