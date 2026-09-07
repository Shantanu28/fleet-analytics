# CLAUDE.md — Fleet Analytics

Fleet Analytics is an organisation-level dashboard for an imaginary cloud coding-agent product.
The prototype implements login, tenant-scoped APIs, metric calculations, deterministic seeding,
the React dashboard and browser tests. Production ingestion infrastructure is a design only.

## Read the right source

- [Research](docs/00-research.md): what belongs in the product and why.
- [Metrics contract](docs/01-metrics-contract.md): formulas, timestamps, states, filters and thresholds.
- [Requirements](docs/02-requirements.md): user behaviour and acceptance criteria.
- [Architecture](docs/03-architecture.md): production direction and prototype boundary.
- [Technical guide](docs/04-technical-spec.md): implementation and configuration.
- [Testing strategy](docs/05-testing-spec.md): verification layers.
- [Execution record](docs/06-plan.md): milestone status and evidence.
- [OpenAPI](contracts/openapi.yaml): HTTP interface.

Do not silently change product rules to match code. Flag conflicts and ask before changing scope.
Keep acceptance IDs and metric definitions stable unless a change is explicitly approved.

## Stack and coding rules

React, TypeScript, Vite and TanStack Query; Java 25, Spring Boot, PostgreSQL, Flyway and jOOQ.
Authentication uses RS256 JWTs and Argon2id. Tests use JUnit/Testcontainers,
Vitest/React Testing Library and Playwright Chromium. Versions live in build manifests.

Follow [.claude/rules/java.md](.claude/rules/java.md) for backend changes and
[.claude/rules/react.md](.claude/rules/react.md) for frontend changes. New dependencies need approval.

## Working agreement

1. Inspect the current files and Git state before working; preserve unrelated changes.
2. Describe intended changes and checks. Wait for approval unless the user has already approved that work.
3. Use focused tests for real behaviour; reproduce bugs before fixing them. Do not add tests merely for counts.
4. Run the relevant checks and report commands actually executed, failures and limitations.
5. Stop for human review. The human stages and commits; do not stage, commit or push unless explicitly asked.

Only edit documentation when authorised. Keep its language direct, link useful references by name,
and avoid copying test counts or version lists into several files. Do not invent ADRs, evidence,
completed CI runs or first-person decisions.

No real credentials, customer identities, private company information, prompts or source contents
belong in fixtures or logs. Published demo credentials are explicitly synthetic. Raw Playwright
diagnostics may contain restricted data: CI uploads only the safe summary, never raw artifacts.

## Commands and status

Use the [README](README.md) for startup and the [execution record](docs/06-plan.md) for verification.
`make dev` starts the dev profile only; it does not enable demo logins. Follow the documented
`dev,demo` command for the seeded dashboard. Ordinary startup never seeds or repairs data.
