# Secure Document Vault v3.2 — Core Specification

> Agent-readable specification for Claude Code, Codex, and human reviewers.

## 1. Specification Metadata

- Project: Secure Document Vault (SDV)
- Specification Version: 3.2 (baseline), amended to v1.5 approved architecture — see §2A
- Master Specification (baseline): `Secure_Document_Vault_v3.2_Cloud_상세기능_파일통합명세.xlsx`
- Master Specification (approved amendment, current authoritative Excel): `SDV_v3.2_전체상세명세_v1.5_선택공유_원본비보관.xlsx`
- Master Baseline Date: 2026-09-03; v1.5 approved amendment applied 2026-09-15
- Current Development Scope: Phase 1 — 10-week Core MVP
- v1.5 inventory: 125 feature IDs, including 74 rows whose phase is `MVP`; 323 file IDs; 67 validation IDs; 40 constraints. These counts describe the master inventory and are not implementation-completion percentages.
- Architecture: Package-by-feature + Ports/Adapters hybrid
- Primary Backend: Java 21 + Spring Boot + Spring Data JPA
- Primary Database: PostgreSQL
- Migration: Flyway
- Core Source (v1.5): Google Drive only — the sole implemented Source and the system of record. Local Vault is **EXCLUDED / RETIRED** at v1.5 (historical function/IDs preserved for traceability only — see §2A.1, §11).
- Retention model (v1.5): no persistent original content, no persistent complete extracted text/plaintext chunks; only Metadata/ACL Catalog + embedding-only index + audit/operational state may be persisted — see §2A.2, §2A.3.
- Retrieval model (v1.5): every `FIND_CONTENT`/`SUMMARIZE`/`COMPARE`/`GROUNDED_ANALYSIS` request performs mandatory live retrieval against Google Drive using publisher-bound delegation after requester SDV authorization — see §2A.5.
- Default LLM: Ollama / Local LLM
- External LLM: OFF by default

This Markdown file is an Agent-readable extraction of the v3.2 master specification, amended in place to reflect the v1.5 approved architecture. Historical v3.2-only content (e.g. Local Vault, content-storing Indexed Mode) remains below only where explicitly marked superseded/excluded/retired — it does not describe an active Core capability.

It does not replace the original Excel master specification. Where this Markdown and the v1.5 Excel disagree, the v1.5 Excel governs (see §2 Precedence).

---

# 2. Precedence / Source of Truth

Latest explicit user decisions control approved revisions. Applied migrations constrain implementation history, not product-spec precedence. When planning, implementing, reviewing, or refactoring SDV, use the following precedence.

1. Latest user-provided v3.2 Excel master — currently the v1.5 approved workbook named in §1
2. `docs/spec/SDV_v3.2_CORE_SPEC.md`
3. `docs/spec/SDV_v3.2_FILE_MANIFEST.md`
5. `CLAUDE.md` / `AGENTS.md` working rules
6. Existing implementation
7. README and older documents
8. Agent assumptions

If an existing implementation conflicts with the v3.2 specification, do not assume the implementation is correct.

Report the conflict before changing architecture.

If the specification already defines a class name, interface name, package path, enum value, responsibility, or phase, do not invent an alternative.

If something is not defined in this specification, do not silently infer it as an official v3.2 requirement.

Mark it as:

`SPEC GAP / DESIGN DECISION REQUIRED`

and report it before implementation.

---

# 2A. v1.5 Architecture — Personal Sources, Selective Sharing and No Original Retention

Revision date: 2026-09-15. These are approved target contracts, NOT claims that the B-model implementation exists. They replace the former requirement that the consuming user must personally possess native Google file permission.

## 2A.1 Source and Retention

Ordinary OIDC USERs connect their own storage. Google Drive is the first provider and remains the original system of record; keep DocumentSourceConnector provider-neutral. Connecting a source does not publish any file. Owner-only metadata browsing may include private files; common discovery, AI and download include only explicitly published SDV shares.

MVP stores no original copies. Old Local Vault and writer IDs remain retired. A separately designed post-MVP original-storage mode (STO-001) is approved for the backlog, not implementation now; it requires opt-in, encryption, retention/deletion/backup, version, offline-entitlement, quota and cost decisions. It does not reactivate retired IDs. The default no-original mode applies identically on-premises, cloud and hybrid.

## 2A.2 Data That May Be Persisted

Metadata/ACL catalog; explicit file-share intent, named recipients, security classification, action grants and generations; encrypted OAuth tokens with externally supplied key; content-free embeddings, generalized locators, source version, keyed digest/HMAC, parser/model versions; content-free audit and operational state. Embeddings remain sensitive customer data and require isolation, access control and deletion.

## 2A.3 Data That Must Not Be Persisted

No durable originals/exports, full extracted text, plaintext chunks, evidence, question/answer/prompt bodies, even encrypted. No content-bearing logs, events, DLQ, retries, caches, backups or dumps. Transient bounded memory is not a durable store; erase on completion/error/cancellation.

V006__zero_original_persistence.sql and M07A are historical completed corrections, not NEXT work. V001–V009 files exist in the inspected checkout; never rewrite applied migrations. This document update did not inspect a runtime database or rerun product tests.

## 2A.4 Metadata Search vs. Content Search

Owner picker: own private metadata only, no publication or content indexing by browsing.
Common file discovery: published shares authorized to the requester, independent of chunk/index existence and MIME parse support.
Content search: authorized published shares with usable version-consistent index/evidence. Embeddings only shortlist candidates; they prove neither permission nor document facts. M10's current structured GET /api/rag/files search is not yet natural-language request routing or B-model sharing.

## 2A.5 Mandatory Live Retrieval

For FIND_CONTENT/SUMMARIZE/COMPARE/GROUNDED_ANALYSIS:
1. Authenticate requester B with OIDC; resolve the exact share/file/source/publisher A and provider account on the server.
2. Validate B's named-recipient grant, requested action, classification/overlay, publication, admin block, source availability and current share/connection generations. Never trust client-supplied ownership.
3. Use only A's verified file-bound delegated credential. B need not have native Google rights. Neither arbitrary owner credentials nor broad admin/service-account credentials are an authorization shortcut. Do not expose A's credential to B. MVP does not require domain-wide delegation or changing Google ACLs.
4. Revalidate A's current provider permission, download/export capability, non-trashed state and version before fetching. UNKNOWN/error is denial, not permission.
5. Bounded transient fetch; isolated parser for supported content only; select minimum evidence.
6. Before releasing evidence to LLM/user, revalidate B's share/action/policy and share/connection generations AND A's provider access/version. Discard on revoke/disconnect/block/change.
7. A changed source version invalidates the entire attempt; at most one retry; repeated change returns DOCUMENT_CHANGED. No usable evidence returns NO_EVIDENCE.
8. Answer/cite only verified evidence and version. Apply §2A.6 on every reuse.

