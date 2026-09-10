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

    /** Source Disconnect 등으로 이 문서를 논리적으로 삭제 상태로 전이한다(행은 유지). */
    public void markDeleted() {
        this.state = "DELETED";
    }
}
