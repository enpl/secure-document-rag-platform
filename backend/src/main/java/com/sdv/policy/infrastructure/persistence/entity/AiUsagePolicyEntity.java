package com.sdv.policy.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * F-BE-091. {@code ai_usage_policies}(V001)의 JPA Persistence 매핑 - 보안
 * 등급별 AI 전송 정책(POL-005, AI-003). 기본 키가 {@code security_level} 그
 * 자체다(V001 스키마 그대로) - 등급당 정책은 하나다.
 */
@Entity
@Table(name = "ai_usage_policies")
public class AiUsagePolicyEntity {

    // PUBLIC, INTERNAL, CONFIDENTIAL, SECRET (chk_ai_security_level)
    @Id
    @Column(name = "security_level", length = 30)
    private String securityLevel;

    // LOCAL_ONLY, EXTERNAL_ALLOWED, AI_DENIED (chk_ai_policy_mode)
    @Column(name = "mode", nullable = false, length = 30)
    private String mode;

    @Column(name = "external_provider_allowed", nullable = false)
    private boolean externalProviderAllowed;

    protected AiUsagePolicyEntity() {
        // JPA
    }

    public AiUsagePolicyEntity(String securityLevel, String mode, boolean externalProviderAllowed) {
        this.securityLevel = securityLevel;
        this.mode = mode;
        this.externalProviderAllowed = externalProviderAllowed;
    }

    public String getSecurityLevel() {
        return securityLevel;
    }

    public String getMode() {
        return mode;
    }

    public boolean isExternalProviderAllowed() {
        return externalProviderAllowed;
    }
}
