-- ============================================================
-- Secure Document Vault
-- V004 : Source Core - Account Isolation & Document Lifecycle
--
-- 역할:
--   source_connections에 소유자(owner_subject, 인증된 Keycloak sub)를
--   추가해 계정 격리(Account Isolation)를 가능하게 하고,
--   source_documents.state를 v3.2 canonical SourceDocumentState
--   (ACTIVE/DELETED)로 제한한다.
--
-- 주의:
--   V001~V003은 수정하지 않는다.
--   기존 설치의 기존 행(owner_subject가 없는 행, 예전 state 값을 가진
--   행)을 이 Migration이 강제로 고치거나 가짜 소유자를 부여하지 않는다 -
--   CHECK ... NOT VALID로 신규/변경 행만 강제하고, 기존 행은 초기 검증을
--   건너뛴 채 그대로 보존한다. Application은 항상 owner_subject =
--   :subject로 조회하므로, owner_subject가 NULL인 레거시 행은 어떤
--   인증된 사용자에게도 보이지 않는다(Fail Closed) - 삭제하거나 값을
--   추측해서 채우지 않는다.
--   향후 별도 작업에서 레거시 행의 실제 소유자를 사람이 확인해 명시적으로
--   채워 넣은 뒤에만 ALTER TABLE ... VALIDATE CONSTRAINT로 완전히
--   강제할 수 있다 - 이 Migration은 그 검증(Validate) 단계까지는
--   수행하지 않는다.
-- ============================================================


-- ============================================================
-- 1. source_connections : 소유자(owner_subject) 추가
-- ============================================================
ALTER TABLE source_connections
    ADD COLUMN owner_subject VARCHAR(255);

CREATE INDEX idx_source_connections_owner_subject
    ON source_connections(owner_subject);

-- 신규로 INSERT되거나 이후 UPDATE되는 행에는 owner_subject가 비어있지
-- 않아야 한다. NOT VALID이므로 기존 행에 대한 초기 검증은 건너뛴다 -
-- 가짜 소유자를 강제로 채우지 않는다.
ALTER TABLE source_connections
    ADD CONSTRAINT chk_source_connection_owner_subject
        CHECK (owner_subject IS NOT NULL AND btrim(owner_subject) <> '')
        NOT VALID;


-- ============================================================
-- 2. source_documents : state를 v3.2 canonical SourceDocumentState로 제한
-- ACTIVE / DELETED만 허용한다 - SYNCED/READY/STALE/FAILED는 더 이상
-- SourceDocumentState 값이 아니다(STALE/FAILED는 DocumentIndexStatus
-- 값일 뿐이다, V003 참고). 마찬가지로 NOT VALID로 기존 행은 보존한다.
-- ============================================================
ALTER TABLE source_documents
    ADD CONSTRAINT chk_source_document_state
        CHECK (state IN ('ACTIVE', 'DELETED'))
        NOT VALID;