Requester, publisher, credential owner, provider identity, source, file, share, requested action and generations are distinct values. SourceAccessContext is server-created and bound to this operation, not a client bearer capability. A provider denial for the publisher cannot be widened by SDV.

## 2A.6 Encrypted Ephemeral Evidence

Selected evidence may be kept only in encrypted volatile storage for the same actor/conversation/share+connection generations/source version. Hard TTL <=300 seconds from original creation; reads/copies/retries never extend or restart it. Check current SDV grant and publisher source access/version on every reuse. Evict on expiry, conversation close, cancellation, errors, revoke, admin block, disconnect or version change. No durable volume/backups/snapshots; after expiry fetch again. No full-document cache.

## 2A.7 File-Format Scope and Download

All types can have owner metadata and, when explicitly shared and authorized, common discovery/download. AI whitelist: PDF, DOCX, TXT, MD; Google Docs transient PDF/DOCX export. Existing XLSX parser retained but default-disabled. Images/archives/audio/video/source code are not parsed for MVP AI. Image-only PDFs become SKIPPED_NO_TEXT without OCR.

Download is a separate non-AI path: GET /api/shares/{shareId}/download performs §2A.5 authorization and pre/post fetch checks but does not run a parser/embedding/LLM. A Google link alone cannot serve B without native Google rights. Use a bounded in-memory buffer and check again before releasing bytes; existing connector byte[] is not end-to-end streaming. Limit per-file bytes, concurrent buffers, time and total request work. Return safe attachment filename/MIME, nosniff, no-store; disable content logging and proxy caching. Reject oversize/export-limit failures safely, never treat partial exports as complete. Bytes already received cannot be recalled.

## 2A.8 Parser Security

Keep compression ratio <=100:1, decompressed bytes <=200 MiB and existing entry/time/row/page limits. No parser network/XXE/external resource loading/macros/scripts/archive recursion. MIME ambiguity or excess limits fail closed. Download has independent transfer limits, not the AI parser whitelist. Detection may require bounded bytes; the security assertion is zero unauthorized parse/embedding/LLM, not fictitious zero inspection bytes.

## 2A.9 AI Boundary

Default local Ollama; external LLM OFF. No training/fine-tuning prerequisite for MVP RAG. Separate instructions from untrusted source data; minimum verified evidence, safe rendered output and content-free audit. Separate cited source facts from AI recommendations. No state-changing LLM tools.

## 2A.10 S3 and Object Storage

Future provider connectors remain source adapters in the default no-original mode. F-BE-159/F-BE-160 and old Vault IDs remain retired; no writer added by this revision. STO-001 is a separate opt-in post-MVP storage mode with new design/IDs, not a blanket permission to persist current evidence or copies.

## 2A.11 Index and Authorization

Index only explicitly shared, eligible files; connection or catalog sync alone is not publication/AI consent. Persist vectors/locators/version/HMAC/model/parser metadata without bodies. Filter the requester's authorized share IDs before vector search; no empty-filter global fallback. At completion, fence index writes by source/file/share/connection generations and source version so old work cannot restore revoked results. Preserve existing SourceDocumentState ACTIVE/DELETED and DocumentIndexStatus PENDING/INDEXED/SKIPPED_UNSUPPORTED/SKIPPED_NO_TEXT/FAILED/STALE.

## 2A.12 Development Snapshot and Order

Inspected branch feat/m16b-file-discovery-ui, HEAD 7b0debab99f3f043f73117d03fd11a17d16cc1e2.
M01–M08 including M07A: existing baseline; M07 retired.
M09A catalog/outbox: completed slice, not full Kafka ingestion. Consumer eventId ledger/retry/DLQ/IndexOrchestrator integration and publisher activation gate remain.
M10: completed owner-bound metadata-discovery slice, not B-model shared discovery.
M16A/OIDC testbed and M16B sync/discovery UI: reported completed bounded slices. M16B frontend tests 53/5 files, lint/build are prior agent reports, not rerun here; live browser/Google M16B checks remain unverified.
Next: user reviews/integrates current M16B; M10B personal sources/sharing/reconnect; M10C authorized download; M16C sharing UI; remaining M09 consumers with M11 ingestion; M12 live retrieval; M13/14 natural-language routing and grounded answers; M15/16 integration; M17/18 real-user MVP gate.
MVP-28 CSS improvement is the first task after MVP, preserving pale-blue sidebar/white main and obtaining visual approval.

## 2A.13 Sharing and Administrator Contract

MVP uses file-level explicit named SDV user recipients; no implicit folder inheritance, group expansion or publish-all on connect. Confirm file, recipients, security level and action grants. Empty recipients never mean public/all users. PUBLIC/INTERNAL/CONFIDENTIAL/SECRET are the four canonical classifications; PUBLIC is not an internet-public audience. Missing/unmapped classification means safe denial (AI_DENIED), not a fifth enum.

Administrator manages SDV-published metadata, labels, policies, blocks and audit only. ADMIN does not reveal another user's private Drive or tokens and does not automatically grant AI/download. A publisher cannot clear an administrative block. Administrative policy may restrict but cannot silently expand the publisher's audience/action consent.

## 2A.14 Disconnect and Reconnect

Persist share intent separately from temporary connection health/provider access and index readiness. Disconnect pauses use, invalidates credentials/in-flight generations and makes prior evidence unavailable; it does not silently erase share settings. Explicit unshare is a separate operation. Restore only for the same SDV owner and verified stable provider identity/store, not matching email/display name. Verify each original file and current policy/permissions/version before restoring availability. Different provider account, same-name replacement, deleted file, revoked share or admin block cannot inherit/revive grants. Preserve settings, not a promise of immediate AI readiness: stale index may need rebuilding. Existing destructive disconnect behavior is an implementation gap to fix in M10B.

## 2A.15 Ordinary-User MVP Acceptance

Real USER: home → OIDC login → connect own Drive → intuitively choose and share files → another authorized USER asks to find files or asks content in natural language → authorized file results/download or grounded AI answer with citations. Test A publishes, B allowed without native Google share, C denied, private files hidden from common discovery/AI/admin, revocation race, same-account reconnect and wrong-account denial. Login/admin mock success alone is not this acceptance gate.

# 3. Project Definition

SDV is an Enterprise Secure RAG Gateway.

Its main purpose is to:

- connect existing enterprise document Sources,
- preserve the publisher's Source authority while enforcing explicit SDV recipient grants,
- apply additional SDV security policies,
- perform permission-aware Retrieval,
- send only permitted context to an LLM,
- return Citations,
- record the entire security decision path in Audit.

The Source remains the System of Record.

SDV must not become a full replacement for Google Drive, SharePoint, or enterprise file management products.

---

# 4. Phase Boundaries

