package com.sdv.sync.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.exception.NotFoundException;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.sync.application.PermissionSyncWriter.PermissionApplyResult;
import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * F-BE-065 (M09A 신규). ACL(만) 다시 동기화하는 독립 Use Case(SRC-005, SYN-003) -
 * Metadata Sync({@link SourceSyncService}/{@link IncrementalSyncService})와
 * 달리 Cursor를 갖지 않는다 - 이미 Catalog에 있는(비삭제) 문서 각각의 현재 ACL을
 * 다시 조회해 반영할 뿐이다.
 *
 * <h2>Fetch(외부 호출)와 Commit(DB Transaction)의 분리</h2>
 * <p>{@link SourceSyncPageWriter}와 동일한 원칙 - 문서 하나당 Google 호출은
 * 이 Class 안에서(Transaction 밖), 그 결과를 반영하는 짧은 Transaction은
 * {@link PermissionSyncWriter}(별도 Bean)가 부모 Source를 재검증한 뒤에만
 * 실행한다. 문서가 많은 Source라도 전체를 하나의 긴 Transaction으로 묶지
 * 않는다(다른 Writer를 오래 막지 않기 위함).</p>
 */
@Service
public class PermissionSyncService {

    private static final String GOOGLE_DRIVE_TYPE = "GOOGLE_DRIVE";
    private static final String EXCLUDED_STATE_DELETED = "DELETED";

    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final GoogleDriveConnector googleDriveConnector;
    private final PermissionSyncWriter permissionSyncWriter;
    private final SyncRunLifecycle syncRunLifecycle;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public PermissionSyncService(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository, GoogleDriveConnector googleDriveConnector,
            PermissionSyncWriter permissionSyncWriter, SyncRunLifecycle syncRunLifecycle, AuditService auditService) {
        this(sourceConnectionJpaRepository, sourceDocumentJpaRepository, googleDriveConnector, permissionSyncWriter,
                syncRunLifecycle, auditService, Clock.systemUTC());
    }

    PermissionSyncService(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository, GoogleDriveConnector googleDriveConnector,
            PermissionSyncWriter permissionSyncWriter, SyncRunLifecycle syncRunLifecycle, AuditService auditService,
            Clock clock) {
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.googleDriveConnector = googleDriveConnector;
        this.permissionSyncWriter = permissionSyncWriter;
        this.syncRunLifecycle = syncRunLifecycle;
        this.auditService = auditService;
        this.clock = clock;
    }

    public PermissionSyncResult syncPermissions(Long sourceId, String ownerSubject) {
        SourceConnectionEntity source = sourceConnectionJpaRepository.findByIdAndOwnerSubject(sourceId, ownerSubject)
                .orElseThrow(() -> new NotFoundException("source connection not found"));
        if (!GOOGLE_DRIVE_TYPE.equals(source.getType())) {
            throw new SourceSyncException(SourceSyncException.Reason.NOT_FOUND,
                    "this source type does not support permission sync");
        }
        if (!SourceConnection.STATUS_ACTIVE.equals(source.getStatus())) {
            throw new SourceSyncException(SourceSyncException.Reason.NOT_FOUND, "source connection is not active");
        }

        SyncRunEntity run = syncRunLifecycle.beginRun(sourceId, ownerSubject, "PERMISSION");
        List<SourceDocumentEntity> documents =
                sourceDocumentJpaRepository.findBySourceIdAndStateNot(sourceId, EXCLUDED_STATE_DELETED);
        int succeeded = 0;
        int failed = 0;
        SyncRunEntity finished;
        try {
            for (SourceDocumentEntity document : documents) {
                if (!clock.instant().isBefore(run.getLeaseExpiresAt())) {
                    failed = documents.size() - succeeded;
                    break;
                }
                // Google 호출 - DB Lock을 쥐지 않지만, 외부 호출 전에 확보한 Source별 Run이
                // Catalog Sync와 이 권한 전용 갱신을 직렬화한다.
                SourcePermissionsResult result = googleDriveConnector.getPermissions(sourceId,
                        document.getSourceDocumentId());
                PermissionApplyResult applied = permissionSyncWriter.applyOneDocument(sourceId, run.getId(),
                        ownerSubject, document.getId(), document.getSourceDocumentId(), result);
                if (!applied.runOwned()) {
                    failed = documents.size() - succeeded;
                    break;
                }
                if (applied.applied()) {
                    succeeded++;
                } else {
                    failed++;
                }
            }
            finished = syncRunLifecycle.finishPermissionRun(sourceId, run.getId(), ownerSubject, documents.size(),
                    succeeded, failed);
        } catch (RuntimeException failure) {
            syncRunLifecycle.abortRun(sourceId, run.getId());
            auditService.record(ownerSubject, "SOURCE_PERMISSION_SYNC", "source:" + sourceId, "FAILURE",
                    failure instanceof SourceSyncException syncFailure ? syncFailure.getReason().name() : "FAILED",
                    Map.of("total", String.valueOf(documents.size()), "succeeded", String.valueOf(succeeded),
                            "failed", String.valueOf(failed)));
            throw failure;
        }
        boolean complete = SyncRunEntity.STATUS_COMPLETED.equals(finished.getStatus());
        String auditResult = complete ? "SUCCESS" : "PARTIAL_FAILURE";
        String reasonCode = complete ? "OK" : "ACL_SYNC_INCOMPLETE";
        auditService.record(ownerSubject, "SOURCE_PERMISSION_SYNC", "source:" + sourceId, auditResult, reasonCode,
                Map.of("total", String.valueOf(documents.size()), "succeeded", String.valueOf(succeeded), "failed",
                        String.valueOf(failed)));
        return new PermissionSyncResult(documents.size(), succeeded, failed);
    }

    public record PermissionSyncResult(int total, int succeeded, int failed) {
    }
}
