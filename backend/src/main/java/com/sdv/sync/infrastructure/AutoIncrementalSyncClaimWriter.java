package com.sdv.sync.infrastructure;

import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceSyncCursorJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

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
 */
@Service
public class AutoIncrementalSyncClaimWriter {

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
     * 즉시 {@code next_check_at}을 다음 주기로 밀어 둔다(Google 호출 전).
     */
    @Transactional
    public List<ClaimedSource> claimDue(int batchSize, long pollIntervalMs) {
        Instant now = clock.instant();
        List<Long> ids = sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(batchSize, now);
        if (ids.isEmpty()) {
            return List.of();
        }
        sourceSyncCursorJpaRepository.markClaimedForAutoSync(ids, now.plusMillis(pollIntervalMs));
        return sourceConnectionJpaRepository.findAllById(ids).stream()
                .map(source -> new ClaimedSource(source.getId(), source.getOwnerSubject()))
                .toList();
    }

    /** 성공(또는 Run이 반환된 경우 - COMPLETED/PARTIAL_FAILURE 모두 포함, 예외가 아니면 성공으로 취급) - 다음 정상 주기로 예약한다. */
    @Transactional
    public void recordSuccess(Long sourceId, long pollIntervalMs) {
        sourceSyncCursorJpaRepository.recordAutoSyncSuccess(sourceId, clock.instant().plusMillis(pollIntervalMs));
    }

    /**
     * 실패(예외 발생) - 연속 실패 횟수를 원자적으로 증가시킨 뒤, 그 "지금 실제 값"
     * 기준으로 상한 있는 지수 Backoff를 계산해 적용한다.
     */
    @Transactional
    public void recordFailure(Long sourceId, long pollIntervalMs, long maxBackoffSeconds) {
        sourceSyncCursorJpaRepository.incrementConsecutiveFailures(sourceId);
        int failures = sourceSyncCursorJpaRepository.findCurrentConsecutiveFailures(sourceId);
        long baseSeconds = Math.max(1L, pollIntervalMs / 1000L);
        long backoffSeconds = Math.min(maxBackoffSeconds, baseSeconds << Math.min(failures, MAX_BACKOFF_EXPONENT));
        sourceSyncCursorJpaRepository.applyAutoSyncBackoff(sourceId, clock.instant().plusSeconds(backoffSeconds));
    }

    /** Claim된 Source 하나 - 실제 {@code IncrementalSyncService.syncChanges} 호출에 필요한 최소 정보. */
    public record ClaimedSource(Long sourceId, String ownerSubject) {
    }
}
