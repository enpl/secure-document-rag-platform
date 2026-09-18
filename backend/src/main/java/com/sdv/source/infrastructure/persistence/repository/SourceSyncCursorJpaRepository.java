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
     * M17 신규(자동 증분 동기화) - {@code
     * com.sdv.sync.infrastructure.AutoIncrementalSyncClaimWriter} 전용 Claim 조회다.
     * 활성(ACTIVE) Google 연결 중 Cursor가 있고, 그 Source의 최초 FULL Sync가 실제로
     * COMPLETED로 끝났으며(PARTIAL_FAILURE/미완료 FULL은 제외 - "복구 필요" 상태를
     * 자동으로 성공 승격하지 않는다), 지금 RUNNING인 Run이 전혀 없고, {@code
     * next_check_at}이 지난 Source만 안정적인 순서(next_check_at, source_id)로
     * Bounded Batch만큼 고른다.
     *
     * <p>{@code FOR UPDATE OF sc SKIP LOCKED}는 {@code source_sync_cursors} 행만
     * 잠근다 - {@code source_connections}는 이 조회에서 잠그지 않는다({@code
     * SyncRunLifecycle}이 실제 실행 직전에 자신의 순서(부모 Source 먼저)로 다시
     * 잠근다 - 이 조회가 먼저 그 행을 잠그면 Lock 순서가 뒤집혀 다른 경로와 Deadlock
     * 위험이 생긴다). 여러 Backend Instance가 동시에 이 조회를 실행해도 같은 행을
     * 두 번 Claim하지 않는다({@code OutboxEventJpaRepository#findClaimableIds}와
     * 동일한 관례). 이 조회 자체는 실제 실행(Google 호출/Run 시작)의 정확성을
     * 보장하지 않는다 - 진짜 안전장치는 여전히 V008 부분 Unique Index +
     * SyncRunLifecycle의 Fencing이며, 이 조회는 순전히 "매 Tick 같은 Source를
     * 반복해서 고르지 않기 위한" Pacing 최적화다.
     */
    @Query(value = "SELECT sc.source_id FROM source_sync_cursors sc "
            + "JOIN source_connections c ON c.id = sc.source_id "
            + "WHERE c.status = 'ACTIVE' AND c.type = 'GOOGLE_DRIVE' "
            + "AND sc.next_check_at <= :now "
            + "AND EXISTS (SELECT 1 FROM sync_runs r WHERE r.source_id = sc.source_id "
            + "    AND r.mode = 'FULL' AND r.status = 'COMPLETED') "
            + "AND NOT EXISTS (SELECT 1 FROM sync_runs r2 WHERE r2.source_id = sc.source_id "
            + "    AND r2.status = 'RUNNING') "
            + "ORDER BY sc.next_check_at ASC, sc.source_id ASC "
            + "LIMIT :batchSize "
            + "FOR UPDATE OF sc SKIP LOCKED",
            nativeQuery = true)
    List<Long> findDueForAutoIncrementalSync(@Param("batchSize") int batchSize, @Param("now") Instant now);

    /** Pre-Claim - 실제 Google 호출 전에 먼저 다음 확인 예정 시각을 다음 주기로 밀어 둔다. */
    @Modifying
    @Query("UPDATE SourceSyncCursorEntity c SET c.nextCheckAt = :nextCheckAt WHERE c.sourceId IN :ids")
    int markClaimedForAutoSync(@Param("ids") List<Long> ids, @Param("nextCheckAt") Instant nextCheckAt);

    /** 성공 - 다음 정상 주기로 예약하고 연속 실패 횟수를 0으로 재설정한다. */
    @Modifying
    @Query("UPDATE SourceSyncCursorEntity c SET c.nextCheckAt = :nextCheckAt, c.consecutiveFailures = 0 "
            + "WHERE c.sourceId = :sourceId")
    void recordAutoSyncSuccess(@Param("sourceId") Long sourceId, @Param("nextCheckAt") Instant nextCheckAt);

    /**
     * 실패 - {@code consecutive_failures}를 DB 컬럼 자신을 기준으로 원자적으로
     * 먼저 증가시킨다({@link com.sdv.source.infrastructure.persistence.repository
     * .SourceConnectionJpaRepository#incrementConnectionEpoch}와 동일한 이유: 두
     * 실패가 겹쳐도 증가분이 유실되지 않는다). Backoff 초는 그 뒤 {@link
     * #findCurrentConsecutiveFailures}로 다시 읽은 "지금 실제 값" 기준으로 호출자가
     * 계산해 {@link #applyAutoSyncBackoff}로 반영한다.
     */
    @Modifying
    @Query(value = "UPDATE source_sync_cursors SET consecutive_failures = consecutive_failures + 1 "
            + "WHERE source_id = :sourceId", nativeQuery = true)
    void incrementConsecutiveFailures(@Param("sourceId") Long sourceId);

    @Query(value = "SELECT consecutive_failures FROM source_sync_cursors WHERE source_id = :sourceId",
            nativeQuery = true)
    Integer findCurrentConsecutiveFailures(@Param("sourceId") Long sourceId);

    /** {@link #incrementConsecutiveFailures}가 반영한 값 기준으로 계산된 Backoff 예정 시각만 적용한다. */
    @Modifying
    @Query("UPDATE SourceSyncCursorEntity c SET c.nextCheckAt = :nextCheckAt WHERE c.sourceId = :sourceId")
    void applyAutoSyncBackoff(@Param("sourceId") Long sourceId, @Param("nextCheckAt") Instant nextCheckAt);
}
