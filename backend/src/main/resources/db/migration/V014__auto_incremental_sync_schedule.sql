-- M17 신규 - 다중 사용자 환경을 고려한 자동 증분 동기화(SYN-006, 이번 작업 지시).
--
-- V001~V013은 Immutable이다 - 이 Migration은 새 Column만 추가한다(CLAUDE.md
-- Flyway Migration Immutability).
--
-- ============================================================
-- source_sync_cursors: 자동 증분 Scheduler 전용 Pacing/Backoff 상태
--
-- 기존 source_sync_cursors는 Source당 정확히 한 행이며(V001), IncrementalSyncService
-- 가 이미 "Cursor가 있어야만 증분 대상"이라는 계약을 강제한다 - 자동 Scheduler의
-- 대상 후보 역시 정확히 이 부분집합(활성 Google + Cursor 존재)이므로, 별도의 새
-- Schedule 테이블을 만들지 않고 이 기존 1:1 테이블에 두 Column만 추가한다(최소 범위 -
-- 이 상태를 위해 별도 테이블/Repository 계층을 새로 만들 이유가 없다).
--
-- next_check_at: 이 Source를 다음으로 확인할 예정 시각. Scheduler의 매 Tick이
-- (1) 이 값이 지난 Source만 Bounded Batch로 Claim하고, (2) 실제 Google 호출 전에
-- 먼저 이 값을 다음 주기로 밀어 둔다(Pre-Claim) - 재시작/다중 Backend Instance에서도
-- "언제 다시 확인해야 하는지"가 DB에 영속되어 의미가 유지된다. 기존 행은 즉시
-- 확인 대상이 되도록 DEFAULT CURRENT_TIMESTAMP를 둔다(단, 실제 자동 Scheduler
-- 자체가 기본 비활성화이므로 이 값만으로 아무 자동 호출도 일어나지 않는다).
--
-- consecutive_failures: 연속 실패 횟수 - 429/일시 장애/기타 실패에 상한 있는
-- 지수 Backoff를 적용하기 위한 값이다. 성공 시 0으로 재설정된다.
-- ============================================================
ALTER TABLE source_sync_cursors
    ADD COLUMN next_check_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;

-- Scheduler의 Claim 조회(활성 Google + 만기된 next_check_at)가 쓰는 조회 경로.
CREATE INDEX idx_source_sync_cursors_next_check_at
    ON source_sync_cursors(next_check_at);
