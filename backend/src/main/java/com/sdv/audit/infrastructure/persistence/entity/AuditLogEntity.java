package com.sdv.audit.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * F-BE-120. {@code audit_logs}(V001) 테이블의 JPA Persistence 매핑.
 *
 * <p>순수 영속성 매핑만 담당한다 - 조회/검색/Aspect 등은 M03 범위 밖이다.
 * {@code metadata}는 이미 위생 처리(sanitize)된 값만 담아 JSONB로 저장한다.</p>
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "actor")
    private String actor;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "target_type", length = 100)
    private String targetType;

    @Column(name = "target_id")
    private String targetId;

    @Column(name = "result", nullable = false, length = 30)
    private String result;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, String> metadata;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AuditLogEntity() {
        // JPA
    }

    public AuditLogEntity(String actor, String action, String targetType, String targetId, String result,
            String reasonCode, String traceId, Map<String, String> metadata) {
        this.actor = actor;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.result = result;
        this.reasonCode = reasonCode;
        this.traceId = traceId;
        this.metadata = metadata;
    }

    public Long getId() {
        return id;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getResult() {
        return result;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getTraceId() {
        return traceId;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
