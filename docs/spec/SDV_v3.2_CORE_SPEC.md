# Secure Document Vault v3.2 — Core Specification

> Agent-readable specification for Claude Code, Codex, and human reviewers.

## 1. Specification Metadata

- Project: Secure Document Vault (SDV)
- Specification Version: 3.2
- Master Specification: `Secure_Document_Vault_v3.2_Cloud_상세기능_파일통합명세.xlsx`
- Master Baseline Date: 2026-09-03
- Current Development Scope: Phase 1 — 10-week Core MVP
- Architecture: Package-by-feature + Ports/Adapters hybrid
- Primary Backend: Java 21 + Spring Boot + Spring Data JPA
- Primary Database: PostgreSQL
- Migration: Flyway
- Core Sources: Google Drive + Local Vault
- Default LLM: Ollama / Local LLM
- External LLM: OFF by default

This Markdown file is an Agent-readable extraction of the v3.2 master specification.

It does not replace the original Excel master specification.

---

# 2. Precedence / Source of Truth

When planning, implementing, reviewing, or refactoring SDV, use the following precedence.

1. `docs/spec/SDV_v3.2_CORE_SPEC.md`
2. `docs/spec/SDV_v3.2_FILE_MANIFEST.md`
3. Applied Flyway migrations for the actual implemented database schema
4. `CLAUDE.md` / `AGENTS.md` working rules
5. Existing implementation
6. README and older documents
7. Agent assumptions

If an existing implementation conflicts with the v3.2 specification, do not assume the implementation is correct.

Report the conflict before changing architecture.

If the specification already defines a class name, interface name, package path, enum value, responsibility, or phase, do not invent an alternative.

If something is not defined in this specification, do not silently infer it as an official v3.2 requirement.

Mark it as:

`SPEC GAP / DESIGN DECISION REQUIRED`

and report it before implementation.

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

- Google Drive Source
- Local Vault Source
- Source ACL synchronization
- Principal normalization
- Overlay Policy
- AI Usage Policy
- Permission-aware RAG
- pgvector retrieval
- Citation
- Kafka-backed asynchronous sync/indexing
- Transactional Outbox
- Retry / DLQ / Idempotency
- Audit
- Oversharing Detection
- Local Vault encryption/integrity
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
- `com.sdv.vault`
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

- GOOGLE_DRIVE
- LOCAL_VAULT

SharePoint and S3 are not full Core implementations.

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

Standard representation of both external and Local Vault documents.

Key concepts:

- id
- name
- MIME type
- source version
- state

Source-specific Google SDK objects must not leak into this class.

---

## 7.4 SourceDocumentState

Location:

`com.sdv.source.domain.SourceDocumentState`

Official values:

```java
SYNCED
READY
STALE
DELETED
FAILED
```

State transitions must not allow deleted/stale/failed documents to accidentally remain valid RAG candidates.

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
- Local Vault must use the same conceptual contract.
- permission lookup failure must not silently become ALLOW.
- unknown permission information must propagate as UNKNOWN/failure and eventually Fail Closed.

Implementations / intended implementations:

- `GoogleDriveConnector`
- `LocalVaultConnector`
- future `S3DocumentSourceConnector`
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

# 11. Local Vault

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

## INV-VAU-001

Vault storage path must not be exposed as a direct/static URL.

## INV-VAU-002

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

Required conceptual flow:

```text
Authenticated User
        ↓
Server UserContext
        ↓
EffectivePermissionService
        ↓
Allowed Document IDs
        ↓
VectorSearchPort
        ↓
Allowed Chunks only
        ↓
AI Usage Policy
        ↓
PolicyEnforcedLlmGateway
        ↓
LLM
        ↓
CitationAssembler
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

Permission filtering must happen before Retrieval.

---

# 22. VectorSearchPort

Location:

`com.sdv.rag.application.port.VectorSearchPort`

Purpose:

Vector search inside an already-authorized document scope.

The Port must not permit an unrestricted search when allowed document IDs are missing.

Implementation:

`PgVectorSearchAdapter`

---

# 23. Indexed Mode

Indexed Mode is the default Core RAG mode.

Source documents are:

1. synced,
2. parsed,
3. chunked,
4. embedded,
5. stored in pgvector,
6. marked READY,
7. searched only within permitted document scope.

When Source version changes:

re-index.

Only valid/READY documents should become RAG candidates.

---

# 24. Federated Mode

Federated Mode is a later PoC, not the primary Core path.

Purpose:

Zero-copy handling for selected sensitive documents.

Persistent storage of raw text/Chunks must not remain after the request.

Do not allow Federated Mode work to delay the Indexed Mode Core MVP.

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

## local_vault_collections

One-level Vault collection.

## local_vault_objects

Encrypted object metadata.

Do not use original filename as storage key.

## document_chunks

RAG Chunk + vector.

Contains document relationship and retrieval metadata.

Permission enforcement is document-scoped server-side.

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

GET    /api/vault/collections
POST   /api/vault/collections
POST   /api/vault/documents
DELETE /api/vault/documents/{id}

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

## Vault

Persistent plaintext original:

```text
0
```

Integrity hash:

matches.

## CI

A failing test or secret scan must block PR merge.

---

# 36. Explicit MVP Exclusions

Do not implement these to make the Core appear more advanced:

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
LocalVaultConnector
```

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
