package com.sdv.source.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * F-BE-033. {@code source_permissions}(V001)의 JPA Persistence 매핑 - 정규화된
 * Source ACL(POL-001, SRC-005). 공식 위치는 {@code com.sdv.source...}다(Policy
 * 패키지가 아님) - v3.2 Manifest 11절이 이 Entity를 Source Persistence로
 * 분류한다: ACL 원본 데이터 소유권은 Source에 남고, Policy는 그것을 읽기만
 * 한다.
 *
 * <p>{@code principal_type}/{@code permission}에는 DB CHECK 제약이 없다(V001) -
 * 인식되지 않는 값은 Policy 계층({@code EffectivePermissionService})이
 * Fail Closed로 처리한다.</p>
 */
@Entity
@Table(name = "source_permissions")
public class SourcePermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    // USER, GROUP, DOMAIN, ANYONE 등 - SourcePrincipal.type과 동일한 소문자 계약
    // (user/group/domain/anyone)을 따른다.
    @Column(name = "principal_type", nullable = false, length = 30)
    private String principalType;

    @Column(name = "principal_value", nullable = false, length = 500)
    private String principalValue;

    // READ 등
    @Column(name = "permission", nullable = false, length = 30)
    private String permission;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    protected SourcePermissionEntity() {
        // JPA
    }

    public SourcePermissionEntity(Long documentId, String principalType, String principalValue, String permission,
            Instant syncedAt) {
        this.documentId = documentId;
        this.principalType = principalType;
        this.principalValue = principalValue;
        this.permission = permission;
        this.syncedAt = syncedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public String getPrincipalValue() {
        return principalValue;
    }

    public String getPermission() {
        return permission;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }
}
