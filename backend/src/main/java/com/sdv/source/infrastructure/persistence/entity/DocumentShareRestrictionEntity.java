package com.sdv.source.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * M10B 보안 교정 신규(V011) - {@code document_share_restrictions}의 JPA
 * Persistence 매핑. ADMIN의 차단을 {@code document_shares.id}(공유를 새로
 * 게시할 때마다 바뀌는 값)가 아니라 파일의 정규 신원({@code source_id} +
 * {@code document_id})에 결합한다 - 게시자가 차단된 공유를 unshare한 뒤 같은
 * 파일을 새 공유로 다시 게시해도 이 행이 그대로 남아있으므로 차단이 사라지지
 * 않는다({@code SourceSharingService.createShare}가 새 공유를 만들 때 이 행을
 * 확인해 {@code admin_blocked}를 그대로 물려받게 한다).
 *
 * <p>문서 하나당 이 행은 최대 하나뿐이다(같은 행을 계속 토글) - {@code
 * document_shares}처럼 철회 이력을 별도 행으로 쌓지 않는다(요구된 "smallest
 * persistent representation compatible with the current model").</p>
 */
@Entity
@Table(name = "document_share_restrictions")
public class DocumentShareRestrictionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "blocked", nullable = false)
    private boolean blocked;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    @Column(name = "blocked_by_subject", nullable = false, length = 255)
    private String blockedBySubject;

    @Column(name = "blocked_at", nullable = false)
    private Instant blockedAt;

    @Column(name = "cleared_by_subject", length = 255)
    private String clearedBySubject;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    protected DocumentShareRestrictionEntity() {
        // JPA
    }

    public DocumentShareRestrictionEntity(Long sourceId, Long documentId, String blockedBySubject, Instant now) {
        this.sourceId = sourceId;
        this.documentId = documentId;
        this.blocked = true;
        this.blockedBySubject = blockedBySubject;
        this.blockedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public String getBlockedBySubject() {
        return blockedBySubject;
    }

    public Instant getBlockedAt() {
        return blockedAt;
    }

    public String getClearedBySubject() {
        return clearedBySubject;
    }

    public Instant getClearedAt() {
        return clearedAt;
    }

    /** ADMIN이 이 파일을 다시(또는 처음) 차단한다 - 기존 행을 재사용한다(새 행을 만들지 않는다). */
    public void applyBlock(String adminSubject, String reason, Instant now) {
        this.blocked = true;
        this.blockedReason = reason;
        this.blockedBySubject = adminSubject;
        this.blockedAt = now;
        this.clearedBySubject = null;
        this.clearedAt = null;
    }

    /**
     * ADMIN 전용 명시적 해제 - 이 메서드가 유일한 해제 경로다(게시자는 호출할 방법이
     * 없다). 해제해도 이미 철회된 공유를 되살리거나 수신자/행위/등급을 넓히지
     * 않는다 - 오직 "다음에 새로 게시되는 공유가 차단 없이 시작될 수 있다"는 사실만
     * 바뀐다.
     */
    public void clear(String adminSubject, Instant now) {
        this.blocked = false;
        this.clearedBySubject = adminSubject;
        this.clearedAt = now;
    }
}
