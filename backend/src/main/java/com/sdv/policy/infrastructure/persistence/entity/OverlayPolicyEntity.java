package com.sdv.policy.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * F-BE-090. {@code overlay_policies}(V001)의 JPA Persistence 매핑 - Source
 * 권한 위에 추가되는 SDV 자체 제한 정책. Source에서 DENY된 권한을 ALLOW로
 * 넓히는 데 사용될 수 없다({@link com.sdv.policy.application.OverlayPolicyService}가
 * 이 불변식을 강제한다 - 이 Entity 자체는 순수 Persistence 매핑일 뿐이다).
 */
@Entity
@Table(name = "overlay_policies")
public class OverlayPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // USER, GROUP, ROLE 등
    @Column(name = "subject_type", nullable = false, length = 30)
    private String subjectType;

    @Column(name = "subject_value", nullable = false, length = 500)
    private String subjectValue;

    // null이면 보안 등급과 무관하게 적용된다.
    @Column(name = "security_level", length = 30)
    private String securityLevel;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    // ALLOW, DENY (chk_overlay_effect) - ALLOW는 비-authoritative다.
    @Column(name = "effect", nullable = false, length = 20)
    private String effect;

    protected OverlayPolicyEntity() {
        // JPA
    }

    public OverlayPolicyEntity(String subjectType, String subjectValue, String securityLevel, String action,
            String effect) {
        this.subjectType = subjectType;
        this.subjectValue = subjectValue;
        this.securityLevel = securityLevel;
        this.action = action;
        this.effect = effect;
    }

    public Long getId() {
        return id;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public String getSubjectValue() {
        return subjectValue;
    }

    public String getSecurityLevel() {
        return securityLevel;
    }

    public String getAction() {
        return action;
    }

    public String getEffect() {
        return effect;
    }
}
