# Secure Document Vault v3.2 — Core Specification

> Agent-readable specification for Claude Code, Codex, and human reviewers.

## 1. Specification Metadata

- Project: Secure Document Vault (SDV)
- Specification Version: 3.2 (baseline), amended to v1.4 frozen architecture — see §2A
- Master Specification (baseline): `Secure_Document_Vault_v3.2_Cloud_상세기능_파일통합명세.xlsx`
- Master Specification (frozen amendment, current authoritative Excel): `SDV_v3.2_전체상세명세_MVP동결_v1.4_원본비보관_개정본.xlsx`
- Master Baseline Date: 2026-09-03; v1.4 freeze amendment applied 2026-09-12
- Current Development Scope: Phase 1 — 10-week Core MVP
- v1.4 inventory: 117 feature IDs, including 68 rows whose phase is `MVP`; 309 file IDs; 58 validation IDs; 34 constraints. These counts describe the master inventory and are not implementation-completion percentages.
- Architecture: Package-by-feature + Ports/Adapters hybrid
- Primary Backend: Java 21 + Spring Boot + Spring Data JPA
- Primary Database: PostgreSQL
- Migration: Flyway
- Core Source (v1.4): Google Drive only — the sole implemented Source and the system of record. Local Vault is **EXCLUDED / RETIRED** at v1.4 (historical function/IDs preserved for traceability only — see §2A.1, §11).
- Retention model (v1.4): no persistent original content, no persistent complete extracted text/plaintext chunks; only Metadata/ACL Catalog + embedding-only index + audit/operational state may be persisted — see §2A.2, §2A.3.
- Retrieval model (v1.4): every `FIND_CONTENT`/`SUMMARIZE`/`COMPARE`/`GROUNDED_ANALYSIS` request performs mandatory live retrieval against Google Drive as the current user — see §2A.5.
- Default LLM: Ollama / Local LLM
- External LLM: OFF by default

This Markdown file is an Agent-readable extraction of the v3.2 master specification, amended in place to reflect the v1.4 frozen architecture. Historical v3.2-only content (e.g. Local Vault, content-storing Indexed Mode) remains below only where explicitly marked superseded/excluded/retired — it does not describe an active Core capability.

It does not replace the original Excel master specification. Where this Markdown and the v1.4 Excel disagree, the v1.4 Excel governs (see §2 Precedence).

---

# 2. Precedence / Source of Truth

When planning, implementing, reviewing, or refactoring SDV, use the following precedence.

1. Latest user-provided v3.2 Excel master — currently the v1.4 frozen workbook named in §1
2. `docs/spec/SDV_v3.2_CORE_SPEC.md`
3. `docs/spec/SDV_v3.2_FILE_MANIFEST.md`
4. Applied Flyway migrations for the actual implemented database schema and migration immutability
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

# 2A. v1.4 Frozen Architecture Amendment — Zero Original/Plaintext Persistence + Mandatory Live Retrieval

This section is the authoritative summary of the v1.4 Excel freeze (`SDV_v3.2_전체상세명세_MVP동결_v1.4_원본비보관_개정본.xlsx`). It supersedes any conflicting statement elsewhere in this document. Sections below that predate v1.4 (§4, §7.1, §11, §21, §22, §23, §24, §31, §32) are marked superseded/excluded/retired inline and must be read together with this section, not on their own.

## 2A.1 Source and Retention

- Google Drive is the only implemented Source in the Core MVP and remains the system of record.
- SDV provides no Local Vault, file-upload repository, original-file replica, or other SDV-managed original-content store. §11 (Local Vault) is preserved below only as an **EXCLUDED / RETIRED** historical record — its file IDs (`F-BE-048`–`F-BE-061`, `F-FE-012`, related tests) must never be reused for a different purpose.
- `/api/vault/*` is not an active API. Such requests must return `404` or an explicit unsupported result — see §32.
- The no-original-retention rule applies uniformly to local, on-premises, cloud, and hybrid deployments (Phase 1 through Phase 3).

## 2A.2 Data That May Be Persisted

Only the following content-related data may be persisted:

