# Runbook — V006 Zero-Original-Persistence Correction

> Task-authorized supporting document (M07A). This is an operational runbook, not a canonical Excel-numbered spec file — it does not replace or amend `docs/spec/SDV_v3.2_CORE_SPEC.md` or `docs/spec/SDV_v3.2_FILE_MANIFEST.md`.

## Purpose

`V006__zero_original_persistence.sql` removes the legacy plaintext-bearing structures (`document_extracted_content` from V005, `document_chunks` from V002) and creates the content-free `document_embedding_index` (v1.4 `CORE_SPEC.md` §2A.2/§2A.3/§2A.11). This runbook covers the operational sequence around applying it safely, and is explicit about what it does and does not prove.

**This runbook proves logical schema/data behavior in an isolated migration test. It does not prove physical erasure from production disk pages, WAL, replicas, snapshots, or backups.** Physical erasure of already-existing plaintext (if any customer data has already been written to `document_extracted_content`/`document_chunks` in a real deployment) is an operator-controlled, deployment-specific activity — see "Physical disposal" below.

## 1. Quiesce old application writers before V006

Before applying V006 to any environment where the previous application version has been running:

1. Stop (or scale to zero) every running instance of the backend that could still call `ContentExtractionService.extract()` against the pre-V006 code (the version that wrote to `document_extracted_content`).
2. Confirm no in-flight request holds a claim row (`document_extracted_content.attempt_id IS NOT NULL`) — an in-flight claim does not block V006 (it is a `DROP TABLE`), but stopping writers first avoids a write racing the migration and erroring out instead of silently succeeding against a half-migrated schema.
3. Confirm no scheduled job (Kafka consumer, cron, manual trigger) is configured to call the old extraction path during the migration window.

## 2. Verify no old instance can recreate plaintext after migration

After V006 is applied, the pre-V006 application JAR (if accidentally started again) would fail fast rather than silently recreating plaintext:

- Its `DocumentExtractedContentJpaRepository`/`DocumentExtractedContentEntity` classes reference tables that no longer exist — any query against them raises a SQL error (`relation "document_extracted_content" does not exist"`), not a silent no-op.
- Do not "fix" this by leaving the old tables in place "just in case" — that defeats the purpose of this correction.
- **There is no safe rollback that restores a pre-V006 database snapshot into live service** — see section 5 below. A pre-V006 application redeployed against a pre-V006 database restore would indeed "work," but only by resurrecting the exact plaintext tables/writers this correction exists to remove, which the frozen v1.4 architecture forbids. Recovery from a bad V006 rollout means fixing forward to another V006-compatible build, not reverting the schema.

## 3. Apply V006 and validate the final schema

1. Apply via the normal Flyway startup path (`spring.flyway` auto-migrate on boot), or via an explicit Flyway CLI/Gradle task run by the operator — the same mechanism already used for V001–V005.
2. After migration, verify:
   - `document_extracted_content` and `document_chunks` are absent (`information_schema.tables`).
   - `document_embedding_index` exists with `fk_document_embedding_document`, `chk_document_embedding_locator_type`, `uq_document_embedding_generation_chunk`, and the `idx_document_embedding_hnsw` HNSW index present.
   - `flyway_schema_history` shows versions `001`–`006` applied, in order, with no gaps.
3. Automated evidence for steps 1–2 above is `V006ZeroOriginalPersistenceMigrationTest` (`backend/src/test/java/com/sdv/migration/`) — run it against a disposable Testcontainers database before applying V006 to any shared environment.

## 4. Logical removal of legacy rows/tables

V006's `DROP TABLE IF EXISTS document_extracted_content;` and `DROP TABLE IF EXISTS document_chunks;` remove both the structure and every row they contained, in one DDL transaction per Flyway's normal execution. There is no separate "soft delete then hard delete" step — the correction is a direct, one-way structural removal, consistent with never creating a backup copy of the removed plaintext (see below).

## 5. Rollback policy: routine rollback to pre-V006 is prohibited, not "the safe path"

