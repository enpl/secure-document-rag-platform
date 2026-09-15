package com.sdv.sync.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.exception.NotFoundException;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceSyncCursorJpaRepository;
import com.sdv.sync.application.job.GoogleDriveSyncJob;
import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;
import com.sdv.sync.infrastructure.persistence.repository.SyncRunJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * M09A 신규(V009 방치된 Run 복구/Fencing 교정 반영) - {@code sync_runs} 행의
 * 시작/종료를 원자적으로 관리하는 별도 Bean. {@link SourceSyncService}/{@link
 * IncrementalSyncService}가 각자 이 Bean을 주입받아 호출한다 - Spring
 * self-invocation 함정({@link SourceSyncPageWriter} Class Javadoc 참고)을
 * 피하기 위해 진짜 Bean 경계를 둔다.
 *
 * <h2>방치된(Abandoned) Run 복구 - 상시 Scheduler 없이</h2>
 * <p>{@link #beginRun}은 새 RUNNING 행을 Insert하기 "직전"에 같은 Source에
 * 대해 Lease가 지난 기존 RUNNING 행이 있으면 먼저 {@code ABANDONED}로
 * 회수한다({@link SyncRunJpaRepository#reapAbandoned}) - 이 회수는 다음
 * 인가된 수동 Sync 요청이 들어올 때만 일어나며, 새 Background Scheduler를
 * 추가하지 않는다. 건강한(Lease가 아직 유효한) RUNNING 행은 절대 건드리지
 * 않으므로, 이 순서(회수 → Insert)와 V008의 부분 Unique Index가 함께
 * 여전히 "Source당 진짜 RUNNING은 최대 1개"를 보장한다.</p>
 *
 * <h2>Fencing - 만료된 Worker는 현재 상태를 절대 되돌릴 수 없다</h2>
 * <p>{@link #finishRun}/{@link #abortRun}은 {@link
 * SyncRunJpaRepository#findByIdForUpdate}로 Row를 잠근 뒤, 그 행이 여전히
 * {@code RUNNING}일 때만 실제로 전이시킨다. 이미 다른 요청에 의해 회수됐거나
 * (ABANDONED) 다른 경로로 종결된 뒤라면 아무것도 바꾸지 않고 현재 상태를
 * 그대로 반환한다 - 만료된(Lease가 지난) Worker의 뒤늦은 완료/실패 보고가
 * 이미 회수/재시작된 Run의 상태를 덮어쓰지 못한다.</p>
 */
@Service
public class SyncRunLifecycle {

    private static final String GOOGLE_DRIVE_TYPE = "GOOGLE_DRIVE";

    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository;
    private final SyncRunJpaRepository syncRunJpaRepository;
    private final AuditService auditService;
    private final SyncRunProperties properties;
    private final Clock clock;

    @Autowired
    public SyncRunLifecycle(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository, SyncRunJpaRepository syncRunJpaRepository,
            AuditService auditService, SyncRunProperties properties) {
        this(sourceConnectionJpaRepository, sourceSyncCursorJpaRepository, syncRunJpaRepository, auditService,
                properties, Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}을 직접 주입하기 위한 패키지 전용 생성자. */
    SyncRunLifecycle(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository, SyncRunJpaRepository syncRunJpaRepository,
            AuditService auditService, SyncRunProperties properties, Clock clock) {
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.sourceSyncCursorJpaRepository = sourceSyncCursorJpaRepository;
        this.syncRunJpaRepository = syncRunJpaRepository;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * V008의 부분 Unique Index가 동시 실행을 DB 레벨로 막는다. 그 전에 먼저
     * 같은 Source의 방치된(Lease 만료) RUNNING 행이 있으면 회수한다(위 Class
     * Javadoc 참고). 반환된 {@link SyncRunEntity#getLeaseExpiresAt()}이 이
     * Run의 Deadline이다 - {@code GoogleDriveSyncJob}의 Page-Loop이 그대로
     * 넘겨받아 스스로 그 시각 전에 멈춘다.
     */
    @Transactional
    public SyncRunEntity beginRun(Long sourceId, String ownerSubject, String mode) {
        lockOwnedActiveGoogleSource(sourceId, ownerSubject);
        return createRun(sourceId, mode);
    }

    /**
     * 단일 Sync 진입점용 획득. Source Lock을 잡은 같은 Transaction 안에서 현재
     * Cursor 존재 여부를 읽고 FULL/INCREMENTAL을 결정하므로, 대기하던 요청이
     * 새 Cursor가 생긴 뒤에도 낡은 "최초 실행" 판단을 재사용하지 않는다.
     */
    @Transactional
    public SyncRunEntity beginAutoRun(Long sourceId, String ownerSubject) {
        lockOwnedActiveGoogleSource(sourceId, ownerSubject);
        String mode = sourceSyncCursorJpaRepository.findBySourceId(sourceId).isPresent() ? "INCREMENTAL" : "FULL";
        return createRun(sourceId, mode);
    }

    private SyncRunEntity createRun(Long sourceId, String mode) {
        Instant now = clock.instant();
        syncRunJpaRepository.reapAbandoned(sourceId, now);
        try {
            Instant leaseExpiresAt = now.plusMillis(properties.maxDurationMs());
            return syncRunJpaRepository.saveAndFlush(new SyncRunEntity(sourceId, mode, now, leaseExpiresAt));
        } catch (DataIntegrityViolationException concurrentRun) {
            // V008 uq_sync_runs_source_running 위반 - 이미 건강한(Lease 유효) RUNNING 행이 있다.
            throw new SyncAlreadyRunningException("a sync is already running for source " + sourceId);
        }
    }

    /**
     * Source DB 변경(Run 종결)과 실제 결과에 맞는 Audit 기록을 같은 Transaction 안에서
     * 함께 Commit/Rollback한다. {@code runId}가 더 이상 {@code RUNNING}이
     * 아니면(다른 요청이 이미 회수/종결했음) 아무것도 바꾸지 않고 현재
     * 상태를 그대로 반환한다(Fencing - 위 Class Javadoc 참고).
     */
    @Transactional
    public SyncRunEntity finishRun(Long runId, GoogleDriveSyncJob.SyncPageLoopResult result, String ownerSubject,
            Long sourceId, String mode) {
        SourceConnectionEntity source = sourceConnectionJpaRepository.findByIdForUpdate(sourceId).orElse(null);
        SyncRunEntity run = syncRunJpaRepository.findByIdForUpdate(runId).orElseThrow();
        Instant now = clock.instant();
        if (!sourceId.equals(run.getSourceId()) || source == null || !ownerSubject.equals(source.getOwnerSubject())) {
            return run;
        }
        if (!SyncRunEntity.STATUS_RUNNING.equals(run.getStatus())) {
            // 이 runId는 더 이상 이 작업의 소유가 아니다 - 되돌리지 않는다.
            return run;
        }
        if (isExpired(run, now)) {
            run.finish(SyncRunEntity.STATUS_ABANDONED, now);
            auditService.record(ownerSubject, "SOURCE_SYNC_ABANDONED", "source:" + sourceId, "PARTIAL_FAILURE",
                    "SYNC_RUN_EXPIRED", Map.of("mode", mode));
            return run;
        }
        int total = result.changed() + result.removed();
        int failed = result.permissionFailures();
        int success = total - failed;
        String status = !SourceConnection.STATUS_ACTIVE.equals(source.getStatus()) || !result.sourceActive()
                || !result.runOwned() || !result.fullyComplete() || failed > 0
                ? SyncRunEntity.STATUS_PARTIAL_FAILURE
                : SyncRunEntity.STATUS_COMPLETED;
        run.applyCounts(total, success, failed);
        run.finish(status, now);
        String auditResult = SyncRunEntity.STATUS_COMPLETED.equals(status) ? "SUCCESS" : "PARTIAL_FAILURE";
        String reasonCode = SyncRunEntity.STATUS_COMPLETED.equals(status) ? "OK" : "SYNC_INCOMPLETE";
        auditService.record(ownerSubject, "SOURCE_SYNC_" + status, "source:" + sourceId, auditResult, reasonCode,
                Map.of("mode", mode, "total", String.valueOf(total), "success", String.valueOf(success), "failed",
                        String.valueOf(failed)));
        return run;
    }

    /** 권한 전용 갱신의 집계를 기록하고, 같은 Run 소유권/기한 규칙으로 종결한다. */
    @Transactional
    public SyncRunEntity finishPermissionRun(Long sourceId, Long runId, String ownerSubject, int total, int success,
            int failed) {
        SourceConnectionEntity source = sourceConnectionJpaRepository.findByIdForUpdate(sourceId).orElse(null);
        SyncRunEntity run = syncRunJpaRepository.findByIdForUpdate(runId).orElseThrow();
        Instant now = clock.instant();
        if (!sourceId.equals(run.getSourceId()) || !SyncRunEntity.STATUS_RUNNING.equals(run.getStatus())) {
            return run;
        }
        run.applyCounts(total, success, failed);
        String status = isExpired(run, now) ? SyncRunEntity.STATUS_ABANDONED
                : source == null || !ownerSubject.equals(source.getOwnerSubject())
                        || !SourceConnection.STATUS_ACTIVE.equals(source.getStatus()) || failed > 0
                                ? SyncRunEntity.STATUS_PARTIAL_FAILURE
                                : SyncRunEntity.STATUS_COMPLETED;
        run.finish(status, now);
        return run;
    }

    /** {@code runId}가 더 이상 {@code RUNNING}이 아니면 아무것도 하지 않는다(Fencing). */
    @Transactional
    public void abortRun(Long sourceId, Long runId) {
        sourceConnectionJpaRepository.findByIdForUpdate(sourceId);
        syncRunJpaRepository.findByIdForUpdate(runId).ifPresent(run -> {
            Instant now = clock.instant();
            if (sourceId.equals(run.getSourceId()) && SyncRunEntity.STATUS_RUNNING.equals(run.getStatus())) {
                run.finish(isExpired(run, now) ? SyncRunEntity.STATUS_ABANDONED : SyncRunEntity.STATUS_FAILED, now);
            }
        });
    }

    private SourceConnectionEntity lockOwnedActiveGoogleSource(Long sourceId, String ownerSubject) {
        SourceConnectionEntity source = sourceConnectionJpaRepository.findByIdForUpdate(sourceId)
                .orElseThrow(() -> new NotFoundException("source connection not found"));
        if (!ownerSubject.equals(source.getOwnerSubject())) {
            throw new NotFoundException("source connection not found");
        }
        if (!GOOGLE_DRIVE_TYPE.equals(source.getType())) {
            throw new SourceSyncException(SourceSyncException.Reason.NOT_FOUND,
                    "this source type does not support synchronization");
        }
        if (!SourceConnection.STATUS_ACTIVE.equals(source.getStatus())) {
            throw new SourceSyncException(SourceSyncException.Reason.NOT_FOUND, "source connection is not active");
        }
        return source;
    }

    private static boolean isExpired(SyncRunEntity run, Instant now) {
        return !run.getLeaseExpiresAt().isAfter(now);
    }
}
