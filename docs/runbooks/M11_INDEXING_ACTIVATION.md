# M11 Embedding-Only Indexing — Activation Runbook

**Status: activation remains OFF by default in every profile.** The M17 local
testbed launcher now has one explicit, fail-closed opt-in path, but this code
change did not execute that path or index a live document. Writing this
runbook and passing offline launcher tests do not prove live Google, Kafka,
Ollama, HMAC, or embedding behavior.

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

Do not export those variables in the parent PowerShell and expect the M16A
testbed to inherit them. `New-IsolatedEnvironment` intentionally strips all
inherited `SDV_*`/`SPRING_*` application settings. For this one controlled
testbed acceptance, use the launcher's explicit parameters; it passes only the
checked allowlist to the backend and keeps Assistant off:

```powershell
scripts\testbed\start-testbed.ps1 -EnableIndexing -IndexingActivationMode FirstRun -KafkaBootstrapServers 127.0.0.1:9092 -IndexingTopic sdv.testbed.m17.indexing.v1 -IndexingGroupId sdv-testbed-m17-indexing-v1 -AiServiceUrl http://127.0.0.1:8000 -IndexHmacState NewEmptyIndex -PrepareNewIndexingTopics
```

Use `NewEmptyIndex` only when the launcher's content-free DB check confirms
that `document_embedding_index` has zero rows. If rows already exist, stop. Use
`ConfirmedSameKey` only when the operator really knows that the still-running
AI process has the same `SDV_INDEX_CONTENT_HMAC_KEY` that created those rows;
the launcher neither reads nor prints that key. It never generates, rotates,
or persists an HMAC key.

First run explicitly creates and verifies an empty pair:

- `sdv.testbed.m17.indexing.v1`
- `sdv.testbed.m17.indexing.v1.DLT`

The broker protocol is checked with the Kafka CLI inside the existing
`sdv-kafka` container; a TCP-open result is not accepted. The topic and group
must use the testbed-only namespaces. A content-free provenance record is
written under ignored `infra/testbed/.state/`, including the testbed PostgreSQL
system identifier. A restart must reuse the same topic/group and the same
preserved database; a recreated database cannot consume old topic history:

```powershell
scripts\testbed\start-testbed.ps1 -EnableIndexing -IndexingActivationMode Resume -KafkaBootstrapServers 127.0.0.1:9092 -IndexingTopic sdv.testbed.m17.indexing.v1 -IndexingGroupId sdv-testbed-m17-indexing-v1 -AiServiceUrl http://127.0.0.1:8000 -IndexHmacState ConfirmedSameKey
```

Resume does not equate "no PENDING/PUBLISHING outbox row" with "no work".
The publisher can have completed its send and marked the row `PUBLISHED`
before the consumer indexes it. When SHARED is still `PENDING` or `STALE` and
no live relevant outbox row remains, the gate verifies the established main
topic partition by partition: retained earliest/end offsets, the exact
testbed group committed positions, matching partition sets, and positions
inside retained ranges. The backend configuration is fixed to
`auto-offset-reset: earliest`; if the group has not been created or a
partition has no committed position, the retained earliest offset is used for
that partition rather than inventing committed offset zero. At least one
retained main-topic record must remain after those effective positions.

An empty or fully consumed main topic remains blocked. DLT records do not
qualify as resumable main-topic work. Missing/malformed/duplicate/mismatched
offset rows, an unavailable broker, or ambiguous group state also block with
a fixed content-free error. This check establishes only that the approved
topic/group has resumable work within the controlled scope; offsets do not
prove that a particular SHARED event will index successfully. The bounded
`INDEXED` polling below remains the acceptance criterion.

Do not pick a fresh topic/group on every restart. Already-published events
would remain on the prior topic. If the topic exists without matching local
provenance, the gate blocks rather than adopting or replaying it. The DLT is a
separate, content-free disposition channel and must retain the same
provenance. The gate also blocks an active consumer group, any testbed runtime
DB session, or any `PUBLISHING` outbox claim instead of trying to stop/reset
another process.

