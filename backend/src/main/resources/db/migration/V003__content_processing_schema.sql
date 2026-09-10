-- ============================================================
-- Secure Document Vault
-- V003 : Content Processing Schema
--
-- 역할:
--   source_documents에 RAG/콘텐츠 색인 상태(index_status)와
--   그 사유(index_reason)를 추가한다.
--   document_chunks에 포맷 독립적 Citation Locator
--   (locator_type, locator_value)를 추가한다.
--
-- 개념 구분 (v3.2 명세 기준):
--   state          : Source 문서 생명주기 (SourceDocumentState: ACTIVE, DELETED)
--   index_status   : RAG/콘텐츠 색인 상태 (DocumentIndexStatus,
--                    com.sdv.source.domain.DocumentIndexStatus)
--   index_reason   : index_status의 사유
-- 두 개념은 서로 다르며 병합하지 않는다.
--
-- 주의:
--   V001__baseline.sql, V002__pgvector.sql은 수정하지 않는다.
--   기존 행을 근거 없이 INDEXED로 표시하지 않는다 — 신규/기존
--   행 모두 기본값 PENDING으로 시작한다.
--   기존 page/section 컬럼은 그대로 유지하며, locator 값은
--   명시적 매핑 요구사항 없이 역산/backfill하지 않는다.
-- ============================================================


-- ============================================================
-- 1. source_documents : RAG/콘텐츠 색인 상태
-- ============================================================
ALTER TABLE source_documents
    ADD COLUMN index_status VARCHAR(30) NOT NULL DEFAULT 'PENDING';

ALTER TABLE source_documents
    ADD COLUMN index_reason TEXT;

ALTER TABLE source_documents
    ADD CONSTRAINT chk_source_document_index_status
        CHECK (
            index_status IN (
                               'PENDING',
                               'INDEXED',
                               'SKIPPED_UNSUPPORTED',
                               'SKIPPED_NO_TEXT',
                               'FAILED',
                               'STALE'
                )
            );


-- ============================================================
-- 2. document_chunks : 포맷 독립적 Citation Locator
--
-- 기존 page/section 컬럼은 하위 호환을 위해 그대로 유지한다.
-- locator_type/locator_value는 v3.2 Citation Model
-- (PAGE/SLIDE/SHEET_RANGE/LINE_RANGE/SECTION/DOCUMENT)을 위한
-- 추가 표현이며, NULL을 기본값으로 시작한다.
-- ============================================================
ALTER TABLE document_chunks
    ADD COLUMN locator_type VARCHAR(30);

ALTER TABLE document_chunks
    ADD COLUMN locator_value TEXT;

ALTER TABLE document_chunks
    ADD CONSTRAINT chk_document_chunk_locator_type
        CHECK (
            locator_type IS NULL
                OR locator_type IN (
                                     'PAGE',
                                     'SLIDE',
                                     'SHEET_RANGE',
                                     'LINE_RANGE',
                                     'SECTION',
                                     'DOCUMENT'
                    )
            );