## Phase 1 — Core MVP

Target: 10 weeks.

Primary execution environment:

- Windows development machine
- Docker Compose
- Backend may run from IDE
- PostgreSQL
- Kafka
- Keycloak
- Ollama
- AI Service
- React frontend

Core MVP must establish:

- Google Drive Source (sole implemented Source and system of record — v1.5, §2A.1)
- ~~Local Vault Source~~ — **EXCLUDED / RETIRED at v1.5** (§2A.1, §11); preserved historically only, never reused
- Source ACL synchronization
- Principal normalization
- Overlay Policy
- AI Usage Policy
- Permission-aware RAG via the Embedding Candidate Index + mandatory Google Drive Live Retrieval (v1.5, §2A.5, §2A.11) — not a durable content-storing "Indexed Mode"
- pgvector retrieval (embedding-only index — no persisted plaintext, §2A.2)
- Encrypted ephemeral evidence store with a hard TTL (v1.5, §2A.6)
- Citation from live-verified content only (v1.5, §2A.5)
- Kafka-backed asynchronous sync/indexing
- Transactional Outbox
- Retry / DLQ / Idempotency
- Audit
- Oversharing Detection
- ~~Local Vault encryption/integrity~~ — **EXCLUDED / RETIRED at v1.5** (§2A.1)
- Core E2E security evidence

Do not begin AWS implementation before Core is functionally complete.

## Phase 1.5 — Kubernetes

After Core.

Purpose:

- deploy the same Core to Local/On-Prem Kubernetes
- Helm / Probe / NetworkPolicy
- AI Service remains internal
- Kubernetes is not allowed to change Core business rules

## Phase 2 — Cloud Portfolio

After Core/Kubernetes path is stable.

Target architecture:

- AWS VPC
- Amazon ECR
- Amazon EKS
- Amazon RDS PostgreSQL
- AWS Secrets Manager
- AWS KMS
- CloudWatch/OpenTelemetry
- Terraform
- Helm

Do not implement ECS in parallel with EKS.

Amazon MSK is optional because of portfolio cost.

## Phase 3 — Hybrid Future

Future product/business architecture.

Sensitive document processing remains in Customer Data Plane.

Raw documents, Chunks, Embeddings, and raw Prompts must not leave the Customer Data Plane.

---

# 5. Core Architecture Rules

## 5.1 Package-by-feature

Use feature packages such as:

- `com.sdv.common`
- `com.sdv.auth`
- `com.sdv.source`
- `com.sdv.vault` — EXCLUDED / RETIRED at v1.5 (§2A.1, §11); listed for historical traceability only, not an active package to build against
- `com.sdv.sync`
- `com.sdv.event`
- `com.sdv.policy`
- `com.sdv.rag`
- `com.sdv.ai`
- `com.sdv.audit`
- `com.sdv.security`

Do not create a global `controller`, `service`, `repository`, or `entity` package shared by unrelated features.

---

## 5.2 Dependency direction

Controller responsibilities:

- HTTP input/output
- request validation
- authentication context forwarding

Controller must not directly call a JPA Repository.

Application Service responsibilities:

- Use Case orchestration
- Transaction Boundary
- interaction with required Ports / Repositories

Domain and Port:

- must not depend on JPA Entity
- must not depend on HTTP DTO
- must not depend on Google Drive SDK
- must not depend on AWS SDK
- must not contain infrastructure-specific types

Infrastructure Adapter:

- implements external technology
- Google Drive API
- Local filesystem
- pgvector
- Kafka
- Ollama
- AWS SDK in later phase

JPA Entity:

- database persistence object
- never returned directly from REST API

Mapper:

- DTO ↔ Domain
- Domain ↔ Entity

MyBatis XML Mapper is not used.

---

# 6. Abstraction Rules

Interface + Composition is the default.

Use an Abstract Class only when a stable workflow invariant exists.

Approved examples:

- `AbstractSourceSyncJob`
- `AbstractIdempotentEventConsumer`

Do not introduce abstract base classes merely to share a few helper methods.

Do not introduce extra Ports only because Hexagonal Architecture usually has such a Port.

A Port must have a concrete architectural reason and should be defined by the specification or approved as a new design decision.

---

# 7. Source Domain

## 7.1 SourceType

Official v3.2 values:

```java
GOOGLE_DRIVE
LOCAL_VAULT
SHAREPOINT
S3
```

Location:

`com.sdv.source.domain.SourceType`

Core actual implementations:

- GOOGLE_DRIVE — the sole active Core implementation and system of record (v1.5, §2A.1).
- ~~LOCAL_VAULT~~ — **EXCLUDED / RETIRED at v1.5** (§2A.1, §11). The enum value itself may remain for historical/schema-compatibility traceability, but no new Local Vault functionality may be built or reactivated.

SharePoint and S3 are not full Core implementations. In the default no-original mode S3 is a read-only provider connector (§2A.10). A separately designed post-MVP STO-001 storage mode requires new approval/IDs; old writer IDs remain retired.

---

## 7.2 SourceConnection

Location:

`com.sdv.source.domain.SourceConnection`

Responsibility:

Represent an SDV Source connection as a technology-independent Domain Model.

Important domain operations defined by v3.2:

```java
activate()
deactivate()
```

`SourceConnection` must not:

- call Google Drive API
- use Google SDK types
- call HTTP directly
- contain JPA annotations
- know PostgreSQL
- know OAuth SDK implementation details

Database and Domain may currently carry similar data, but they have different responsibilities.

The current `source_connections` database model contains:

- id
- type
- display_name
- status
- sync_mode
- token_ref
- last_sync_at

The exact SourceConnection status enum and sync mode enum are not declared as official enums in the current v3.2 file manifest.

Do not invent new enums for those fields without a separate design decision.

---

## 7.3 SourceDocument

Location:

`com.sdv.source.domain.SourceDocument`

Standard representation of Source documents. At v1.5, Google Drive is the sole active Source (§2A.1); any Local Vault-origin rows are historical/retired only (§11), not a currently-populated Core path.

Key concepts:

- id
- name
- MIME type
- source version
- state (`SourceDocumentState`) — Source document lifecycle
- indexStatus (`DocumentIndexStatus`) — RAG/content indexing status
- indexReason — reason for the current indexStatus

`state` and `indexStatus` are distinct concepts and must not be merged (see `SourceDocumentState` and `DocumentIndexStatus` below).

Source-specific Google SDK objects must not leak into this class.

---

## 7.4 SourceDocumentState

Location:

`com.sdv.source.domain.SourceDocumentState`

Official values:

```java
ACTIVE
DELETED
```

`SourceDocumentState` represents only the Source document lifecycle. It must not be used to represent RAG/content indexing status.

State transitions must not allow deleted documents, or documents whose current content is not `INDEXED`, to accidentally remain valid RAG candidates.

