package com.sdv.event.infrastructure.persistence.repository;

import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * F-BE-138 (M09A 신규, M09A 교정 - Claim 소유권 Fencing 반영). {@code
 * outbox_events} Persistence(SYN-005) - {@link
 * com.sdv.event.infrastructure.OutboxEventPublisher}가 쓰는 Claim/완료/재시도/
 * 복구 경로를 제공한다.
 *
 * <p>{@link #findClaimableIds}는 {@code FOR UPDATE SKIP LOCKED}로 여러
 * Publisher Instance가 같은 행을 동시에 Claim하지 않게 한다 - 한 Instance가
 * 이미 Lock을 쥔 행은 다른 Instance의 조회에서 조용히 건너뛴다(대기하지
 * 않는다). 이 조회 자체는 짧은 Transaction 안에서만 호출되어야 한다(Broker
 * 호출은 그 Transaction 밖에서 일어난다) - {@link
 * com.sdv.event.infrastructure.OutboxEventPublisher} Class Javadoc 참고.</p>
 *
 * <p><b>Claim Token Fencing(V009)</b>: {@link #markClaimed}가 한 Batch에
 * Claim된 모든 행에 같은 {@code claim_token}을 부여한다. {@link
 * #markPublished}/{@link #markRetry}/{@link #markFailedTerminal}은 모두
 * {@code status='PUBLISHING'}뿐 아니라 그 Token까지 정확히 일치해야만
 * 적용된다 - {@link #recoverStaleClaims}로 다른 Instance가 같은 행을
 * 재Claim(새 Token)한 뒤에는, 원래(만료된) Instance의 뒤늦은 완료/재시도/
 * 실패 Callback이 0행에 적용되어 그 새 Claim을 절대 건드리지 못한다.</p>
 */
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, Long> {

    @Query(value = "SELECT id FROM outbox_events "
            + "WHERE status = 'PENDING' AND next_attempt_at <= :now "
            + "ORDER BY created_at "
            + "LIMIT :batchSize "
            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Long> findClaimableIds(@Param("batchSize") int batchSize, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE OutboxEventEntity o SET o.status = 'PUBLISHING', o.claimedAt = :now, o.claimToken = :token "
            + "WHERE o.id IN :ids")
    int markClaimed(@Param("ids") List<Long> ids, @Param("now") Instant now, @Param("token") UUID token);

    /** {@code status='PUBLISHING'}이고 Claim Token이 정확히 일치할 때만 전이한다(Fencing, 멱등 가드). */
    @Modifying
    @Query("UPDATE OutboxEventEntity o SET o.status = 'PUBLISHED', o.publishedAt = :now "
            + "WHERE o.id = :id AND o.status = 'PUBLISHING' AND o.claimToken = :token")
    int markPublished(@Param("id") Long id, @Param("now") Instant now, @Param("token") UUID token);

    /** Broker 전송 실패 - Bounded 재시도를 위해 PENDING으로 되돌리고 Backoff 시각을 늦춘다(Fencing). */
    @Modifying
    @Query("UPDATE OutboxEventEntity o SET o.status = 'PENDING', o.attempts = :attempts, "
            + "o.nextAttemptAt = :nextAttemptAt, o.claimedAt = null, o.claimToken = null, o.lastError = :lastError "
            + "WHERE o.id = :id AND o.status = 'PUBLISHING' AND o.claimToken = :token")
    int markRetry(@Param("id") Long id, @Param("attempts") int attempts, @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError, @Param("token") UUID token);

    /** Bounded 재시도 소진 - 종결 실패로 표시한다(삭제하지 않는다, 여전히 조회/재현 가능하게 남긴다, Fencing). */
    @Modifying
    @Query("UPDATE OutboxEventEntity o SET o.status = 'FAILED', o.attempts = :attempts, o.claimedAt = null, "
            + "o.claimToken = null, o.lastError = :lastError "
            + "WHERE o.id = :id AND o.status = 'PUBLISHING' AND o.claimToken = :token")
    int markFailedTerminal(@Param("id") Long id, @Param("attempts") int attempts, @Param("lastError") String lastError,
            @Param("token") UUID token);

    /**
     * 안전한 다중 Publisher 복구 - {@code claimedAt}이 {@code staleBefore}보다
     * 오래된 {@code PUBLISHING} 행(Claim한 Instance가 응답 전에 죽은 경우)을
     * 다시 {@code PENDING}으로 되돌려 다른 Instance가 재Claim(새 Token)할 수
     * 있게 한다 - 원래 Token을 지워, 만료된 Instance의 뒤늦은 Callback이 더
     * 이상 일치할 Token 자체가 없게 한다.
     */
    @Modifying
    @Query("UPDATE OutboxEventEntity o SET o.status = 'PENDING', o.claimedAt = null, o.claimToken = null "
            + "WHERE o.status = 'PUBLISHING' AND o.claimedAt < :staleBefore")
    int recoverStaleClaims(@Param("staleBefore") Instant staleBefore);

    /**
     * M11 후속 교정 - 겹치거나 반복되는 색인 스케줄링 호출(공유 생성/수정/관리자 차단
     * 해제, 재연결, 수동 Backfill)이 같은 문서에 대해 아직 소비되지 않은 {@code
     * INDEX_REQUESTED} 요청을 중복으로 쌓지 않도록 하는 가드다. {@code PENDING}/{@code
     * PUBLISHING} 상태만 "아직 살아있는 요청"으로 본다 - 이미 {@code PUBLISHED}되면
     * Consumer 쪽 {@code processed_events} 멱등성이 중복 처리를 막고, {@code FAILED}
     * (Bounded 재시도 소진)는 더 이상 저절로 재시도되지 않으므로 새 요청을 막을 이유가
     * 없다. 이 확인만으로 완벽한 직렬화를 보장하지는 않는다(경쟁 자체는 여전히 가능하다)
     * - 순전히 "뻔히 보이는 반복 호출의 중복 적재"를 줄이기 위한 최선 노력이다.
     */
    @Query("SELECT (COUNT(o) > 0) FROM OutboxEventEntity o WHERE o.partitionKey = :partitionKey "
            + "AND o.eventType = :eventType AND o.status IN ('PENDING', 'PUBLISHING')")
    boolean existsPendingByPartitionKeyAndEventType(@Param("partitionKey") String partitionKey,
            @Param("eventType") String eventType);
}
