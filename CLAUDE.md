# CLAUDE.md — Fleet Analytics

Org-level analytics dashboard for **Fleet**, an imaginary cloud coding-agent platform (engineers delegate tasks to agents running in isolated cloud sandboxes; the agents open pull requests). Built **spec-first**: the documents in `docs/` are the source of truth, and they land one commit at a time before any code does.

## Where we are

Research is complete and the prototype scope (**P0**) is frozen in `docs/00-research.md` §7. The metrics contract is next. There is no application code, no dependencies and no stack yet. Do not scaffold, install, or invent requirements until a task explicitly asks for it and the relevant spec exists.

## Document sequence, and what each one owns

| Document | Owns | Status |
|---|---|---|
| `docs/00-research.md` | **product scope** — personas, the one question, which metrics earn a place, the frozen P0 (§7) | landed |
| `docs/01-metrics-contract.md` | **calculations** — formulas, timestamp rules, exclusions, sample thresholds, benchmark scope, filter semantics, attention-rule evaluation | next |
| `docs/02-requirements.md` | **acceptance criteria** — user stories `US-n` and criteria `AC-n.m` | planned |
| `docs/03-architecture.md` | **system boundaries** — the production design, and what the prototype mocks | planned |
| `docs/04-technical-spec.md` | **implementation** — stack, project structure, API contracts | planned |
| `docs/05-testing-spec.md` | **verification** — how every acceptance criterion is proven | planned |
| `docs/06-plan.md` | **execution order** — milestones and the cut line | planned |
| `docs/07-ai-workflow.md` | a **log kept during development**: how Claude Code was driven, and where it was wrong | maintained as work proceeds |

**ADRs** are added alongside significant decisions as those decisions are made. None exist yet — do not cite an ADR number that has not been written.

Answer a question from the document that owns it: *what we build* is §00, *how a number is computed* is 01, *what counts as done* is 02. If a spec you need doesn't exist yet, say so and stop. If code and spec disagree, stop and ask — never silently change either.

## Stack

Undecided until `docs/04-technical-spec.md`. Assume nothing.

## Conventions that apply from commit one

- Small commits. Format `type(scope): what and why`; add milestone and spec ids once they exist, e.g. `M3: metrics — merge rate (AC-1.2, AC-1.4)`. Types: chore, docs, feat, test, fix, ci, refactor.
- The human writes or edits every commit message. Stage and propose; don't commit unless asked.
- Never edit `docs/` unless the task says so — propose the change instead.
- Never add a dependency that isn't listed in the technical spec.
- No secrets and no assignment text in the repo. "No real company names" means no real customer identities or private company information in fixtures or examples; public vendor names and cited research sources are fine.

## How to work a task

1. Read this file and any spec section the task references.
2. Plan mode first: list the files you'll touch and the tests you'll write. Wait for approval.
3. Do the work. Run whatever gates exist.
4. Report: what changed, which ACs are covered, any deviation from spec and why.

This file grows with the repo: stack, commands, layout and coding rules are added in the commit that lands the technical spec.
