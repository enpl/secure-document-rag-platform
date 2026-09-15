-- M09A: Google Drive catalog sync + durable Outbox-to-Kafka publication
-- (docs/spec/SDV_v3.2_FILE_MANIFEST.md F-BE-062..067/136..137/073..074).
--
-- V001~V007은 Immutable이다 - 이 Migration은 새 Column/Index/Constraint만
-- 추가한다(CLAUDE.md Flyway Migration Immutability).

-- ============================================================
-- 1. sync_runs: 동시 실행 방지(DB 레벨, JVM Mutex 아님)
--
-- 같은 source_id에 대해 status='RUNNING'인 행이 동시에 둘 이상 존재할 수
-- 없도록 부분 Unique Index로 강제한다. SourceSyncService/IncrementalSyncService
-- 는 새 Run을 시작할 때 이 Index를 우회하지 않고, 위반 시 발생하는
-- DataIntegrityViolationException을 "이미 동기화가 진행 중"이라는 명시적
-- 실패로 변환한다 - 여러 Backend Instance에서도 동일하게 안전하다.
-- ============================================================
CREATE UNIQUE INDEX uq_sync_runs_source_running
    ON sync_runs(source_id)
    WHERE status = 'RUNNING';

-- ============================================================
-- 2. outbox_events: 경계 있는(Bounded) 재시도/Backoff/종결 실패 상태 +
--    안전한 다중 Publisher Claim/복구
--
-- status에 PUBLISHING을 추가한다 - PENDING(대기)과 구분되는 "짧은 DB
-- Transaction 안에서 배치를 Claim했지만 아직 Broker 응답을 받지 못한 상태"다.
-- 이렇게 Claim과 실제 Kafka 전송(Network 호출)을 분리해야, Broker 호출 동안
-- 어떤 DB Row Lock/Transaction도 열어두지 않을 수 있다(MVP-22가 기록한
-- "Network 호출 동안 Lock을 쥐고 있던" 실수를 이 Publisher에서 반복하지
-- 않는다). claimed_at이 오래된(Stale) PUBLISHING 행은 별도 복구 절차가
-- PENDING으로 되돌려 다른 Publisher Instance가 다시 Claim할 수 있게 한다.
-- ============================================================
ALTER TABLE outbox_events
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN last_error VARCHAR(500),
    -- Kafka Partition Key - Source/문서 범위로 순서를 보장하기 위한 값
    -- (예: "source:<id>:doc:<sourceDocumentId>"). 원본 Payload를 다시
    -- 파싱하지 않고도 발행 시점에 바로 쓸 수 있도록 쓰기 시점에 함께
    -- 저장한다. Opaque Page Token/Version을 절대 이 값으로 쓰지 않는다.
    ADD COLUMN partition_key VARCHAR(500);

ALTER TABLE outbox_events DROP CONSTRAINT chk_outbox_status;
ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_status
    CHECK (
        status IN (
                   'PENDING',
                   'PUBLISHING',
                   'PUBLISHED',
                   'FAILED'
            )
        );

-- Publisher가 배치를 Claim할 때 쓰는 조회 경로(상태 + 재시도 예정 시각).
CREATE INDEX idx_outbox_events_claimable
    ON outbox_events(status, next_attempt_at);
