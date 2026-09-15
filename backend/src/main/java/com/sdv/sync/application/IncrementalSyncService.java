package com.sdv.sync.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.exception.NotFoundException;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceSyncCursorEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceSyncCursorJpaRepository;
import com.sdv.sync.application.job.GoogleDriveSyncJob;
import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * F-BE-064 (M09A 신규, M09A 교정 반영). 증분 Sync Use Case(SYN-001) - 저장된
 * {@code source_sync_cursors} 행부터 Google {@code changes.list}를 소진한다.
 *
 * <p>{@link SourceSyncService#sync}가 이미 Cursor가 있는 Source에 대해서만
 * 이 Service를 호출한다 - Cursor가 없는 상태로 직접 호출되면(잘못된
 * 호출) {@link SourceSyncException}으로 Fail Closed 한다(존재하지 않는
 * Cursor를 지어내 Google을 부르지 않는다).</p>
 *
 * <p><b>교정 - Cursor는 Run을 확보한 뒤에 읽는다.</b> 이전에는 Cursor를 Run
 * 확보보다 먼저 읽어, 그 사이 다른(더 빠른) 요청이 먼저 Run을 잡고 Cursor를
 * 전진시키면 이 요청이 이미 낡은 Cursor를 그대로 들고 뒤늦게 재생(Replay)할
 * 위험이 있었다. 이제 {@link SyncRunLifecycle#beginRun}(V008 DB Unique
 * Index로 동시 Run 자체를 막는다)을 먼저 확보한 뒤에만 권위 있는 최신
 * Cursor를 읽는다.</p>
 */
@Service
public class IncrementalSyncService {

    private static final String GOOGLE_DRIVE_TYPE = "GOOGLE_DRIVE";

    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository;
    private final GoogleDriveSyncJob googleDriveSyncJob;
    private final SyncRunLifecycle syncRunLifecycle;
    private final SyncRunProperties syncRunProperties;
    private final AuditService auditService;

    @Autowired
    public IncrementalSyncService(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository, GoogleDriveSyncJob googleDriveSyncJob,
            SyncRunLifecycle syncRunLifecycle, SyncRunProperties syncRunProperties, AuditService auditService) {
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.sourceSyncCursorJpaRepository = sourceSyncCursorJpaRepository;
        this.googleDriveSyncJob = googleDriveSyncJob;
        this.syncRunLifecycle = syncRunLifecycle;
        this.syncRunProperties = syncRunProperties;
        this.auditService = auditService;
    }

    public SyncRunEntity syncChanges(Long sourceId, String ownerSubject) {
        validateOwnedActiveGoogleSource(sourceId, ownerSubject);
        SyncRunEntity run = syncRunLifecycle.beginRun(sourceId, ownerSubject, "INCREMENTAL");
        return syncChanges(sourceId, ownerSubject, run);
    }

    SyncRunEntity syncChanges(Long sourceId, String ownerSubject, SyncRunEntity run) {
        try {
            // Run을 확보한 뒤에만 권위 있는 최신 Cursor를 읽는다(위 Class Javadoc 참고).
            SourceSyncCursorEntity cursor = sourceSyncCursorJpaRepository.findBySourceId(sourceId)
                    .orElseThrow(() -> new SourceSyncException(SourceSyncException.Reason.FAILED,
                            "no sync cursor exists yet - run the initial scan first"));
            GoogleDriveSyncJob.SyncPageLoopResult result = googleDriveSyncJob.runIncrementalSync(sourceId,
                    run.getId(), cursor.getCursor(), run.getLeaseExpiresAt(), syncRunProperties.maxPagesPerRun());
            return syncRunLifecycle.finishRun(run.getId(), result, ownerSubject, sourceId, "INCREMENTAL");
        } catch (RuntimeException failure) {
            syncRunLifecycle.abortRun(sourceId, run.getId());
            auditService.record(ownerSubject, "SOURCE_SYNC_FAILED", "source:" + sourceId, "FAILURE",
                    safeReason(failure), Map.of("mode", "INCREMENTAL"));
            throw failure;
        }
    }

    private SourceConnectionEntity validateOwnedActiveGoogleSource(Long sourceId, String ownerSubject) {
        SourceConnectionEntity source = sourceConnectionJpaRepository.findByIdAndOwnerSubject(sourceId, ownerSubject)
                .orElseThrow(() -> new NotFoundException("source connection not found"));
        if (!GOOGLE_DRIVE_TYPE.equals(source.getType())) {
            throw new SourceSyncException(SourceSyncException.Reason.NOT_FOUND,
                    "this source type does not support catalog sync");
        }
        if (!SourceConnection.STATUS_ACTIVE.equals(source.getStatus())) {
            throw new SourceSyncException(SourceSyncException.Reason.NOT_FOUND, "source connection is not active");
        }
        return source;
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