The content-free DB gate resolves the approved fixture by `source_id=2` plus
the exact synthetic name; document/outbox IDs are never hard-coded. It checks
one active, unblocked INTERNAL share owned by the publisher on source 2,
active publisher connection/token binding, INTERNAL `LOCAL_ONLY` with external
use disabled, PRIVATE unshared/no embeddings, other currently fetch-eligible
shared documents, and both `INDEX_REQUESTED` and `SOURCE_DOCUMENT_CHANGED`
backlog. Malformed/unknown targets fail closed. Other catalog events and
relevant events for currently unshared/ineligible private documents may be
published because `IndexOrchestrator` rechecks current share authorization
before any fetch; the launcher does not invent a per-document runtime filter.

**2026-09-17 correction (current approved sharing contract, `docs/spec/SDV_v3.2_CORE_SPEC.md`
§2A.13):** the gate no longer assumes exactly one recipient or an exact
`allowed_actions = 'VIEW'` string - both were pre-audience/multi-action
assumptions from before V013. It now reads the persisted `audience` column
directly (never inferred from recipient count): `ALL_AUTHENTICATED` must have
exactly zero recipients, and `NAMED_USERS` must have at least one recipient
with a non-blank subject - the same correlation
`SourceSharingService.validateAudienceRecipients`/`DocumentShare`'s
constructor already enforce at write time, re-checked here at read time, not
a new rule. `allowed_actions` (a comma-separated `ShareAction` set) is parsed
into its individual values rather than string-matched - `VIEW` is required,
`DOWNLOAD` is optional and independent (SHR-001/M10C), and any unknown or
duplicate action fails the gate closed. `sharedRecipientCount` remains in the
snapshot for diagnostics only; it no longer gates activation by itself.

This is a point-in-time workload gate, not a concurrency fence. During this
controlled run, do not create/change unrelated shares or start another
publisher. Runtime share/connection generation fencing and provider checks
remain authoritative.

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

## M17 user-run bounded acceptance

Keep the current AI-service PowerShell window open. Do not regenerate its
HMAC key. While the current testbed Postgres is still available, this optional
content-free check tells you which HMAC declaration is truthful:

```powershell
docker exec sdv-testbed-postgres psql -X -v ON_ERROR_STOP=1 -U sdv_user -d sdv -c "SELECT COUNT(*) AS embedding_rows FROM document_embedding_index;"
```

If the count is zero, use the documented `FirstRun` command with
`NewEmptyIndex`. If it is nonzero and continuity of the still-running AI key
is not known, stop. Do not replace the key or delete rows. Then perform the
user-controlled restart (the launcher repeats a stronger DB gate after it has
brought the preserved testbed DB back):

```powershell
scripts\testbed\stop-testbed.ps1
scripts\testbed\start-testbed.ps1 -EnableIndexing -IndexingActivationMode FirstRun -KafkaBootstrapServers 127.0.0.1:9092 -IndexingTopic sdv.testbed.m17.indexing.v1 -IndexingGroupId sdv-testbed-m17-indexing-v1 -AiServiceUrl http://127.0.0.1:8000 -IndexHmacState NewEmptyIndex -PrepareNewIndexingTopics
```

The launcher above is the existing secret-loading entry point and must be run
by the user, never by an agent. It does not stop AI/Ollama or any unrelated
development container.

Poll at most 24 times / 5 seconds. Output is limited to status, safe reason,
IDs, and counts; it does not select names, provider IDs, payloads, tokens, or
content:

```powershell
$pollSql = @'
WITH shared AS (
  SELECT id, index_status, index_reason, source_version
  FROM source_documents
  WHERE source_id = 2 AND name = 'SDV_M17_SHARED.txt'
), private AS (
  SELECT id FROM source_documents
  WHERE source_id = 2 AND name = 'SDV_M17_PRIVATE.txt'
)
SELECT json_build_object(
  'document_id', (SELECT id FROM shared),
  'index_status', (SELECT index_status FROM shared),
  'index_reason', (SELECT index_reason FROM shared),
  'current_generation_rows', (SELECT COUNT(*) FROM document_embedding_index e JOIN shared s ON s.id = e.document_id AND e.source_version = s.source_version),
  'other_generation_rows', (SELECT COUNT(*) FROM document_embedding_index e JOIN shared s ON s.id = e.document_id AND e.source_version <> s.source_version),
  'private_embedding_rows', (SELECT COUNT(*) FROM document_embedding_index e JOIN private p ON p.id = e.document_id),
  'outbox_id', (SELECT o.id FROM outbox_events o JOIN shared s ON CASE WHEN COALESCE(o.payload->>'internalDocumentId','') ~ '^[0-9]+$' THEN (o.payload->>'internalDocumentId')::bigint END = s.id WHERE o.event_type IN ('INDEX_REQUESTED','SOURCE_DOCUMENT_CHANGED') ORDER BY o.id DESC LIMIT 1),
  'outbox_status', (SELECT o.status FROM outbox_events o JOIN shared s ON CASE WHEN COALESCE(o.payload->>'internalDocumentId','') ~ '^[0-9]+$' THEN (o.payload->>'internalDocumentId')::bigint END = s.id WHERE o.event_type IN ('INDEX_REQUESTED','SOURCE_DOCUMENT_CHANGED') ORDER BY o.id DESC LIMIT 1),
  'outbox_attempts', (SELECT o.attempts FROM outbox_events o JOIN shared s ON CASE WHEN COALESCE(o.payload->>'internalDocumentId','') ~ '^[0-9]+$' THEN (o.payload->>'internalDocumentId')::bigint END = s.id WHERE o.event_type IN ('INDEX_REQUESTED','SOURCE_DOCUMENT_CHANGED') ORDER BY o.id DESC LIMIT 1),
  'consumer_indexed', (SELECT COUNT(*) FROM processed_events p JOIN shared s ON s.id = p.document_id WHERE p.consumer_name = 'rag-index-orchestrator' AND p.outcome = 'INDEXED'),
  'consumer_terminal_failed', (SELECT COUNT(*) FROM processed_events p JOIN shared s ON s.id = p.document_id WHERE p.consumer_name = 'rag-index-orchestrator' AND p.outcome IN ('FAILED_TERMINAL','FAILED_TERMINAL_MALFORMED')),
  -- M17 진단 교정 - 이제 SKIPPED_INELIGIBLE도 자격 검사/자격증명·원본 읽기/버전 검증/
  -- 최종 세대 검증 중 어느 단계였는지 고정 허용 코드(예: INELIGIBLE_AT_ELIGIBILITY_CHECK)를
  -- 남긴다(IndexOrchestrator/IndexRequestedConsumer 참고) - 원시 예외/Google 응답/파일명/
  -- 토큰/원문은 절대 담기지 않으므로 이 값 그대로 노출해도 안전하다.
  'latest_skipped_ineligible_reason_code', (SELECT p.reason_code FROM processed_events p JOIN shared s ON s.id = p.document_id WHERE p.consumer_name = 'rag-index-orchestrator' AND p.outcome = 'SKIPPED_INELIGIBLE' ORDER BY p.processed_at DESC LIMIT 1)
);
'@
$indexed = $false
for ($attempt = 1; $attempt -le 24; $attempt++) {
    $raw = & docker exec sdv-testbed-postgres psql -X -q -t -A -v ON_ERROR_STOP=1 -U sdv_user -d sdv -c $pollSql
    if ($LASTEXITCODE -ne 0) { throw 'Content-free polling query failed; stop polling.' }
    $state = $raw | ConvertFrom-Json
    $state | ConvertTo-Json -Compress
    if ($state.index_status -eq 'INDEXED' -and $state.current_generation_rows -gt 0 -and
            $state.other_generation_rows -eq 0 -and $state.private_embedding_rows -eq 0 -and
            $state.consumer_indexed -gt 0) {
        $indexed = $true
        break
    }
    Start-Sleep -Seconds 5
}
if (-not $indexed) { Write-Warning 'INDEXED acceptance was not reached within 120 seconds; do not republish or reset anything.' }
```

Actual success is all of: SHARED `INDEXED`, at least one embedding row for its
current source version, no other generation rows for SHARED, a durable
consumer `INDEXED` disposition, and zero PRIVATE embeddings. `outbox_status =
PUBLISHED` or application health alone is not success.