### DocumentIndexStatus

Location:

`com.sdv.source.domain.DocumentIndexStatus`

File ID: `F-BE-182`

Type: Java Enum

Official values:

```java
PENDING
INDEXED
SKIPPED_UNSUPPORTED
SKIPPED_NO_TEXT
FAILED
STALE
```

`DocumentIndexStatus` represents RAG/content indexing status. It is a distinct concept from `SourceDocumentState` and must not be merged into it.

`SourceDocument.java` distinguishes:

- `state` — Source document lifecycle (`SourceDocumentState`)
- `indexStatus` — RAG/content indexing status (`DocumentIndexStatus`)
- `indexReason` — reason for the current `indexStatus`

`DocumentIndexStatus` belongs to `com.sdv.source.domain`, not `com.sdv.rag.domain`. RAG may depend on the Source domain; Source must not depend on RAG. `com.sdv.rag.application`'s `ContentProcessingPolicy` may classify Source content into a `DocumentIndexStatus` value.

---

## 7.5 SourcePermission

Location:

`com.sdv.source.domain.SourcePermission`

Responsibility:

Normalized Source ACL model.

Source-specific permission objects must be normalized before use by the Policy layer.

---

## 7.6 SourcePrincipal

Location:

`com.sdv.source.domain.SourcePrincipal`

Record / Value Object.

Represents:

- user
- group
- domain
- anyone

Core fields:

- type
- value

---

# 8. Source Port

## DocumentSourceConnector

Location:

`com.sdv.source.application.port.DocumentSourceConnector`

This is the common boundary between SDV and document Sources.

Core operations:

```text
getMetadata(SourceDocumentId)
fetchContent(SourceDocumentId)
getPermissions(SourceDocumentId)
findChanges(SyncCursor)
```

Rules:

- Google SDK types must not be exposed through this interface.
- ~~Local Vault must use the same conceptual contract.~~ EXCLUDED / RETIRED at v1.5 (§2A.1, §11) — Local Vault is not an active implementation target.
- permission lookup failure must not silently become ALLOW.
- unknown permission information must propagate as UNKNOWN/failure and eventually Fail Closed.

Implementations / intended implementations:

- `GoogleDriveConnector` — the sole active Core implementation at v1.5 (§2A.1)
- ~~`LocalVaultConnector`~~ — EXCLUDED / RETIRED at v1.5 (§2A.1, §11), preserved for historical traceability only
- future `S3DocumentSourceConnector` — read-only only, per §2A.10 (never a writer of SDV-managed content)
- SharePoint skeleton

---

# 9. Source Token Boundary

## SourceTokenStore

Location:

`com.sdv.source.application.port.SourceTokenStore`

Purpose:

Safe OAuth token persistence boundary.

Conceptual methods:

```text
save(SourceId, TokenEnvelope)
load(SourceId)
delete(SourceId)
```

Rules:

- raw Refresh Token must not be logged.
- raw token must not be stored in general plaintext DB fields.
- missing token and decryption failure must be distinguishable.
- `source_connections.token_ref` is a token reference, not the raw token.

**M08 MVP OAuth backing-store decision (2026-09-13).** This section defines the
conceptual `SourceTokenStore` contract and rules above, but did not by itself specify
where encrypted token material is durably stored or how it is keyed — that was a real
gap (previously recorded as a `SPEC GAP / PRODUCT DECISION REQUIRED` in
`.claude-handoff/latest.md`, not a documentation-sync drift). The user has since
approved PostgreSQL ciphertext storage with an externally supplied master key. The full
implementation contract (storage schema, AES/GCM parameters, key handling, OAuth/PKCE
flow, refresh/disconnect rules, acceptance criteria) is recorded in the supplemental
document `docs/spec/SDV_M08_TOKEN_CONTRACT.md` — read it together with this section, not
as a replacement for it. The backing table is `source_oauth_tokens`, added by
`V006__zero_original_persistence.sql`'s immediate successor migration
`V007__oauth_token_store.sql` (V001–V007 are all applied; V001–V006 remain Immutable as
before, and V007 itself must not be edited once applied either). This decision does not
reopen or restate the storage topology/crypto defaults themselves as canonical
Excel-sourced facts — `SDV_M08_TOKEN_CONTRACT.md` is explicit that those details are
coordinator-selected implementation choices within the user's storage-backend
authorization, not claims copied from the approved v3.2 workbook.

---

# 10. Google Drive Boundary

Google Drive infrastructure location:

`com.sdv.source.infrastructure.google`

Do not create:

`com.sdv.source.infrastructure.external.googledrive`

unless the master specification is explicitly revised.

## GoogleDriveConnector

Implements:

`DocumentSourceConnector`

Responsibility:

Translate Source-neutral connector operations to Google Drive behavior.

Core operations:

- getMetadata
- fetchContent
- getPermissions
- findChanges

## GoogleDriveClient

Responsibility:

Low-level Google Drive API wrapper.

Owns technical calls around:

- files
- permissions
- changes
- export

Google SDK types should remain inside the infrastructure boundary.

## GoogleDrivePermissionAdapter

Responsibility:

Convert Google Permission representation into SDV `SourcePermission`.

## PrincipalResolver

Responsibility:

Map / compare Source Principal with authenticated SDV `UserContext`.

Ambiguous mapping must Fail Closed.

## GoogleDriveContentAdapter

Responsibility:

Fetch / export Google Docs, Slides, and allowed file types.

Content must only be fetched after required permission/policy checks.

## Google OAuth

Google Drive is read-only first.

Refresh Tokens must be safely stored.

Raw token values must not appear in:

- normal logs
- Kafka events
- source_connections plaintext fields

---

# 11. Local Vault — EXCLUDED / RETIRED (v1.5)

> **EXCLUDED / RETIRED at v1.5 (§2A.1).** SDV provides no Local Vault, file-upload repository, original-file replica, or other SDV-managed original-content store. `/api/vault/*` is not an active API (§32). This section is preserved below verbatim, unmodified, only as a historical/traceability record of the pre-v1.4 design — it does not describe an active Core capability. Related Local Vault File Manifest IDs (`F-BE-048`–`F-BE-061`, `F-FE-012`) are retired and must never be reused for a different purpose. The old Vault test meaning of `F-TST-007` is retired, while v1.5 assigns that ID to the canonical `ZeroOriginalPersistenceE2ETest`. Do not implement, extend, or reactivate anything in this section.

Local Vault is a Source, not a replacement enterprise file management system.

Scope is intentionally limited.

Core:

- one-level Collection
- upload
- encrypted local storage
- SHA-256 integrity
- Source Document creation
- deletion
- same RAG permission flow as other Sources

Core document types:

- PDF
- DOCX
- TXT

## Encryption

Persistent raw content must use:

AES-256-GCM

