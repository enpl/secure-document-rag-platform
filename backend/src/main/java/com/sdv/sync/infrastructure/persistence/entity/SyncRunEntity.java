package com.sdv.sync.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * F-BE-136 (M09A 신규). {@code sync_runs}(V001 + V008 부분 Unique Index)의 JPA
 * Persistence 매핑 - Source 동기화 실행 이력(SYN-004).
 *
 * <p>{@code status='RUNNING'}인 행은 Source당 최대 1개만 존재할 수 있다(V008
 * {@code uq_sync_runs_source_running}) - 새 Run을 시작하는 쪽(저장 시도)이
 * 이 제약을 위반하면 {@link org.springframework.dao.DataIntegrityViolationException}이
 * 발생하며, 호출자({@code sync.application.SourceSyncService} 등)는 이를 "이미
 * 진행 중"이라는 안전한 실패로 변환한다 - JVM Mutex가 아니라 DB 자체가 여러
 * Backend Instance에 걸쳐 동시 실행을 막는다.</p>
 */
@Entity
@Table(name = "sync_runs")
public class SyncRunEntity {

    /** {@code status} 값 - V001에는 CHECK 제약이 없지만(자유 문자열), 이 클래스가 실제로 쓰는 값만 고정한다. */
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 일부 페이지는 성공했지만 {@code isComplete}가 false였거나 중간에 실패해 전체를 COMPLETED로 표시할 수 없는 상태. */
    public static final String STATUS_PARTIAL_FAILURE = "PARTIAL_FAILURE";
    public static final String STATUS_FAILED = "FAILED";
    /** Lease가 만료된 채로 방치된 RUNNING 행을 다음 인가된 요청이 회수한 상태(V009). */
    public static final String STATUS_ABANDONED = "ABANDONED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    // FULL / INCREMENTAL / PERMISSION - PERMISSION은 기존 ACL-only Use Case도 같은 Source 실행권을 공유한다.
    @Column(name = "mode", nullable = false, length = 30)
    private String mode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "total", nullable = false)
    private int total;

    @Column(name = "success", nullable = false)
    private int success;

    @Column(name = "failed", nullable = false)
    private int failed;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    // V009 - 이 시각이 지나면 다음 인가된 요청이 이 RUNNING 행을 방치된 것으로 간주해
    // 회수(ABANDONED)할 수 있다. runId 자체가 Fencing Token 역할을 한다(SyncRunLifecycle 참고).
    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    protected SyncRunEntity() {
        // JPA
    }

    public SyncRunEntity(Long sourceId, String mode, Instant startedAt, Instant leaseExpiresAt) {
        this.sourceId = sourceId;
        this.mode = mode;
        this.startedAt = startedAt;
        this.total = 0;
        this.success = 0;
        this.failed = 0;
        this.status = STATUS_RUNNING;
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getMode() {
        return mode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public int getTotal() {
        return total;
    }

    public int getSuccess() {
        return success;
    }

    public int getFailed() {
        return failed;
    }

    public String getStatus() {
        return status;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    /** 진행 중 한 문서 처리 결과를 반영한다 - 실제 진행 상황을 그대로 누적한다(가짜 낙관적 카운트 없음). */
    public void recordOutcome(boolean succeeded) {
        this.total++;
        if (succeeded) {
            this.success++;
        } else {
            this.failed++;
        }
    }

    /** 이미 집계된 최종 카운트를 한 번에 반영한다({@code com.sdv.sync.application.job.GoogleDriveSyncJob}의 Page-Loop 결과). */
    public void applyCounts(int total, int success, int failed) {
        this.total = total;
        this.success = success;
        this.failed = failed;
    }

    /** Run을 종결한다 - {@code endedAt}과 최종 status를 함께 기록한다(반복 호출 없음, 종료는 1회). */
    public void finish(String finalStatus, Instant endedAt) {
        this.status = finalStatus;
        this.endedAt = endedAt;
    }
}