If the bound ends, keep only the final JSON above. `FAILED` means use its
allowlisted `index_reason` and consumer failure count for the next focused
diagnosis. `PENDING` with a PENDING/PUBLISHING outbox row means do not enqueue
another copy; wait for the publisher state to settle. `PENDING` after a
PUBLISHED outbox row with no consumer disposition points to the isolated
Kafka/group path. The following checks expose only topic/group coordinates and
counts; do not consume raw records or print application logs/payloads:

```powershell
docker exec sdv-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group sdv-testbed-m17-indexing-v1 --state
docker exec sdv-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group sdv-testbed-m17-indexing-v1 --topic sdv.testbed.m17.indexing.v1
docker exec sdv-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic sdv.testbed.m17.indexing.v1 --time -2
docker exec sdv-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic sdv.testbed.m17.indexing.v1 --time -1
docker exec sdv-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic sdv.testbed.m17.indexing.v1.DLT --time -1
```

Do not repeatedly unshare/republish, reset offsets, mark outbox rows, change AI
policy, or use backfill. Report the allowlisted state/count evidence and take
the matching focused branch.

To disable indexing again without deleting data, topic history, credentials,
or embeddings, restart in baseline mode. Do not rotate HMAC keys:

```powershell
scripts\testbed\stop-testbed.ps1
scripts\testbed\start-testbed.ps1
```

Implementation-complete means the launcher correction and offline tests are
done. Live activation/INDEXED remains NOT RUN until the operator supplies the
evidence above. Assistant/grounded-answer acceptance and overall MVP remain
separate and incomplete even after INDEXED.

## M17 Assistant explicit activation (local generation)

A third independent switch, following exactly the same pattern as indexing
above: `sdv.rag.assistant.enabled` defaults to `false` in every profile
(`application.yml`). `-EnableAssistant` is the only way to turn it on for the
testbed, and it is completely independent of `-EnableIndexing` — enabling one
never enables the other, and the launcher never infers Assistant activation
from any inherited parent-shell environment variable
(`scripts\testbed\_lib.ps1`'s `New-IsolatedEnvironment` already strips every
`SDV_*`/`SPRING_*`/... variable from the parent process before applying this
gate's explicit overrides, and `Assert-M17AssistantParameters` separately
refuses any Assistant parameter without the explicit switch).

When enabled, the launcher sets exactly three existing `application.yml`
placeholders on the isolated backend child process — no new configuration key
was added:

```
SDV_RAG_ASSISTANT_ENABLED=true
SDV_RAG_ASSISTANT_MODEL=qwen2.5:7b-instruct-q4_K_M
SDV_RAG_ASSISTANT_OLLAMA_URL=http://127.0.0.1:11434
```

`AssistantModel`/`AssistantOllamaUrl` must be **exactly** the
installed/approved model and the loopback Ollama endpoint above — any other
value is rejected before the backend is ever built or spawned, the same
fail-closed convention `KafkaBootstrapServers`/`AiServiceUrl` already use for
indexing. Before launch, the gate (`scripts\testbed\_assistant-activation.ps1`)
confirms the loopback endpoint answers (`GET /api/version`) and that the
approved model is already present (`GET /api/tags`) — it never pulls/installs
a model, never connects to any other endpoint, and never generates, rotates,
or inspects a key. All other `sdv.rag.assistant.*` settings (context/deadline
budgets, etc.) keep their existing `application.yml` defaults; this change
does not add or tune any of them.

**The actual command for the current situation (existing topic/group,
SHARED already `INDEXED`, no new share/topic/re-index needed).** The
Assistant reads whatever is already durably persisted in
`document_embedding_index` — it does not require the indexing
consumer/publisher to be running at all. The minimal command that turns on
Assistant without touching indexing, Kafka, or any share is:

```powershell
scripts\testbed\stop-testbed.ps1
scripts\testbed\start-testbed.ps1 -EnableAssistant -AssistantModel qwen2.5:7b-instruct-q4_K_M -AssistantOllamaUrl http://127.0.0.1:11434
```

A restart (`stop-testbed.ps1` then `start-testbed.ps1`) is required either
way — a running JVM cannot pick up new environment variables without one;
this does not delete the testbed database, topic history, or embeddings (see
"To disable indexing again" above — the same baseline restart already
documented there).

