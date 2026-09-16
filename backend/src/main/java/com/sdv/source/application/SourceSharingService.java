package com.sdv.source.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.exception.NotFoundException;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.event.domain.IndexRequestedEvent;
import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.source.domain.DocumentShare;
import com.sdv.source.domain.ShareAction;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareRecipientEntity;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareRestrictionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareRecipientJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareRestrictionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * M10B 신규(SHR-001/002/003/006, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.13) - 게시자
 * 소유 공유 관리 Use Case. Controller는 HTTP 관심사만 다룬다(CLAUDE.md) - 이
 * Service가 소유권/현재 Metadata 재검증, 낙관적 세대 확인, ADMIN 차단 배타성을
 * 전부 강제한다.
 *
 * <p>Client가 보낸 {@code sourceId}/{@code documentId}/{@code publisherSubject}는
 * 그 자체로 인가 증거가 아니다 - 이 Service는 매 쓰기 작업마다 서버가 저장한
 * 실제 소유권({@code source_documents}/{@code source_connections.owner_subject})을
 * 다시 조회해 확인한다("Do not accept caller metadata as authorization evidence").</p>
 */
@Service
public class SourceSharingService {

    private static final String GOOGLE_DRIVE_TYPE = "GOOGLE_DRIVE";
    private static final String DOCUMENT_ACTIVE_STATE = "ACTIVE";
    private static final String SUCCESS = "SUCCESS";
    private static final String OK = "OK";
    /** 공유당 최대 수신자 수 - Bounded 목록 요구사항(무한정 증가를 막는다). */
    static final int MAX_RECIPIENTS = 20;
    private static final int MAX_RECIPIENT_LENGTH = 255;

    private final DocumentShareJpaRepository documentShareJpaRepository;
    private final DocumentShareRecipientJpaRepository documentShareRecipientJpaRepository;
    private final DocumentShareRestrictionJpaRepository documentShareRestrictionJpaRepository;
    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public SourceSharingService(DocumentShareJpaRepository documentShareJpaRepository,
            DocumentShareRecipientJpaRepository documentShareRecipientJpaRepository,
            DocumentShareRestrictionJpaRepository documentShareRestrictionJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            OutboxEventJpaRepository outboxEventJpaRepository, AuditService auditService) {
        this(documentShareJpaRepository, documentShareRecipientJpaRepository, documentShareRestrictionJpaRepository,
                sourceDocumentJpaRepository, sourceConnectionJpaRepository, outboxEventJpaRepository, auditService,
                Clock.systemUTC());
    }

