# M11 Embedding-Only Indexing — Activation Runbook

**Status: activation is intentionally still OFF in every profile.** This document
describes the prerequisites and steps an operator must complete before turning
the pipeline on against a real environment. Writing this runbook does not
itself enable anything, and no step here has been executed against a live
Google account, Ollama server, or the user's testbed.

## What "activation" means

Two independent switches must both be turned on, and both default to `false`
everywhere (`application.yml`):

- `sdv.sync.outbox-publisher.enabled` — the M09A outbox → Kafka publisher.
- `sdv.rag.index-consumer.enabled` — the M11 `IndexRequestedConsumer` /
  `IndexOrchestrator` path documented here.

Turning on the consumer without the publisher (or vice versa) is safe but
useless — no events will ever reach it. Both are required together.

## Prerequisites (operator-provided, not defaulted)

1. **A reachable Kafka broker** — `spring.kafka.bootstrap-servers` must point
   at a real broker (local dev/compose already provide one; the isolated
   Testcontainers broker used by this repo's tests is not it).
2. **A reachable Ollama server with `bge-m3:567m` present** —
   `OLLAMA_BASE_URL` (ai-service, same variable name as the existing
   `.env.example`/`application-*.yml` entries) must resolve to it. The
   embedding adapter does **not** pull the model automatically
   (`no automatic model downloads`); if the model is missing, `/index` returns
   `FAILED` and the document is retried a bounded number of times, then
   dead-lettered.
3. **A Content HMAC key**, set as `SDV_INDEX_CONTENT_HMAC_KEY` on the
   ai-service process. There is no default — leaving it unset makes every
   `/index` call return `FAILED` with reason `"content hmac key not
   configured"` (fail closed, not a silent insecure default). This key is
   separate from the backend's OAuth token-encryption key; it never leaves
   the ai-service process and is never logged or returned in any response.
4. **At least one `ai_usage_policies` row per classification you expect to
   index** (`PUBLIC`/`INTERNAL`/`CONFIDENTIAL`/`SECRET`) with a `mode` other
   than `AI_DENIED`. A missing row denies indexing for that classification by
   design (`AiUsagePolicyService`, existing M05 behavior — this runbook does
   not change it). Local embedding generation only needs `LOCAL_ONLY` or
   `EXTERNAL_ALLOWED`; it never uses an external provider.

## Enabling it

Set both properties (env vars, matching `application.yml`'s placeholders):

```
SDV_OUTBOX_PUBLISHER_ENABLED=true
SDV_RAG_INDEX_CONSUMER_ENABLED=true
```

Optional tuning (defaults shown):

```
SDV_RAG_INDEX_CONSUMER_TOPIC=sdv.source.events        # same topic the outbox publisher writes to
SDV_RAG_INDEX_CONSUMER_GROUP_ID=sdv-rag-index-orchestrator
SDV_RAG_INDEX_CONSUMER_MAX_ATTEMPTS=4                 # bounded retries before dead-lettering
SDV_RAG_INDEX_CONSUMER_BACKOFF_MS=2000
SDV_INDEX_CHUNK_MAX_CHARS=1800                        # ai-service chunking (proposed defaults)
SDV_INDEX_CHUNK_OVERLAP_CHARS=200
SDV_INDEX_MAX_CHUNKS=200
```

## Backfilling already-published shares

Any `document_shares` row created **before** the consumer was ever enabled
never received an `IndexRequestedEvent` (the event didn't exist yet, or the
publisher was off). After enabling both switches, an operator calls
`IndexBackfillService.runOnce(afterShareId, batchSize)` (bounded, one page at
a time; pass the previous call's `lastShareId` to resume) to enqueue those
gaps.

**Correctness note (2026-09-16 correction).** This method's candidate query
checks whether `document_embedding_index` already has a row matching the
document's *current* `source_version` — not whether `processed_events` ever
once recorded an `INDEXED` outcome for it. A document that was indexed at an
older version (content changed since) or whose embeddings were wiped by a
later unshare/admin-block/disconnect is correctly re-offered, even though it
has a historical `INDEXED` entry.

**Duplicate-enqueue guard is best-effort, not a concurrency guarantee
(corrected 2026-09-16).** Before inserting, `runOnce` checks for an
already-`PENDING`/`PUBLISHING` `INDEX_REQUESTED` outbox row for the same
document and skips if one exists. This check-then-insert sequence is **not**
atomic: it does not prevent two genuinely concurrent `runOnce` calls (two
operators at once, or an overlapping automated retry) from both passing the
check for the same document before either has committed its insert, which
can produce two pending requests for the same document. It reliably prevents
duplicates only for **sequential** calls, once the earlier call's insert has
already committed — e.g. an operator re-running `runOnce` after a prior
invocation returned, or resuming from a saved cursor. Do not run this method
concurrently from more than one operator/process against the same database
without accepting that residual risk (a duplicate pending request is
processed idempotently downstream by `IndexOrchestrator`'s generation
fencing, so a duplicate is wasteful, not unsafe, but it is not eliminated by
this guard alone). There is no scheduled/automatic trigger for this by
design; it is a deliberate one-time operator action taken after activation,
not a standing background job.

**Practical invocation path (not yet built — documented intent only).** No
REST/CLI endpoint or scheduled runner for this exists in the codebase today,
and this correction did not add one; `IndexBackfillService` is a plain
`@Service` bean with no caller. Before activation, an operator would need to
add one of: a temporary `CommandLineRunner`/Spring Shell command gated behind
a dedicated profile that is never active by default (e.g. `backfill`), or an
equivalent debugger/REPL call into the running `IndexBackfillService` bean —
neither exists yet. Whichever is built should loop calling
`runOnce(afterShareId, batchSize)`, threading each result's `lastShareId`
into the next call's `afterShareId`, and stop only when a call's returned
`lastShareId` **equals the `afterShareId` it was given** — meaning the
candidate query itself returned no rows and the scan has genuinely reached
the end. Do not stop when `publishedCount() == 0` alone: a page whose
candidates were all skipped by the duplicate-enqueue guard above still
returns `publishedCount() == 0` while `lastShareId` keeps advancing past
already-considered shares, and stopping there would leave later, unrelated
candidates unscanned. This section documents the intended invocation
contract only — building the runner itself is out of scope for this
correction and remains deferred until real pipeline activation.

**What now also schedules fresh work automatically (no manual backfill
needed for these cases).** A verified same-account reconnect
(`GoogleDriveOAuthService`) now schedules bounded (up to 200 documents)
reindexing for that source's currently active, unblocked, shared documents in
the same transaction that reactivates the connection — because disconnect
already wiped that source's embeddings, every one of those documents needs a
fresh generation. A share create/update/admin-unblock, and now also an
ordinary `SOURCE_DOCUMENT_CHANGED` catalog-sync event (a real content-version
change on an already-shared document), each schedule reindexing for exactly
that one document. Manual backfill remains for: shares that existed before
this pipeline was ever enabled, and any reconnect batch larger than 200
documents (the remainder is not automatically retried — rerun backfill to
pick it up).

## What is NOT covered by activation

- **Mandatory Live Retrieval / actual grounded answers (M12)** are a
  completely separate, unimplemented slice. The embedding index this pipeline
  builds is a candidate shortlist only (§2A.4/§2A.5) — it is never treated as
  authorization or as evidence on its own.
- Dead-lettered messages land in the Kafka `<topic>.DLT` topic (Spring Kafka's
  default naming) as a best-effort secondary signal; the authoritative,
  durable disposition is always the DB write (`source_documents.index_status
  = FAILED`, only if it is not already `INDEXED` — an obsolete retry can never
  downgrade a newer successful generation — + a `processed_events`
  `FAILED_TERMINAL` row), recorded before the DLT publish is attempted and
  never swallowed on failure (a genuine DB write failure propagates so the
  record is not silently treated as recovered). **Correction (2026-09-16):**
  the DLT payload is a small, hand-built, allowlisted JSON envelope (a fixed
  reason code, the source topic/partition/offset, and a best-effort
  `eventId`/`documentId`) — it no longer forwards the original Kafka
  record's key/value/headers or any exception message/stack trace, so
  whatever a malformed or attacker-influenced payload contained cannot reach
  the DLT topic. Nothing currently consumes the DLT topic — an operator
  inspects it manually if deeper investigation is needed.
- **Publication fencing (2026-09-16 correction).** The pre-publish
  revalidation now captures the connection's `connection_epoch` and the
  exact `document_shares` row id + `@Version` generation at eligibility time,
  and re-checks both (the share row locked with `SELECT ... FOR UPDATE`)
  immediately before writing the embedding generation — not just connection
  `status=ACTIVE` and document `source_version`. A disconnect followed by a
  reconnect (even same-account), an unshare followed by a fresh republish, or
  an admin block followed by an unblock, all bump one of these values, so a
  fetch/embed cycle that began before any of those events can no longer
  publish afterward just because *something* currently valid happens to
  exist.
- Live acceptance testing against a real Google account, real Ollama server,
  or the project's local testbed remains explicitly postponed, per every
  prior M-series handoff in this repository.