**If the operator also wants the indexing consumer kept on in the same run**,
add the exact same `-EnableIndexing -IndexingActivationMode Resume
-KafkaBootstrapServers -IndexingTopic -IndexingGroupId -AiServiceUrl
-IndexHmacState` values already established by that operator's own prior
Resume run (this document does not know or invent those values — they must
match the recorded provenance under `infra/testbed/.state/`, checked by
`Assert-M17ResumeProvenance`). Doing so does not force a re-index of the
already-`INDEXED` SHARED document: `Invoke-M17IndexingActivationPreflight`'s
extra "must have resumable Kafka work" check only applies when
`sharedIndexStatus` is still `PENDING`/`STALE` — once it is `INDEXED`, Resume
proceeds without requiring any new or resumable topic work, and the
orchestrator's own generation fencing does not re-embed a current, unchanged
`source_version`.

**What this correction did not verify (explicit).** No Ollama call was made
during this offline implementation session — the reachability/model-installed
checks above run for real only when an operator actually executes
`start-testbed.ps1 -EnableAssistant`. "The approved model is installed and
approved" is the operator's own prior confirmation, not something this
document or its offline tests re-derive. The 34 partial catalog-sync failures
reported separately are unrelated to this switch and remain unresolved and
out of scope here, as is any automatic (e.g. 30-second) sync loop, a UI
change, or a new policy/API/dependency — none of those were added.

Once enabled and confirmed reachable, the existing `POST /api/rag/ask`
endpoint (`RagQueryController`, unchanged by this correction) starts
returning generated, cited answers instead of a `MODEL_UNAVAILABLE` failure
for classifications the existing `ai_usage_policies` table already allows —
no new endpoint was added.

## M17 automatic incremental sync (multi-user-safe scheduler)

A fourth independent switch, following exactly the same pattern as indexing
and Assistant above. The 30-second automatic sync loop the Assistant section
above explicitly said was **not** added is now implemented as
`AutoIncrementalSyncScheduler` (`sdv.sync.auto-incremental.enabled`, default
`false` in every profile). `-EnableAutoSync` is the only way to turn it on
for the testbed, and it is completely independent of `-EnableIndexing`/
`-EnableAssistant` — enabling one never enables another, and the launcher
never infers activation from any inherited parent-shell environment variable
(same `New-IsolatedEnvironment` stripping + explicit-switch-only convention
as the other two gates, `scripts\testbed\_auto-sync-activation.ps1`).

When enabled, the launcher sets exactly one existing `application.yml`
placeholder on the isolated backend child process — no new configuration key
was added, and no CLI-tunable poll interval/batch size/concurrency/backoff
was exposed (those keep their existing `application.yml` defaults: 30s poll,
batch 20, max-concurrent 3, max backoff 1800s):

```
SDV_SYNC_AUTO_INCREMENTAL_ENABLED=true
```

The scheduler only ever calls the existing, unchanged
`IncrementalSyncService.syncChanges(sourceId, ownerSubject)` entry point — the
same one `POST /api/sources/{id}/sync` already uses — for Google connections
that are (1) `ACTIVE`, (2) already have a `source_sync_cursors` row, (3) whose
initial `FULL` sync actually ended `COMPLETED` (not `PARTIAL_FAILURE` or
still-running), and (4) whose current `RUNNING` row (if any) still holds a
healthy, unexpired lease — a `RUNNING` row left behind by a crashed process
(lease already expired) does **not** block eligibility; the existing
`SyncRunLifecycle.beginRun` recovery path (`reapAbandoned`) still runs the
first time anything (manual, ACL-only, or this scheduler) actually attempts
that source again. A connection whose first sync is incomplete, missing a
cursor, or ended `PARTIAL_FAILURE` is never auto-promoted to eligible.

**A connection whose only `FULL` run ended `PARTIAL_FAILURE` currently has no
supported recovery path once a cursor already exists.** `POST
/api/sources/{id}/sync` (`SourceSyncService.sync`) always picks `INCREMENTAL`
whenever a `source_sync_cursors` row exists, regardless of the FULL run's own
status — it never re-attempts a fresh `FULL` scan. The only method that starts
a `FULL` run (`SourceSyncService.startInitialSync`) is not wired to any
controller endpoint. Do not tell an operator that "running manual sync once"
will turn a `PARTIAL_FAILURE` initial sync into `COMPLETED` — it will not; it
only resumes the existing (already-partial) incremental catch-up from
wherever the cursor last safely committed. A dedicated resync/recovery
endpoint was out of scope for this correction and was not added; this gap is
recorded in `docs/plan/SDV_MVP_DEFERRED.md`.

