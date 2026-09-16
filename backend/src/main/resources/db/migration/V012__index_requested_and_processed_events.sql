-- ============================================================
-- Secure Document Vault
-- V012 : Index-Requested Consumer Idempotency + Embedding Generation
--        Chunking-Version Identity
--
-- M11 (docs/spec/SDV_v3.2_CORE_SPEC.md §2A.11) - completes the M09A
-- event-to-index wiring: an IndexRequestedEvent (new DomainEvent permit,
-- published by SourceSharingService on create/update/admin-unblock) is
-- consumed by com.sdv.rag.infrastructure.event.IndexRequestedConsumer and
-- orchestrated by com.sdv.rag.application.IndexOrchestrator.
--
-- V001~V011은 Immutable이다 - 이 Migration은 새 Table/Column만 추가한다.
-- ============================================================

-- ============================================================
-- 1. processed_events : Consumer 쪽 멱등성 Ledger
--
-- "A received event is not completed work" - Kafka는 At-Least-Once
-- 전달만 보장한다(OutboxEventPublisher Class Javadoc 참고). 이 Table은
-- (event_id, consumer_name) 조합으로 "이 Consumer가 이 이벤트를 이미
-- 종결 처리했다"를 기록한다 - 중복 재전달을 받았을 때 이미 끝난 작업을
-- 다시 하지 않기 위한 것이지, 동시성 정확성 자체는(문서 단위 Lock +
-- document_embedding_index.replaceGeneration의 원자적 세대 교체가) 이미
-- 보장한다 - 이 Table은 관측/감사 겸 재작업 회피용이다.
--
-- outcome은 안전한 고정 값만 담는다(원본 예외 메시지/Stack Trace 금지) -
-- INDEXED / SKIPPED_INELIGIBLE / SKIPPED_UNSUPPORTED / SKIPPED_NO_TEXT /
-- FAILED_TERMINAL / IGNORED_MALFORMED / IGNORED_OTHER_EVENT_TYPE.
-- ============================================================
CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,

    outcome VARCHAR(30) NOT NULL,
    reason_code VARCHAR(200),

    -- Null 허용 - Payload 자체가 Malformed라 document_id를 알아낼 수 없는 경우.
    document_id BIGINT,

    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer_name)
);

-- IndexBackfillService가 "이 문서가 이미 성공적으로 색인됐는가"를 확인할 때 탄다.
CREATE INDEX idx_processed_events_document
    ON processed_events(document_id, consumer_name, outcome);

-- ============================================================
-- 2. document_embedding_index : Chunking Version 명시적 식별자 추가
--
-- 이미 있는 source_version/parser_version/embedding_model만으로는 "같은
-- 입력을 어떤 Chunking 규칙(크기/겹침/개수 상한)으로 나눴는가"를 구분할
-- 수 없다 - Chunking 규칙이 바뀌면(운영자가 크기/겹침 설정을 조정하는 등)
-- 이전 세대의 Chunk 경계와 새 세대가 뒤섞여서는 안 된다. 이 Migration
-- 이전에는 이 Table에 행이 전혀 없었다(M11 자체가 이번에 처음 생긴다) -
-- 기본값은 오직 "이 Column이 추가되기 전에 우연히 존재할 수 있는 행"을
-- 위한 안전망일 뿐, 실제 운영 값이 아니다.
-- ============================================================
ALTER TABLE document_embedding_index
    ADD COLUMN chunking_version VARCHAR(50) NOT NULL DEFAULT 'unversioned';

ALTER TABLE document_embedding_index
    ALTER COLUMN chunking_version DROP DEFAULT;
