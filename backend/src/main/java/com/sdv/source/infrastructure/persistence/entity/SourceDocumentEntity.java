package com.sdv.source.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * F-BE-032. {@code source_documents}(V001 + V003 index_status/index_reason)의
 * JPA Persistence 매핑.
 *
 * {@code state}는 V004부터 {@code ACTIVE}/{@code DELETED}로 제약된다
 * (신규/변경 행 기준, {@code chk_source_document_state} NOT VALID).
 */
@Entity
@Table(name = "source_documents")
public class SourceDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "source_document_id", nullable = false, length = 500)
    private String sourceDocumentId;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "mime_type", length = 150)
    private String mimeType;

    @Column(name = "source_version", length = 255)
    private String sourceVersion;

    @Column(name = "modified_at")
    private Instant modifiedAt;

    // ACTIVE, DELETED만 유효하다(V004 chk_source_document_state).
    @Column(name = "state", nullable = false, length = 30)
    private String state;

    // PENDING, INDEXED, SKIPPED_UNSUPPORTED, SKIPPED_NO_TEXT, FAILED, STALE (V003).
    @Column(name = "index_status", nullable = false, length = 30)
    private String indexStatus;

    @Column(name = "index_reason")
    private String indexReason;

    // V009 - ACL 재조회가 실패/불확실(UNKNOWN/FAILED)했던 가장 최근 시각. null이면 현재
    // source_permissions 행을 신뢰할 수 있는 증거로 취급한다(EffectivePermissionService 참고).
    @Column(name = "permissions_untrusted_since")
    private Instant permissionsUntrustedSince;

    protected SourceDocumentEntity() {
        // JPA
    }

    public SourceDocumentEntity(Long sourceId, String sourceDocumentId, String name, String mimeType,
            String sourceVersion, Instant modifiedAt, String state, String indexStatus, String indexReason) {
        this.sourceId = sourceId;
        this.sourceDocumentId = sourceDocumentId;
        this.name = name;
        this.mimeType = mimeType;
        this.sourceVersion = sourceVersion;
        this.modifiedAt = modifiedAt;
        this.state = state;
        this.indexStatus = indexStatus;
        this.indexReason = indexReason;
    }

    public Long getId() {
        return id;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getSourceDocumentId() {
        return sourceDocumentId;
    }

    public String getName() {
        return name;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    public String getState() {
        return state;
    }

    public String getIndexStatus() {
        return indexStatus;
    }

    public String getIndexReason() {
        return indexReason;
    }

    public Instant getPermissionsUntrustedSince() {
        return permissionsUntrustedSince;
    }

    /** Source Disconnect 등으로 이 문서를 논리적으로 삭제 상태로 전이한다(행은 유지). */
    public void markDeleted() {
        this.state = "DELETED";
    }

    /** V009 - ACL 재조회 실패/불확실 - 기존 source_permissions 행은 건드리지 않고 이 시각만 남긴다. */
    public void markPermissionsUntrusted(Instant since) {
        this.permissionsUntrustedSince = since;
    }

    /** V009 - ACL 재조회 성공(OK) - 신뢰를 원자적으로 회복한다(같은 Transaction 안에서 ACL 교체와 함께). */
    public void markPermissionsTrusted() {
        this.permissionsUntrustedSince = null;
    }

    /**
     * M09A 신규 - Catalog Sync가 이미 존재하는 문서 행에 새로 관측된 실제
     * Version 변경을 반영한다(변경이 없는 문서는 이 메서드 자체를 호출하지
     * 않는다 - 호출자가 {@code sourceVersion} 비교로 먼저 판단한다). 색인
     * 상태를 PENDING/사유 없음으로 재설정한다 - 낡은 Generation을 더 이상
     * "최신"으로 취급하지 않기 위함이다(실제 Embedding 행 삭제는 별도로
     * {@code SourceDocumentJpaRepository.deleteEmbeddingIndexForDocument}가
     * 같은 Transaction 안에서 수행한다).
     */
    public void applySyncedMetadata(String name, String mimeType, String sourceVersion, Instant modifiedAt,
            String state) {
        this.name = name;
        this.mimeType = mimeType;
        this.sourceVersion = sourceVersion;
        this.modifiedAt = modifiedAt;
        this.state = state;
        this.indexStatus = "PENDING";
        this.indexReason = null;
    }
}
