package com.sdv.sync.infrastructure;

import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceSyncCursorJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M17 신규 - {@link AutoIncrementalSyncScheduler}의 DB Claim/성공/실패 Transaction
 * 단위를 담당하는 별도 Bean. {@code com.sdv.event.infrastructure.OutboxPublishWriter}와
 * 동일한 이유(Spring self-invocation 함정, {@code
 * com.sdv.sync.application.SourceSyncPageWriter} Class Javadoc 참고)로 분리했다 -
 * 이 Bean의 각 Method는 짧은 Transaction 하나만 열고, 실제 Google 호출(느릴 수 있는
 * Network I/O)은 {@link AutoIncrementalSyncScheduler}가 이 Bean 밖에서(어떤 DB Row
 * Lock도 쥐지 않은 채) 수행한다.
 *
 * <h2>이 Claim은 정확성이 아니라 Pacing을 위한 것이다</h2>
 * <p>{@link #claimDue}가 고른 Source에 대해 실제로 단 하나의 유효한 Sync Run만
 * 시작될 수 있다는 보장은 이 Class가 아니라 기존 V008 부분 Unique Index + {@code
 * SyncRunLifecycle}의 Fencing이 이미 제공한다(자동/수동/ACL 전용 실행이 전부 같은
 * 그 계약을 공유한다) - 이 Class가 없어도 정확성은 깨지지 않는다. 이 Class의
 * 유일한 역할은 "같은 Source를 매 Tick·매 Instance가 반복해서 재시도하지 않도록"
 * {@code next_check_at}을 실제 호출 전에 먼저 다음 주기로 밀어 두는 것뿐이다.</p>
 *
 * <h2>M17 후속 교정 - 완료 기록(next_check_at/consecutive_failures) 자체의 Fencing</h2>
 * <p>{@link #claimDue}는 매 호출마다 새 {@link UUID} Claim Token을 발급해 Claim된
 * 모든 행에 기록한다({@code source_sync_cursors.claim_token}, V015). Bounded Worker
 * Pool로 여러 Source를 동시 처리하는 동안, 한 Source의 {@code syncChanges} 호출이
 * 예상보다 오래 걸리면(다음 Tick이 이미 그 Source를 다시 Due로 보고 재Claim할 수
 * 있다 - 이전 Claim이 이미 {@code next_check_at}을 다음 주기로 밀어 뒀더라도, 호출
 * 자체가 그 주기보다 오래 걸리면 재Claim이 실제로 일어난다) 두 실행이 겹칠 수 있다.
 * {@link #recordSuccess}/{@link #recordFailure}는 이제 호출자가 자신의 Claim에서
 * 받은 정확한 Token을 함께 넘겨야 하며, 그 사이 다른(더 새로운) Claim이 이미 이
 * 행을 재Claim(새 Token 발급)했다면 조용한 No-op이 된다 - 뒤늦게 도착한 오래된
 * 실행의 완료 보고가 이미 새로 진행 중인 실행의 예약 상태를 덮어쓰지 못한다.</p>
 */
@Service
public class AutoIncrementalSyncClaimWriter {

    private static final Logger log = LoggerFactory.getLogger(AutoIncrementalSyncClaimWriter.class);
    private static final int MAX_BACKOFF_EXPONENT = 20;

    private final SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository;
    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final Clock clock;

    @Autowired
    public AutoIncrementalSyncClaimWriter(SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository) {
        this(sourceSyncCursorJpaRepository, sourceConnectionJpaRepository, Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}(Pacing/Backoff 판정 결정론화)을 직접 주입하기 위한 패키지 전용 생성자. */
    AutoIncrementalSyncClaimWriter(SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository, Clock clock) {
        this.sourceSyncCursorJpaRepository = sourceSyncCursorJpaRepository;
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.clock = clock;
    }

    /**
     * 만기된 Source를 Bounded Batch만큼 Claim하고, 같은 짧은 Transaction 안에서
     * 즉시 {@code next_check_at}을 다음 주기로 밀고 새 Claim Token을 부여한다(Google
     * 호출 전).
     */
    @Transactional
    public List<ClaimedSource> claimDue(int batchSize, long pollIntervalMs) {
        Instant now = clock.instant();
        List<Long> ids = sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(batchSize, now);
        if (ids.isEmpty()) {
            return List.of();
        }
        UUID token = UUID.randomUUID();
        sourceSyncCursorJpaRepository.markClaimedForAutoSync(ids, now.plusMillis(pollIntervalMs), token);
        return sourceConnectionJpaRepository.findAllById(ids).stream()
                .map(source -> new ClaimedSource(source.getId(), source.getOwnerSubject(), token))
                .toList();
    }

    /**
     * 성공(Run이 실제로 COMPLETED로 끝난 경우만 호출자가 이 Method를 부른다 - PARTIAL_FAILURE/
     * ABANDONED는 {@link #recordFailure}로 간다) - 다음 정상 주기로 예약한다. {@code
     * claimToken}이 지금 저장된 값과 일치하지 않으면(더 새로운 Claim이 이미 재Claim했으면)
     * 조용히 아무것도 바꾸지 않는다(Fencing).
     */
    @Transactional
    public void recordSuccess(Long sourceId, UUID claimToken, long pollIntervalMs) {
        int updated = sourceSyncCursorJpaRepository.recordAutoSyncSuccess(sourceId,
                clock.instant().plusMillis(pollIntervalMs), claimToken);
        if (updated == 0) {
            log.debug("auto-incremental sync success ignored - claim token superseded, sourceId={}", sourceId);
        }
    }

    /**
     * 실패(예외 발생, 또는 Run이 COMPLETED가 아닌 상태로 끝난 경우) - 연속 실패 횟수를
     * 원자적으로 증가시킨 뒤, 그 "지금 실제 값" 기준으로 상한 있는 지수 Backoff를
     * 계산해 적용한다. {@code claimToken}이 일치하지 않으면(더 새로운 Claim이 이미
     * 재Claim했으면) 증가 자체가 적용되지 않고 조용히 반환한다(Fencing).
     */
    @Transactional
    public void recordFailure(Long sourceId, UUID claimToken, long pollIntervalMs, long maxBackoffSeconds) {
        int incremented = sourceSyncCursorJpaRepository.incrementConsecutiveFailures(sourceId, claimToken);
        if (incremented == 0) {
            log.debug("auto-incremental sync failure ignored - claim token superseded, sourceId={}", sourceId);
            return;
        }
        int failures = sourceSyncCursorJpaRepository.findCurrentConsecutiveFailures(sourceId);
        long baseSeconds = Math.max(1L, pollIntervalMs / 1000L);
        long backoffSeconds = Math.min(maxBackoffSeconds, baseSeconds << Math.min(failures, MAX_BACKOFF_EXPONENT));
        sourceSyncCursorJpaRepository.applyAutoSyncBackoff(sourceId, clock.instant().plusSeconds(backoffSeconds),
                claimToken);
    }

    /** Claim된 Source 하나 - 실제 {@code IncrementalSyncService.syncChanges} 호출과 완료 기록 Fencing에 필요한 최소 정보. */
    public record ClaimedSource(Long sourceId, String ownerSubject, UUID claimToken) {
    }
}
