# Secure Document Vault (SDV)

SDV is a secure enterprise RAG gateway for finding and analyzing documents a user can currently access. Google Drive is the sole Core source and remains the system of record.

## Core architecture

- SDV does not provide an original-file upload repository or retain original bytes, Google Workspace exports, complete extracted text, or plaintext chunks.
- SDV may persist a Metadata/ACL Catalog and a content-free embedding index containing vectors, generalized locators, source version, keyed digest/HMAC, and parser/model version metadata.
- Metadata search covers every Google Drive file type. Before returning a result, SDV rechecks access against Drive as the final user.
- Content search and answers use the Catalog and embeddings only to shortlist candidates. Each request verifies current Drive permission and version, fetches required content transiently, and answers only from version-consistent evidence.
- Selected evidence may remain encrypted for the active conversation for at most 300 seconds from its original creation time. Reuse does not extend that deadline.

Core content answers support PDF, DOCX, TXT, and MD. Google Docs uses a transient DOCX or PDF export. The existing XLSX parser is disabled in the default Core path; other formats remain metadata-only until separately implemented.

## Current status

M01 through M06 are complete at the recorded `c96c885` baseline. The V005 implementation still persists `normalized_text`; this is known drift against the v1.4 retention contract.

The next implementation order is:

1. V006 zero-original-persistence correction
2. Real Google Drive connector
3. Content-free embedding index and transient indexing
4. Mandatory live retrieval and grounded answer flow

See [Core Specification](docs/spec/SDV_v3.2_CORE_SPEC.md) and [Core File Manifest](docs/spec/SDV_v3.2_FILE_MANIFEST.md) for the authoritative repository-readable contract.

## Technology

Java 21 and Spring Boot form the backend; PostgreSQL/pgvector provides allowed durable indexing data; Python/FastAPI provides internal parsing and embedding services; React provides the guided UI; Keycloak, Kafka, and Docker Compose support authentication, asynchronous processing, and local infrastructure.
