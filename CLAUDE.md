# CLAUDE.md — Fleet Analytics

Org-level analytics dashboard for **Fleet**, an imaginary cloud coding-agent platform (engineers delegate tasks to agents running in isolated cloud sandboxes; the agents open pull requests). Built **spec-first**: the documents in `docs/` are the source of truth, and they land one commit at a time before any code does.

## Where we are

Day 0. No code, no dependencies, no docs yet. Do not scaffold, install, or invent requirements until a task explicitly asks for it and the relevant spec exists.

## Source of truth (as the docs land)

`docs/02-requirements.md` (what — `US-n`, `AC-n.m`) → `docs/03-technical-spec.md` (how) → `docs/04-testing-spec.md` (proof) → `docs/05-plan.md` (order of work).

If a spec you need doesn't exist yet, say so and stop. If code and spec disagree, stop and ask — never silently change either.

## Stack

Decided in `docs/03-technical-spec.md`. Until that file exists, assume nothing.

## Conventions that apply from commit one

- Small commits. Format `type(scope): what and why`; add milestone and spec ids once they exist, e.g. `M3: metrics — merge rate (AC-1.2, AC-1.4)`. Types: chore, docs, feat, test, fix, ci, refactor.
- The human writes or edits every commit message. Stage and propose; don't commit unless asked.
- Never edit `docs/` unless the task says so — propose the change instead.
- Never add a dependency that isn't listed in the technical spec.
- No secrets, no real company names, no assignment text in the repo.

## How to work a task

1. Read this file and any spec section the task references.
2. Plan mode first: list the files you'll touch and the tests you'll write. Wait for approval.
3. Do the work. Run whatever gates exist.
4. Report: what changed, which ACs are covered, any deviation from spec and why.

This file grows with the repo: stack, commands, layout and coding rules are added in the commit that lands the technical spec.