## Integrity

Use:

SHA-256

If integrity verification fails:

- content fetch denied
- indexing denied
- RAG denied

Storage path must never be directly exposed as a public/static URL.

Original filename must not be used as the physical storage key.

---

# 12. Persistence

Persistence technology:

Spring Data JPA.

Do not use MyBatis XML Mapper.

Schema is managed only by Flyway.

Hibernate automatic schema generation must not create/update production schema.

Applied migration files must never be edited.

Use new migration versions for new schema changes.

## Migration responsibility

`V001__baseline.sql`

Core relational schema.

`V002__pgvector.sql`

pgvector / document chunk vector-related migration scope.

Actual applied migration state takes precedence over speculative Entity changes.

---

# 13. Important Source Persistence Files

Official v3.2 files:

```text
com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity
com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity
com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity
com.sdv.source.infrastructure.persistence.entity.SourceSyncCursorEntity

com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository
com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository
com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository
com.sdv.source.infrastructure.persistence.repository.SourceSyncCursorJpaRepository

com.sdv.source.infrastructure.persistence.mapper.SourcePersistenceMapper
```

`SourcePersistenceMapper` responsibility:

Domain ↔ JPA Entity conversion.

Official methods are conceptually:

```text
toDomain
toEntity
```

Do not place it directly in:

`com.sdv.source.infrastructure.persistence`

The official package includes `.mapper`.

---

# 14. Important Spec Gap Rule for Source Persistence

The v3.2 file manifest explicitly defines:

- `SourceConnection`
- `SourceConnectionService`
- `SourceConnectionJpaRepository`
- `SourcePersistenceMapper`

It does NOT currently define these classes:

```text
SourceConnectionRepository
SourceConnectionPersistenceAdapter
```

Therefore:

DO NOT create either class solely because a generic Ports/Adapters architecture would normally contain them.

If implementation shows that an additional persistence Port/Adapter is necessary:

1. report it as a specification gap,
2. explain why the existing v3.2 structure is insufficient,
3. propose the exact path and responsibility,
4. obtain approval,
5. update the specification / ADR before implementation.

Architecture patterns must not override the explicit v3.2 file manifest.

---

# 15. Source Application Layer

## SourceConnectionService

Location:

`com.sdv.source.application.SourceConnectionService`

Responsibility:

Source registration / disconnection Use Case.

Official operations:

```text
create
disconnect
list
```

Related features:

- SRC-002
- SRC-010

Source disconnect must:

- deactivate the Source
- prevent new Sync
- prevent new Retrieval
- remove/revoke stored token access
- preserve existing Audit history

## SourceConnectorRegistry

Location:

`com.sdv.source.application.SourceConnectorRegistry`

Responsibility:

Resolve:

`SourceType → DocumentSourceConnector`

Official operation:

```text
getConnector()
```

Service code should not contain repeated:

```java
if (type == GOOGLE_DRIVE) ...
else if (type == LOCAL_VAULT) ...
```

for Source implementation selection.

---

# 16. API Boundary

JPA Entity must never be used as API Response.

Official Source flow:

```text
HTTP
 ↓
SourceAdminController
 ↓
SourceConnectionService
 ↓
Domain / Connector / Repository
 ↓
Persistence or external Source
```

Source DTOs:

- `CreateSourceRequest`
- `SourceResponse`

API mapper:

`com.sdv.source.api.mapper.SourceApiMapper`

Responsibility:

DTO ↔ Domain conversion.

Persistence mapper and API mapper are separate concepts.

```text
External Source data
        ↓
Google/Local Adapter
        ↓
Domain
        ↓
SourcePersistenceMapper
        ↓
JPA Entity
```

and:

```text
HTTP DTO
   ↓
SourceApiMapper
   ↓
Domain
```

must not be merged into one mapper.

---

# 17. Permission Model

Source permissions refer to the verified PUBLISHER's authority over the exact original, not a requirement that the recipient already has native Google rights. SDV share recipients/actions/classification/overlay are the REQUESTER's authority. An owner-private metadata picker is a distinct scope, not public discovery or AI permission.
A stale/unknown publisher permission, unavailable connection, unpublished/blocked share, wrong provider identity or generation mismatch fails closed. ADMIN cannot bypass these rules to access content.

# 18. Effective Permission

```text
Authenticated requester
AND explicit published share + named recipient + requested action
AND classification / SDV overlay / no administrative block
AND publisher's verified source authority
AND current connection / file / share generations and version
AND (for AI) AI usage policy + supported verified evidence
```

SecurityLevel is PUBLIC/INTERNAL/CONFIDENTIAL/SECRET. PUBLIC does not imply any audience. Unknown classification is safe denial, not an invented fifth enum.

# 19. Security Invariants

These rules cannot be bypassed for implementation convenience.

## INV-SRC-001

SDV must not expand a publisher's denied Source permission into ALLOW. Recipient content access additionally requires an explicit SDV share/action grant. B need not possess native Google permission; only server-bound A delegation is permitted.

## INV-SRC-002

Stale or untrusted Source ACL must Fail Closed.

Failure result:

DENY.

## INV-SRC-003

Deleted Source documents must disappear from:

- Search
- RAG
- new Citation candidates

## INV-SRC-004

Before metadata/evidence/download release, revalidate requester SDV grant and publisher's exact file-bound source access/version and share/connection generations. Unknown/error means denial. This ID disambiguates the workbook's former duplicate INV-SRC-003 live-check row; INV-SRC-003 retains deletion semantics.

## INV-RAG-001

Vector search must never run without:

- allowed document IDs, or
- an equivalent server-side permission boundary.

Unauthorized Chunk target:

```text
0
```

## INV-RAG-002

Never trust client-supplied:

- sourceId
- owner
- securityLevel

as proof of permission.

## INV-AI-001

No LLM Provider call before AI Usage Policy evaluation.

## INV-AI-002

External LLM is OFF by default.

LOCAL_ONLY and AI_DENIED documents must not be sent to External Provider.

## INV-AUD-001

Never record the following in normal logs:

- Access Token
- Refresh Token
- encryption key
- raw document content
- raw Prompt

## INV-AUD-002

Every important RAG answer must be reconstructable with traceId across:

```text
Permission
→ Retrieval
→ LLM Provider
→ Citation
```

## INV-SYN-001

Event/Sync processing must be idempotent.

Same eventId must not create duplicate:

- Chunk
- permission rows

## INV-SYN-002

If ACL state and Vector metadata are inconsistent:

temporarily deny RAG for that document.

## INV-VAU-001 — EXCLUDED / RETIRED (v1.5)

> Local Vault is excluded/retired at v1.5 (§2A.1, §11); this invariant is preserved for historical traceability only and no longer applies to an active Core path.

Vault storage path must not be exposed as a direct/static URL.

