package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourceSyncCursorEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
