package com.sdv.event.infrastructure;

import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M09A 신규(M09A 교정 - Claim 소유권 Fencing 반영) - {@link OutboxEventPublisher}
 * 의 DB Claim/완료/재시도/복구 Transaction 단위를 담당하는 별도 Bean. {@link
 * com.sdv.sync.application.SourceSyncPageWriter}와 동일한 이유(Spring
 * self-invocation 함정, 그 Class Javadoc 참고)로 분리했다.
 *
 * <h2>Claim Token - 왜 필요한가</h2>
 * <p>{@link #claimBatch} 한 번의 호출로 함께 Claim된 모든 행은 같은
 * {@link UUID} Token을 받는다. 이 Token 없이 {@code status='PUBLISHING'}만
 * 확인하면: Publisher A가 행을 Claim하고 Broker 전송이 오래 걸리는 동안
 * {@link #recoverStaleClaims}가 그 행을 Stale로 판단해 PENDING으로 되돌리고,
 * Publisher B가 같은 행을 다시 Claim(PUBLISHING으로)한 뒤, A의 뒤늦은 완료/
 * 실패 Callback이 "status가 PUBLISHING이니까 내 것"이라고 착각해 B의 진행
 * 중인 작업을 덮어쓸 수 있다. Token을 함께 확인하면 A의 뒤늦은 Callback은
 * 0행에 적용되어 조용히 무시된다.</p>
 */
@Service
public class OutboxPublishWriter {

    private static final int MAX_BACKOFF_SECONDS = 300;

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final Clock clock;

    @Autowired
    public OutboxPublishWriter(OutboxEventJpaRepository outboxEventJpaRepository) {
        this(outboxEventJpaRepository, Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}(Backoff/Stale 판정 결정론화)을 직접 주입하기 위한 패키지 전용 생성자. */
    OutboxPublishWriter(OutboxEventJpaRepository outboxEventJpaRepository, Clock clock) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.clock = clock;
    }

    @Transactional
    public List<OutboxEventEntity> claimBatch(int batchSize) {
        Instant now = clock.instant();
        List<Long> ids = outboxEventJpaRepository.findClaimableIds(batchSize, now);
        if (ids.isEmpty()) {
            return List.of();
        }
        UUID token = UUID.randomUUID();
        outboxEventJpaRepository.markClaimed(ids, now, token);
        return outboxEventJpaRepository.findAllById(ids);
    }

    @Transactional
    public void recoverStaleClaims(long staleClaimSeconds) {
        Instant staleBefore = clock.instant().minusSeconds(staleClaimSeconds);
        outboxEventJpaRepository.recoverStaleClaims(staleBefore);
    }

    /** {@code claimToken}은 {@link #claimBatch}가 이 행을 Claim할 때 부여한 Token이어야 한다(Fencing). */
    @Transactional
    public void markPublished(Long id, UUID claimToken) {
        outboxEventJpaRepository.markPublished(id, clock.instant(), claimToken);
    }

    /**
     * @return 이번 실패로 종결(Terminal FAILED)됐으면 {@code true} - 호출자가 그때만 경고 로그를
     *         남긴다. {@code event.getClaimToken()}이 여전히 일치할 때만 실제로 적용된다(Fencing) -
     *         이미 다른 Instance가 재Claim했다면 이 호출은 0행에 적용되는 조용한 No-op이다.
     */
    @Transactional
    public boolean handleFailure(OutboxEventEntity event, String safeError, int maxAttempts) {
        int attempts = event.getAttempts() + 1;
        String truncated = safeError.length() > 500 ? safeError.substring(0, 500) : safeError;
        UUID token = event.getClaimToken();
        if (attempts >= maxAttempts) {
            return outboxEventJpaRepository.markFailedTerminal(event.getId(), attempts, truncated, token) == 1;
        }
        Instant nextAttemptAt = clock.instant().plusSeconds(backoffSeconds(attempts));
        outboxEventJpaRepository.markRetry(event.getId(), attempts, nextAttemptAt, truncated, token);
        return false;
    }

    /** 2^attempts초, 최대 5분으로 경계 지어진 지수 Backoff. */
    private static long backoffSeconds(int attempts) {
        return Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempts, 20));
    }
}