    SourceSharingService(DocumentShareJpaRepository documentShareJpaRepository,
            DocumentShareRecipientJpaRepository documentShareRecipientJpaRepository,
            DocumentShareRestrictionJpaRepository documentShareRestrictionJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            OutboxEventJpaRepository outboxEventJpaRepository, AuditService auditService, Clock clock) {
        this.documentShareJpaRepository = documentShareJpaRepository;
        this.documentShareRecipientJpaRepository = documentShareRecipientJpaRepository;
        this.documentShareRestrictionJpaRepository = documentShareRestrictionJpaRepository;
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * {@code POST /api/shares} - 정확한 파일 하나를 지정 수신자에게 게시한다.
     * 문서당 활성 공유는 하나뿐이다(V010 부분 Unique Index) - 이미 활성 공유가
     * 있으면 {@link InvalidShareRequestException}(PATCH로 갱신하거나 먼저
     * unshare해야 한다).
     *
     * <h2>M10B 보안 교정(그룹 B) - 관리자 차단 우회 방지</h2>
     * <p>이 문서에 아직 해제되지 않은 관리자 차단({@code document_share_restrictions},
     * V011 - {@code document_shares.id}가 아니라 이 파일의 정규 신원에 결합돼 있다)이
     * 있으면, 새로 만드는 공유도 처음부터 차단된 채로 태어난다 - 게시자가 차단된
     * 공유를 unshare한 뒤 새 shareId로 재게시해서 차단을 지울 수 없다. 이 확인/기록을
     * {@link SourceDocumentJpaRepository#findByIdForUpdate}로 문서 행을 먼저 잠근
     * 채로 수행한다 - 같은 문서를 동시에 차단하려는 {@link #adminSetBlocked}와
     * 경쟁해도 둘 중 하나가 완전히 끝난 뒤에만 다른 하나가 진행된다(Lost Update 방지,
     * Network 호출이 없으므로 Lock 보유 자체는 짧고 안전하다).</p>
     */
    @Transactional
    public DocumentShare createShare(String publisherSubject, Long sourceId, Long documentId,
            String classificationRaw, Set<String> actionsRaw, Set<String> recipientsRaw) {
        SecurityLevel classification = parseClassification(classificationRaw);
        Set<ShareAction> actions = parseActions(actionsRaw);
        Set<String> recipients = normalizeRecipients(recipientsRaw);

        SourceDocumentEntity document = requireOwnedActiveDocument(publisherSubject, sourceId, documentId);
        requireOwnedActiveGoogleDriveSource(publisherSubject, sourceId);
        // 이 문서에 대한 공유/차단 갱신을 서로 직렬화한다(아래 Class Javadoc 참고).
        sourceDocumentJpaRepository.findByIdForUpdate(document.getId());

        if (documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(document.getId()).isPresent()) {
            throw new InvalidShareRequestException(
                    "This document already has an active share - update or unshare it first.");
        }

        Instant now = clock.instant();
        DocumentShareEntity entity = new DocumentShareEntity(publisherSubject, sourceId, document.getId(),
                classification.name(), joinActions(actions), now);
        documentShareRestrictionJpaRepository.findBySourceIdAndDocumentId(sourceId, document.getId())
                .filter(DocumentShareRestrictionEntity::isBlocked)
                .ifPresent(restriction -> entity.applyAdminBlock(true, restriction.getBlockedReason(), now));
        DocumentShareEntity saved = documentShareJpaRepository.save(entity);
        saveRecipients(saved.getId(), recipients);
        if (!saved.isAdminBlocked()) {
            // M11 - 관리자 차단으로 태어난 공유는 애초에 색인 자격이 없다(IndexOrchestrator가
            // 어차피 거부하겠지만, 헛된 Google/Python 호출을 굳이 예약하지 않는다).
            publishIndexRequested(document, saved.getPublisherSubject());
        }

        auditService.record(publisherSubject, "SHARE_PUBLISHED", "share:" + saved.getId(), SUCCESS, OK, Map.of());
        return toDomain(saved, recipients);
    }

    /** {@code GET /api/shares} - 내가 게시한 모든 공유(철회 이력 포함) 목록. */
    @Transactional(readOnly = true)
    public List<DocumentShare> listOwn(String publisherSubject) {
        List<DocumentShare> result = new ArrayList<>();
        for (DocumentShareEntity entity : documentShareJpaRepository
                .findAllByPublisherSubjectOrderByCreatedAtDesc(publisherSubject)) {
            result.add(toDomain(entity, recipientsOf(entity.getId())));
        }
        return List.copyOf(result);
    }

    /**
     * {@code PATCH /api/shares/{shareId}} - 수신자/등급/행위를 갱신한다.
     * {@code expectedGeneration}이 현재 값과 다르면 {@link
     * ShareGenerationConflictException}(다른 소유자의 자원 존재 여부를 노출하지 않는
     * 안전한 409) - ADMIN 차단 자체는 이 메서드로 해제할 수 없다(그 필드를 건드리지
     * 않는다, {@code SharedFileAdminController}만 가능).
     *
     * <h2>M10B 보안 교정(그룹 A) - Java 값 비교만으로는 부족했다</h2>
     * <p>{@code share.getGeneration() != expectedGeneration}만 확인하고 그대로
     * 저장하면, 두 동시 Transaction이 똑같이 그 확인을 통과한 뒤 서로의 변경을
     * 덮어쓸 수 있었다(고전적 Lost Update) - {@code UPDATE ... WHERE id=?}에는
     * {@code generation} 조건이 전혀 없었기 때문이다. {@link DocumentShareEntity#generation}이
     * 이제 진짜 JPA {@code @Version}이므로, 이 확인은 빠른 실패(Fail Fast)를 위한
     * 사전 확인일 뿐이고, 실제 강제는 아래 {@code saveAndFlush}가 던질 수 있는
     * {@link ObjectOptimisticLockingFailureException}이 담당한다 - 그 사이 다른
     * Transaction(예: {@link #adminSetBlocked})이 먼저 커밋했다는 뜻이며, 수신자
     * 삭제/재삽입을 포함해 이 Transaction 전체가 함께 Rollback된다(이 메서드가
     * {@code @Transactional}이므로).</p>
     */
    @Transactional
    public DocumentShare updateShare(String publisherSubject, Long shareId, long expectedGeneration,
            String classificationRaw, Set<String> actionsRaw, Set<String> recipientsRaw) {
        SecurityLevel classification = parseClassification(classificationRaw);
        Set<ShareAction> actions = parseActions(actionsRaw);
        Set<String> recipients = normalizeRecipients(recipientsRaw);

        DocumentShareEntity share = requireActiveOwnShare(publisherSubject, shareId);
        if (share.getGeneration() != expectedGeneration) {
            throw new ShareGenerationConflictException(
                    "The share has changed since it was last read - reload and retry.");
        }

        share.applyOwnerUpdate(classification.name(), joinActions(actions), clock.instant());
        documentShareJpaRepository.deleteRecipients(shareId);
        saveRecipients(shareId, recipients);
        try {
            documentShareJpaRepository.saveAndFlush(share);
        } catch (ObjectOptimisticLockingFailureException raceLostToAnotherWriter) {
            throw new ShareGenerationConflictException(
                    "The share has changed since it was last read - reload and retry.");
        }
        if (!share.isAdminBlocked()) {
            // M11 - 등급/행위가 바뀌면 AI 색인 자격 판단(AiUsagePolicyService, 등급 기준)도
            // 달라질 수 있다 - IndexOrchestrator가 소비 시점에 다시 처음부터 판단한다.
            SourceDocumentEntity document = sourceDocumentJpaRepository.findById(share.getDocumentId()).orElse(null);
            if (document != null) {
                publishIndexRequested(document, share.getPublisherSubject());
            }
        }

        auditService.record(publisherSubject, "SHARE_UPDATED", "share:" + shareId, SUCCESS, OK, Map.of());
        return toDomain(share, recipients);
    }

    /**
     * {@code DELETE /api/shares/{shareId}} - 명시적 unshare. Disconnect와 완전히
     * 별개 연산이다 - 이 메서드는 연결/문서에 전혀 손대지 않는다. 철회된 공유는
     * 되살릴 수 없다(재공유는 새 {@link #createShare}). {@code generation}이 이제
     * {@code @Version}이므로, 이 저장도 같은 행을 향한 동시 쓰기(예: {@link
     * #updateShare})와 경쟁하면 안전하게 충돌로 실패한다({@code
     * ObjectOptimisticLockingFailureException} → {@link ShareGenerationConflictException}).
     */
    @Transactional
    public void unshare(String publisherSubject, Long shareId) {
        DocumentShareEntity share = requireActiveOwnShare(publisherSubject, shareId);
        // M11 - 이 문서에 대한 IndexOrchestrator의 발행 직전 재검증과 직렬화한다(Class
        // Javadoc/IndexOrchestrator Class Javadoc "동시성 - 명시적 Lock 순서" 참고) - 둘
        // 다 같은 source_documents 행을 잠그므로, 어느 쪽이 먼저 시작했든 한쪽이 완전히
        // Commit할 때까지 다른 쪽이 대기한다(오래된 색인 작업이 방금 철회된 공유의
        // Embedding을 뒤늦게 되살리는 것을 막는다).
        sourceDocumentJpaRepository.findByIdForUpdate(share.getDocumentId());
        share.revoke(clock.instant());
        try {
            documentShareJpaRepository.saveAndFlush(share);
        } catch (ObjectOptimisticLockingFailureException raceLostToAnotherWriter) {
            throw new ShareGenerationConflictException(
                    "The share has changed since it was last read - reload and retry.");
        }
        // M11 - 철회는 즉시 색인 자격을 무효화한다("Deletion/revocation/disconnect must
        // promptly invalidate eligibility and remove or retire affected embeddings").
        // index_status 자체는 건드리지 않는다 - SourceDeletionService.handleDeleted(M09A)와
        // 동일한 선례: index_status는 "마지막 처리 시도"만 기록할 뿐, 지금의 공유 자격은
        // document_shares.revoked_at/admin_blocked가 단독으로 결정한다.
        sourceDocumentJpaRepository.deleteEmbeddingIndexForDocument(share.getDocumentId());
        auditService.record(publisherSubject, "SHARE_UNSHARED", "share:" + shareId, SUCCESS, OK, Map.of());
    }

    /** {@code GET /api/admin/shares} - 활성 공유만 관리 대상이다(철회된 공유는 이미 아무 접근도 부여하지 않는다). */
    @Transactional(readOnly = true)
    public List<DocumentShare> adminList() {
        List<DocumentShare> result = new ArrayList<>();
        for (DocumentShareEntity entity : documentShareJpaRepository.findAllByRevokedAtIsNullOrderByCreatedAtDesc()) {
            result.add(toDomain(entity, recipientsOf(entity.getId())));
        }
        return List.copyOf(result);
    }

    /**
     * {@code PATCH /api/admin/shares/{shareId}} - 게시된 자료의 정책/차단만 관리한다.
     * 수신자/등급/행위는 이 경로로 절대 바꿀 수 없다(그 필드들을 건드리지 않는다) -
     * "Administrative changes cannot expand publisher consent."
     *
     * <h2>M10B 보안 교정(그룹 B) - 차단을 파일의 정규 신원에 영구 결합한다</h2>
     * <p>이 공유 자신의 {@code adminBlocked}뿐 아니라, 이 파일의 정규 신원({@code
     * sourceId}+{@code documentId})에 결합된 {@code document_share_restrictions}
     * (V011) 행도 함께 갱신한다 - 그래야 게시자가 나중에 이 공유를 unshare하고 새
     * shareId로 재게시해도 {@link #createShare}가 이 restriction을 보고 새 공유를
     * 처음부터 차단된 채로 만든다. 이 문서에 대한 {@link #createShare}와 직렬화하기
     * 위해 같은 문서 행을 먼저 잠근다(Class Javadoc 참고) - ADMIN 해제는 이 행의
     * {@code cleared_by_subject}/{@code cleared_at}만 남기고, 이미 철회된 다른
     * 공유를 되살리거나 수신자/행위/등급을 넓히지 않는다.</p>
     */
    @Transactional
    public DocumentShare adminSetBlocked(String adminSubject, Long shareId, boolean blocked, String reason) {
        DocumentShareEntity share = documentShareJpaRepository.findByIdAndRevokedAtIsNull(shareId)
                .orElseThrow(() -> new NotFoundException("Share not found"));
        // 이 문서에 대한 공유/차단 갱신을 createShare와 직렬화한다(위 Javadoc 참고).
        sourceDocumentJpaRepository.findByIdForUpdate(share.getDocumentId());

        Instant now = clock.instant();
        String safeReason = blankToNull(reason);
        boolean wasBlocked = share.isAdminBlocked();
        share.applyAdminBlock(blocked, safeReason, now);
        applyRestriction(share.getSourceId(), share.getDocumentId(), adminSubject, blocked, safeReason, now);
        try {
            documentShareJpaRepository.saveAndFlush(share);
        } catch (ObjectOptimisticLockingFailureException raceLostToAnotherWriter) {
            throw new ShareGenerationConflictException(
                    "The share has changed since it was last read - reload and retry.");
        }
        if (blocked && !wasBlocked) {
            // M11 - 차단은 unshare와 동일하게 즉시 색인 자격을 무효화한다.
            sourceDocumentJpaRepository.deleteEmbeddingIndexForDocument(share.getDocumentId());
        } else if (!blocked && wasBlocked) {
            // M11 - 해제는 색인 자격을 되돌릴 수 있다 - 새 IndexRequestedEvent로 다시 판단하게 한다.
            SourceDocumentEntity document = sourceDocumentJpaRepository.findById(share.getDocumentId()).orElse(null);
            if (document != null) {
                publishIndexRequested(document, share.getPublisherSubject());
            }
        }

        auditService.record(adminSubject, blocked ? "SHARE_ADMIN_BLOCKED" : "SHARE_ADMIN_UNBLOCKED",
                "share:" + shareId, SUCCESS, OK, Map.of());
        return toDomain(share, recipientsOf(shareId));
    }

    /**
     * 파일의 정규 신원에 결합된 차단 표시를 갱신한다. 이미 있는 행을 재사용한다(문서당
     * 하나뿐 - Class/V011 Javadoc 참고) - 해제(blocked=false) 요청인데 아직 아무 행도
     * 없으면(한 번도 차단된 적 없음) 아무것도 하지 않는다(지울 것이 없다).
     */
    private void applyRestriction(Long sourceId, Long documentId, String adminSubject, boolean blocked,
            String reason, Instant now) {
        Optional<DocumentShareRestrictionEntity> existing = documentShareRestrictionJpaRepository
                .findBySourceIdAndDocumentId(sourceId, documentId);
        if (blocked) {
            DocumentShareRestrictionEntity restriction = existing
                    .orElseGet(() -> new DocumentShareRestrictionEntity(sourceId, documentId, adminSubject, now));
            restriction.applyBlock(adminSubject, reason, now);
            documentShareRestrictionJpaRepository.save(restriction);
        } else {
            existing.ifPresent(restriction -> {
                restriction.clear(adminSubject, now);
                documentShareRestrictionJpaRepository.save(restriction);
            });
        }
    }

    // ------------------------------------------------------------------
    // 서버 측 검증 - Client가 보낸 값은 그 자체로 인가 증거가 아니다.
    // ------------------------------------------------------------------

    private SourceDocumentEntity requireOwnedActiveDocument(String publisherSubject, Long sourceId,
            Long documentId) {
        SourceDocumentEntity document = sourceDocumentJpaRepository.findByIdAndOwnerSubject(documentId,
                publisherSubject).orElseThrow(() -> new NotFoundException("Source document not found"));
        if (!document.getSourceId().equals(sourceId) || !DOCUMENT_ACTIVE_STATE.equals(document.getState())) {
            // sourceId가 실제 소속과 다르거나(위조 시도) 문서가 이미 삭제됨 - 존재하지
            // 않는 것과 동일하게 취급해 Cross-Source 결합 시도 여부를 노출하지 않는다.
            throw new NotFoundException("Source document not found");
        }
        return document;
    }

    private void requireOwnedActiveGoogleDriveSource(String publisherSubject, Long sourceId) {
        SourceConnectionEntity connection = sourceConnectionJpaRepository
                .findByIdAndOwnerSubject(sourceId, publisherSubject)
                .orElseThrow(() -> new NotFoundException("Source connection not found"));
        if (!GOOGLE_DRIVE_TYPE.equals(connection.getType())
                || !SourceConnection.STATUS_ACTIVE.equals(connection.getStatus())) {
            throw new NotFoundException("Source connection not found");
        }
    }

    private DocumentShareEntity requireActiveOwnShare(String publisherSubject, Long shareId) {
        DocumentShareEntity share = documentShareJpaRepository.findByIdAndPublisherSubject(shareId, publisherSubject)
                .orElseThrow(() -> new NotFoundException("Share not found"));
        if (!share.isActive()) {
            // 이미 철회된 공유 - 되살릴 수 없다("explicit unshare removes active grants
            // without allowing reconnect revival"), 더 이상 수정 대상이 아니다.
            throw new NotFoundException("Share not found");
        }
        return share;
    }

    private static SecurityLevel parseClassification(String raw) {
        if (raw == null || raw.isBlank()) {
            // 누락된 등급은 안전하게 거부한다 - 다섯 번째 값을 지어내지 않는다(CORE_SPEC §2A.13).
            throw new InvalidShareRequestException("classification is required");
        }
        try {
            return SecurityLevel.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidShareRequestException("classification must be one of PUBLIC/INTERNAL/CONFIDENTIAL/SECRET");
        }
    }

    private static Set<ShareAction> parseActions(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new InvalidShareRequestException("at least one action must be granted");
        }
        Set<ShareAction> actions = EnumSet.noneOf(ShareAction.class);
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                throw new InvalidShareRequestException("action must not be blank");
            }
            try {
                actions.add(ShareAction.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new InvalidShareRequestException("unrecognized action: " + value);
            }
        }
        return actions;
    }

    /**
     * 수신자 목록을 검증/정규화한다 - 빈 목록은 "전체 공개"로 절대 해석되지 않고
     * 이 메서드 자체가 거부한다. 정확한 OIDC subject 문자열만 받는다(이메일/
     * 도메인/Wildcard/Group 표기를 검증하지 않고 그대로 문자열로 저장하지도
     * 않는다 - 이 M10B Slice는 그런 값을 해석할 방법이 없으므로, 형태만 검사한다).
     */
    private static Set<String> normalizeRecipients(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new InvalidShareRequestException("recipients must not be empty");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                throw new InvalidShareRequestException("recipient subject must not be blank");
            }
            String trimmed = value.trim();
            if (trimmed.length() > MAX_RECIPIENT_LENGTH) {
                throw new InvalidShareRequestException("recipient subject exceeds the maximum length");
            }
            normalized.add(trimmed);
        }
        if (normalized.size() > MAX_RECIPIENTS) {
            throw new InvalidShareRequestException("too many recipients (max " + MAX_RECIPIENTS + ")");
        }
        return normalized;
    }

    private void saveRecipients(Long shareId, Set<String> recipients) {
        List<DocumentShareRecipientEntity> rows = new ArrayList<>(recipients.size());
        for (String recipient : recipients) {
            rows.add(new DocumentShareRecipientEntity(shareId, recipient));
        }
        documentShareRecipientJpaRepository.saveAll(rows);
    }

    private Set<String> recipientsOf(Long shareId) {
        return Set.copyOf(documentShareJpaRepository.findRecipientSubjects(shareId));
    }

    private static String joinActions(Set<ShareAction> actions) {
        StringBuilder builder = new StringBuilder();
        for (ShareAction action : actions) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(action.name());
        }
        return builder.toString();
    }

    /**
     * M11 신규 - "지금 이 문서가 색인 자격이 있을 수 있으니 다시 확인해 달라"는
     * 신호를 Outbox에 원자적으로(이 메서드를 호출하는 각 Use Case의 같은
     * {@code @Transactional} 안에서) 기록한다. 이 Payload의 어떤 값도 인가
     * 증거가 아니다 - {@code IndexOrchestrator}가 소비 시점에 전부 다시
     * 조회한다({@link IndexRequestedEvent} Class Javadoc 참고).
     *
     * <p>M11 후속 교정 - 이 문서에 대해 아직 소비되지 않은(PENDING/PUBLISHING)
     * {@code INDEX_REQUESTED}가 이미 있으면 다시 적재하지 않는다({@link
     * OutboxEventJpaRepository#existsPendingByPartitionKeyAndEventType} 참고) -
     * 짧은 시간 안에 create/update가 반복되거나 재연결 스케줄링과 겹쳐도 같은
     * 문서에 대한 중복 대기열이 쌓이지 않는다.</p>
     */
    private void publishIndexRequested(SourceDocumentEntity document, String ownerSubject) {
        Instant now = clock.instant();
        IndexRequestedEvent event = new IndexRequestedEvent(UUID.randomUUID(), document.getSourceId(), ownerSubject,
                document.getId(), document.getSourceDocumentId(), document.getSourceVersion(), now,
                MDC.get(TraceIdFilter.MDC_KEY));
        String partitionKey = "source:" + event.sourceId() + ":doc:" + event.externalDocumentId();
        if (outboxEventJpaRepository.existsPendingByPartitionKeyAndEventType(partitionKey, event.eventType())) {
            return;
        }
        outboxEventJpaRepository.save(new OutboxEventEntity(event.eventId(), event.eventType(), event.toPayload(),
                partitionKey, now));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static DocumentShare toDomain(DocumentShareEntity entity, Set<String> recipients) {
        return new DocumentShare(entity.getId(), entity.getPublisherSubject(), entity.getSourceId(),
                entity.getDocumentId(), SecurityLevel.valueOf(entity.getClassification()),
                parseStoredActions(entity.getAllowedActions()), recipients, entity.isAdminBlocked(),
                entity.getAdminBlockReason(), entity.getGeneration(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getRevokedAt());
    }

    private static Set<ShareAction> parseStoredActions(String stored) {
        Set<ShareAction> actions = EnumSet.noneOf(ShareAction.class);
        for (String value : stored.split(",")) {
            actions.add(ShareAction.valueOf(value));
        }
        return actions;
    }
}