**Correction (this section previously stated the opposite of the following — that a prior version of this runbook called a pre-V006 database restore "the only safe rollback path" was itself a defect: restoring a pre-V006 snapshot resurrects the removed plaintext tables and re-enables the old plaintext writers, which the frozen v1.4 architecture forbids outright. That is not a safe rollback; it is undoing the correction.**

- **Routine rollback to a pre-M07A application and/or a pre-V006 schema is prohibited**, full stop — not merely discouraged. It is not an acceptable incident response to a bad V006 rollout.
- Do not deploy any application version older than this M07A change against a post-V006 database — it will fail at the first `document_extracted_content`/`document_chunks` query, which is the intended fail-safe (not a bug to work around), and must not be "fixed" by restoring the old schema.
- **"Rolling back" this service means one of:**
  1. **Stop** the affected instance(s) (scale to zero / take out of the load balancer) while the issue is investigated, with the database left on V006; or
  2. **Roll forward** to a different, already-V006-compatible build (e.g., the previous M07A-era release, or a subsequent fix) — never to a pre-V006 build.
- **A pre-V006 backup/snapshot must never be restored into a live, traffic-serving environment as a normal rollback.** This is the same restriction as section 6 below, stated here explicitly as a rollback rule, not only a backup-handling rule.
- **Exceptional restore for forensic or audit purposes** (not a service rollback) is permitted only when **all** of the following hold:
  - it is restored into an **isolated** environment, physically/logically separate from production;
  - that environment is **access-controlled** to the specific investigators who need it, not the general operations team;
  - it is **never connected to production traffic** (no load balancer, no shared DNS, no shared credentials with production) for any duration;
  - it is **migrated to V006 (or otherwise sanitized of the plaintext columns) immediately** upon being stood up, unless the plaintext itself is the specific object of the forensic investigation, in which case that exception is time-boxed and documented;
  - once its purpose is served, it is **disposed of** under the same operator-controlled procedure as section 7 (not simply left running or forgotten).
- **Changing the zero-original-persistence product rule itself** (i.e., deciding V006's correction was wrong and durable plaintext should be reintroduced as a matter of product direction) **is not a rollback at all** — it is a new, separate, explicit, user-approved architecture decision requiring its own review, its own new forward migration (never editing V006 or any earlier migration), and its own updated public specification. It must never be triggered as an operational incident response.
- Do not write a "V007 restore document_extracted_content" migration to walk this back as a rollback mechanism — that would reintroduce the exact plaintext-persistence violation this correction removes. The only path that could ever reintroduce such a column is the explicit, user-approved architecture decision described immediately above — never a routine or emergency rollback procedure.

## 6. Excluding historical content-bearing backups/snapshots from normal restore

- Any database backup or snapshot taken **before** V006 was applied to that environment may still contain the removed plaintext (`document_extracted_content.normalized_text`, `document_chunks.content`) inside its physical data.
- A normal disaster-recovery restore procedure must not restore such a pre-V006 backup directly into a live post-V006 environment — doing so would resurrect plaintext rows into a schema that the running application no longer expects, and would reintroduce data the product line no longer retains.
- If a pre-V006 backup must be restored for a legitimate reason (e.g., forensic investigation, contractual audit), restore it into an isolated, access-controlled environment separate from production, apply V006 to that isolated copy immediately, and treat the isolated copy under the same operator-controlled disposal requirements as production (next section) once its purpose is served.

## 7. Operator-controlled disposal and proof requirements for database pages, WAL, replicas, snapshots, backups, and storage media

This migration does not and cannot address the following — they are operator/infrastructure responsibilities, deployment-specific, and outside a Flyway migration's reach:

- **Database pages / table storage**: `DROP TABLE` removes PostgreSQL's catalog entry and marks the underlying pages reusable; it does not necessarily zero the physical bytes immediately (ordinary MVCC/vacuum behavior applies). If a compliance requirement demands physical zeroing, that requires operator action (e.g., `VACUUM FULL`, or lower-level storage-media procedures) beyond this migration's scope.
- **WAL (Write-Ahead Log)**: pre-V006 WAL segments may contain the removed plaintext in their change records. WAL retention/archival policy (how long WAL is kept, whether it is archived to durable storage) is an operator/infrastructure decision; disposing of archived WAL containing pre-V006 data is an operator action.
- **Replicas**: any streaming/logical replica must apply V006 through the same replication stream (it will, automatically, as a normal DDL+DML change) — but a replica's own WAL/storage retention is independently subject to the same physical-disposal concerns as the primary.
- **Snapshots and backups**: covered in section 6 above — pre-V006 snapshots/backups are not automatically remediated by this migration and require their own operator-controlled disposal or isolation decision.
- **Storage media**: decommissioned physical/virtual disks that ever hosted a pre-V006 database are subject to the operator's/cloud provider's own media-sanitization procedures — this runbook does not and cannot verify or perform that.

**This task explicitly does not claim to prove any of the above.** `V006ZeroOriginalPersistenceMigrationTest` and `ZeroOriginalPersistenceE2ETest` prove logical schema/data behavior inside a disposable, ephemeral Testcontainers database created and destroyed within a single test run — they say nothing about a real deployment's WAL, replicas, snapshots, backups, or storage media.

## 8. Incident handling if content-bearing remnants are found

If, after this correction is deployed, a plaintext content-bearing remnant is discovered anywhere (a surviving `document_extracted_content`/`document_chunks` row via an unexpected code path, a log line, a cache entry, a backup restored into a live environment, etc.):

1. Treat it as a security/privacy incident, not a routine bug — it violates the v1.4 zero-original-retention contract.
2. Do not silently delete the remnant without recording what was found, when, and by what path it was created — the root cause (a missed writer, a restored old backup, a rolled-back application version) must be identified before remediation, per the incident-handling expectations already established for this project's audit/security posture (`CLAUDE.md`/`AGENTS.md` Security and Audit sections).
3. Determine whether the remnant reached any durable backup taken after its creation, and extend the disposal/isolation steps in sections 6–7 to that backup as well.
4. After remediation, add a regression test (in the style of `ZeroOriginalPersistenceE2ETest`) targeting the specific path that leaked the remnant, so the same defect cannot silently reoccur.

## Explicit non-goals of this runbook

- It does not create a backup copy of the removed original/plaintext content to make V006 reversible — doing so would defeat the purpose of the correction.
- It does not claim any KMS/encryption-at-rest implementation beyond what already exists in this repository (none is introduced by V006 itself — encryption-at-rest for the database, if required, remains an infrastructure/deployment concern outside this migration).
- It does not cover M08 (real Google Drive connector), M11 (embedding/indexing orchestration), or M12/M17 (encrypted ephemeral evidence lifecycle) — those are separate, later work items.
