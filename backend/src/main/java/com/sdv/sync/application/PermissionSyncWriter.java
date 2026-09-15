package com.sdv.sync.application;

import com.sdv.common.trace.TraceIdFilter;
import com.sdv.event.domain.SourcePermissionChangedEvent;
import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.SourcePermission;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;
import com.sdv.sync.infrastructure.persistence.repository.SyncRunJpaRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * M09A 신규(M09A 교정 - 문서-Source 결합 재검증/신뢰 상태 반영) -
 * {@link PermissionSyncService}(F-BE-065)의 문서 하나 단위 원자적 Writer.
 * {@link SourceSyncPageWriter}와 동일한 이유로 별도 Bean이다(Spring
 * self-invocation 함정 - {@link SourceSyncPageWriter} Class Javadoc 참고).
 *
 * <h2>교정 - 문서-Source 결합과 현재 상태를 다시 검증한다</h2>
 * <p>{@link PermissionSyncService}가 문서 목록을 읽은 시점과 이 Method가 실제로
 * 실행되는 시점 사이에, 같은 문서가 동시 Catalog Sync에 의해 은퇴(DELETED)되거나
 * 완전히 다른 Source로 재배정될 수는 없지만(문서 행은 Source가 고정이다) 최소한
 * 삭제될 수는 있다 - 그런 뒤늦은 ACL-Only 쓰기가 이미 은퇴된 문서를 마치 여전히
 * 유효한 것처럼 되살리지 않도록, 이 Method는 Source Lock 이후 문서 자신도
 * 다시 읽어 {@code sourceId} 일치와 {@code ACTIVE} 상태를 재확인한다.</p>
 */
@Service
public class PermissionSyncWriter {

    private static final String ACTIVE_STATE = "ACTIVE";

    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final SourcePermissionJpaRepository sourcePermissionJpaRepository;
    private final SyncRunJpaRepository syncRunJpaRepository;
    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final Clock clock;

    @Autowired
    public PermissionSyncWriter(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourcePermissionJpaRepository sourcePermissionJpaRepository,
            SyncRunJpaRepository syncRunJpaRepository, OutboxEventJpaRepository outboxEventJpaRepository) {
        this(sourceConnectionJpaRepository, sourceDocumentJpaRepository, sourcePermissionJpaRepository,
                syncRunJpaRepository, outboxEventJpaRepository, Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}을 직접 주입하기 위한 패키지 전용 생성자. */
    PermissionSyncWriter(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourcePermissionJpaRepository sourcePermissionJpaRepository,
            SyncRunJpaRepository syncRunJpaRepository, OutboxEventJpaRepository outboxEventJpaRepository, Clock clock) {
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.sourcePermissionJpaRepository = sourcePermissionJpaRepository;
        this.syncRunJpaRepository = syncRunJpaRepository;
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.clock = clock;
    }

    /**
     * 문서 하나 - 부모 Source를 재검증한 뒤(동시 Disconnect가 뒤늦은 쓰기로
     * 되살아나지 않는다), 문서 자신의 Source 결합/ACTIVE 상태까지 다시
     * 확인한 뒤에만 반영한다(위 Class Javadoc 참고).
     */
    @Transactional
    public PermissionApplyResult applyOneDocument(Long sourceId, Long runId, String ownerSubject, Long documentId,
            String sourceDocumentId, SourcePermissionsResult result) {
        SourceConnectionEntity source = sourceConnectionJpaRepository.findByIdForUpdate(sourceId).orElse(null);
        if (source == null || !ownerSubject.equals(source.getOwnerSubject())
                || !SourceConnection.STATUS_ACTIVE.equals(source.getStatus())) {
            return PermissionApplyResult.notApplied(false);
        }
        SyncRunEntity run = syncRunJpaRepository.findByIdForUpdate(runId).orElse(null);
        Instant now = clock.instant();
        if (run == null || !sourceId.equals(run.getSourceId())
                || !SyncRunEntity.STATUS_RUNNING.equals(run.getStatus())) {
            return PermissionApplyResult.notApplied(false);
        }
        if (!run.getLeaseExpiresAt().isAfter(now)) {
            run.finish(SyncRunEntity.STATUS_ABANDONED, now);
            return PermissionApplyResult.notApplied(false);
        }
        Optional<SourceDocumentEntity> maybeDocument = sourceDocumentJpaRepository.findById(documentId);
        if (maybeDocument.isEmpty()) {
            return PermissionApplyResult.notApplied(true);
        }
        SourceDocumentEntity document = maybeDocument.get();
        if (!sourceId.equals(document.getSourceId()) || !sourceDocumentId.equals(document.getSourceDocumentId())
                || !ACTIVE_STATE.equals(document.getState())) {
            // 이 문서가 다른 Source에 속하거나(있을 수 없지만 방어적으로 확인) 그 사이 은퇴됐다 -
            // 뒤늦은 ACL-Only 쓰기로 되살리지 않는다.
            return PermissionApplyResult.notApplied(true);
        }

        if (result.kind() != SourcePermissionsResult.Kind.OK) {
            // 실패/불확실 - 기존 행을 그대로 둔다(지우거나 "새로 확인됨"으로 재기록하지 않는다).
            // 대신 이 시각을 남겨 인가 판단이 그 낡은 행을 더 이상 신뢰하지 않게 한다(V009).
            document.markPermissionsUntrusted(now);
            return PermissionApplyResult.notApplied(true);
        }
        Set<PermissionKey> previousPermissions = permissionKeys(
                sourcePermissionJpaRepository.findByDocumentId(documentId));
        Set<PermissionKey> observedPermissions = result.permissions().stream()
                .map(permission -> new PermissionKey(permission.principal().type(), permission.principal().value(),
                        permission.permission()))
                .collect(Collectors.toUnmodifiableSet());
        boolean trustRestored = document.getPermissionsUntrustedSince() != null;
        sourcePermissionJpaRepository.deleteByDocumentId(documentId);
        for (SourcePermission permission : result.permissions()) {
            sourcePermissionJpaRepository.save(new SourcePermissionEntity(documentId, permission.principal().type(),
                    permission.principal().value(), permission.permission(), now));
        }
        // 신뢰 회복 - ACL 교체와 같은 Transaction 안에서 원자적으로 지운다.
        document.markPermissionsTrusted();
        if (trustRestored || !previousPermissions.equals(observedPermissions)) {
            SourcePermissionChangedEvent event = new SourcePermissionChangedEvent(UUID.randomUUID(), sourceId,
                    ownerSubject, documentId, sourceDocumentId, now, MDC.get(TraceIdFilter.MDC_KEY));
            outboxEventJpaRepository.save(new OutboxEventEntity(event.eventId(), event.eventType(), event.toPayload(),
                    "source:" + sourceId + ":doc:" + sourceDocumentId, now));
        }
        return PermissionApplyResult.success();
    }

    private static Set<PermissionKey> permissionKeys(java.util.List<SourcePermissionEntity> permissions) {
        return permissions.stream()
                .map(permission -> new PermissionKey(permission.getPrincipalType(), permission.getPrincipalValue(),
                        permission.getPermission()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private record PermissionKey(String principalType, String principalValue, String permission) {
    }

    public record PermissionApplyResult(boolean runOwned, boolean applied) {
        static PermissionApplyResult success() {
            return new PermissionApplyResult(true, true);
        }

        static PermissionApplyResult notApplied(boolean runOwned) {
            return new PermissionApplyResult(runOwned, false);
        }
    }
}
