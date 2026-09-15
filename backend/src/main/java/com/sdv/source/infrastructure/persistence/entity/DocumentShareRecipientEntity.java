package com.sdv.source.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * M10B 신규(V010) - {@code document_share_recipients}의 JPA Persistence 매핑.
 * 하나의 공유({@link DocumentShareEntity})에 결합된 정확한 OIDC subject
 * 문자열만 담는다 - 이메일/도메인/Group 값을 저장하지 않는다(CORE_SPEC
 * §2A.13).
 */
@Entity
@Table(name = "document_share_recipients")
public class DocumentShareRecipientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "share_id", nullable = false)
    private Long shareId;

    @Column(name = "recipient_subject", nullable = false, length = 255)
    private String recipientSubject;

    protected DocumentShareRecipientEntity() {
        // JPA
    }

    public DocumentShareRecipientEntity(Long shareId, String recipientSubject) {
        this.shareId = shareId;
        this.recipientSubject = recipientSubject;
    }

    public Long getId() {
        return id;
    }

    public Long getShareId() {
        return shareId;
    }

    public String getRecipientSubject() {
        return recipientSubject;
    }
}
