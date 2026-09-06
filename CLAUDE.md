# CLAUDE.md — Fleet Analytics

Org-level analytics dashboard for **Fleet**, an imaginary cloud coding-agent platform (engineers delegate tasks to agents running in isolated cloud sandboxes; the agents open pull requests). Built **spec-first**: the documents in `docs/` are the source of truth, and they land one commit at a time before any code does.

## Where we are

Specifications are written; **no application code, no dependencies and no executable tests exist yet**. Research (`00`), the metrics contract (`01`) and requirements (`02`) are landed. Architecture (`03`), the technical spec (`04`), the testing spec (`05`) and the execution plan (`06`) exist as **drafts**. Do not scaffold, install, or invent requirements until a task explicitly asks for it.

## Document sequence, and what each one owns

| Document | Owns | Status |
|---|---|---|
| `docs/00-research.md` | **product scope** — personas, the one question, which metrics earn a place, the frozen P0 (§7) | landed |
| `docs/01-metrics-contract.md` | **calculations** — formulas, timestamp rules, exclusions, sample thresholds, benchmark scope, filter semantics, attention-rule evaluation | landed |
| `docs/02-requirements.md` | **acceptance criteria** — user stories `US-n` and criteria `AC-n.m` | landed |
| `docs/03-architecture.md` | **system boundaries** — the production design, and what the prototype mocks | **draft** |
| `docs/04-technical-spec.md` | **implementation** — stack, project structure, API contracts | **draft** |
| `docs/05-testing-spec.md` | **verification** — how every acceptance criterion is proven | **draft** |
| `docs/06-plan.md` | **execution order** — milestones and the cut line | **draft** |
| `docs/07-ai-workflow.md` | a **log kept during development**: how Claude Code was driven, and where it was wrong | maintained as work proceeds |

**ADRs** are added alongside significant decisions as those decisions are made. None exist yet — do not cite an ADR number that has not been written.

Answer a question from the document that owns it: *what we build* is §00, *how a number is computed* is 01, *what counts as done* is 02. If a spec you need doesn't exist yet, say so and stop. If code and spec disagree, stop and ask — never silently change either.

## Stack

Approved direction, specified in `docs/04-technical-spec.md`: **React + TypeScript** frontend with **TanStack Query** for server state; **Java 25 + Spring Boot** backend; **PostgreSQL**; **Flyway** for migrations; **jOOQ** for SQL, with no JPA/Hibernate. Authentication is username + password with short-lived JWTs.

**Still unresolved** — do not assume: the JWT algorithm, the password-hashing algorithm, frontend build and test libraries, and every dependency version. These are listed in `docs/04-technical-spec.md` §8 and must be approved before anything is added.

## Conventions that apply from commit one

- Small commits. Format `type(scope): what and why`; add milestone and spec ids once they exist, e.g. `M3: metrics — merge rate (AC-1.2, AC-1.4)`. Types: chore, docs, feat, test, fix, ci, refactor.
- The human stages and commits. Propose file groups and commit messages; do not stage, commit or push unless explicitly asked.
- Never edit `docs/` unless the task says so — propose the change instead.
- Never add a dependency that isn't listed in the technical spec.
- No secrets and no assignment text in the repo. "No real company names" means no real customer identities or private company information in fixtures or examples; public vendor names and cited research sources are fine.

## How to work a task

1. Read this file and any spec section the task references.
2. Plan mode first: list the files you'll touch and the tests you'll write. Wait for approval.
3. Do the work. Run whatever gates exist.
4. Report: what changed, which ACs are covered, any deviation from spec and why.

This file grows with the repo: stack, commands, layout and coding rules are added in the commit that lands the technical spec.
