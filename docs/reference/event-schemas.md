# Production event contracts

> **Proposed design, not implemented ingestion endpoints or executable schemas.** These events support the [production architecture](../03-architecture.md). They do not change the prototype's metrics, PostgreSQL migrations or HTTP contract. Event names and payloads below are the proposed internal Fleet format, not vendor webhook formats.

## 1. Where the contract applies

Ingestion verifies the producer, resolves the tenant, validates the source payload and converts allowlisted fields to this envelope before publishing to Kafka. Fleet-controlled producers can use the format directly; external adapters perform the conversion. Workers resolve relationships and produce analytical facts/history in ClickHouse.

Kafka has two independent consumer paths: processing workers write analytics; an archive consumer saves the accepted Fleet events to object storage. Archival is not a prerequisite hop before ClickHouse. Replay preserves event identity and uses the normal processing rules. Archive progress must be monitored against Kafka expiry.

Do not archive entire provider payloads by default: no prompt text, model responses, source code, PR bodies, review comments, credentials or tokens. Restricted denied-domain metadata is an explicit exception governed by access and retention controls.

## 2. Common envelope

| Field | Type / requirement | Meaning |
|---|---|---|
| `schema_version` | Positive integer, required | Contract version for this event type; not an entity revision |
| `event_id` | Non-empty string, required | Stable event identity within `(org_id, source)`; preserved on retry/replay |
| `event_type` | Registered string, required | Selects the payload schema |
| `source` | Non-empty string, required | Stable producer/integration namespace, not just a display name |
| `org_id` | Fleet identifier, required | Derived or verified against authenticated producer/integration ownership |
| `entity_type` | Registered string, required | Task, run, PR, usage record or another registered entity family |
| `entity_id` | Non-empty string, required | Stable ID within `(org_id, source, entity_type)`; never a mutable name |
| `occurred_at` | UTC RFC 3339 timestamp, required | Business transition time; for snapshots, observation time as specified below |
| `ingested_at` | UTC RFC 3339 timestamp, required | Acceptance time assigned by ingestion; never substituted for metric event time |
| `source_version` | Non-negative integer or `null`, required | Monotonic entity revision only where the source guarantees one; otherwise `null` |
| `payload` | Object, required | Fields defined by the event type and version |

Task/run/repository references belong in payloads where relevant, not as mandatory envelope fields. Fleet IDs and provider IDs must be named distinctly; cross-provider references also carry the provider namespace. ID strings in examples are illustrative, not a requirement to use UUIDs everywhere.

On transport redelivery, `ingested_at` may differ; it does not change event identity or semantic content. Archive replay preserves the saved envelope; record replay time separately as processing metadata. A new correction has a new event ID and the same business entity identity, with a reliable revision or explicit correction relationship.

## 3. Event catalogue

Fields below are required unless marked optional. All timestamps are UTC. Each type uses a compatible `entity_type`; unknown types/versions are not interpreted as a known schema.

| Event type | Payload requirements | Purpose / time rule |
|---|---|---|
| `task.created` | `owner_user_id`, `team_id`, `repository_id`, `task_type`, `created_at` | Creation-time attribution; `occurred_at = created_at` |
| `task.terminal` | `status` (completed/failed/cancelled), `terminal_at` | Orchestrator-owned outcome; `occurred_at = terminal_at` |
| `run.started` | `task_id`, `attempt_no` (integer ≥ 1), `started_at`; optional `retry_initiator` (platform/user) | Execution attempt; `occurred_at = started_at` |
| `run.terminal` | `task_id`, `status` (completed/failed/cancelled/timed_out), `ended_at`; `failure_reason` required for failed/timed_out runs | Attempt outcome, not task outcome; `occurred_at = ended_at` |
| `pr.opened` | `repository_source_id`, `pr_number` (positive integer), `target_branch`, `opened_at` | Provider PR identity; `occurred_at = opened_at` |
| `pr.merged` | `repository_source_id`, `pr_number`, `target_branch`, `merged_at` | Merge outcome; `occurred_at = merged_at` |
| `pr.closed_unmerged` | `repository_source_id`, `pr_number`, `target_branch`, `closed_at` | Non-merge closure; `occurred_at = closed_at` |
| `task.pr_linked` | `task_id`, `pr_source`, `pr_source_id`, `repository_source_id`, `linked_at`; optional `run_id` | Platform-owned association; entity is the stable association ID; `occurred_at = linked_at` |
| `usage.recorded` | `run_id`, `metered_at`, `amount_cents` (integer ≥ 0), `currency` (USD), `model_tier` | Entity ID is a stable metering-ledger record ID; `occurred_at = metered_at` |
| `network.denied` | `task_id`, `domain`, occurrence time in envelope; optional `run_id` | One denial observation; workers normalise the domain and calculate distinct affected tasks/owners |

