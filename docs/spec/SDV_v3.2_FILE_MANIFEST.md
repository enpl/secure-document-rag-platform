# Secure Document Vault v3.2 — Core File Manifest

> Agent-readable file manifest extracted from the SDV v3.2 master specification.

## 1. Purpose

This file defines canonical:

- package paths
- file names
- file types
- primary responsibilities
- major methods/content
- related feature IDs
- phase boundaries

for the SDV Core MVP.

Agents must not invent an alternative file layout where this manifest already defines one.

The original master remains:

`Secure_Document_Vault_v3.2_Cloud_상세기능_파일통합명세.xlsx`

**v1.4 frozen amendment (current authoritative Excel):** `SDV_v3.2_전체상세명세_MVP동결_v1.4_원본비보관_개정본.xlsx` — Google Drive as sole system of record, no persistent original/plaintext content, Metadata/ACL Catalog + embedding-only index, mandatory live retrieval per request. Full contract detail lives in `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A. This manifest is amended below to align existing IDs with that contract and to add the small number of new files it introduces (§37). All existing IDs are preserved unchanged and the manifest is not renumbered.

The v1.4 master inventory contains 117 feature IDs, including 68 rows whose phase is `MVP`, 309 file IDs, 58 validation IDs, and 34 constraints. These are inventory counts, not implementation completion percentages.

---

# 2. Interpretation Rules

Exact path and filename in this manifest take precedence over generic architectural conventions.

A file not present in this manifest is not automatically forbidden, but it is not an approved v3.2 file merely because an Agent believes the architecture normally requires it.

For an additional architecture file:

```text
SPEC GAP / DESIGN DECISION REQUIRED
```

must be reported before implementation.

Do not move an existing canonical file merely to match a preferred architecture style.

---

# 3. Important Current Spec-Gap Warning

The v3.2 master does NOT currently define:

```text
SourceConnectionRepository.java
SourceConnectionPersistenceAdapter.java
```

as separate files.

Do not create them automatically.

The v3.2 source connection structure explicitly contains:

```text
SourceConnection
SourceConnectionService
SourceConnectionJpaRepository
SourcePersistenceMapper
```

If a separate persistence Port/Adapter is proposed later, it requires an explicit architecture decision and specification update.

---

# 4. Backend Bootstrap / Common

| ID | Canonical Path | Type | Responsibility | Core Content | Feature |
|---|---|---|---|---|---|
| F-BE-001 | `backend/src/main/java/com/sdv/SecureDocumentVaultApplication.java` | Java Application | Spring Boot entry point | `main()` | INS-001 |
| F-BE-002 | `backend/src/main/java/com/sdv/common/config/SecurityConfig.java` | Config | OIDC/JWT protection and ADMIN API separation | SecurityFilterChain, JwtDecoder | AUT-001, AUT-003 |
| F-BE-003 | `backend/src/main/java/com/sdv/common/config/KafkaConfig.java` | Config | Kafka producer/consumer common config | producerFactory, consumerFactory | SYN-005~007 |
| F-BE-004 | `backend/src/main/java/com/sdv/common/config/JacksonConfig.java` | Config | JSON serialization rules | ObjectMapper configuration | INS-001 |
| F-BE-005 | `backend/src/main/java/com/sdv/common/config/WebConfig.java` | Config | CORS/time/common web rules | WebMvcConfigurer | INS-001 |
| F-BE-006 | `backend/src/main/java/com/sdv/common/security/CurrentUserProvider.java` | Component | Trusted UserContext from JWT | `getCurrentUser()` | AUT-002 |
| F-BE-007 | `backend/src/main/java/com/sdv/common/security/JwtAuthenticationConverter.java` | Component | Keycloak role/group claim conversion | `convert()` | AUT-001, AUT-002 |
| F-BE-008 | `backend/src/main/java/com/sdv/common/model/UserContext.java` | Record/VO | Immutable current-user data | subject, email, roles, groups | AUT-002, AUT-004 |
| F-BE-009 | `backend/src/main/java/com/sdv/common/model/Role.java` | Enum | Application roles | USER, ADMIN | AUT-003 |
| F-BE-010 | `backend/src/main/java/com/sdv/common/exception/GlobalExceptionHandler.java` | Advice | Standard API errors | `handle*()` | POL-008, INS-003 |
| F-BE-011 | `backend/src/main/java/com/sdv/common/dto/ApiErrorResponse.java` | DTO | Standard error response | code, message, traceId | POL-008 |
| F-BE-012 | `backend/src/main/java/com/sdv/common/trace/TraceIdFilter.java` | Filter | HTTP traceId generation/propagation | `doFilterInternal()` | OPS-001 |
| F-BE-013 | `backend/src/main/java/com/sdv/common/logging/SensitiveLogFilter.java` | Utility | Sensitive log masking | `mask()` | AUD-004, OPS-002 |
| F-BE-128 | `backend/src/main/java/com/sdv/common/config/SecretProperties.java` | ConfigurationProperties | OAuth/Vault secret references | validated properties | INS-002, SRC-003, VAU-003 |

---

# 5. Admin / Health

| ID | Canonical Path | Type | Responsibility | Core Content | Feature |
|---|---|---|---|---|---|
| F-BE-014 | `backend/src/main/java/com/sdv/admin/api/AdminHealthController.java` | Controller | Admin dependency health API | `getHealth()` | INS-003, OPS-006 |
| F-BE-015 | `backend/src/main/java/com/sdv/admin/application/DependencyHealthService.java` | Service | DB/Kafka/Keycloak/AI/Source health aggregation | `checkAll()` | INS-003, OPS-006 |

---

# 6. Authentication

| ID | Canonical Path | Type | Responsibility | Core Content | Feature |
|---|---|---|---|---|---|
| F-BE-016 | `backend/src/main/java/com/sdv/auth/api/MeController.java` | Controller | Current user API | `getMe()` | AUT-002 |
| F-BE-017 | `backend/src/main/java/com/sdv/auth/api/dto/MeResponse.java` | DTO | Current user response | `from(UserContext)` | AUT-002 |

---

# 7. Source Domain

## F-BE-018 — SourceType

Path:

`backend/src/main/java/com/sdv/source/domain/SourceType.java`

Type:

Java Enum

Responsibility:

Canonical Source type definition.

Official values:

```java
GOOGLE_DRIVE
LOCAL_VAULT
SHAREPOINT
S3
```

Related:

SRC-001, SRC-002

Core rule (v1.4 — `CORE_SPEC.md` §2A.1):

Only Google Drive receives a full Core implementation and remains the system of record. ~~Local Vault~~ is EXCLUDED / RETIRED at v1.4 (§15 below) — the enum value may remain for schema/historical traceability only, never for new functionality.

---

## F-BE-019 — SourceConnection

Path:

`backend/src/main/java/com/sdv/source/domain/SourceConnection.java`

Type:

Java Domain

Responsibility:

Technology-independent Source connection model.

Official domain operations:

```text
activate
deactivate
```

Related:

SRC-002, SRC-010

Do not add JPA annotations.

Do not call Google APIs here.

---

## F-BE-020 — SourceDocument

Path:

`backend/src/main/java/com/sdv/source/domain/SourceDocument.java`

Type:

Java Domain

Responsibility:

Standard Source document model.

Representative content:

```text
id
name
mime
version
state
indexStatus
indexReason
```

Related:

SRC-004, SYN-002

---

## F-BE-021 — SourcePermission

Path:

`backend/src/main/java/com/sdv/source/domain/SourcePermission.java`

Type:

Java Domain

Responsibility:

Normalized Source ACL model.

Representative content:

```text
principal
permission
```

Related:

POL-001, SRC-005

---

## F-BE-022 — SourcePrincipal

Path:

`backend/src/main/java/com/sdv/source/domain/SourcePrincipal.java`

Type:

Java Record / VO

Responsibility:

Canonical Source principal.

Representative content:

```text
type
value
```

Principal categories include:

```text
user
group
domain
anyone
```

Related:

SRC-005, AUT-004

---

## F-BE-129 — SourceDocumentState

Path:

`backend/src/main/java/com/sdv/source/domain/SourceDocumentState.java`

Type:

Java Enum

Responsibility:

Source document lifecycle only, distinct from RAG/content indexing status (see `DocumentIndexStatus`, `F-BE-182`).

Official values:

```java
ACTIVE
DELETED
```

Related:

SRC-004, SYN-004, RAG-008

---

## F-BE-182 — DocumentIndexStatus

Path:

`backend/src/main/java/com/sdv/source/domain/DocumentIndexStatus.java`

Type:

Java Enum

Responsibility:

RAG/content indexing status, distinct from `SourceDocumentState` (`F-BE-129`).

Official values:

```java
PENDING
INDEXED
SKIPPED_UNSUPPORTED
SKIPPED_NO_TEXT
FAILED
STALE
```

`SourceDocument` (`F-BE-020`) distinguishes `state` (`SourceDocumentState`) from `indexStatus` (`DocumentIndexStatus`) and `indexReason` (reason for `indexStatus`).

`DocumentIndexStatus` belongs to `com.sdv.source.domain`; RAG may depend on Source, Source must not depend on RAG.

Related:

SRC-004, SYN-004, RAG-008

---

# 8. Source Application Ports

## F-BE-023 — DocumentSourceConnector

Path:

`backend/src/main/java/com/sdv/source/application/port/DocumentSourceConnector.java`

Type:

Java Interface

Responsibility:

Common external/Local Source contract.

Core methods:

```text
getMetadata
fetchContent
getPermissions
findChanges
```

Related:

SRC-001

Technology types from Google, AWS, JPA, or HTTP must not leak into this Port.

**v1.4 update (`CORE_SPEC.md` §2A.1, §2A.5):** the active Core implementation of this Port is `GoogleDriveConnector` only. `fetchContent` is invoked per-request for Mandatory Live Retrieval (bounded stream, pre/post version+access verification), not to populate a durable content store. A `LocalVaultConnector` implementation is EXCLUDED / RETIRED (§15).

---

## F-BE-024 — SourceTokenStore

Path:

`backend/src/main/java/com/sdv/source/application/port/SourceTokenStore.java`

Type:

Java Interface

Responsibility:

OAuth token storage boundary.

Core methods:

```text
save
load
delete
```

Related:

SRC-003, SRC-010

Raw token values must not be logged.

---

# 9. Source Application

## F-BE-025 — SourceConnectionService

Path:

`backend/src/main/java/com/sdv/source/application/SourceConnectionService.java`

Type:

Java Service

Responsibility:

Source registration/disconnection Use Case.

Official methods:

```text
create
disconnect
list
```

Related:

SRC-002, SRC-010

Primary collaborator in master specification:

DocumentSourceConnector registry.

Do not replace this class with an alternative service name.

---

## F-BE-026 — SourceConnectorRegistry

Path:

`backend/src/main/java/com/sdv/source/application/SourceConnectorRegistry.java`

Type:

Java Service

Responsibility:

Resolve Source type to connector.

Official method:

```text
getConnector()
```

Related:

SRC-001, SRC-008, SRC-009

---

# 10. Source API

## F-BE-027 — SourceAdminController

Path:

`backend/src/main/java/com/sdv/source/api/SourceAdminController.java`

Responsibility:

Source administration API.

Operations:

```text
list
create
disconnect
```

Related:

SRC-002, SRC-010

Must call Application Service, not JpaRepository directly.

---

## F-BE-028 — CreateSourceRequest

Path:

`backend/src/main/java/com/sdv/source/api/dto/CreateSourceRequest.java`

Type:

DTO

Representative fields:

```text
type
name
syncMode
```

Related:

SRC-002

---

## F-BE-029 — SourceResponse

Path:

`backend/src/main/java/com/sdv/source/api/dto/SourceResponse.java`

Type:

DTO

Representative fields:

```text
id
type
status
lastSyncAt
```

Related:

SRC-002, SRC-010

---

## F-BE-030 — SourceApiMapper

Path:

`backend/src/main/java/com/sdv/source/api/mapper/SourceApiMapper.java`

Type:

Java Mapper

Responsibility:

DTO ↔ Domain conversion.

Methods:

```text
toDomain
toResponse
```

May be manual or MapStruct.

This is not a MyBatis mapper.

---

# 11. Source Persistence

## F-BE-031 — SourceConnectionEntity

Path:

`backend/src/main/java/com/sdv/source/infrastructure/persistence/entity/SourceConnectionEntity.java`

Type:

JPA Entity

Responsibility:

Map `source_connections`.

Canonical DB fields are governed by Flyway/data model:

```text
id
type
display_name
status
sync_mode
token_ref
last_sync_at
```

Related:

SRC-002, SRC-003, SRC-010

Raw Google token must not be persisted here.

---

## F-BE-032 — SourceDocumentEntity

Path:

`backend/src/main/java/com/sdv/source/infrastructure/persistence/entity/SourceDocumentEntity.java`

Type:

JPA Entity

Responsibility:

Map `source_documents`.

Representative fields:

```text
sourceId
sourceDocId
version
state
```

Related:

SRC-004, SYN-002, SYN-004

---

## F-BE-033 — SourcePermissionEntity

Path:

`backend/src/main/java/com/sdv/source/infrastructure/persistence/entity/SourcePermissionEntity.java`

Type:

JPA Entity

Responsibility:

Map normalized Source ACL.

Representative fields:

```text
documentId
principalType
principalValue
permission
```

Related:

SRC-005, SYN-003, POL-001

---

## F-BE-034 — SourceSyncCursorEntity

Path:

`backend/src/main/java/com/sdv/source/infrastructure/persistence/entity/SourceSyncCursorEntity.java`

Type:

JPA Entity

Responsibility:

Incremental Source cursor.

Representative fields:

```text
sourceId
cursor
updatedAt
```

Related:

SYN-002

---

# 12. Source Spring Data Repositories

## F-BE-035

Path:

`backend/src/main/java/com/sdv/source/infrastructure/persistence/repository/SourceConnectionJpaRepository.java`

Type:

Spring Data Repository

Responsibility:

Source connection persistence.

Canonical methods:

```text
findById
findAll
```

Related:

SRC-002, SRC-010

---

## F-BE-036

Path:

`backend/src/main/java/com/sdv/source/infrastructure/persistence/repository/SourceDocumentJpaRepository.java`

Responsibility:

Source document persistence/query.

Representative methods:

```text
findBySourceAndSourceDocId
findIndexEligibleIds
```

Related:

SRC-004, RAG-004

---

## F-BE-037

Path:

`backend/src/main/java/com/sdv/source/infrastructure/persistence/repository/SourcePermissionJpaRepository.java`

Responsibility:

Source ACL persistence/query.

Representative methods:

```text
findByDocumentId
deleteByDocumentId
```

Related:

SRC-005, SYN-003

---

## F-BE-038

Path:

`backend/src/main/java/com/sdv/source/infrastructure/persistence/repository/SourceSyncCursorJpaRepository.java`

Responsibility:

Sync cursor persistence.

Method:

```text
findBySourceId
```

Related:

SYN-002

---

# 13. Source Persistence Mapper

## F-BE-039 — SourcePersistenceMapper

Canonical path:

`backend/src/main/java/com/sdv/source/infrastructure/persistence/mapper/SourcePersistenceMapper.java`

Type:

Java Mapper

Responsibility:

JPA Entity ↔ Source Domain conversion.

Official conceptual methods:

```text
toDomain
toEntity
```

Related:

SRC-002, SRC-004

Important:

The package is:

```text
com.sdv.source.infrastructure.persistence.mapper
```

NOT:

```text
com.sdv.source.infrastructure.persistence
```

Entity must not be returned directly through REST.

---

# 14. Google Drive Adapter

All canonical Google adapter files are under:

`backend/src/main/java/com/sdv/source/infrastructure/google`

Do not introduce `infrastructure.external.googledrive` without changing the specification.

## F-BE-040 — GoogleDriveConnector

File:

`GoogleDriveConnector.java`

Type:

Java Adapter

Responsibility:

`DocumentSourceConnector` Google Drive implementation.

Methods:

```text
getMetadata
fetchContent
getPermissions
findChanges
```

Related:

SRC-004, SRC-005, SRC-006

**v1.4 update (`CORE_SPEC.md` §2A.5, §2A.11):** this is the sole active Core `DocumentSourceConnector` implementation. `fetchContent` must be called only as part of the Mandatory Live Retrieval cycle (bounded stream, pre-fetch and post-fetch access/version verification as the current user) — never to populate a durable content cache.

---

## F-BE-041 — GoogleDriveClient

File:

`GoogleDriveClient.java`

Type:

Java Client

Responsibility:

Google Drive API call wrapper.

Technical areas:

```text
files
permissions
changes
export
```

Related:

SRC-004, SRC-005, SRC-006

Google SDK/HTTP dependency remains here.

**v1.4 update (`CORE_SPEC.md` §2A.7):** `files.export` for Google Workspace documents is limited to 10 MB synchronously. For a larger document, use a supported long-running `files.download`/revision flow, or return `EXPORT_LIMIT_EXCEEDED`. Never treat a partial export as a complete document, and never persist the export.

---

## F-BE-042 — GoogleDriveOAuthController

File:

`GoogleDriveOAuthController.java`

Type:

Controller

Responsibility:

OAuth authorization start/callback.

Representative operations:

```text
authorize
callback
```

Related:

SRC-003

---

## F-BE-043 — GoogleTokenService

File:

`GoogleTokenService.java`

Type:

Service

Responsibility:

Google token encryption / retrieval / revocation.

Methods:

```text
store
load
revoke
```

Related:

SRC-003, SRC-010

Collaborates with:

`SourceTokenStore`

---

## F-BE-044 — GoogleTokenStoreAdapter

File:

`GoogleTokenStoreAdapter.java`

Type:

Adapter

Responsibility:

`SourceTokenStore` implementation.

Methods:

```text
save
load
delete
```

Related:

SRC-003

---

## F-BE-045 — GoogleDrivePermissionAdapter

File:

`GoogleDrivePermissionAdapter.java`

Type:

Adapter

Responsibility:

Google Permission → SDV `SourcePermission`.

Method:

```text
mapPermissions()
```

Related:

SRC-005, POL-001

Collaborates with:

`PrincipalResolver`

---

## F-BE-046 — GoogleDriveContentAdapter

File:

`GoogleDriveContentAdapter.java`

Type:

Adapter

Responsibility:

Google Docs/Slides/export/general file content handling.

Method:

```text
fetchTextOrBinary()
```

Related:

SRC-006

**v1.4 update (`CORE_SPEC.md` §2A.1, §2A.7):** content fetched here is transient — used only within a single indexing or Mandatory Live Retrieval request cycle, never written to a durable original/export store. Google Docs export uses a transient DOCX/PDF export followed by immediate cleanup.

---

## F-BE-047 — PrincipalResolver

File:

`PrincipalResolver.java`

Type:

Service

Responsibility:

Map Source Principal against `UserContext`.

Method:

```text
matches()
```

Related:

AUT-004, SRC-005

---

# 15. Local Vault — EXCLUDED / RETIRED (v1.4)

> **EXCLUDED / RETIRED at v1.4 (`CORE_SPEC.md` §2A.1, §11).** Every file ID in this section (`F-BE-048`–`F-BE-061`) is preserved below unmodified, for historical/traceability purposes only. None of these files describe an active Core capability, and none of these IDs may be reused for a different file or purpose. Do not implement, extend, or reactivate any file in this table.

| ID | Path | Type | Responsibility | Content | Feature |
|---|---|---|---|---|---|
| F-BE-048 | `backend/src/main/java/com/sdv/vault/domain/VaultCollection.java` | Domain | Single-level collection | id, name, owner | VAU-001 |
| F-BE-049 | `backend/src/main/java/com/sdv/vault/api/VaultCollectionController.java` | Controller | Collection create/list | list, create | VAU-001 |
| F-BE-050 | `backend/src/main/java/com/sdv/vault/api/VaultDocumentController.java` | Controller | Upload/fetch/delete | upload, content, delete | VAU-002,005,006 |
| F-BE-051 | `backend/src/main/java/com/sdv/vault/application/VaultCollectionService.java` | Service | Collection Use Case | create, list | VAU-001 |
| F-BE-052 | `backend/src/main/java/com/sdv/vault/application/VaultDocumentService.java` | Service | Vault document Use Case | upload, fetch, delete | VAU-002,005,006 |
| F-BE-053 | `backend/src/main/java/com/sdv/vault/application/VaultCryptoService.java` | Service | AES-256-GCM | encrypt, decrypt | VAU-003 |
| F-BE-054 | `backend/src/main/java/com/sdv/vault/application/FileIntegrityService.java` | Service | SHA-256 integrity | hash, verify | VAU-004 |
| F-BE-055 | `backend/src/main/java/com/sdv/vault/infrastructure/VaultStorageAdapter.java` | Adapter | Encrypted file I/O | store, open, delete | VAU-003,005,006 |
| F-BE-056 | `backend/src/main/java/com/sdv/vault/infrastructure/LocalVaultConnector.java` | Adapter | Vault as DocumentSourceConnector | connector methods | SRC-007 |
| F-BE-057 | `backend/src/main/java/com/sdv/vault/infrastructure/persistence/entity/VaultCollectionEntity.java` | Entity | collection table | id, name, owner | VAU-001 |
| F-BE-058 | `backend/src/main/java/com/sdv/vault/infrastructure/persistence/entity/VaultObjectEntity.java` | Entity | vault object metadata | storageKey, hash, keyVersion | VAU-002~004 |
| F-BE-059 | `backend/src/main/java/com/sdv/vault/infrastructure/persistence/repository/VaultCollectionJpaRepository.java` | Repository | collection persistence | findAllByOwner | VAU-001 |
| F-BE-060 | `backend/src/main/java/com/sdv/vault/infrastructure/persistence/repository/VaultObjectJpaRepository.java` | Repository | object metadata | findBySourceDocumentId | VAU-002,006 |
| F-BE-061 | `backend/src/main/java/com/sdv/vault/api/mapper/VaultApiMapper.java` | Mapper | Vault DTO conversion | toResponse, toCommand | VAU-001,002 |

---

# 16. Sync

| ID | Path | Type | Responsibility | Core Method | Feature |
|---|---|---|---|---|---|
| F-BE-062 | `backend/src/main/java/com/sdv/sync/api/SourceSyncController.java` | Controller | Initial/manual Sync API | sync, resync | SYN-001,008 |
| F-BE-063 | `backend/src/main/java/com/sdv/sync/application/SourceSyncService.java` | Service | Initial Sync orchestration | startInitialSync | SYN-001 |
| F-BE-064 | `backend/src/main/java/com/sdv/sync/application/IncrementalSyncService.java` | Service | Cursor-based incremental Sync | syncChanges | SYN-002 |
| F-BE-065 | `backend/src/main/java/com/sdv/sync/application/PermissionSyncService.java` | Service | ACL sync/replacement | syncPermissions | SYN-003 |
| F-BE-066 | `backend/src/main/java/com/sdv/sync/application/SourceDeletionService.java` | Service | Source deletion propagation | handleDeleted | SYN-004 |
| F-BE-067 | `backend/src/main/java/com/sdv/sync/application/AbstractSourceSyncJob.java` | Abstract Class | Common Sync workflow | loadCursor→fetchChanges→persist→publish | SYN-001,002 |
| F-BE-136 | `backend/src/main/java/com/sdv/sync/infrastructure/persistence/entity/SyncRunEntity.java` | Entity | sync_runs mapping | mode,total,success,failed,status | SYN-001 |
| F-BE-137 | `backend/src/main/java/com/sdv/sync/infrastructure/persistence/repository/SyncRunJpaRepository.java` | Repository | Sync history | findBySourceId | SYN-001,008 |
| F-BE-196 | `backend/src/main/java/com/sdv/sync/application/job/GoogleDriveSyncJob.java` | Service | Google Drive initial/incremental Sync template; cursor commit occurs atomically with metadata, permission, and Outbox state | fetchChanges, mapMetadata, mapPermissions | SYN-001,002,003; SRC-004 |
| F-BE-197 | `backend/src/main/java/com/sdv/sync/application/job/LocalVaultSyncJob.java` | Service | **EXCLUDED / RETIRED at v1.4** — historical Local Vault Sync Job; `GoogleDriveSyncJob` is the Core implementation | fetchChanges, mapMetadata, mapPermissions | SYN-001,002,003; SRC-007 |

`AbstractSourceSyncJob` is allowed because the workflow invariant is explicit.

Do not generalize this permission to unrelated inheritance hierarchies.

**v1.4 update for F-BE-066 (`CORE_SPEC.md` §2A.11):** `SourceDeletionService.handleDeleted` must also retire the deleted document's `document_embedding_index` rows. It must never depend on a plaintext content row after the `V006` correction (`F-INF-020`) lands, because none may durably exist.

---

# 17. Event / Kafka / Outbox

| ID | Path | Type | Responsibility |
|---|---|---|---|
| F-BE-068 | `backend/src/main/java/com/sdv/event/domain/DomainEvent.java` | Interface | Common event contract |
| F-BE-069 | `backend/src/main/java/com/sdv/event/domain/SourceDocumentChangedEvent.java` | Record | Source document change |
| F-BE-070 | `backend/src/main/java/com/sdv/event/domain/SourcePermissionChangedEvent.java` | Record | ACL change |
| F-BE-071 | `backend/src/main/java/com/sdv/event/domain/SourceDocumentDeletedEvent.java` | Record | document deletion |
| F-BE-072 | `backend/src/main/java/com/sdv/event/domain/IndexRequestedEvent.java` | Record | index request |
| F-BE-073 | `backend/src/main/java/com/sdv/event/infrastructure/persistence/entity/OutboxEventEntity.java` | Entity | outbox_events mapping |
| F-BE-138 | `backend/src/main/java/com/sdv/event/infrastructure/persistence/repository/OutboxEventJpaRepository.java` | Repository | find unpublished Outbox |
| F-BE-074 | `backend/src/main/java/com/sdv/event/infrastructure/OutboxEventPublisher.java` | Scheduler/Service | Publish pending Outbox to Kafka |
| F-BE-075 | `backend/src/main/java/com/sdv/event/infrastructure/PermissionChangedConsumer.java` | Kafka Consumer | Permission change processing |
| F-BE-076 | `backend/src/main/java/com/sdv/event/config/KafkaRetryConfig.java` | Config | Retry/DLQ policy |
| F-BE-077 | `backend/src/main/java/com/sdv/event/infrastructure/DlqHandler.java` | Consumer | DLQ diagnostics/state |

Event payload must contain minimal identifiers/version/trace information.

No raw token/document/prompt.

---

# 18. Policy Domain / Application

| ID | Path | Type | Responsibility | Feature |
|---|---|---|---|---|
| F-BE-078 | `backend/src/main/java/com/sdv/policy/domain/SecurityLevel.java` | Enum/VO | PUBLIC→SECRET security ordering | POL-002 |
| F-BE-079 | `backend/src/main/java/com/sdv/policy/domain/PolicyDecision.java` | Record/VO | ALLOW/DENY + reason | POL-004,008 |
| F-BE-080 | `backend/src/main/java/com/sdv/policy/domain/PolicyReasonCode.java` | Enum | machine-readable reason | POL-008 |
| F-BE-081 | `backend/src/main/java/com/sdv/policy/application/EffectivePermissionService.java` | Service | Source ACL + Overlay + State | POL-004 |
| F-BE-082 | `backend/src/main/java/com/sdv/policy/application/OverlayPolicyService.java` | Service | additional restriction policy | POL-003 |
| F-BE-083 | `backend/src/main/java/com/sdv/policy/application/AiUsagePolicyService.java` | Service | AI provider permission | POL-005,AI-003 |
| F-BE-084 | `backend/src/main/java/com/sdv/policy/application/LabelMappingService.java` | Service | Source label→SecurityLevel | POL-006 |
| F-BE-085 | `backend/src/main/java/com/sdv/policy/application/PermissionFreshnessPolicy.java` | Service | stale ACL decision | POL-007 |
| F-BE-132 | `backend/src/main/java/com/sdv/policy/application/SecurityLabelService.java` | Service | security label get/set | POL-002,006 |

SecurityLevel official values:

```java
PUBLIC
INTERNAL
CONFIDENTIAL
SECRET
```

---

# 19. Policy API / Persistence

| ID | Path | Responsibility |
|---|---|---|
| F-BE-086 | `backend/src/main/java/com/sdv/policy/api/OverlayPolicyController.java` | Overlay policy administration |
| F-BE-087 | `backend/src/main/java/com/sdv/policy/api/AiUsagePolicyController.java` | AI policy administration |
| F-BE-133 | `backend/src/main/java/com/sdv/policy/api/PolicyAdminController.java` | Security level / label mapping admin |
| F-BE-089 | `backend/src/main/java/com/sdv/policy/infrastructure/persistence/entity/DocumentSecurityLabelEntity.java` | security label mapping |
| F-BE-090 | `backend/src/main/java/com/sdv/policy/infrastructure/persistence/entity/OverlayPolicyEntity.java` | overlay_policies |
| F-BE-091 | `backend/src/main/java/com/sdv/policy/infrastructure/persistence/entity/AiUsagePolicyEntity.java` | ai_usage_policies |
| F-BE-092 | `backend/src/main/java/com/sdv/policy/infrastructure/persistence/repository/OverlayPolicyJpaRepository.java` | Overlay persistence |
| F-BE-093 | `backend/src/main/java/com/sdv/policy/infrastructure/persistence/repository/AiUsagePolicyJpaRepository.java` | AI policy persistence |
| F-BE-134 | `backend/src/main/java/com/sdv/policy/infrastructure/persistence/repository/SecurityLabelJpaRepository.java` | Security label lookup |
| F-BE-094 | `backend/src/main/java/com/sdv/policy/api/mapper/PolicyApiMapper.java` | policy DTO conversion |

`PolicyPreviewController` and `PolicyCache` are extension scope and should not delay Core.

---

# 20. RAG API

| ID | Path | Type | Responsibility |
|---|---|---|---|
| F-BE-095 | `backend/src/main/java/com/sdv/rag/api/RagQueryController.java` | Controller | search / ask |
| F-BE-096 | `backend/src/main/java/com/sdv/rag/api/dto/RagSearchRequest.java` | DTO | search request |
| F-BE-097 | `backend/src/main/java/com/sdv/rag/api/dto/RagAskRequest.java` | DTO | RAG question |
| F-BE-098 | `backend/src/main/java/com/sdv/rag/api/dto/RagAnswerResponse.java` | DTO | answer + Citation + traceId |
| F-BE-099 | `backend/src/main/java/com/sdv/rag/api/mapper/RetrievalFilterMapper.java` | Mapper | request filter→validated retrieval filter |

Client filters must never widen authorization scope.

---

# 21. RAG Application / Persistence

| ID | Path | Type | Responsibility |
|---|---|---|---|
| F-BE-100 | `backend/src/main/java/com/sdv/rag/application/RagRetrievalService.java` | Service | ACL Catalog prefilter + embedding-only candidate shortlist (v1.4 §2A.4 — not a permission decision, not evidence) |
| F-BE-101 | `backend/src/main/java/com/sdv/rag/application/RagAnswerService.java` | Service | Candidate shortlist → Mandatory Live Retrieval (v1.4 §2A.5: current-user Drive verify → bounded fetch → parse → re-verify → ephemeral evidence) → Policy → LLM → Citation |
| F-BE-102 | `backend/src/main/java/com/sdv/rag/application/CitationAssembler.java` | Service | verified live-retrieval evidence → Citation (v1.4 §2A.5 step 8; core locators `PAGE`/`SECTION`/`LINE_RANGE`, §2A.7) — not from a durable Chunk/text table |
| F-BE-103 | `backend/src/main/java/com/sdv/rag/application/LiveEvidenceRetrievalService.java` | Service | Every query: current-user Drive permission/version verification + transient evidence fetch; `retrieveLive()`, `retryOnVersionChange()`; depends on `DocumentSourceConnector`, `SourceConsistencyGuard`, `EphemeralEvidenceStore` |
| F-BE-104 | `backend/src/main/java/com/sdv/rag/application/port/VectorSearchPort.java` | Interface | authorized vector search |
| F-BE-105 | `backend/src/main/java/com/sdv/rag/infrastructure/PgVectorSearchAdapter.java` | Adapter | pgvector search |
| F-BE-106 | `backend/src/main/java/com/sdv/rag/infrastructure/persistence/entity/DocumentEmbeddingEntity.java` | JPA Entity | Content-free `document_embedding_index`: `documentId`, `chunkIndex`, `embedding`, `locatorType`, `locatorValue`, `sourceVersion`, `contentHmac`, `modelVersion`; parser/model generation metadata remains required by the data model; no plaintext field |
| F-BE-107 | `backend/src/main/java/com/sdv/rag/infrastructure/persistence/repository/DocumentEmbeddingJpaRepository.java` | Spring Data Repository | Atomic embedding generation replacement/delete/allowed search: `replaceGeneration()`, `deleteByDocumentId()`, `searchAllowed()`; generation key includes source, parser, and model versions |
| F-BE-108 | `backend/src/main/java/com/sdv/rag/infrastructure/ai/DocumentParsingClient.java` | Client | FastAPI parse/index — v1.4: parse output is durable only as embedding-index rows (§2A.2); `document_extracted_content.normalized_text` (V005) is known drift requiring `V006` (§2A.3) |
| F-BE-114 | `backend/src/main/java/com/sdv/rag/infrastructure/event/IndexRequestedConsumer.java` | Kafka Consumer | index request → AI service; builds embedding-only index rows (v1.4 §2A.2), never a durable text store |
| F-BE-173 | `backend/src/main/java/com/sdv/rag/application/ContentProcessingPolicy.java` | Service/Policy | `classify(metadata)`, `isIndexable(mimeType)` classify Core processing/index status from metadata, MIME, size, and policy; PDF/DOCX/TXT/MD content, XLSX parser retained but default-disabled |
| F-BE-188 | `backend/src/main/java/com/sdv/rag/application/IndexOrchestrator.java` | Service | `index()`, `replaceGeneration()`, `cleanup()`, `markFailed()`: transient Drive fetch → parse/chunk/embed → atomic content-free generation replacement → cleanup; no content-bearing Kafka/DB retry state |

**v1.4 correction (`CORE_SPEC.md` §2A.11):** Mandatory Live Retrieval (§2A.5) is a Core MVP requirement, not a PoC. The class name `FederatedRetrievalService` below remains listed in §30 as a separate deferred/extension identifier, but this must not be read as meaning live, per-request, permission-verified retrieval itself is deferred — it is not; it is mandatory for every `FIND_CONTENT`/`SUMMARIZE`/`COMPARE`/`GROUNDED_ANALYSIS` request via `RagRetrievalService`/`RagAnswerService` above.

---

# 22. AI Gateway

| ID | Path | Type | Responsibility |
|---|---|---|---|
| F-BE-109 | `backend/src/main/java/com/sdv/ai/application/port/LlmProvider.java` | Interface | common provider contract |
| F-BE-110 | `backend/src/main/java/com/sdv/ai/application/PolicyEnforcedLlmGateway.java` | Service | AI policy before provider |
| F-BE-111 | `backend/src/main/java/com/sdv/ai/application/PromptComposer.java` | Service | question + smallest live-verified ephemeral evidence prompt (v1.4 §2A.6, §2A.9 — never a durable Chunk/text table) |
| F-BE-112 | `backend/src/main/java/com/sdv/ai/infrastructure/OllamaLlmAdapter.java` | Adapter | Local Ollama provider |
| F-BE-113 | `backend/src/main/java/com/sdv/ai/infrastructure/ExternalLlmAdapter.java` | Adapter | External provider/mock; OFF by default |
| F-BE-145 | `backend/src/main/java/com/sdv/ai/domain/LlmInvocationMetadata.java` | Record/VO | provider/model/version/latency |

No direct External LLM call that bypasses `PolicyEnforcedLlmGateway`.

---

# 23. Audit

| ID | Path | Type | Responsibility |
|---|---|---|---|
| F-BE-115 | `backend/src/main/java/com/sdv/audit/domain/AuditEvent.java` | Domain | actor/action/target/result/reason/traceId |
| F-BE-116 | `backend/src/main/java/com/sdv/audit/application/AuditService.java` | Service | Audit save Use Case |
| F-BE-117 | `backend/src/main/java/com/sdv/audit/application/RagAuditRecorder.java` | Service | connect Policy/Retrieval/LLM/Citation |
| F-BE-118 | `backend/src/main/java/com/sdv/audit/api/AuditAdminController.java` | Controller | Audit search |
| F-BE-119 | `backend/src/main/java/com/sdv/audit/application/AuditQueryService.java` | Service | admin Audit query |
| F-BE-120 | `backend/src/main/java/com/sdv/audit/infrastructure/persistence/entity/AuditLogEntity.java` | Entity | audit_logs |
| F-BE-121 | `backend/src/main/java/com/sdv/audit/infrastructure/persistence/repository/AuditLogJpaRepository.java` | Repository | Audit query |
| F-BE-139 | `backend/src/main/java/com/sdv/audit/infrastructure/AuditAspect.java` | AOP | common admin/policy Audit hook |

Raw Prompt / token / key / document content must not be stored.

---

# 24. Security Risk

| ID | Path | Type | Responsibility |
|---|---|---|---|
| F-BE-122 | `backend/src/main/java/com/sdv/security/application/OversharingDetectionService.java` | Service | broad-sharing detection |
| F-BE-123 | `backend/src/main/java/com/sdv/security/application/PermissionSyncRiskService.java` | Service | stale/sync error Finding |
| F-BE-124 | `backend/src/main/java/com/sdv/security/api/SecurityFindingController.java` | Controller | Finding list/update |
| F-BE-126 | `backend/src/main/java/com/sdv/security/infrastructure/persistence/entity/SecurityFindingEntity.java` | Entity | security_findings |
| F-BE-127 | `backend/src/main/java/com/sdv/security/infrastructure/persistence/repository/SecurityFindingJpaRepository.java` | Repository | Finding persistence |

Automatic Source permission remediation is not part of Core.

---

# 25. Python AI Service

| ID | Path | Type | Responsibility |
|---|---|---|---|
| F-AI-001 | `ai-service/app/main.py` | Python | FastAPI entry point |
| F-AI-002 | `ai-service/app/api/routes.py` | Router | `/parse`, `/index`, `/health` internal APIs |
| F-AI-003 | `ai-service/app/services/parser_service.py` | Service | PDF/DOCX/TXT text extraction |
| F-AI-004 | `ai-service/app/services/chunking_service.py` | Service | Chunk + metadata — v1.4: Chunk text is transient within one request/response cycle only, never returned for durable plaintext storage (§2A.2, §2A.3) |
| F-AI-005 | `ai-service/app/services/embedding_service.py` | Service | Embedding — output (vectors + locators + version/digest metadata, §2A.2) is the only content-derived data the Backend may persist from this pipeline |
| F-AI-006 | `ai-service/app/workers/index_worker.py` | Worker | Index orchestration — builds the v1.4 Embedding Candidate Index (§2A.11) only; must not cause plaintext to be written to a durable store |
| F-AI-007 | `ai-service/app/models/schemas.py` | Pydantic | Internal schemas |
| F-AI-008 | `ai-service/app/core/config.py` | Config | AI service settings |
| F-AI-009 | `ai-service/tests/test_parser.py` | Test | parser validation |
| F-AI-010 | `ai-service/tests/test_chunking.py` | Test | Chunk metadata validation |

AI Service is internal.

Do not expose it as a public application API.

Authorization remains Backend-owned.

---

# 26. Frontend Core

| ID | Path | Responsibility |
|---|---|---|
| F-FE-001 | `frontend/src/main.tsx` | React entry |
| F-FE-002 | `frontend/src/app/router.tsx` | USER/ADMIN routes |
| F-FE-003 | `frontend/src/features/auth/authClient.ts` | OIDC auth/token wrapper |
| F-FE-004 | `frontend/src/features/sources/SourceListPage.tsx` | Source list/connect/disconnect |
| F-FE-005 | `frontend/src/features/sources/GoogleDriveConnectPage.tsx` | Google Drive connect |
| F-FE-006 | `frontend/src/features/sources/SourceSyncPanel.tsx` | Sync status/action |
| F-FE-007 | `frontend/src/features/rag/RagChatPage.tsx` | question/answer/Citation UI |
| F-FE-008 | `frontend/src/features/rag/CitationList.tsx` | Citation rendering |
| F-FE-009 | `frontend/src/features/policies/PolicyAdminPage.tsx` | Overlay/AI policy UI |
| F-FE-010 | `frontend/src/features/security/SecurityDashboardPage.tsx` | Finding/risk UI |
| F-FE-011 | `frontend/src/features/audit/AuditLogPage.tsx` | Audit search |
| F-FE-012 | `frontend/src/features/vault/VaultPage.tsx` | **EXCLUDED / RETIRED at v1.4** (`CORE_SPEC.md` §2A.1, §11) — Vault collection/upload; preserved for historical/traceability reference only, `/api/vault/*` is not an active API |
| F-FE-013 | `frontend/src/shared/api/httpClient.ts` | common HTTP/401/trace handling |
| F-FE-014 | `frontend/src/shared/types/api.ts` | shared API Types |
| F-FE-015 | `frontend/src/*.test.tsx` | core UI tests |

Frontend must not reimplement Backend permission logic.

---

# 27. Core Infrastructure / Configuration

| ID | Path | Type | Responsibility |
|---|---|---|---|
| F-INF-001 | `compose.yaml` | Docker Compose | PostgreSQL/Kafka/Keycloak/Ollama/apps |
| F-INF-002 | `backend/src/main/resources/application.yml` | YAML | common Backend config |
| F-INF-003 | `backend/src/main/resources/application-local.yml` | YAML | localhost development |
| F-INF-004 | `backend/src/main/resources/application-compose.yml` | YAML | Compose DNS config |
| F-INF-005 | `.env.example` | ENV template | environment variable names; no real secrets |
| F-INF-006 | `infra/keycloak/realm-export.json` | JSON | Realm/client/roles |
| F-INF-007 | `backend/src/main/resources/db/migration/V001__baseline.sql` | SQL | Core relational schema |
| F-INF-008 | `backend/src/main/resources/db/migration/V002__pgvector.sql` | SQL | pgvector / Chunk vector |
| F-INF-009 | `scripts/bootstrap.ps1` | PowerShell | initial environment startup |
| F-INF-010 | `scripts/verify.ps1` | PowerShell | health/secret/E2E checks |
| F-INF-011 | `scripts/backup.ps1` | PowerShell | v1.4: DB (Metadata/ACL Catalog, embedding-only index, audit) backup only — ~~Vault backup~~ EXCLUDED/RETIRED (§2A.1); the Encrypted Ephemeral Evidence store (§2A.6) must never be backed up |
| F-INF-012 | `scripts/restore.ps1` | PowerShell | restore/verification — same v1.4 scope narrowing as F-INF-011; ephemeral evidence is never restored (it is never backed up) |
| F-INF-016 | `.github/workflows/ci.yml` | GitHub Actions | Gradle/pytest/npm/secret scan |
| F-INF-017 | `backend/src/main/resources/db/migration/V003__content_processing_schema.sql` | SQL | Applied immutable ALTER-only migration for source document index status/reason and chunk locator type/value |
| F-INF-018 | `backend/src/main/resources/db/migration/V004__consumer_idempotency.sql` | SQL | **KNOWN VERSION COLLISION:** the workbook assigns the `processed_events` ledger and unique constraints to this path, but repository V004 is already the applied immutable `V004__source_account_isolation.sql`. Do not create a second V004; during M09 retain the F-INF-018 responsibility and assign a verified unused version after V006, then update this public path transparently. |

Applied migration files must not be edited.

If V001 is already applied, new schema changes use a new migration.

---

# 28. Core Documentation

| ID | Path | Responsibility |
|---|---|---|
| F-DOC-001 | `README.md` | project / quick start / demo |
| F-DOC-002 | `docs/architecture.md` | Source→Policy→RAG→Audit |
| F-DOC-003 | `docs/threat-model.md` | assets/threats/controls |
| F-DOC-004 | `docs/scope.md` | Core include/exclude |
| F-DOC-005 | `docs/api.md` | API examples |

This Agent-readable specification supplements those project-facing documents.

---

# 29. Core Security Tests

| ID | Path | Purpose |
|---|---|---|
| F-TST-001 | `backend/src/test/java/com/sdv/security/EffectivePermissionServiceTest.java` | Source/Overlay policy matrix |
| F-TST-002 | `backend/src/test/java/com/sdv/rag/UnauthorizedRetrievalE2ETest.java` | Unauthorized Chunk = 0; v1.4 update: must also prove the current-user Drive access recheck fails closed (DENY/UNKNOWN/deleted/trashed/non-downloadable) before content ever reaches a parser/LLM (§2A.5) |
| F-TST-003 | `backend/src/test/java/com/sdv/source/GoogleDriveConnectorContractTest.java` | Connector contract; v1.4 update: must also cover the `files.export` 10 MB boundary/`EXPORT_LIMIT_EXCEEDED` and the version-changed-during-fetch retry-once-then-`DOCUMENT_CHANGED` behavior (§2A.5, §2A.7) |
| F-TST-004 | `backend/src/test/java/com/sdv/sync/PermissionSyncE2ETest.java` | Drive permission removal→RAG exclusion |
| F-TST-005 | `backend/src/test/java/com/sdv/ai/ExternalLlmPolicyTest.java` | LOCAL_ONLY external call = 0 |
| F-TST-006 | `backend/src/test/java/com/sdv/audit/AuditTraceE2ETest.java` | RAG trace reconstruction |
| F-TST-007 | `backend/src/test/java/com/sdv/rag/ZeroOriginalPersistenceE2ETest.java` | Verify zero durable retention of originals, exports, complete extracted text, and plaintext chunks by scanning DB/files/cache/logs; cover hard-TTL expiry and cleanup recovery with Testcontainers and a temporary filesystem |
| F-TST-008 | `backend/src/test/java/com/sdv/security/OversharingDetectionTest.java` | SECRET + anyone → HIGH |

Feature-specific smaller unit/integration tests may be added next to these canonical portfolio tests when needed.

---

# 30. Explicitly Deferred / Extension Files

The following must not be pulled into an unrelated Core feature without justification.

Examples:

```text
FederatedRetrievalService
PolicyPreviewController
PolicyCache
ChatSessionEntity
ChatMessageEntity
ChatSessionService
LlmTimeoutConfig
AuditHashService
SecurityDashboardController
```

`PromptSecurityService` (File ID `F-BE-146`) is a Core MVP security control, not a deferred/extension file, and must not be classified as deferred-only. Its Core responsibility is to prevent direct and indirect prompt injection — including injection carried by retrieved content — from overriding ACL, Source permission, Overlay Policy, AI Usage Policy, provider-selection policy, System instructions, or Tool permissions. Its canonical path/package is not established by the sources reviewed for this correction; this is reported as an unresolved path, not invented.

**v1.4 clarification (`CORE_SPEC.md` §2A.11):** `FederatedRetrievalService` above is a deferred **class name** only. It must not be read as meaning live, per-request, permission-verified retrieval itself is deferred — Mandatory Live Retrieval (§2A.5) is a required Core capability, implemented by `RagRetrievalService`/`RagAnswerService` (§21), not by this deferred class.

SharePoint and S3 Core work is contract/skeleton only.

Actual AWS S3 Source implementation belongs to Cloud Portfolio, and even then only as a read-only `DocumentSourceConnector` (v1.4 §2A.10) — never as a writer of SDV-managed content. See §37 for `ObjectStoragePort`/`S3ObjectStorageAdapter` (`F-BE-159`, `F-BE-160`), which are excluded/retired specifically because their writer role conflicts with the v1.4 retention rule.

---

# 31. Cloud / Kubernetes Boundary

Core agents should know these exist but must not implement them during unrelated Phase 1 work.

Later canonical areas include:

```text
com.sdv.cloud
com.sdv.security.infrastructure.aws
com.sdv.storage.application.port
com.sdv.storage.infrastructure.aws
com.sdv.source.infrastructure.s3
com.sdv.hybrid

infra/terraform
infra/helm/sdv
```

Cloud deployment target:

```text
AWS VPC
→ ECR
→ EKS
→ RDS PostgreSQL
→ Secrets Manager
→ KMS
→ CloudWatch/OpenTelemetry
```

ECS parallel implementation is excluded.

---

# 32. Current source_connections Development Reference

For the current implementation slice, relevant canonical files are:

```text
backend/src/main/java/com/sdv/source/domain/SourceType.java
backend/src/main/java/com/sdv/source/domain/SourceConnection.java

backend/src/main/java/com/sdv/source/application/port/DocumentSourceConnector.java
backend/src/main/java/com/sdv/source/application/port/SourceTokenStore.java

backend/src/main/java/com/sdv/source/application/SourceConnectionService.java
backend/src/main/java/com/sdv/source/application/SourceConnectorRegistry.java

backend/src/main/java/com/sdv/source/api/SourceAdminController.java
backend/src/main/java/com/sdv/source/api/dto/CreateSourceRequest.java
backend/src/main/java/com/sdv/source/api/dto/SourceResponse.java
backend/src/main/java/com/sdv/source/api/mapper/SourceApiMapper.java

backend/src/main/java/com/sdv/source/infrastructure/persistence/entity/SourceConnectionEntity.java
backend/src/main/java/com/sdv/source/infrastructure/persistence/repository/SourceConnectionJpaRepository.java
backend/src/main/java/com/sdv/source/infrastructure/persistence/mapper/SourcePersistenceMapper.java
```

External implementation later connects through:

```text
backend/src/main/java/com/sdv/source/infrastructure/google/GoogleDriveConnector.java
```

(`backend/src/main/java/com/sdv/vault/infrastructure/LocalVaultConnector.java` is EXCLUDED / RETIRED at v1.4 — §15, `CORE_SPEC.md` §2A.1 — and is not part of the active implementation target.)

---

# 33. Current Architecture Decision Guard

When planning the next Source slice:

DO NOT automatically propose:

```text
com.sdv.source.domain.SourceConnectionRepository
```

or:

```text
com.sdv.source.infrastructure.persistence.SourceConnectionPersistenceAdapter
```

because neither file is in the v3.2 master manifest.

If such an abstraction is believed necessary, the Plan must explicitly say:

```text
The following class is not present in v3.2.
This is a proposed architecture change.
Reason:
...
Benefit:
...
Cost:
...
Existing v3.2 alternative:
...
```

No implementation before approval.

---

# 34. Mapper Guard

Canonical Source persistence mapper:

```text
com.sdv.source.infrastructure.persistence.mapper.SourcePersistenceMapper
```

Canonical Source API mapper:

```text
com.sdv.source.api.mapper.SourceApiMapper
```

Their responsibilities are different.

```text
SourceApiMapper
API DTO ↔ Domain

SourcePersistenceMapper
Domain ↔ JPA Entity
```

Google Drive permission/content conversion belongs to Google infrastructure adapters, not either mapper.

---

# 35. Agent Validation Checklist

Before presenting any implementation Plan, verify:

```text
[ ] Exact file exists in this manifest or is clearly declared as a new proposal
[ ] Exact package matches this manifest
[ ] Feature is Core vs Extension vs Cloud
[ ] No applied Flyway migration is modified
[ ] Domain contains no JPA/HTTP/Google/AWS type
[ ] Controller does not call JpaRepository directly
[ ] Entity is not returned through REST
[ ] External Source transformation and Persistence transformation are separated
[ ] Permission failure is Fail Closed
[ ] Kafka payload has no raw document/token
[ ] External LLM remains OFF by default
[ ] No unrelated refactoring
```

If any item is uncertain:

stop the architecture assumption and report the uncertainty.

---

# 36. Final Manifest Rule

The purpose of this manifest is not to maximize the number of layers.

The purpose is to make every Agent implement the same SDV v3.2 architecture.

When generic best practice and the explicit v3.2 file manifest disagree:

follow the v3.2 manifest first,

then propose an intentional specification change if a better design is truly necessary.

---

# 37. v1.4 New Files (Excel v1.4 freeze) and Excluded/Retired Writer Files

## 37.1 New files added at v1.4

Do not create the actual code or migration files for these from this manifest entry alone — implementation is separate, future work (see `CORE_SPEC.md` §2A.12 development order). These IDs are added here only so future Plans reference the correct canonical path.

| ID | Canonical Path | Type | Responsibility |
|---|---|---|---|
| F-BE-206 | `backend/src/main/java/com/sdv/rag/application/port/out/EphemeralEvidenceStore.java` | Java Interface | Encrypted ephemeral-evidence contract with a hard TTL of at most 300 seconds from original creation; `putEncrypted()`, `getIfAuthorizedAndCurrent()`, `evict()` (`CORE_SPEC.md` §2A.6) |
| F-BE-207 | `backend/src/main/java/com/sdv/rag/application/SourceConsistencyGuard.java` | Java Service | Drive access/version checks before and after fetch, with one bounded retry on version change (`CORE_SPEC.md` §2A.5) |
| F-BE-208 | `backend/src/main/java/com/sdv/rag/infrastructure/ephemeral/EncryptedEphemeralEvidenceStore.java` | Java Adapter | `EphemeralEvidenceStore` implementation: encrypted volatile evidence storage with forced eviction and no sliding TTL; `putEncrypted()`, `evictExpired()`, `evictByDocument()` (`CORE_SPEC.md` §2A.6); never backed up, snapshotted, or mounted on a durable volume |
| F-INF-020 | `backend/src/main/resources/db/migration/V006__zero_original_persistence.sql` | SQL (future) | Corrects the persistence model (removes/relocates durable plaintext such as `document_extracted_content.normalized_text`) without editing `V001`–`V005` (`CORE_SPEC.md` §2A.3) |

The `F-BE-206`–`F-BE-208` package paths above are explicit values from the v1.4 Excel master, not inferred placements.

## 37.2 S3 / Object Storage — excluded/retired from introduction (v1.4)

| ID | Canonical Path | Type | Responsibility | Status |
|---|---|---|---|---|
| F-BE-159 | `backend/src/main/java/com/sdv/storage/application/port/ObjectStoragePort.java` | Java Interface | Generic object storage write/read contract | **EXCLUDED / RETIRED at v1.4** — writer behavior conflicts with the global no-original-retention rule (`CORE_SPEC.md` §2A.10) |
| F-BE-160 | `backend/src/main/java/com/sdv/storage/infrastructure/aws/S3ObjectStorageAdapter.java` | Java Adapter | `ObjectStoragePort` AWS S3 implementation | **EXCLUDED / RETIRED at v1.4** — same reason as `F-BE-159`; S3 must never be described as an SDV original-file, export, extracted-text, or evidence store. A future S3 integration may exist only as a read-only `DocumentSourceConnector` for customer-owned source data (§2A.10). |

The `F-BE-159` and `F-BE-160` paths above are explicit v1.4 Excel identities retained only for traceability. Their writer responsibilities remain excluded/retired.

