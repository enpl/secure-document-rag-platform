package com.sdv.source.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * F-BE-034 (M09A 신규). {@code source_sync_cursors}(V001)의 JPA Persistence
 * 매핑 - Source 하나당 정확히 한 행(Google {@code changes.list} Page Token
 * 계승용). {@code source_id}가 그대로 기본키다(V001이 이미 그렇게 정의했다 -
 * 별도 Surrogate ID를 추가하지 않는다).
 *
 * <p>이 행을 쓰는(갱신하는) 모든 경로는 반드시 {@link
 * com.sdv.source.infrastructure.persistence.repository.SourceSyncCursorJpaRepository#findBySourceIdForUpdate}
 * 로 먼저 잠근 뒤 같은 Transaction 안에서 Catalog/Outbox 갱신과 함께 Commit해야
 * 한다 - Cursor만 따로 먼저 커밋하면 "일부만 반영된 Catalog인데 Cursor는 이미
 * 전진한" 상태가 생길 수 있다({@code sync.application.AbstractSourceSyncJob}
 * 참고).</p>
 */
@Entity
@Table(name = "source_sync_cursors")
public class SourceSyncCursorEntity {

    @Id
    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "cursor", nullable = false, columnDefinition = "TEXT")
    private String cursor;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // M17 신규 - 다음 자동 증분 확인 예정 시각(Scheduler Pacing). 새 행 생성 시점에는
    // 생성 시각 자체를 기본값으로 둔다 - 실제 자동 대상 여부는 이 값만으로 정해지지
    // 않는다(AutoIncrementalSyncClaimWriter의 조회가 활성 Google + FULL COMPLETED 여부를
    // 함께 확인한다). 실제 Page 반영({@link #advance})은 이 값을 전혀 건드리지 않는다 -
    // 항상 AutoIncrementalSyncClaimWriter의 전용 Bulk Update로만 바뀐다(별도 관심사).
    @Column(name = "next_check_at", nullable = false)
    private Instant nextCheckAt;

    // M17 신규 - 연속 실패 횟수(상한 있는 지수 Backoff 계산용). 성공 시 0으로 재설정된다.
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    protected SourceSyncCursorEntity() {
        // JPA
    }

    public SourceSyncCursorEntity(Long sourceId, String cursor, Instant updatedAt) {
        this.sourceId = sourceId;
        this.cursor = cursor;
        this.updatedAt = updatedAt;
        this.nextCheckAt = updatedAt;
        this.consecutiveFailures = 0;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getCursor() {
        return cursor;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getNextCheckAt() {
        return nextCheckAt;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** 같은 Row(Source)를 새 Page Token/시각으로 전진시킨다 - 새 행을 만들지 않는다. */
    public void advance(String cursor, Instant updatedAt) {
        this.cursor = cursor;
        this.updatedAt = updatedAt;
    }
}