## INV-VAU-002 — EXCLUDED / RETIRED (v1.5)

> Local Vault is excluded/retired at v1.5 (§2A.1, §11); this invariant is preserved for historical traceability only and no longer applies to an active Core path.

Local Vault persistent content:

- AES-256-GCM encrypted
- SHA-256 verified

## INV-CAC-001

Cache failure must never widen permission.

## INV-RSK-001

Oversharing Detection does not automatically change Source permissions in MVP.

---

# 20. AI Usage Policy

Security levels:

```text
PUBLIC
INTERNAL
CONFIDENTIAL
SECRET
```

AI policy modes:

```text
LOCAL_ONLY
EXTERNAL_ALLOWED
AI_DENIED
```

External LLM default:

```text
OFF
```

`PolicyEnforcedLlmGateway` must enforce policy immediately before Provider invocation.

Application code should not directly bypass this gateway to invoke an LLM Provider.

---

# 21. RAG Security Flow

```text
OIDC requester B → explicit share/audience/action/policy gate
→ allowed share/file IDs → vector candidates (no text, not evidence)
→ server-bound publisher A source context → live pre-check
→ bounded transient fetch/parse → live post-check + generation fence
→ minimum encrypted volatile evidence → LLM → safe answer + verified citation
```

No candidate/evidence fallback outside authorized IDs. Sharing revocation, source disconnect or provider/version mismatch invalidates in-flight work. See §2A.5–6.

# 22. VectorSearchPort

Location:

`com.sdv.rag.application.port.VectorSearchPort`

Purpose:

Vector search inside an already-authorized document scope.

The Port must not permit an unrestricted search when allowed document IDs are missing.

**v1.5 clarification (§2A.4):** a result returned by this Port is a candidate shortlist only — it is neither permission proof nor answer evidence. Callers must not infer document contents from a Chunk/embedding hit alone, and must not skip Mandatory Live Retrieval (§2A.5) on the basis of a vector search result.

Implementation:

`PgVectorSearchAdapter`

---

# 23. Indexed Mode — SUPERSEDED at v1.4; retained in v1.5

> **SUPERSEDED at v1.4; retained in v1.5 — see §2A.11.** The content-storing description below (durable plaintext Chunks as the default Core RAG mode) is no longer the Core path. It is retained only as a historical record of the pre-v1.4 design. The v1.5 Core path is the **Embedding Candidate Index** (§2A.11): the same parse→chunk→embed pipeline runs, but only embedding vectors, generalized locators, source version, keyed digest/HMAC, and parser/embedding model version are persisted — no plaintext Chunk or extracted text is retained after indexing completes (§2A.2, §2A.3). M06/V005 introduced historical plaintext drift; M07A/V006 corrected it. Do not recreate those durable writers.

Indexed Mode (pre-v1.5 description, historical):

Source documents are:

1. synced,
2. parsed,
3. chunked,
4. embedded,
5. stored in pgvector,
6. searched only within permitted document scope.

A document is an eligible RAG retrieval candidate only when:

- the source document is `ACTIVE`, and
- its current content has `indexStatus = INDEXED`, and
- permission and policy checks pass before retrieval.

When Source version changes:

the index becomes `STALE` before reprocessing.

Unsupported or textless content must not produce chunks or embeddings.

The candidate-eligibility rule above (`ACTIVE` + `indexStatus = INDEXED` + permission/policy checks) still applies to the v1.5 Embedding Candidate Index — only the phrase "eligible RAG retrieval candidate" must now be read as "eligible **candidate for Mandatory Live Retrieval**" (§2A.5), never as "eligible to be answered directly from stored content."

---

# 24. Federated Mode — SUPERSEDED at v1.4; retained in v1.5

> **SUPERSEDED at v1.4; retained in v1.5 — see §2A.11.** This section previously framed live, zero-copy retrieval as "a later PoC, not the primary Core path." That framing is corrected: live, per-request, permission-verified retrieval (§2A.5) is now a **mandatory Core requirement**, not a deferred PoC. It was never true, and must never be stated, that live retrieval is only a later proof-of-concept — the statement below is preserved solely to show what was corrected.

Historical text (superseded, do not follow):

~~Federated Mode is a later PoC, not the primary Core path.~~

Purpose (the underlying zero-copy intent is now Core, not a PoC):

Zero-copy handling — now applied to **all** Core content-bearing requests, not only "selected sensitive documents."

Persistent storage of raw text/Chunks must not remain after the request. This requirement is now the Core-wide rule (§2A.3), not a Federated-Mode-only exception.

The distinctly-named class `FederatedRetrievalService` (File Manifest §30, deferred/extension list) is a separate class-naming matter and must not be read as meaning live retrieval itself remains deferred.

---

# 25. AI Service Responsibility

Python FastAPI service responsibilities:

- parsing
- chunking
- embedding
- indexing support
- health

Backend owns:

- authentication
- permission
- SDV policy
- allowed document calculation
- security decision

Python AI Service must not become the owner of authorization policy.

**v1.5 clarification (§2A.2, §2A.3):** chunking and embedding output must not be persisted as plaintext by the Backend. Only the resulting embedding vectors, generalized locators, source version, keyed digest/HMAC, and parser/embedding model/version may be written to the embedding-only index. Chunk text exists only transiently within a single indexing or live-retrieval request/response cycle.

---

# 26. Kafka and Sync

Kafka exists only where genuine asynchronous behavior is required.

Core cases:

- Source document change
- Source permission change
- indexing
- retry / DLQ

Do not introduce Kafka merely for portfolio appearance.

## Core event flow

```text
Source Change
    ↓
DB Transaction
metadata / permission
+
Outbox
    ↓
Commit
    ↓
Outbox Publisher
    ↓
Kafka
    ↓
Consumer
    ↓
Index / Policy metadata update
    ↓
Consistency verification
```

If processing permanently fails:

```text
Retry
 ↓
DLQ
 ↓
affected document RAG Fail Closed
```

---

# 27. Core Event Payload Rules

Events contain identifiers and versions, not raw document content.

Examples:

## SourceSyncRequested

```text
sourceId
mode
eventId
traceId
```

## SourceDocumentChanged

```text
sourceDocumentId
sourceVersion
eventId
traceId
```

## SourcePermissionChanged

```text
sourceDocumentId
permissionVersion
eventId
traceId
```

## SourceDocumentDeleted

```text
sourceDocumentId
eventId
traceId
```

## IndexRequested

```text
sourceDocumentId
sourceVersion
eventId
traceId
```

## IndexCompleted

```text
sourceDocumentId
chunkCount
embeddingModel
eventId
```

## IndexFailed

```text
sourceDocumentId
errorCode
retryCount
eventId
```

Raw content and raw token values are forbidden event fields.

---

# 28. Transactional Outbox

DB business state and event creation must be kept consistent.

