package com.sdv.source.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * M10B 신규(V010) - {@code document_shares}의 JPA Persistence 매핑. 다른 Source
 * Entity들(예: {@link SourceConnectionEntity})과 같은 원칙 - 매핑된 연관관계
 * ({@code @ManyToOne} 등)를 쓰지 않고, Repository의 JPQL {@code JOIN ... ON}으로만
 * {@code source_documents}/{@code source_connections}와 연결한다.
 *
 * <p>{@code allowedActions}는 콤마로 구분된 {@link com.sdv.source.domain.ShareAction}
 * 이름 문자열로 저장한다(V010 Javadoc 참고) - 별도 자식 테이블을 두지 않는다(이번
 * Slice가 실제로 강제하는 행위는 VIEW 하나뿐이라 과설계를 피한다).</p>
 *
 * <h2>M10B 보안 교정 - {@code generation}은 이제 진짜 JPA {@code @Version}이다</h2>
 * <p>이전에는 평범한 {@code @Column}이었다 - {@code SourceSharingService}가
 * Java 값으로만 {@code share.getGeneration() != expectedGeneration}을 비교한 뒤
 * 무조건 저장했는데, 실제 UPDATE SQL 자체는 {@code WHERE id=?}만 걸고 {@code
 * generation}은 전혀 확인하지 않아 두 동시 Transaction이 서로의 변경을 덮어쓸 수
 * 있었다(고전적 Lost Update - "merely comparing Java values before an
 * unconditional save is insufficient"). {@code @Version}으로 바꾸면 Hibernate가
 * 이 Entity의 모든 UPDATE에 자동으로 {@code WHERE ... AND generation=?}을 붙이고
 * 저장 직후 값을 스스로 증가시킨다 - 0행이 갱신되면(다른 Transaction이 먼저
 * 커밋했다는 뜻) {@link jakarta.persistence.OptimisticLockException}(Spring Data
 * 경계에서는 {@link org.springframework.orm.ObjectOptimisticLockingFailureException})을
 * 던진다. 그래서 {@code applyOwnerUpdate}/{@code applyAdminBlock}은 더 이상 이
 * 값을 직접 증가시키지 않는다(수동 증가 + 자동 증가를 섞으면 이중 증가/불일치가
 * 생긴다) - 생성자의 초기값 {@code 1L}만 신규 Insert 시 그대로 쓰인다.</p>
 */
@Entity
@Table(name = "document_shares")
public class DocumentShareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "publisher_subject", nullable = false, length = 255)
    private String publisherSubject;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    // PUBLIC, INTERNAL, CONFIDENTIAL, SECRET만 유효하다(chk_document_share_classification).
    @Column(name = "classification", nullable = false, length = 30)
    private String classification;

    // ShareAction 이름을 콤마로 구분해 담는다(예: "VIEW" 또는 "VIEW,DOWNLOAD").
    @Column(name = "allowed_actions", nullable = false, length = 255)
    private String allowedActions;

    @Column(name = "admin_blocked", nullable = false)
    private boolean adminBlocked;

    @Column(name = "admin_block_reason", length = 500)
    private String adminBlockReason;

    @Version
    @Column(name = "generation", nullable = false)
    private long generation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected DocumentShareEntity() {
        // JPA
    }

    public DocumentShareEntity(String publisherSubject, Long sourceId, Long documentId, String classification,
            String allowedActions, Instant createdAt) {
        this.publisherSubject = publisherSubject;
        this.sourceId = sourceId;
        this.documentId = documentId;
        this.classification = classification;
        this.allowedActions = allowedActions;
        this.adminBlocked = false;
        this.generation = 1L;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
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

    public String getClassification() {
        return classification;
    }

    public String getAllowedActions() {
        return allowedActions;
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

    public boolean isActive() {
        return revokedAt == null;
    }

    /**
     * 소유자(게시자) 변경 - {@code generation}은 더 이상 여기서 손으로 올리지 않는다.
     * {@code @Version}이 이 Entity를 UPDATE할 때 자동으로 올리고, 그 사이 다른
     * Transaction이 먼저 커밋했으면(관리자 차단 등) 이 메서드가 아니라 저장 시점에
     * {@code OptimisticLockException}이 발생한다({@code SourceSharingService.updateShare}
     * 참고).
     */
    public void applyOwnerUpdate(String classification, String allowedActions, Instant now) {
        this.classification = classification;
        this.allowedActions = allowedActions;
        this.updatedAt = now;
    }

    /** 명시적 unshare - Disconnect와 별개 연산이며, 되살릴 수 없다(재공유는 새 행). */
    public void revoke(Instant now) {
        this.revokedAt = now;
        this.updatedAt = now;
    }

    /**
     * ADMIN 전용 - 게시자는 이 메서드를 호출할 경로 자체가 없다(SharedFileAdminController만
     * 호출). {@code @Version}이 이 UPDATE에서도 자동으로 세대를 올리므로, 관리자 차단이
     * 먼저 커밋되면 그 이전에 읽은 세대를 들고 있던 게시자의 동시 PATCH는 저장 시점에
     * {@code OptimisticLockException}으로 실패한다(수동 증가 불필요).
     */
    public void applyAdminBlock(boolean blocked, String reason, Instant now) {
        this.adminBlocked = blocked;
        this.adminBlockReason = blocked ? reason : null;
        this.updatedAt = now;
    }
}
