package com.sdv.sync.application;

import com.sdv.audit.application.AuditService;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.sync.application.job.GoogleDriveSyncJob;
import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * F-BE-063 (M09A 신규, M09A 교정 반영). Source Sync 진입 Use Case(SYN-001) -
 * {@code POST /api/admin/sources/{id}/sync}가 호출하는 유일한 Service
 * Method다.
 *
 * <p>{@link #sync}는 아직 {@code source_sync_cursors} 행이 없으면(한 번도 최초
 * 스캔을 완료하지 못한 Source) {@link #startInitialSync}를 수행하고, 이미
 * 있으면 {@link IncrementalSyncService#syncChanges}로 위임한다 - 클라이언트가
 * "최초냐 증분이냐"를 직접 판단할 필요가 없다(Manifest가 지정한 단일 Sync/
 * Resync 진입점 계약).</p>
 *
 * <p>Run의 시작/종료 원자적 관리(방치된 Run 회수/Fencing 포함)는 {@link
 * SyncRunLifecycle}(별도 Bean)에 위임한다 - Spring self-invocation 함정
 * ({@link SourceSyncPageWriter} Class Javadoc 참고)을 피하기 위함이다.
 * {@link SyncRunEntity#getLeaseExpiresAt()}을 그대로 Page-Loop의 Deadline으로
 * 넘긴다 - 건강한 Run은 자신의 Lease 안에서 스스로 끝난다.</p>
 */
@Service
public class SourceSyncService {

    private final GoogleDriveSyncJob googleDriveSyncJob;
    private final IncrementalSyncService incrementalSyncService;
    private final SyncRunLifecycle syncRunLifecycle;
    private final SyncRunProperties syncRunProperties;
    private final AuditService auditService;

    @Autowired
    public SourceSyncService(GoogleDriveSyncJob googleDriveSyncJob, IncrementalSyncService incrementalSyncService,
            SyncRunLifecycle syncRunLifecycle, SyncRunProperties syncRunProperties, AuditService auditService) {
        this.googleDriveSyncJob = googleDriveSyncJob;
        this.incrementalSyncService = incrementalSyncService;
        this.syncRunLifecycle = syncRunLifecycle;
        this.syncRunProperties = syncRunProperties;
        this.auditService = auditService;
    }

    /** 단일 Sync/Resync 진입점 - 최초/증분 여부를 이 Service가 판단한다. */
    public SyncRunEntity sync(Long sourceId, String ownerSubject) {
        SyncRunEntity run = syncRunLifecycle.beginAutoRun(sourceId, ownerSubject);
        return "INCREMENTAL".equals(run.getMode())
                ? incrementalSyncService.syncChanges(sourceId, ownerSubject, run)
                : executeInitialSync(sourceId, ownerSubject, run);
    }

    /**
     * 최초 전체 스캔 - Source Owner의 Credential로 Whole-Drive Metadata Discovery를
     * 수행하고, 같은 Run 안에서 스캔 도중 쌓인 변경까지 Catch-up한다. 시작 전에 먼저
     * {@code changes.getStartPageToken}을 캡처해둔다(스캔이 진행되는 동안의 변경을
     * 놓치지 않고, Catch-up의 출발점으로 쓰기 위함) - "Freeze된 Snapshot"이라고
     * 주장하지 않는다.
     */
    public SyncRunEntity startInitialSync(Long sourceId, String ownerSubject) {
        SyncRunEntity run = syncRunLifecycle.beginRun(sourceId, ownerSubject, "FULL");
        return executeInitialSync(sourceId, ownerSubject, run);
    }

    private SyncRunEntity executeInitialSync(Long sourceId, String ownerSubject, SyncRunEntity run) {
        try {
            String startCursor = googleDriveSyncJob.captureStartCursor(sourceId);
            GoogleDriveSyncJob.SyncPageLoopResult result = googleDriveSyncJob.runInitialScan(sourceId, run.getId(),
                    startCursor, run.getLeaseExpiresAt(), syncRunProperties.maxPagesPerRun());
            return syncRunLifecycle.finishRun(run.getId(), result, ownerSubject, sourceId, "FULL");
        } catch (RuntimeException failure) {
            syncRunLifecycle.abortRun(sourceId, run.getId());
            auditService.record(ownerSubject, "SOURCE_SYNC_FAILED", "source:" + sourceId, "FAILURE",
                    safeReason(failure), Map.of("mode", "FULL"));
            throw failure;
        }
    }

    /** 테스트 전용 - {@link SyncRunLifecycle#beginRun}을 직접 호출해 "이미 실행 중" 상태를 미리 만든다. */
    SyncRunEntity beginRun(Long sourceId, String ownerSubject, String mode) {
        return syncRunLifecycle.beginRun(sourceId, ownerSubject, mode);
    }

    private static String safeReason(RuntimeException failure) {
        if (failure instanceof SourceSyncException syncFailure) {
            return syncFailure.getReason().name();
        }
        if (failure instanceof SyncAlreadyRunningException) {
            return "ALREADY_RUNNING";
        }
        return "FAILED";
    }
}
