package com.sdv.source.domain;

import com.sdv.policy.domain.SecurityLevel;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * M10B 신규(SHR-001/002/003, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.13) - 기술
 * 독립적인 파일 단위 명시적 공유 모델. {@link SourceConnection}과 같은 원칙을
 * 따른다 - JPA Annotation을 갖지 않으며, 이 Class 자체는 어떤 저장소도 조회하지
 * 않는다(검증/영속은 {@code SourceSharingService}/{@code
 * DocumentShareJpaRepository}의 책임).
 *
 * <p>공유는 연결(Connection) 상태와 완전히 독립적으로 존재한다 - Disconnect는
 * 이 객체가 표현하는 값을 절대 바꾸지 않는다(§2A.14). {@link #isActive()}가
 * {@code false}(명시적 unshare)이거나 {@link #isAdminBlocked()}가 {@code true}면,
 * 연결이 아무리 건강해도 이 공유는 어떤 접근도 허용하지 않는다.</p>
 */
public final class DocumentShare {

    private final Long id;
    private final String publisherSubject;
    private final Long sourceId;
    private final Long documentId;
    private final ShareAudience audience;
    private final SecurityLevel classification;
    private final Set<ShareAction> allowedActions;
    private final Set<String> recipients;
    private final boolean adminBlocked;
    private final String adminBlockReason;
    private final long generation;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant revokedAt;

    public DocumentShare(Long id, String publisherSubject, Long sourceId, Long documentId, ShareAudience audience,
            SecurityLevel classification, Set<ShareAction> allowedActions, Set<String> recipients,
            boolean adminBlocked, String adminBlockReason, long generation, Instant createdAt, Instant updatedAt,
            Instant revokedAt) {
        this.id = id;
        this.publisherSubject = requireNonBlank(publisherSubject, "publisherSubject");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.audience = Objects.requireNonNull(audience, "audience must not be null");
        // 누락/미확정 등급은 이 생성자 자체가 거부한다(Fail Closed) - CORE_SPEC
        // §2A.13 "Missing/unmapped classification means safe denial", 다섯 번째
        // 값을 지어내지 않는다.
        this.classification = Objects.requireNonNull(classification, "classification must not be null");
        if (allowedActions == null || allowedActions.isEmpty()) {
            throw new IllegalArgumentException("allowedActions must not be empty");
        }
        this.allowedActions = EnumSet.copyOf(allowedActions);
        if (recipients == null
                || (audience == ShareAudience.NAMED_USERS && recipients.isEmpty())
                || (audience == ShareAudience.ALL_AUTHENTICATED && !recipients.isEmpty())) {
            throw new IllegalArgumentException("audience and recipients are inconsistent");
        }
        this.recipients = Set.copyOf(recipients);
        this.adminBlocked = adminBlocked;
        this.adminBlockReason = adminBlockReason;
        this.generation = generation;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.revokedAt = revokedAt;
    }

    /** Legacy named-share compatibility; absence never becomes all-authenticated. */
    public DocumentShare(Long id, String publisherSubject, Long sourceId, Long documentId,
            SecurityLevel classification, Set<ShareAction> allowedActions, Set<String> recipients,
            boolean adminBlocked, String adminBlockReason, long generation, Instant createdAt, Instant updatedAt,
            Instant revokedAt) {
        this(id, publisherSubject, sourceId, documentId, ShareAudience.NAMED_USERS, classification,
                allowedActions, recipients, adminBlocked, adminBlockReason, generation, createdAt, updatedAt,
                revokedAt);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** {@code true}면 명시적으로 unshare되지 않았다 - 그렇다고 접근이 허용된다는 뜻은 아니다({@link #isAdminBlocked()}도 함께 확인해야 한다). */
    public boolean isActive() {
        return revokedAt == null;
    }

    /**
     * 이 공유가 지금 이 수신자에게 지정된 행위를 허용하는지 - 활성 상태, 관리자
     * 차단 없음, 수신자 명단 포함, 행위 부여를 모두 확인한다. 등급/Overlay/AI
     * Policy 평가는 이 메서드의 책임이 아니다(호출자 - {@code
     * EffectivePermissionService.evaluateSharedAccess}).
     */
    public boolean grants(String recipientSubject, ShareAction action) {
        return isActive() && !adminBlocked && recipientSubject != null
                && (audience == ShareAudience.ALL_AUTHENTICATED || recipients.contains(recipientSubject))
                && allowedActions.contains(action);
    }

    public Long getId() {
        return id;
    }

    public String getPublisherSubject() {
        return publisherSubject;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public ShareAudience getAudience() {
        return audience;
    }

    public SecurityLevel getClassification() {
        return classification;
    }

    public Set<ShareAction> getAllowedActions() {
        return allowedActions;
    }

    public Set<String> getRecipients() {
        return new LinkedHashSet<>(recipients);
    }

    public boolean isAdminBlocked() {
        return adminBlocked;
    }

    public String getAdminBlockReason() {
        return adminBlockReason;
    }

    public long getGeneration() {
        return generation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