- Metadata and ACL Catalog data (the synchronized `source_documents`/`source_permissions` model, §31).
- An embedding index containing no original or plaintext content: embedding vectors, generalized locators (§2A.7), source version, a keyed digest/HMAC, parser version, and embedding model/version.
- Audit and operational state containing no document, evidence, question, answer, or prompt body.

Embeddings are sensitive content-derived customer data. They require tenant isolation, customer-scoped encryption, access control, deletion, and reindexing rules — the same rigor as original content, not a lesser category.

## 2A.3 Data That Must Not Be Persisted (even encrypted, even long-term)

- Original file bytes or replicas; Google Workspace export files.
- Complete extracted text.
- `document_extracted_content.normalized_text`.
- Plaintext chunks such as `document_chunks.content`.
- Answer evidence text.
- Questions, answers, prompts, or document content in logs, Kafka events, DLQs, traces, caches, backups, snapshots, crash dumps, or retry payloads.

**Known implementation drift (as of `develop` `c96c885`, M06):** the current M06 implementation and the already-applied `V005__extracted_content.sql` migration persist `document_extracted_content.normalized_text` durably. This is recorded here as known drift, not as an acceptable exception. Correcting it requires a **future** `V006__zero_original_persistence.sql` migration plus an application change (see File Manifest `F-INF-020`). `V001`–`V005` are immutable and must never be edited to "fix" this — the correction is additive, in a new migration, per the Flyway Migration Immutability rule in `CLAUDE.md`/`AGENTS.md`.

## 2A.4 Metadata Search vs. Content Search

- Metadata search covers every Google Drive file type through the Metadata/ACL Catalog, followed by a live Drive check before returning each result.
- Content search, summarization, comparison, and analysis use the Catalog and the embedding-only index **only to shortlist** likely files and locations.
- An embedding hit is neither permission proof nor answer evidence — it may only identify where relevant content is likely to exist.
- Never infer document contents from metadata or embeddings alone.
- The ACL Catalog (synchronized `source_permissions`) is a fast prefilter only. The current-user Drive access check performed during live retrieval (§2A.5, step 3) is the final authorization decision — a synchronized ACL entry, a broad administrator's access, or a service account's access are never treated as final proof of the requesting user's access.

## 2A.5 Mandatory Live Retrieval

Every `FIND_CONTENT`, `SUMMARIZE`, `COMPARE`, and `GROUNDED_ANALYSIS` request must:

1. Shortlist candidates with the Metadata/ACL Catalog and the embedding-only index.
2. Use either the final user's OAuth credential or customer-approved domain-wide delegation impersonating that same user.
3. Before fetching, verify Drive access, `capabilities.canDownload`, `trashed`, `version`, and `modifiedTime` as that user.
4. Fetch only the current content of candidates that passed the check, through a bounded stream.
5. Parse untrusted content in an isolated parser and select only the evidence needed for the question.
6. Recheck the same user's access and the source version after the fetch.
7. Use evidence only when the pre-fetch and post-fetch versions match.
8. Generate the answer and citations only from that verified version.
9. Apply the ephemeral-evidence lifecycle (§2A.6).

If the current-user Drive check fails or returns `DENY`, `UNKNOWN`, deleted, trashed, non-downloadable, or otherwise unverifiable state: fail closed before sending content to a parser or LLM.

If the version changes during processing: discard the entire attempt and retry once. If it changes again: do not mix versions or guess — return `DOCUMENT_CHANGED`. If no verified evidence remains: return `NO_EVIDENCE`.

This flow is a Core MVP requirement, not a later PoC — see the superseded-mode note in §23/§24.

## 2A.6 Encrypted Ephemeral Evidence

- Evidence may exist only in encrypted ephemeral storage for an active conversation.
- Hard TTL: at most 300 seconds from the original evidence creation time; a shorter deployment value is allowed.
- Reads and reuse must not extend `expiresAt`. Sliding TTL is prohibited.
- Reuse is allowed only for the same conversation and user while current Drive access remains valid and the source version is unchanged; recheck current Drive access and version before every reuse.
- Delete evidence when the conversation closes, a request is cancelled, an error occurs, access is revoked, the version changes, or the hard TTL expires.
- A later question after deletion or expiry must fetch the source again.
- A successful answer does not require immediate deletion while the same conversation remains active, but the original hard TTL must never be extended.
- Never back up, snapshot, or mount this cache on a durable volume.

