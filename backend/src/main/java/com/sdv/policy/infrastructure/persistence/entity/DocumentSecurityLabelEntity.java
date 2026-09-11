package com.sdv.policy.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * F-BE-089. {@code document_security_labels}(V001)의 JPA Persistence 매핑 -
 * 문서별 SDV 자체 보안 등급(POL-002, POL-006).
 *
 * <p>기본 키가 {@code document_id} 그 자체다(별도 자동증가 id 없음, V001 스키마
 * 그대로) - 문서당 라벨은 최대 하나다.</p>
 */
@Entity
@Table(name = "document_security_labels")
public class DocumentSecurityLabelEntity {

    @Id
    @Column(name = "document_id")
    private Long documentId;

    // PUBLIC, INTERNAL, CONFIDENTIAL, SECRET (chk_security_level)
    @Column(name = "security_level", nullable = false, length = 30)
    private String securityLevel;

    // SOURCE_MAPPING, MANUAL (chk_security_label_origin)
    @Column(name = "origin", nullable = false, length = 30)
    private String origin;

    @Column(name = "source_label", length = 255)
    private String sourceLabel;

    protected DocumentSecurityLabelEntity() {
        // JPA
    }

    public DocumentSecurityLabelEntity(Long documentId, String securityLevel, String origin, String sourceLabel) {
        this.documentId = documentId;
        this.securityLevel = securityLevel;
        this.origin = origin;
        this.sourceLabel = sourceLabel;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getSecurityLevel() {
        return securityLevel;
    }

    public String getOrigin() {
        return origin;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    /** 라벨을 갱신한다(Upsert의 Update 분기 - {@code SecurityLabelService.setLabel}). */
    public void update(String securityLevel, String origin, String sourceLabel) {
        this.securityLevel = securityLevel;
        this.origin = origin;
        this.sourceLabel = sourceLabel;
    }
}
