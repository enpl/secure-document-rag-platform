package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourceSyncCursorEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F-BE-038 (M09A 신규). {@code source_sync_cursors} Persistence(SYN-001).
 *
 * <p>{@code findBySourceId}는 Manifest가 지정한 canonical 조회다(잠그지 않는
 * 단순 조회 - 예: 다음 Sync 시작 전 현재 Cursor를 진단/표시할 때). 실제 Sync
 * Transaction 안에서 이 행을 갱신할 때는 반드시 {@link
 * #findBySourceIdForUpdate}로 먼저 잠근다 - Catalog/Outbox 갱신과 Cursor 전진이
 * 같은 Row Lock 아래 원자적으로 일어나야 동시 Sync 시도가 서로의 Cursor를
 * 덮어쓰지 않는다({@code sync_runs}의 부분 Unique Index(V008)가 애초에 동시
 * RUNNING 자체를 막지만, 이 Lock은 그와 별개로 "같은 Row를 읽고 쓰는 사이"의
 * 일반적인 Read-Modify-Write 경합을 막는 두 번째 방어선이다).</p>
 */
public interface SourceSyncCursorJpaRepository extends JpaRepository<SourceSyncCursorEntity, Long> {

    Optional<SourceSyncCursorEntity> findBySourceId(Long sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM SourceSyncCursorEntity c WHERE c.sourceId = :sourceId")
    Optional<SourceSyncCursorEntity> findBySourceIdForUpdate(@Param("sourceId") Long sourceId);

    /**
     * M17 신규(자동 증분 동기화), M17 후속 교정(만료 RUNNING도 회수 대상으로 고른다) -
     * {@code com.sdv.sync.infrastructure.AutoIncrementalSyncClaimWriter} 전용 Claim
     * 조회다. 활성(ACTIVE) Google 연결 중 Cursor가 있고, 그 Source의 최초 FULL Sync가
     * 실제로 COMPLETED로 끝났으며(PARTIAL_FAILURE/미완료 FULL은 제외 - "복구 필요"
     * 상태를 자동으로 성공 승격하지 않는다), {@code next_check_at}이 지난 Source만
     * 안정적인 순서(next_check_at, source_id)로 Bounded Batch만큼 고른다.
     *
     * <p><b>교정 - 건강한 RUNNING만 제외한다.</b> 이전에는 이 조회가 {@code
     * status='RUNNING'}인 행이 하나라도 있으면 Lease 만료 여부와 무관하게 그 Source
     * 전체를 제외했다 - 프로세스 중단으로 남은 만료 RUNNING 행은 어떤 수동 요청도 오지
     * 않는 한 이 조회 자체에서 영원히 제외되어, 기존 {@code
     * SyncRunLifecycle.beginRun}의 {@code reapAbandoned} 회수 경로에 도달할 기회조차
     * 얻지 못했다. 이제 {@code r2.lease_expires_at > :now}(아직 건강한 RUNNING)일
     * 때만 제외한다 - 만료된 RUNNING은 그대로 통과시켜, 실제 {@code
     * IncrementalSyncService.syncChanges}→{@code beginRun}이 그 기존 회수(ABANDONED로
     * 전이) + 새 RUNNING 행 획득을 평소와 똑같이 수행하게 한다. 이 조회는 회수를 직접
     * 수행하지 않는다 - 그저 회수 "시도"조차 막지 않을 뿐이다. 두 Worker가 동시에 같은
     * 만료 Source를 Claim해도, 실제 실행권은 여전히 V008 부분 Unique Index +
     * reapAbandoned의 원자성이 하나로 강제한다(둘 중 하나만 성공, 다른 하나는 {@code
     * SyncAlreadyRunningException}).</p>
     *
     * <p>{@code FOR UPDATE OF sc SKIP LOCKED}는 {@code source_sync_cursors} 행만
     * 잠근다 - {@code source_connections}/{@code sync_runs}는 이 조회에서 잠그지
     * 않는다({@code SyncRunLifecycle}이 실제 실행 직전에 자신의 순서(부모 Source
     * 먼저)로 다시 잠근다 - 이 조회가 먼저 그 행을 잠그면 Lock 순서가 뒤집혀 다른
     * 경로와 Deadlock 위험이 생긴다). 여러 Backend Instance가 동시에 이 조회를
     * 실행해도 같은 행을 두 번 Claim하지 않는다({@code
     * OutboxEventJpaRepository#findClaimableIds}와 동일한 관례). 이 조회 자체는
     * 실제 실행(Google 호출/Run 시작)의 정확성을 보장하지 않는다 - 진짜 안전장치는
     * 여전히 V008 부분 Unique Index + SyncRunLifecycle의 Fencing이며, 이 조회는
     * 순전히 "매 Tick 같은 Source를 반복해서 고르지 않기 위한" Pacing 최적화다.</p>
     */
    @Query(value = "SELECT sc.source_id FROM source_sync_cursors sc "
            + "JOIN source_connections c ON c.id = sc.source_id "
            + "WHERE c.status = 'ACTIVE' AND c.type = 'GOOGLE_DRIVE' "
            + "AND sc.next_check_at <= :now "
            + "AND EXISTS (SELECT 1 FROM sync_runs r WHERE r.source_id = sc.source_id "
            + "    AND r.mode = 'FULL' AND r.status = 'COMPLETED') "
            + "AND NOT EXISTS (SELECT 1 FROM sync_runs r2 WHERE r2.source_id = sc.source_id "
            + "    AND r2.status = 'RUNNING' AND r2.lease_expires_at > :now) "
            + "ORDER BY sc.next_check_at ASC, sc.source_id ASC "
            + "LIMIT :batchSize "
            + "FOR UPDATE OF sc SKIP LOCKED",
            nativeQuery = true)
    List<Long> findDueForAutoIncrementalSync(@Param("batchSize") int batchSize, @Param("now") Instant now);

    /**
     * Pre-Claim - 실제 Google 호출 전에 먼저 다음 확인 예정 시각을 다음 주기로 밀어
     * 두고, 이 Batch 전용 소유권 Fencing Token(V015 {@code claim_token})을 부여한다.
     * 뒤늦게 도착하는 이전 Claim의 완료 결과가 이 Token을 다시 확인해야만 적용되므로
     * (아래 {@link #recordAutoSyncSuccess}/{@link #applyAutoSyncBackoff} 참고), 오래
     * 걸린 이전 Tick의 결과가 그 사이 새로 Claim된 상태를 덮어쓰지 못한다.
     */
    @Modifying
    @Query("UPDATE SourceSyncCursorEntity c SET c.nextCheckAt = :nextCheckAt, c.claimToken = :token "
            + "WHERE c.sourceId IN :ids")
    int markClaimedForAutoSync(@Param("ids") List<Long> ids, @Param("nextCheckAt") Instant nextCheckAt,
            @Param("token") UUID token);

    /**
     * 성공 - 다음 정상 주기로 예약하고 연속 실패 횟수를 0으로 재설정한다. {@code
     * claimToken}이 지금 저장된 값과 정확히 일치할 때만 적용된다(Fencing) - 이미 더
     * 새로운 Claim이 이 행을 재Claim(새 Token)했다면 0행에 적용되는 조용한 No-op이다.
     *
     * @return 실제로 적용됐으면 {@code true}(호출자가 구분해 로그할 수 있도록) -
     *         {@code false}면 이 완료 결과는 더 이상 이 행의 소유자가 아니다.
     */
    @Modifying
    @Query("UPDATE SourceSyncCursorEntity c SET c.nextCheckAt = :nextCheckAt, c.consecutiveFailures = 0 "
            + "WHERE c.sourceId = :sourceId AND c.claimToken = :token")
    int recordAutoSyncSuccess(@Param("sourceId") Long sourceId, @Param("nextCheckAt") Instant nextCheckAt,
            @Param("token") UUID token);

    /**
     * 실패 - {@code consecutive_failures}를 DB 컬럼 자신을 기준으로 원자적으로
     * 먼저 증가시킨다({@link com.sdv.source.infrastructure.persistence.repository
     * .SourceConnectionJpaRepository#incrementConnectionEpoch}와 동일한 이유: 두
     * 실패가 겹쳐도 증가분이 유실되지 않는다). {@code claimToken}이 일치할 때만
     * 증가한다(Fencing) - 반환된 영향 행 수가 0이면 이 완료 결과는 더 이상 이 행의
     * 소유자가 아니므로 호출자가 이후 단계를 건너뛴다. Backoff 초는 그 뒤 {@link
     * #findCurrentConsecutiveFailures}로 다시 읽은 "지금 실제 값" 기준으로 호출자가
     * 계산해 {@link #applyAutoSyncBackoff}로 반영한다(역시 같은 Token으로 Fencing).
     */
    @Modifying
    @Query(value = "UPDATE source_sync_cursors SET consecutive_failures = consecutive_failures + 1 "
            + "WHERE source_id = :sourceId AND claim_token = :token", nativeQuery = true)
    int incrementConsecutiveFailures(@Param("sourceId") Long sourceId, @Param("token") UUID token);

    @Query(value = "SELECT consecutive_failures FROM source_sync_cursors WHERE source_id = :sourceId",
            nativeQuery = true)
    Integer findCurrentConsecutiveFailures(@Param("sourceId") Long sourceId);

    /** {@link #incrementConsecutiveFailures}가 반영한 값 기준으로 계산된 Backoff 예정 시각만 적용한다(같은 Token으로 Fencing). */
    @Modifying
    @Query("UPDATE SourceSyncCursorEntity c SET c.nextCheckAt = :nextCheckAt "
            + "WHERE c.sourceId = :sourceId AND c.claimToken = :token")
    int applyAutoSyncBackoff(@Param("sourceId") Long sourceId, @Param("nextCheckAt") Instant nextCheckAt,
            @Param("token") UUID token);
}
