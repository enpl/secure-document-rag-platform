package com.sdv.sync.infrastructure.persistence.repository;

import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;
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
 * F-BE-137 (M09A 신규, V009 회수/Fencing 교정 반영). {@code sync_runs}
 * Persistence(SYN-004).
 *
 * <p>{@code findBySourceId}는 Manifest가 지정한 canonical 조회다. 동시 실행
 * 방지 자체는 이 Repository의 메서드가 아니라 V008의 부분 Unique Index({@code
 * uq_sync_runs_source_running})가 담당한다 - {@link #save}로 새
 * {@code RUNNING} 행을 만들려는 시도가 그 제약을 위반하면 JPA/Hibernate가
 * {@link org.springframework.dao.DataIntegrityViolationException}을 던진다.</p>
 *
 * <p>{@link #reapAbandoned}(V009)는 {@code SyncRunLifecycle.beginRun}이
 * 새 Run을 만들기 직전에 호출한다 - Lease가 지난
 * RUNNING 행만, 그것도 같은 Source에 대해서만 회수한다(다른 Source에 영향
 * 없음). {@link #findByIdForUpdate}는 Page Commit/종료 전에 "이 runId가 지금도
 * RUNNING인가"를 재확인하는 Fencing 용도다.</p>
 */
public interface SyncRunJpaRepository extends JpaRepository<SyncRunEntity, Long> {

    List<SyncRunEntity> findBySourceId(Long sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SyncRunEntity r WHERE r.id = :id")
    Optional<SyncRunEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * 같은 Source에 대해 Lease가 지난 RUNNING 행을 ABANDONED로 회수한다 -
     * {@code beginRun}이 새 RUNNING 행을 Insert하기 직전에 호출해, V008 부분
     * Unique Index에 걸리지 않고 새 Run을 시작할 수 있게 한다. 건강한(Lease가
     * 아직 유효한) RUNNING 행은 절대 건드리지 않는다.
     */
    @Modifying
    @Query("UPDATE SyncRunEntity r SET r.status = 'ABANDONED', r.endedAt = :now "
            + "WHERE r.sourceId = :sourceId AND r.status = 'RUNNING' AND r.leaseExpiresAt <= :now")
    int reapAbandoned(@Param("sourceId") Long sourceId, @Param("now") Instant now);
}
