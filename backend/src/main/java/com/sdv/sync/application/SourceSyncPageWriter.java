package com.sdv.sync.application;

import com.sdv.common.trace.TraceIdFilter;
import com.sdv.event.domain.DomainEvent;
import com.sdv.event.domain.SourceDocumentChangedEvent;
import com.sdv.event.domain.SourceDocumentDeletedEvent;
import com.sdv.event.domain.SourcePermissionChangedEvent;
import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.source.domain.SourceChangeRecord;
import com.sdv.source.domain.SourceChangeType;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.DocumentAccessMetadataChangedEvent;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourcePermission;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceSyncCursorEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceSyncCursorJpaRepository;
import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;
import com.sdv.sync.infrastructure.persistence.repository.SyncRunJpaRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F-BE-067 (M09A 신규) 실 구현, M09A 교정(방치된 Run Fencing/재시도 가능한
 * ACL 실패) 반영 - {@link AbstractSourceSyncJob}이 정의하는 {@code
 * loadCursor→fetchChanges→persist→publish} 불변식 중 "persist" 단계의 실제
 * 원자적(Transactional) Writer다.
 *
 * <h2>왜 별도 Bean인가 - Spring self-invocation 함정</h2>
 * <p>이 로직은 원래 {@link AbstractSourceSyncJob} 자신의 {@code @Transactional}
 * 메서드였다 - 그러나 그 메서드를 호출하는 쪽({@code
 * com.sdv.sync.application.job.GoogleDriveSyncJob}의 {@code run*} 메서드)이
 * 상속받은 같은 Instance의 메서드를 "self-invocation"으로 호출하면, Spring의
 * Proxy 기반 {@code @Transactional}은 Proxy를 거치지 않으므로 조용히
 * 무시된다(Spring 공식 문서 - "self-invocation... does not lead to an actual
 * transaction at runtime"). 그래서 이 Transactional 단위를 진짜 별도의 Spring
 * Bean으로 분리했다 - {@link AbstractSourceSyncJob}은 이 Bean을 주입받아
 * 호출할 뿐이고, Bean 경계를 진짜로 넘는 호출이라야 Proxy Advice(Transaction)
 * 가 실제로 걸린다.</p>
 *
 * <h2>원자성 + Run Fencing</h2>
 * <p>{@link #applyPage} 하나가 정확히 한 Page 분량의: (1) 부모 {@code
 * source_connections} 행 재검증(Lock, 부모-먼저 순서), (2) 이 Page를 만든
 * {@code runId}가 지금도 {@code sync_runs.status='RUNNING'}인지 재검증(Lock,
 * Source 다음), (3) 문서 Upsert, (4) ACL 교체(있다면)/신뢰 상태 갱신, (5)
 * Outbox 이벤트 기록, (6) Cursor 전진(마지막 Page이고 이 Page에 ACL 실패가
 * 전혀 없을 때만)을 하나의 {@code @Transactional} 안에서 수행한다 - 실패하면
 * 전부 Rollback되어 "일부만 반영된" 상태가 남지 않는다. 동시 Disconnect가
 * Source Lock을 먼저 잡거나, 이 Run의 Lease가 지나 다른 요청이 먼저 회수
 * (ABANDONED)하면 {@link #applyPage}는 즉시 아무것도 반영하지 않고 되돌아간다.</p>
 *
 * <h2>재시도 가능한(Retryable) ACL 실패</h2>
 * <p>한 Page 안에 UNKNOWN/FAILED ACL이 하나라도 있으면, 그 Page의 Cursor는
 * (원래 Commit하기로 했더라도) Commit하지 않는다 - 마지막으로 안전하게
 * Commit된 지점을 그대로 유지해, 다음 Sync 시도가 이 Page부터 다시 시도할 수
 * 있게 한다(새 Queue를 만들지 않는다). 대신 실패한 문서 각각에는 {@code
 * source_documents.permissions_untrusted_since}(V009)를 남겨, 그 사이 이
 * 문서에 대한 인가 판단이 낡은 ACL 행을 신뢰하지 않게 한다({@code
 * EffectivePermissionService} 참고) - 성공적인 재조회만 이 표시를 원자적으로
 * 지운다.</p>
 */
@Service
public class SourceSyncPageWriter {

    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final SourcePermissionJpaRepository sourcePermissionJpaRepository;
    private final SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository;
    private final SyncRunJpaRepository syncRunJpaRepository;
    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final SourceDeletionService sourceDeletionService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;

    @Autowired
    public SourceSyncPageWriter(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourcePermissionJpaRepository sourcePermissionJpaRepository,
            SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository, SyncRunJpaRepository syncRunJpaRepository,
            OutboxEventJpaRepository outboxEventJpaRepository, SourceDeletionService sourceDeletionService,
            ApplicationEventPublisher applicationEventPublisher) {
        this(sourceConnectionJpaRepository, sourceDocumentJpaRepository, sourcePermissionJpaRepository,
                sourceSyncCursorJpaRepository, syncRunJpaRepository, outboxEventJpaRepository, sourceDeletionService,
                Clock.systemUTC(), applicationEventPublisher);
    }

    /** 테스트가 통제된 {@link Clock}을 직접 주입하기 위한 패키지 전용 생성자. */
    SourceSyncPageWriter(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourcePermissionJpaRepository sourcePermissionJpaRepository,
            SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository, SyncRunJpaRepository syncRunJpaRepository,
            OutboxEventJpaRepository outboxEventJpaRepository, SourceDeletionService sourceDeletionService,
            Clock clock) {
        this(sourceConnectionJpaRepository, sourceDocumentJpaRepository, sourcePermissionJpaRepository,
                sourceSyncCursorJpaRepository, syncRunJpaRepository, outboxEventJpaRepository, sourceDeletionService,
                clock, event -> { });
    }

    SourceSyncPageWriter(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourcePermissionJpaRepository sourcePermissionJpaRepository,
            SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository, SyncRunJpaRepository syncRunJpaRepository,
            OutboxEventJpaRepository outboxEventJpaRepository, SourceDeletionService sourceDeletionService,
            Clock clock, ApplicationEventPublisher applicationEventPublisher) {
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.sourcePermissionJpaRepository = sourcePermissionJpaRepository;
        this.sourceSyncCursorJpaRepository = sourceSyncCursorJpaRepository;
        this.syncRunJpaRepository = syncRunJpaRepository;
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.sourceDeletionService = sourceDeletionService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.clock = clock;
    }

    /**
     * 이미 외부에서 가져온 한 Page 분량의 변경을 원자적으로 반영한다.
     * {@code runId}는 이 Page를 만들어낸 현재 Sync Run이다 - 이 Run이 더 이상
     * {@code RUNNING}이 아니면(다른 요청이 방치된 것으로 간주해 회수했으면)
     * 아무것도 반영하지 않는다. {@code cursorToCommit}이 {@code null}이
     * 아니고 이 Page에 ACL 실패가 전혀 없을 때만 같은 Transaction 안에서
     * {@code source_sync_cursors}도 함께 전진시킨다.
     */
    @Transactional
    public PageApplyResult applyPage(Long sourceId, Long runId, List<PreparedChange> preparedChanges,
            String cursorToCommit) {
        SourceConnectionEntity source = sourceConnectionJpaRepository.findByIdForUpdate(sourceId).orElse(null);
        if (source == null || !SourceConnection.STATUS_ACTIVE.equals(source.getStatus())) {
            return PageApplyResult.abortedSourceNotActive();
        }
        SyncRunEntity run = syncRunJpaRepository.findByIdForUpdate(runId).orElse(null);
        Instant now = clock.instant();
        if (run == null || !sourceId.equals(run.getSourceId())
                || !SyncRunEntity.STATUS_RUNNING.equals(run.getStatus())) {
            // 이 Run은 더 이상 이 작업의 소유가 아니다(Lease 만료 후 회수됨 등) - Fencing.
            return PageApplyResult.abortedRunNotOwned();
        }
        if (!run.getLeaseExpiresAt().isAfter(now)) {
            run.finish(SyncRunEntity.STATUS_ABANDONED, now);
            return PageApplyResult.abortedRunNotOwned();
        }
        int changed = 0;
        int removed = 0;
        int permissionFailures = 0;
        for (PreparedChange prepared : preparedChanges) {
            SourceChangeRecord change = prepared.change();
            if (change.type() == SourceChangeType.CHANGED) {
                if (!applyChangedDocument(sourceId, source.getOwnerSubject(), change.document(),
                        prepared.permissions(), now)) {
                    permissionFailures++;
                }
                changed++;
            } else {
                applyRemovedDocument(sourceId, source.getOwnerSubject(), change.sourceDocumentId(),
                        SourceDocumentDeletedEvent.REASON_REMOVED_OR_ACCESS_LOST, now);
                removed++;
            }
        }
        // 재시도 가능한 ACL 실패 - 이 Page에 하나라도 있으면 Cursor를 전진시키지 않는다(마지막으로
        // 안전하게 Commit된 지점을 유지해, 다음 시도가 이 Page부터 다시 시도할 수 있게 한다).
        if (cursorToCommit != null && permissionFailures == 0) {
            advanceCursor(sourceId, cursorToCommit, now);
        }
        return PageApplyResult.ok(changed, removed, permissionFailures);
    }

    /** @return 이 문서의 권한이 신뢰 가능하게(OK) 반영됐으면 {@code true} - Run 집계(성공/실패 카운트)에 쓰인다. */
    private boolean applyChangedDocument(Long sourceId, String ownerSubject, SourceDocument document,
            SourcePermissionsResult permissions, Instant now) {
        Optional<SourceDocumentEntity> existing =
                sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, document.getSourceDocumentId());
        boolean firstPermissionObservation = existing.isEmpty();
        String newState = document.getState().name();
        SourceDocumentEntity entity;
        boolean metadataChanged;
        boolean wasAlreadyDeleted;
        if (existing.isPresent()) {
            entity = existing.get();
            wasAlreadyDeleted = "DELETED".equals(entity.getState());
            metadataChanged = !Objects.equals(entity.getSourceVersion(), document.getSourceVersion())
                    || !newState.equals(entity.getState());
            if (metadataChanged) {
                entity.applySyncedMetadata(document.getName(), document.getMimeType(), document.getSourceVersion(),
                        document.getModifiedAt(), newState);
                sourceDocumentJpaRepository.deleteEmbeddingIndexForDocument(entity.getId());
            }
        } else {
            entity = sourceDocumentJpaRepository.save(new SourceDocumentEntity(sourceId,
                    document.getSourceDocumentId(), document.getName(), document.getMimeType(),
                    document.getSourceVersion(), document.getModifiedAt(), newState, "PENDING", null));
            metadataChanged = true;
            wasAlreadyDeleted = false;
        }

        if ("DELETED".equals(newState)) {
            // Google이 trashed=true로 보고한 경우 - 실제 삭제(REMOVED_OR_ACCESS_LOST)와는 다른, 더 구체적인
            // 사유(TRASHED)로 은퇴시킨다. 이미 DELETED였다면(재확인 Page) 중복 알림을 만들지 않는다(멱등,
            // F-BE-066 SourceDeletionService와 동일한 "새로 전이될 때만 알림" 원칙).
            if (!wasAlreadyDeleted) {
                sourcePermissionJpaRepository.deleteByDocumentId(entity.getId());
                writeOutboxEvent(new SourceDocumentDeletedEvent(UUID.randomUUID(), sourceId, ownerSubject,
                        entity.getId(), document.getSourceDocumentId(), SourceDocumentDeletedEvent.REASON_TRASHED,
                        now, currentTraceId()));
            }
            applicationEventPublisher.publishEvent(new DocumentAccessMetadataChangedEvent(entity.getId()));
            return true;
        }

        boolean permissionsOk = permissions != null && permissions.kind() == SourcePermissionsResult.Kind.OK;
        if (permissionsOk) {
            Set<PermissionKey> previousPermissions = permissionKeys(
                    sourcePermissionJpaRepository.findByDocumentId(entity.getId()));
            Set<PermissionKey> observedPermissions = permissions.permissions().stream()
                    .map(permission -> new PermissionKey(permission.principal().type(), permission.principal().value(),
                            permission.permission()))
                    .collect(Collectors.toUnmodifiableSet());
            boolean trustRestored = entity.getPermissionsUntrustedSince() != null;
            sourcePermissionJpaRepository.deleteByDocumentId(entity.getId());
            for (SourcePermission permission : permissions.permissions()) {
                sourcePermissionJpaRepository.save(new SourcePermissionEntity(entity.getId(),
                        permission.principal().type(), permission.principal().value(), permission.permission(),
                        now));
            }
            // 신뢰 회복 - 이전에 실패로 표시됐었다면(있었다면) 같은 Transaction에서 원자적으로 지운다.
            entity.markPermissionsTrusted();
            if (firstPermissionObservation || trustRestored || !previousPermissions.equals(observedPermissions)) {
                writeOutboxEvent(new SourcePermissionChangedEvent(UUID.randomUUID(), sourceId, ownerSubject,
                        entity.getId(), document.getSourceDocumentId(), now, currentTraceId()));
            }
        } else {
            // 실패/불확실(UNKNOWN/FAILED) - 기존 source_permissions 행은 증거로 그대로 두되(지우거나
            // "새로 확인됨"으로 재기록하지 않는다), 이 시각을 남겨 인가 판단이 그 낡은 행을 더 이상
            // 신뢰하지 않게 한다(V009 permissions_untrusted_since, EffectivePermissionService 참고).
            entity.markPermissionsUntrusted(now);
        }

        if (metadataChanged) {
            writeOutboxEvent(new SourceDocumentChangedEvent(UUID.randomUUID(), sourceId, ownerSubject, entity.getId(),
                    document.getSourceDocumentId(), document.getSourceVersion(), now, currentTraceId()));
        }
        applicationEventPublisher.publishEvent(new DocumentAccessMetadataChangedEvent(entity.getId()));
        return permissionsOk;
    }

    /**
     * REMOVED_OR_ACCESS_LOST(Google {@code changes.list}의 {@code removed=true}) 처리 -
     * 실제 은퇴 절차는 {@link SourceDeletionService#handleDeleted}(F-BE-066)에 위임한다.
     */
    private void applyRemovedDocument(Long sourceId, String ownerSubject, String sourceDocumentId, String reason,
            Instant now) {
        Optional<SourceDocumentEntity> existing =
                sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, sourceDocumentId);
        if (existing.isEmpty()) {
            // 한 번도 Catalog에 존재한 적 없는 문서 - 지어낼 근거가 없으므로 아무것도 하지 않는다.
            return;
        }
        SourceDocumentEntity entity = existing.get();
        boolean retired = sourceDeletionService.handleDeleted(entity);
        if (!retired) {
            return; // 이미 삭제 처리됨 - 멱등(중복 Trigger/재시도에서도 두 번째 이벤트를 만들지 않는다).
        }
        writeOutboxEvent(new SourceDocumentDeletedEvent(UUID.randomUUID(), sourceId, ownerSubject, entity.getId(),
                sourceDocumentId, reason, now, currentTraceId()));
        applicationEventPublisher.publishEvent(new DocumentAccessMetadataChangedEvent(entity.getId()));
    }

    private void advanceCursor(Long sourceId, String cursor, Instant now) {
        SourceSyncCursorEntity entity = sourceSyncCursorJpaRepository.findBySourceIdForUpdate(sourceId).orElse(null);
        if (entity == null) {
            sourceSyncCursorJpaRepository.save(new SourceSyncCursorEntity(sourceId, cursor, now));
        } else {
            entity.advance(cursor, now);
        }
    }

    private void writeOutboxEvent(DomainEvent event) {
        outboxEventJpaRepository.save(new OutboxEventEntity(event.eventId(), event.eventType(), event.toPayload(),
                partitionKeyFor(event), event.occurredAt()));
    }

    private static String partitionKeyFor(DomainEvent event) {
        return switch (event) {
            case SourceDocumentChangedEvent e -> "source:" + e.sourceId() + ":doc:" + e.externalDocumentId();
            case SourceDocumentDeletedEvent e -> "source:" + e.sourceId() + ":doc:" + e.externalDocumentId();
            case SourcePermissionChangedEvent e -> "source:" + e.sourceId() + ":doc:" + e.externalDocumentId();
            // M11 - this Writer never actually creates an IndexRequestedEvent itself
            // (SourceSharingService does, with its own partition-key helper) - this branch only
            // keeps the exhaustive sealed-interface switch compiling now that DomainEvent has a
            // fourth permit.
            case com.sdv.event.domain.IndexRequestedEvent e -> "source:" + e.sourceId() + ":doc:" + e.externalDocumentId();
        };
    }

    private static String currentTraceId() {
        return MDC.get(TraceIdFilter.MDC_KEY);
    }

    private static Set<PermissionKey> permissionKeys(List<SourcePermissionEntity> permissions) {
        return permissions.stream()
                .map(permission -> new PermissionKey(permission.getPrincipalType(), permission.getPrincipalValue(),
                        permission.getPermission()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private record PermissionKey(String principalType, String principalValue, String permission) {
    }

    /** 이미 외부에서 가져온 변경 하나 + (있다면) 그 문서의 이미 조회된 권한 결과. */
    public record PreparedChange(SourceChangeRecord change, SourcePermissionsResult permissions) {
    }

    /**
     * {@link #applyPage} 결과. {@code sourceActive=false}면 부모 Source가 더 이상
     * ACTIVE가 아니어서 아무것도 반영되지 않았다. {@code runOwned=false}면 이 Run의
     * Lease가 지나 다른 요청에 의해 이미 회수돼(ABANDONED) 역시 아무것도 반영되지
     * 않았다. 둘 다 {@code true}일 때만 실제로 Page가 적용된 것이다.
     */
    public record PageApplyResult(boolean sourceActive, boolean runOwned, int changed, int removed,
            int permissionFailures) {
        static PageApplyResult abortedSourceNotActive() {
            return new PageApplyResult(false, true, 0, 0, 0);
        }

        static PageApplyResult abortedRunNotOwned() {
            return new PageApplyResult(true, false, 0, 0, 0);
        }

        static PageApplyResult ok(int changed, int removed, int permissionFailures) {
            return new PageApplyResult(true, true, changed, removed, permissionFailures);
        }
    }
}