Persistent state + Outbox event:

same DB transaction where applicable.

Kafka publication:

after DB commit.

Publisher must support retry of unpublished rows.

Consumer must tolerate duplicate delivery.

---

# 29. Audit

Audit is a product requirement, not simple application logging.

Important actions include:

- login
- Source connection
- Source disconnect
- Sync
- permission decision
- policy change
- search
- RAG
- document fetch
- Security Finding state change

Audit fields should include identifiers such as:

- actor
- action
- target
- result
- reasonCode
- traceId
- sanitized metadata
- timestamp

Do not include:

- token
- key
- raw document
- raw Prompt

RAG Audit must allow reconstruction:

```text
Source
→ Policy
→ Retrieval
→ LLM
→ Citation
```

---

# 30. Oversharing Detection

Core detection target:

broad Source sharing such as:

- anyone
- wide domain sharing

combined with high security classification.

Representative required evidence:

```text
SECRET + anyone → HIGH Finding
```

MVP behavior:

detect and report.

Do NOT automatically change the original Source permission.

---

# 31. Core Database Model

## source_connections

Role:

Source connection.

Fields:

```text
id
type
display_name
status
sync_mode
token_ref
last_sync_at
```

Raw Google token must not be stored.

## source_sync_cursors

```text
source_id
cursor
updated_at
```

One active cursor per Source.

## source_documents

```text
id
source_id
source_document_id
name
mime_type
source_version
modified_at
state
```

`source_id + source_document_id` must be unique.

## source_permissions

```text
id
document_id
principal_type
principal_value
permission
synced_at
```

Permission replacement must be transactionally safe.

## document_security_labels

```text
document_id
security_level
origin
source_label
```

Origin examples:

```text
SOURCE_MAPPING
MANUAL
```

## overlay_policies

Additional SDV restriction policy.

Must never be used to widen Source permission.

## ai_usage_policies

Controls Local / External AI use.

## local_vault_collections — EXCLUDED / RETIRED (v1.5)

> One-level Vault collection. **EXCLUDED / RETIRED at v1.5 (§2A.1, §11).** Preserved for historical/traceability reference only — not part of the active Core schema going forward.

## local_vault_objects — EXCLUDED / RETIRED (v1.5)

> Encrypted object metadata. **EXCLUDED / RETIRED at v1.5 (§2A.1, §11).** Preserved for historical/traceability reference only.

Do not use original filename as storage key.

## document_chunks / document_extracted_content (historical)

V002/V005 originally introduced content-bearing storage. M07A/V006 is the completed correction history; do not recreate normalized_text/plaintext chunks or edit those migrations.

## document_embedding_index

Content-free vector/locator/version/HMAC/parser/model records. SourceConnection, publication and generation checks must gate eligibility and final writes. Index readiness is independent of source lifecycle and share intent.

## document_shares (v1.5 planned, not yet migrated)

Publisher subject, source/file binding, classification, action grants, published flag, admin block and generation. Connection availability is separate. Persist settings through pause; explicit unshare removes active grants without allowing reconnect revival.

## document_share_recipients (v1.5 planned)

Named SDV subject IDs bound to one share; enforce uniqueness and tenant/account scoping where present. Empty recipients never mean all users. Owner mutation requires current generation; administrative restrictions cannot expand owner consent.

## Migration identity drift: F-INF-018

Consumer idempotency ledger still planned. Existing V004 is source account isolation; V001–V009 are historical files. Use the next actually unused migration version at implementation, not a second V004 or hardcoded reserved version.

## Encrypted ephemeral evidence (v1.5, §2A.6 — not a durable schema table)

Evidence used to answer a `FIND_CONTENT`/`SUMMARIZE`/`COMPARE`/`GROUNDED_ANALYSIS` request exists only in encrypted, non-durable storage scoped to an active conversation, with a hard TTL of at most 300 seconds and no sliding extension (`F-BE-206` `EphemeralEvidenceStore`, `F-BE-208` `EncryptedEphemeralEvidenceStore`). This is intentionally listed here to make explicit that it is **not** one of the persistent tables above and must never be backed up, snapshotted, or mounted on a durable volume.

## audit_logs

Security decision and activity audit.

## security_findings

Security risk findings.

No automatic remediation in MVP.

## outbox_events

DB → Kafka Outbox.

No raw document/token in payload.

## sync_runs

Sync operation history.

---

# 32. Core REST API Boundaries

Legacy/current and target APIs are distinguished below. Existing /api/admin/sources remains an owner-bound compatibility surface, not authority over others' private storage. Google authorize requires authenticated source ownership (including ordinary USER in the target); callback validates one-time state bound to owner/source/session and does not require a browser Authorization header.

Existing/previous Core endpoint inventory:

```text
POST   /api/admin/sources
GET    /api/admin/sources
DELETE /api/admin/sources/{id}

GET    /api/admin/sources/google/authorize
GET    /api/admin/sources/google/callback

POST   /api/admin/sources/{id}/sync

GET    /api/me

# EXCLUDED / RETIRED at v1.5 (§2A.1) — NOT active endpoints.
# A request to any /api/vault/* path must return 404 or an explicit
# unsupported result. Listed here only for historical traceability.
# GET    /api/vault/collections
# POST   /api/vault/collections
# POST   /api/vault/documents
# DELETE /api/vault/documents/{id}

POST   /api/rag/search
POST   /api/rag/ask

GET    /api/admin/policies/overlay
POST   /api/admin/policies/overlay
PUT    /api/admin/policies/ai
PUT    /api/admin/policies/label-mapping

GET    /api/admin/audits

GET    /api/admin/security/findings
PATCH  /api/admin/security/findings/{id}

GET    /api/admin/health

GET    /actuator/health
```

Entity direct responses are forbidden.

---

## v1.5 planned USER/share endpoints

- `GET /api/sources` — 본인 저장소 목록/등록; subject 소유권·기본 비공개.
- `POST /api/sources` — 본인 저장소 목록/등록; subject 소유권·기본 비공개.
- `DELETE /api/sources/{id}` — 본인 연결 중단; 신규 이용 차단·Token 폐기·공유 의도 보존; 영구 공유 삭제는 별도.
- `POST /api/sources/{id}/sync` — 본인 Metadata 갱신; 본인 Source·활성 연결; 공유/색인 자동 허용 없음.
- `GET /api/sources/{id}/files` — 비공개 Metadata 파일 선택기; 소유자만; AI/content fetch 없이 탐색; 토큰/타인 파일 미노출.
- `POST /api/shares` — 공유·수정·명시적 철회; 서버 권한·파일 바인딩·세대 검사; 관리자 차단은 게시자가 해제 불가.
- `PATCH /api/shares/{shareId}` — 공유·수정·명시적 철회; 서버 권한·파일 바인딩·세대 검사; 관리자 차단은 게시자가 해제 불가.
- `DELETE /api/shares/{shareId}` — 공유·수정·명시적 철회; 서버 권한·파일 바인딩·세대 검사; 관리자 차단은 게시자가 해제 불가.
- `GET /api/shares/{shareId}/download` — 허용 공유 파일 다운로드; 수신자 공유+DOWNLOAD+게시자 원본 pre/post 검증; no-store·비보관.
- `GET /api/admin/shares` — SDV 공유 자료 관리; 비공개 Drive·Token 제외; 관리 권한은 읽기/다운로드 우회 아님.
- `PATCH /api/admin/shares/{shareId}` — SDV 공유 자료 관리; 비공개 Drive·Token 제외; 관리 권한은 읽기/다운로드 우회 아님.
- `GET /api/shares` — 본인이 게시한 공유 설정 목록; 본인 게시자 범위; 중단 상태의 설정도 관리 가능.