## 2A.7 File-Format Scope

- All Drive file types: Metadata/ACL search.
- Core content search and answers: PDF, DOCX, TXT, MD.
- Google Docs: transient DOCX or PDF export followed by immediate cleanup.
- XLSX: existing parser code is retained but disabled in the default Core path.
- PPTX, spreadsheets, images/OCR, audio, video, archives, and source code: Metadata-only in Core.
- Core citation locators: `PAGE`, `SECTION`, `LINE_RANGE`.
- Future locators (not Core): `SLIDE`, `SHEET_RANGE`.
- Image-only PDF: inspect transiently through the PDF path, perform no OCR in Core, record `SKIPPED_NO_TEXT`, and delete content-bearing intermediates.
- Large files may take longer; the UI must show clear waiting, asynchronous-processing, or bounded-failure status.
- The synchronous Google Drive `files.export` response is currently limited to 10 MB. For a larger Google Workspace document, use a supported long-running `files.download`/revision flow, or return `EXPORT_LIMIT_EXCEEDED`. Never process a partial export as a complete document, and never persist the export.

## 2A.8 Parser Security

- Maximum compression ratio: `100:1` (approved; do not reopen this decision).
- Maximum decompressed size: `200 MiB`.
- Preserve existing item-count, processing-time, row-count, and page-count limits.
- No parser network access.
- No external-resource resolution or XML external entities.
- No macro or script execution.
- Fail closed when content type is ambiguous or a bound is exceeded.

## 2A.9 AI Boundary

