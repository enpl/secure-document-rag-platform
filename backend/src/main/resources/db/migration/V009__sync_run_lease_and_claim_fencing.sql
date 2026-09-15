-- M09A targeted correction: abandoned-run recovery fencing, untrusted-ACL-
-- evidence marker, and Outbox claim-ownership fencing.
--
-- V001~V008은 Immutable이다(V008은 이 세션 이전에 이미 이 작업 트리에 추가된
-- 상태로 취급한다 - 다시 고치지 않는다). 이 Migration은 새 Column만 추가한다.

-- ============================================================
-- 1. sync_runs: 방치된(Abandoned) Run 복구를 위한 Lease
--
-- 각 Run은 시작 시점에 유한한 Lease(sdv.sync.run.max-duration-ms)를 부여받는다.
-- Lease가 지난 RUNNING 행은 다음 인가된 수동 Sync 요청이 들어올 때(별도의
-- 상시 Scheduler 없이) 안전하게 회수(ABANDONED)되고, 그제서야 같은 Source에
-- 대한 새 RUNNING 행을 만들 수 있다(V008 부분 Unique Index와 함께 동작).
-- runId 자체가 Fencing Token 역할을 한다 - SourceSyncPageWriter/SyncRunLifecycle
-- 이 매 Page Commit/종료 전에 "이 runId가 지금도 RUNNING인가"를 다시 확인한다.
-- ============================================================
ALTER TABLE sync_runs
    ADD COLUMN lease_expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- ============================================================
-- 2. source_documents: 신뢰할 수 없는(Untrusted) ACL 증거 표시
--
-- ACL 조회가 실패/불확실(UNKNOWN/FAILED)하면 기존 source_permissions 행은
-- (증거로서) 그대로 두되, 이 시각을 기록해 EffectivePermissionService가 그
-- 문서를 TTL/Freshness와 무관하게 즉시 거부하게 한다 - "낡은 값이 우연히
-- Freshness Window 안에 있어 계속 ALLOW로 남는" 결함을 막는다. 성공적인
-- 재조회가 이 컬럼을 다시 NULL로 원자적으로 되돌린다(신뢰 회복).
-- ============================================================
ALTER TABLE source_documents
    ADD COLUMN permissions_untrusted_since TIMESTAMPTZ;

-- ============================================================
-- 3. outbox_events: Claim 소유권 Fencing Token
--
-- claimBatch 한 번 호출로 함께 Claim된 모든 행은 같은 claim_token(UUID)을
-- 받는다. 이후 markPublished/markRetry/markFailedTerminal은 status='PUBLISHING'
-- 뿐 아니라 claim_token까지 정확히 일치해야만 적용된다 - Stale Claim 복구로
-- 다른 Publisher Instance가 같은 행을 재Claim(새 Token)한 뒤, 원래(만료된)
-- Instance의 뒤늦은 완료/재시도/실패 Callback이 그 새 Claim을 건드리지
-- 못하게 한다(0행 영향, 조용한 No-op).
-- ============================================================
ALTER TABLE outbox_events
    ADD COLUMN claim_token UUID;