Task types and failure-reason groups follow the [metrics contract](../01-metrics-contract.md); do not introduce new dashboard categories through ingestion. USD cents follows the current contract: sub-cent metering, credits and multi-currency support need an explicit extension, not float rounding or silently accepted negative records.

Metadata is also required. Versioned snapshots provide repository identity/default branch, organisation/team/user attribution, seat assignments, and organisation/team monthly budgets. They must carry stable entity IDs and source observation/effective timestamps where available. These are analytical copies, never credentials or the authority for current permissions. The prototype's fixed seats and team attribution do not define all production membership/licensing semantics.

## 4. Representative events

### PR merged: after external-to-Fleet mapping

```json
{
  "schema_version": 1,
  "event_id": "github-delivery-abc123",
  "event_type": "pr.merged",
  "source": "github-installation-123",
  "org_id": "org_example",
  "entity_type": "pull_request",
  "entity_id": "github-pr-98765",
  "occurred_at": "2026-09-06T14:30:00Z",
  "ingested_at": "2026-09-06T14:30:03Z",
  "source_version": null,
  "payload": {
    "repository_source_id": "github-repo-456",
    "pr_number": 42,
    "target_branch": "main",
    "merged_at": "2026-09-06T14:30:00Z"
  }
}
```

GitHub is not expected to supply Fleet's task ID. Workers resolve the association recorded by `task.pr_linked`. Until the association and prerequisite facts arrive, keep the record unresolved; do not invent attribution, discard it, or count it as a confirmed agent outcome. Integration reinstallation must preserve or explicitly migrate provider-entity mappings.

### Metered usage: independent of a PR

```json
{
  "schema_version": 1,
  "event_id": "meter-event-104",
  "event_type": "usage.recorded",
  "source": "fleet-metering",
  "org_id": "org_example",
  "entity_type": "usage_record",
  "entity_id": "ledger-record-104",
  "occurred_at": "2026-09-06T14:20:00Z",
  "ingested_at": "2026-09-06T14:20:05Z",
  "source_version": 1,
  "payload": {
    "run_id": "run_example",
    "metered_at": "2026-09-06T14:20:00Z",
    "amount_cents": 125,
    "currency": "USD",
    "model_tier": "standard"
  }
}
```

This is the amount for one ledger record, not an increment to add again on each delivery. Workers obtain task type, owner and attribution through the run/task relationship, not through a PR. This illustrative `model_tier` is not a new approved prototype tier list.

## 5. Processing and evolution rules

| Case | Required behaviour |
|---|---|
| Same event delivered again | Same logical effect; no new cost, PR or task. Deduplication correctness must cover retained replay, not only a short Redis TTL. |
| Different event IDs describe the same fact | Resolve by business identity and version/transition semantics before aggregation; delivery IDs alone are insufficient. |
| Same event ID with conflicting semantic payload | Flag a conflict for investigation; do not silently accept one as a new event. |
| Out-of-order lifecycle events | Preserve history; late opening cannot overwrite a known merge. A genuinely reopened PR is a new transition requiring explicit lifecycle semantics. |
| Missing parent or association | Retain as unresolved, retry resolution/reconciliation, and expose affected coverage gaps; unknown is not zero activity. |
| Correction | Versioned sources replace the logical record with the newer full value, not an additive delta. Without reliable revision semantics, reconcile or quarantine the conflict rather than treating arrival order as truth. |
| Reconciliation snapshot | Separate type, e.g. `pr.snapshot`, with `observed_at`, current state and known milestone timestamps. `occurred_at = observed_at`; metric membership still uses the relevant milestone timestamp. Do not manufacture intermediate transitions. |
| Unsupported schema or malformed payload | Reject before durable acceptance when detectable; downstream non-processable records go to durable quarantine and remain visible to coverage checks. |
| New schema version | Version per event type; deploy compatible consumers before producers. Preserve old replay inputs and use explicit upcasting where required. Never reinterpret an old event under changed semantics. |

An archive entry is the accepted event representation; a ClickHouse row is an analytical representation. ClickHouse queries must resolve duplicates/versions before calculation. Raw insertion counts and background merging are not proofs of correctness.

## 6. Validation before production

Convert this reference into executable per-type schemas and contract fixtures. Define snapshot/metadata payloads, correction semantics and provider mappings before those producers are enabled. Review/reopen events are extensions, not implemented P0 behaviour.

Required tests: schema rejection; wrong-tenant mapping; stable replay identity; conflicting duplicate; repeated metering record; out-of-order PR events; missing task link followed by resolution; snapshot versus transition timestamps; schema-version replay; and independent archive/analytics consumer recovery. Also verify that excluded content never enters Kafka or the archive.

GitHub adapter implementations should follow its [webhook payload and delivery documentation](https://docs.github.com/en/webhooks/webhook-events-and-payloads) and [webhook handling guidance](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks). These provider contracts are inputs to, not substitutes for, Fleet's internal contract.