- Ollama/local LLM is the default; external LLM access is OFF by default.
- Send only the smallest live-verified evidence needed for the answer (from §2A.6's ephemeral store, never from a durable chunk/text table).
- Treat retrieved content as untrusted data, never as policy or system instructions (see the existing Prompt Injection rules in `CLAUDE.md`/`AGENTS.md`).
- Separate source facts from AI-generated analysis and recommendations.
- Attach citations only to claims supported by verified source evidence.
- The MVP LLM has no tool that can delete, move, upload, share, or modify files, permissions, sources, or policies.

## 2A.10 S3 and Object Storage

- `ObjectStoragePort`, `S3ObjectStorageAdapter` (File Manifest `F-BE-159`, `F-BE-160`) are **EXCLUDED / RETIRED** — their writer behavior conflicts with the global no-original-retention rule (§2A.1, §2A.3).
- Do not describe S3 as an SDV original-file, export, extracted-text, or evidence store anywhere in this specification.
- A future S3 integration may exist only as a read-only `DocumentSourceConnector` for customer-owned source data — never as a writer of SDV-managed content.

## 2A.11 Embedding Candidate Index + Google Drive Live Retrieval (replaces "Indexed Mode vs. Federated Mode")

The v3.2 "Indexed Mode" (content-storing: sync → parse → chunk → embed → store plaintext/vectors → search) described in §23, and "Federated Mode" (§24, previously framed as a later zero-copy PoC) are both **superseded** by a single Core model:

1. **Embedding Candidate Index** — Google Drive documents are parsed transiently, chunked, and embedded; only the embedding vectors, generalized locators, source version, keyed digest/HMAC, and parser/embedding model version are persisted (§2A.2). No plaintext chunk or extracted text is retained after indexing completes.
2. **Google Drive Live Retrieval** — every content-bearing request re-fetches and re-verifies the current Drive content as the requesting user before answering (§2A.5), using the embedding index only as a candidate shortlist (§2A.4).

The previous "Federated Mode" framing as a deferred/later PoC is corrected: live, per-request, permission-verified retrieval is now the mandatory Core path, not an optional future extension. (The distinctly-named class `FederatedRetrievalService`, File Manifest §30, remains a separate deferred/extension class identifier and must not be read as implying live retrieval itself is deferred — see the File Manifest note.)

## 2A.12 Development Status Snapshot (v1.4)

- M01 (V003 Content Processing Schema), M02 (Common/Operations/Audit), M03 (Keycloak Auth), M04 (Source Core — real Google Drive connector still pending), M05 (Policy/ACL Core), M06 (Content Processing/Parser Security) are complete on `develop` at `c96c885`.
- M06's plaintext/extracted-text persistence is known drift against v1.4 (§2A.3) — not yet corrected.
- Next: `V006` correction (`F-INF-020`) → real Google Drive Connector → embedding-only index → live retrieval and answer flow.
- The previous "M07 Local Vault" work item is retired — Local Vault is excluded at v1.4 (§2A.1, §11). It must not be described as the next task anywhere in this specification or in `CLAUDE.md`/`AGENTS.md`.

---

# 3. Project Definition

SDV is an Enterprise Secure RAG Gateway.

Its main purpose is to:

- connect existing enterprise document Sources,
- preserve Source permissions,
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

- Google Drive Source (sole implemented Source and system of record — v1.4, §2A.1)
- ~~Local Vault Source~~ — **EXCLUDED / RETIRED at v1.4** (§2A.1, §11); preserved historically only, never reused
- Source ACL synchronization
- Principal normalization
- Overlay Policy
- AI Usage Policy
- Permission-aware RAG via the Embedding Candidate Index + mandatory Google Drive Live Retrieval (v1.4, §2A.5, §2A.11) — not a durable content-storing "Indexed Mode"
- pgvector retrieval (embedding-only index — no persisted plaintext, §2A.2)
- Encrypted ephemeral evidence store with a hard TTL (v1.4, §2A.6)
- Citation from live-verified content only (v1.4, §2A.5)
- Kafka-backed asynchronous sync/indexing
- Transactional Outbox
- Retry / DLQ / Idempotency
- Audit
- Oversharing Detection
- ~~Local Vault encryption/integrity~~ — **EXCLUDED / RETIRED at v1.4** (§2A.1)
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
- `com.sdv.vault` — EXCLUDED / RETIRED at v1.4 (§2A.1, §11); listed for historical traceability only, not an active package to build against
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

- GOOGLE_DRIVE — the sole active Core implementation and system of record (v1.4, §2A.1).
- ~~LOCAL_VAULT~~ — **EXCLUDED / RETIRED at v1.4** (§2A.1, §11). The enum value itself may remain for historical/schema-compatibility traceability, but no new Local Vault functionality may be built or reactivated.

SharePoint and S3 are not full Core implementations. S3 in particular may only ever exist as a read-only `DocumentSourceConnector` (§2A.10) — never as a writer of SDV-managed content.

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

Standard representation of Source documents. At v1.4, Google Drive is the sole active Source (§2A.1); any Local Vault-origin rows are historical/retired only (§11), not a currently-populated Core path.

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
- ~~Local Vault must use the same conceptual contract.~~ EXCLUDED / RETIRED at v1.4 (§2A.1, §11) — Local Vault is not an active implementation target.
- permission lookup failure must not silently become ALLOW.
- unknown permission information must propagate as UNKNOWN/failure and eventually Fail Closed.

Implementations / intended implementations:

- `GoogleDriveConnector` — the sole active Core implementation at v1.4 (§2A.1)
- ~~`LocalVaultConnector`~~ — EXCLUDED / RETIRED at v1.4 (§2A.1, §11), preserved for historical traceability only
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

# 11. Local Vault — EXCLUDED / RETIRED (v1.4)

> **EXCLUDED / RETIRED at v1.4 (§2A.1).** SDV provides no Local Vault, file-upload repository, original-file replica, or other SDV-managed original-content store. `/api/vault/*` is not an active API (§32). This section is preserved below verbatim, unmodified, only as a historical/traceability record of the pre-v1.4 design — it does not describe an active Core capability. Related Local Vault File Manifest IDs (`F-BE-048`–`F-BE-061`, `F-FE-012`) are retired and must never be reused for a different purpose. The old Vault test meaning of `F-TST-007` is retired, while v1.4 assigns that ID to the canonical `ZeroOriginalPersistenceE2ETest`. Do not implement, extend, or reactivate anything in this section.

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

Final access decision:

```text
Source Permission
AND
SDV Overlay Policy
AND
Document State
AND
AI Usage Policy (when AI is requested)
```

Absolute rule:

```text
Source DENY → Final DENY
```

SDV Overlay Policy may restrict Source access.

It may never widen Source access.

Examples:

```text
Source ALLOW + Overlay ALLOW → potentially ALLOW
Source ALLOW + Overlay DENY  → DENY
Source DENY  + Overlay ALLOW → DENY
Source UNKNOWN               → DENY
Source STALE                 → DENY
```

---

# 18. Effective Permission

Central service:

`EffectivePermissionService`

Permission decisions must not be duplicated across:

- Controller
- Repository query logic
- RAG Controller
- Google Drive adapter

EffectivePermissionService combines:

- Source Permission
- Overlay Policy
- Document State

If ACL freshness cannot be trusted:

Fail Closed.

**v1.4 clarification (§2A.4):** the synchronized ACL Catalog that `EffectivePermissionService` reads is a fast prefilter, not the final authorization decision for content-bearing requests. For `FIND_CONTENT`/`SUMMARIZE`/`COMPARE`/`GROUNDED_ANALYSIS`, the current-user Google Drive access check performed during Mandatory Live Retrieval (§2A.5, step 3 and step 6) is the final authorization decision — a synchronized ACL entry alone must never be treated as sufficient proof to release content to a parser or LLM.

---

# 19. Security Invariants

These rules cannot be bypassed for implementation convenience.

## INV-SRC-001

Source DENY cannot become SDV ALLOW.

Failure result:

DENY.

## INV-SRC-002

Stale or untrusted Source ACL must Fail Closed.

Failure result:

DENY.

## INV-SRC-003

Deleted Source documents must disappear from:

- Search
- RAG
- new Citation candidates

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

## INV-VAU-001 — EXCLUDED / RETIRED (v1.4)

> Local Vault is excluded/retired at v1.4 (§2A.1, §11); this invariant is preserved for historical traceability only and no longer applies to an active Core path.

Vault storage path must not be exposed as a direct/static URL.

## INV-VAU-002 — EXCLUDED / RETIRED (v1.4)

> Local Vault is excluded/retired at v1.4 (§2A.1, §11); this invariant is preserved for historical traceability only and no longer applies to an active Core path.

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

**Superseded by v1.4 §2A.5 (Mandatory Live Retrieval).** The flow below described only the pre-v1.4 permission-prefilter-then-vector-retrieval shape and is retained for historical continuity of the permission-filtering principle ("filter before retrieval, never after"), which remains true. It is no longer sufficient on its own: an embedding/Chunk hit is a candidate shortlist only (§2A.4), not proof of current permission or evidence — every content-bearing request must additionally perform the full live Drive verify-fetch-verify cycle before evidence reaches the LLM.

Pre-v1.4 conceptual flow (candidate shortlisting stage only):

```text
Authenticated User
        ↓
Server UserContext
        ↓
EffectivePermissionService (ACL Catalog prefilter — not final proof, §2A.4)
        ↓
Allowed Document IDs
        ↓
VectorSearchPort
        ↓
Candidate Chunks (embedding-only; not evidence, §2A.4)
```

v1.4 Mandatory Live Retrieval continuation (required before any evidence reaches an LLM — full detail in §2A.5):

```text
Candidate Chunks (from above)
        ↓
Current-user OAuth / customer-approved domain-wide delegation
        ↓
Pre-fetch Drive check (access, canDownload, trashed, version, modifiedTime)
        ↓ (fail closed on DENY/UNKNOWN/deleted/trashed/non-downloadable)
Bounded fetch of current content
        ↓
Isolated parser — select only needed evidence
        ↓
Post-fetch recheck (access + source version)
        ↓ (version mismatch → discard + retry once → still mismatched → DOCUMENT_CHANGED; no evidence → NO_EVIDENCE)
Encrypted ephemeral evidence store (hard TTL ≤ 300s, §2A.6)
        ↓
AI Usage Policy
        ↓
PolicyEnforcedLlmGateway
        ↓
LLM
        ↓
CitationAssembler (citations from verified evidence only)
        ↓
Audit
```

Forbidden flow:

```text
Vector search all documents
        ↓
Send to LLM
        ↓
Remove unauthorized results afterward
```

Also forbidden (v1.4): treating a candidate Chunk/embedding hit, or a synchronized ACL entry, as sufficient evidence/permission proof to skip the live Drive verify-fetch-verify cycle.

Permission filtering must happen before Retrieval, and live access/version verification must happen immediately before and after content fetch.

---

# 22. VectorSearchPort

Location:

`com.sdv.rag.application.port.VectorSearchPort`

Purpose:

Vector search inside an already-authorized document scope.

The Port must not permit an unrestricted search when allowed document IDs are missing.

**v1.4 clarification (§2A.4):** a result returned by this Port is a candidate shortlist only — it is neither permission proof nor answer evidence. Callers must not infer document contents from a Chunk/embedding hit alone, and must not skip Mandatory Live Retrieval (§2A.5) on the basis of a vector search result.

Implementation:

`PgVectorSearchAdapter`

---

# 23. Indexed Mode — SUPERSEDED at v1.4

> **SUPERSEDED at v1.4 — see §2A.11.** The content-storing description below (durable plaintext Chunks as the default Core RAG mode) is no longer the Core path. It is retained only as a historical record of the pre-v1.4 design. The v1.4 Core path is the **Embedding Candidate Index** (§2A.11): the same parse→chunk→embed pipeline runs, but only embedding vectors, generalized locators, source version, keyed digest/HMAC, and parser/embedding model version are persisted — no plaintext Chunk or extracted text is retained after indexing completes (§2A.2, §2A.3). The current M06/V005 implementation still persists `normalized_text` and (if present) `document_chunks.content`; this is documented known drift requiring `V006` (§2A.3), not an accepted exception to this rule.

Indexed Mode (pre-v1.4 description, historical):

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

The candidate-eligibility rule above (`ACTIVE` + `indexStatus = INDEXED` + permission/policy checks) still applies to the v1.4 Embedding Candidate Index — only the phrase "eligible RAG retrieval candidate" must now be read as "eligible **candidate for Mandatory Live Retrieval**" (§2A.5), never as "eligible to be answered directly from stored content."

---

# 24. Federated Mode — SUPERSEDED at v1.4

> **SUPERSEDED at v1.4 — see §2A.11.** This section previously framed live, zero-copy retrieval as "a later PoC, not the primary Core path." That framing is corrected: live, per-request, permission-verified retrieval (§2A.5) is now a **mandatory Core requirement**, not a deferred PoC. It was never true, and must never be stated, that live retrieval is only a later proof-of-concept — the statement below is preserved solely to show what was corrected.

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

**v1.4 clarification (§2A.2, §2A.3):** chunking and embedding output must not be persisted as plaintext by the Backend. Only the resulting embedding vectors, generalized locators, source version, keyed digest/HMAC, and parser/embedding model/version may be written to the embedding-only index. Chunk text exists only transiently within a single indexing or live-retrieval request/response cycle.

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

## local_vault_collections — EXCLUDED / RETIRED (v1.4)

> One-level Vault collection. **EXCLUDED / RETIRED at v1.4 (§2A.1, §11).** Preserved for historical/traceability reference only — not part of the active Core schema going forward.

## local_vault_objects — EXCLUDED / RETIRED (v1.4)

> Encrypted object metadata. **EXCLUDED / RETIRED at v1.4 (§2A.1, §11).** Preserved for historical/traceability reference only.

Do not use original filename as storage key.

## document_chunks (V002, applied legacy drift)

Applied `V002__pgvector.sql` created this legacy RAG table with document relationship, page/section metadata, `content TEXT NOT NULL`, vector, and source version. V001–V005 are immutable, so this historical definition must not be rewritten as if it were the v1.4 target.

The plaintext `content` column violates §2A.3. Future `V006__zero_original_persistence.sql` (`F-INF-020`) and the corresponding application changes must remove or disable this legacy content-bearing persistence path. No new writer may target it.

## document_embedding_index (v1.4 target; future V006)

Content-free embedding candidate index represented by `DocumentEmbeddingEntity` (`F-BE-106`) and `DocumentEmbeddingJpaRepository` (`F-BE-107`). The target model contains `id`, `document_id`, `chunk_index`, `locator_type`, `locator_value`, `embedding`, `source_version`, `content_hmac`, `parser_version`, `embedding_model`, and `indexed_at`. It contains no original bytes, complete extracted text, plaintext chunk, or reversible content field.

Generation replacement is atomic and scoped by document/source version, parser version, and embedding model/version. Account/tenant isolation and document-scoped server-side permission filtering remain mandatory. An index hit is only a candidate pointer and never permission proof or answer evidence.

## document_extracted_content (V005, already applied)

Extraction claim/publish state used by M06 Content Processing (`ContentExtractionService`): per-document exclusive processing claim (`attempt_id`, `attempt_started_at`), published parser output (`parser_name`, `parser_version`, `normalization_version`, `locations`), and `normalized_text`.

**Known drift (v1.4, §2A.3):** `normalized_text` is complete extracted text persisted durably — this directly conflicts with the v1.4 zero-plaintext-persistence rule. `V005__extracted_content.sql` is an already-applied, immutable migration and must never be edited to remove this column. The correction is a **future**, additive `V006__zero_original_persistence.sql` migration (`F-INF-020`) plus an application change to `ContentExtractionService`, moving durable persistence to the content-free `document_embedding_index` target above and keeping full extracted text transient only. Selected answer evidence may enter the distinct encrypted volatile store under §2A.6, but raw files and full extracted text may not.

## Migration identity drift: F-INF-018

The v1.4 Excel row `F-INF-018` still names `V004__consumer_idempotency.sql`, while the repository has already applied `V004__source_account_isolation.sql`. A second V004 must never be created and the applied migration must never be renamed or edited. Preserve the `F-INF-018` processed-event idempotency responsibility, then assign it a verified unused migration version after V006 during M09 and update the public manifest transparently. The final replacement filename is intentionally unresolved until actual migration history is inspected at implementation time.

## Encrypted ephemeral evidence (v1.4, §2A.6 — not a durable schema table)

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

Important Core endpoints include:

```text
POST   /api/admin/sources
GET    /api/admin/sources
DELETE /api/admin/sources/{id}

GET    /api/admin/sources/google/authorize
GET    /api/admin/sources/google/callback

POST   /api/admin/sources/{id}/sync

GET    /api/me

# EXCLUDED / RETIRED at v1.4 (§2A.1) — NOT active endpoints.
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

## Zero Original/Plaintext Retention (v1.4, §2A.3 — replaces the pre-v1.4 "Vault" evidence item)

Persistent original file bytes, persistent complete extracted text, or persistent plaintext Chunks anywhere in the system (not only in a since-retired Local Vault, §11):

```text
0
```

**Known current exception (must be resolved by `V006`, not accepted as passing evidence):** `document_extracted_content.normalized_text` (V005, already applied) is currently non-zero. Core Completion cannot be claimed on this criterion until the `V006` correction (`F-INF-020`, §2A.3) lands.

Encrypted ephemeral evidence hard TTL (§2A.6) never exceeded, never extended by reuse:

matches design.

## CI

A failing test or secret scan must block PR merge.

---

# 36. Explicit MVP Exclusions

Do not implement these to make the Core appear more advanced:

- Local Vault / SDV-managed original-content store of any kind (EXCLUDED / RETIRED at v1.4, §2A.1, §11) — do not implement, extend, or reactivate
- persistent original file content, persistent complete extracted text, or persistent plaintext Chunks of any kind (v1.4, §2A.3)
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

# 39. Current Source Slice Interpretation

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

(`LocalVaultConnector` is EXCLUDED / RETIRED at v1.4 — §2A.1, §11 — and is not part of the active implementation target.)

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
The user cannot retrieve or send to AI any document that the Source and SDV policy do not permit.
```

All architectural and implementation decisions should preserve that invariant.