Enabling this switch
does **not** enable the Outbox Publisher or Index Consumer (`-EnableIndexing`)
or the Assistant (`-EnableAssistant`) — each stays its own explicit switch;
without `-EnableIndexing`, the auto-synced metadata/permission changes still
reach `outbox_events` (unchanged existing write path) but are never published
to Kafka or indexed until that separate gate is also turned on.

```powershell
scripts\testbed\stop-testbed.ps1
scripts\testbed\start-testbed.ps1 -EnableAutoSync
```

A restart is required either way — a running JVM cannot pick up new
environment variables without one; this does not delete the testbed
database, topic history, or embeddings.

**2026-09-18 후속 교정(다중 사용자 환경 안전성 결함 3건, Migration V015).** 세 가지 결함을
최소 수정했다: (1) 대상 조회가 건강한 `RUNNING`만 제외하도록 좁혀, 프로세스 중단으로 남은
만료 `RUNNING` 행이 기존 `beginRun`의 회수(`reapAbandoned`) 경로에 도달하지 못하고 영원히
막히던 문제를 해소했다(새 회수 로직을 만들지 않음, 기존 V008/V009를 그대로 재사용). (2)
`AutoIncrementalSyncScheduler.processOne`이 이제 `syncChanges`가 반환한 실제 `SyncRunEntity`의
상태를 확인해 `COMPLETED`일 때만 성공으로 기록하고, `PARTIAL_FAILURE`/`ABANDONED`는 예외
경로와 동일한 상한 있는 지수 Backoff로 처리한다(이전에는 예외만 없으면 무조건 성공으로
기록해 지속적인 부분 실패가 연속 실패 횟수를 계속 0으로 재설정했다). (3) `source_sync_cursors`에
`claim_token`(V015, UUID)을 추가해, 매 Claim마다 새 Token을 부여하고 완료 기록
(`recordSuccess`/`recordFailure`)이 그 Token과 정확히 일치할 때만 적용되게 했다 - 오래
걸린 이전 Tick의 뒤늦은 완료 보고가 그 사이 새로 Claim된 상태(`next_check_at`/
`consecutive_failures`)를 덮어쓰지 못한다(기존 `outbox_events.claim_token`과 동일한 패턴
재사용). 실제 Testcontainers PostgreSQL 기준 6개 신규 테스트(만료 RUNNING 포함/제외, 회수된
Run에 대한 실제 동시 두 번째 시도 거부, PARTIAL_FAILURE의 Backoff 처리, 오래된 성공/실패
보고 무시 2건)로 검증했다. 자동 동기화는 여전히 기본 OFF이며 이 교정 세션에서 live 활성화는
하지 않았다.

**What this correction did not verify (explicit).** No real Google Drive
account, backend restart, or live 30-second cycle was executed during this
offline implementation session — the behavior above runs for real only when
an operator actually executes `start-testbed.ps1 -EnableAutoSync` against a
connection that already has a genuinely `COMPLETED` initial sync. Whether any
particular already-connected source (for example, one previously left in a
`PARTIAL_FAILURE` state) is currently eligible is not asserted here — the
operator should confirm the specific connection's own history before
expecting it to start auto-syncing.

## Backfilling already-published shares

Any `document_shares` row created **before** the consumer was ever enabled
never received an `IndexRequestedEvent` (the event didn't exist yet, or the
publisher was off). After enabling both switches, an operator calls
`IndexBackfillService.runOnce(afterShareId, batchSize)` (bounded, one page at
a time; pass the previous call's `lastShareId` to resume) to enqueue those
gaps.

**Correctness note (2026-09-16 correction).** This method's candidate query
checks whether `document_embedding_index` already has a row matching the
document's _current_ `source_version` — not whether `processed_events` ever
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
  publish afterward just because _something_ currently valid happens to
  exist.
- Live acceptance testing against a real Google account, real Ollama server,
  or the project's local testbed remains explicitly postponed, per every
  prior M-series handoff in this repository.