Existing metadata discovery is `GET /api/rag/files`; extend it with requester share authorization, not an alternate /api/search/files endpoint. Typed search alone is not natural-language intent routing. Return DTOs, never persistence entities.

# 33. Local Development Baseline

Repository:

`C:\workspace\secure-document-rag-platform`

JDK:

Eclipse Temurin 21

Backend build:

Gradle Wrapper

Python:

3.12 virtual environment

Node:

24 LTS

Development infrastructure:

Docker Compose

Primary ports:

```text
Backend       8080
AI Service    8000
Frontend      5173
Keycloak      8180 → 8080
PostgreSQL    5432
Kafka         9092 / internal listener
Ollama        11434
```

Configuration files:

```text
application.yml
application-local.yml
application-compose.yml
```

Kubernetes/Cloud configuration is later scope.

---

# 34. Secrets

Real secret values must not be committed.

Sensitive examples:

- datasource password
- Google OAuth client secret
- Source token encryption key
- Vault master key
- external LLM key

Repository may contain:

- environment variable names
- placeholders
- `.env.example`

Repository must not contain:

- real passwords
- real Refresh Tokens
- real API keys
- real encryption keys

---

# 35. Core Completion Evidence

Mandatory user acceptance: §2A.15. A/B/C sharing, real ordinary-USER OIDC, natural-language file discovery and content answers, citations, SDV download, private isolation, revoke/disconnect/reconnect and wrong-account denial must all pass. M16B mock UI completion does not satisfy this gate.

Core is not complete merely because endpoints return 200.

Required security/portfolio evidence includes:

## Permission

Source permission removed:

subsequent RAG returns no unauthorized Chunk.

## RAG

Unauthorized Chunk:

```text
0
```

## Citation

Citation points to actual retrieved Source/file/page or section.

## No Evidence

Unsupported question returns:

```text
NO_EVIDENCE
```

instead of invented answer.

## External LLM

With external disabled:

network call count:

```text
0
```

## AI policy

LOCAL_ONLY / restricted document:

external call count:

```text
0
```

## Audit

Single traceId reconstructs:

```text
Policy
→ Retrieval
→ LLM
→ Citation
```

## Sensitive logs

Forbidden raw token/key/prompt/document patterns:

```text
0
```

## Zero Original/Plaintext Retention (v1.5, §2A.3 — replaces the pre-v1.5 "Vault" evidence item)

Persistent original file bytes, persistent complete extracted text, or persistent plaintext Chunks anywhere in the system (not only in a since-retired Local Vault, §11):

```text
0
```

**History:** the M06/V005 persistence defect was corrected by M07A/V006. Require regression evidence for the active no-original path; this documentation revision did not rerun it.

Encrypted ephemeral evidence hard TTL (§2A.6) never exceeded, never extended by reuse:

matches design.

## CI

A failing test or secret scan must block PR merge.

---

# 36. Explicit MVP Exclusions

Do not implement these to make the Core appear more advanced:

- Original storage is excluded from MVP. Old Local Vault IDs remain retired; approved post-MVP STO-001 is a separate design, not reactivation.
- persistent original file content, persistent complete extracted text, or persistent plaintext Chunks of any kind (v1.5, §2A.3)
- autonomous AI Agent
- Fine-tuning
- dedicated LLM training
- OCR
- AI automatic security classification
- Slack Connector
- Teams Connector
- Mail Connector
- full SharePoint integration
- full DLP
- full DSPM
- automatic Source permission remediation
- complex HR organization integration
- Multi-GPU ML platform
- Multi-tenant public SaaS
- complex Google Drive replacement UI
- ECS parallel deployment
- Multi-region / Active-Active

Kubernetes and AWS are later phases.

---

# 37. Agent Planning Rules

Before creating or modifying code:

1. Read this file.
2. Read `SDV_v3.2_FILE_MANIFEST.md`.
3. Inspect the actual Repository.
4. Inspect relevant Flyway migration.
5. Compare current code against the specification.
6. Present a Plan and exact file list.
7. Report specification conflicts.
8. Modify only approved scope.

Do not generate code based on generic architectural expectations when the v3.2 manifest provides an explicit structure.

---

# 38. No-Invention Rule

The following are examples of things Agents must not invent without approval:

- alternative package paths
- new Domain Repository interfaces
- new Persistence Adapter layers
- new Source type enum values
- Source status enum values not specified by v3.2
- new Flyway tables
- Kafka topics
- new external dependencies
- AWS components during Core
- alternate Google package hierarchy

If a pattern appears architecturally attractive but is absent from v3.2:

report it first.

---

# 39. Historical Source Slice and v1.5 Extension

The list below records the established owner-bound source slice. It is not sufficient for B-model USER sharing; planned F-BE-209–218 extend it without turning ADMIN into a private-drive superuser.

For the currently developed `source_connections` slice, the intended v3.2 pieces are:

```text
SourceType
SourceConnection
SourceConnectionService
SourceConnectorRegistry
SourceAdminController
CreateSourceRequest
SourceResponse
SourceApiMapper

SourceConnectionEntity
SourceConnectionJpaRepository
SourcePersistenceMapper
```

External Source interaction is handled separately by:

```text
DocumentSourceConnector
GoogleDriveConnector
```

(`LocalVaultConnector` is EXCLUDED / RETIRED at v1.5 — §2A.1, §11 — and is not part of the active implementation target.)

The Persistence Mapper handles:

```text
Domain ↔ JPA Entity
```

The Google/Local Adapter handles:

```text
Provider-specific representation ↔ SDV Source Domain
```

These are different boundaries.

Do not merge them.

---

# 40. Final Principle

SDV is not successful because it contains many technologies.

SDV is successful when it can prove:

```text
A requester may receive only explicitly shared/action-authorized content whose publisher still has verified Source authority; no arbitrary credential or admin bypass.
```

All architectural and implementation decisions should preserve that invariant.
