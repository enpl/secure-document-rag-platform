package com.sdv.event.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * F-BE-073 (M09A 신규). {@code outbox_events}(V001 + V008)의 JPA Persistence
 * 매핑 - Transactional Outbox 패턴의 저장소(SYN-005).
 *
 * <p>{@code payload}는 {@link com.sdv.audit.infrastructure.persistence.entity.AuditLogEntity#metadata}
 * 와 동일한 방식(JSONB, {@code @JdbcTypeCode(SqlTypes.JSON)})으로 저장한다 -
 * 임의 Object 직렬화가 아니라 이미 위생 처리된 {@code Map<String, String>}만
 * 담는다({@link com.sdv.event.domain.DomainEvent#toPayload()}가 그 계약을
 * 보장한다).</p>
 *
 * <p>{@code status}는 V008 이후 PENDING/PUBLISHING/PUBLISHED/FAILED 네 값을
 * 가진다 - {@code PUBLISHING}은 {@link com.sdv.event.infrastructure.OutboxEventPublisher}
 * 가 배치를 Claim했지만 아직 Broker 응답을 못 받은 상태다(그 Class Javadoc
 * 참고).</p>
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHING = "PUBLISHING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> payload;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "partition_key", length = 500)
    private String partitionKey;

    // V009 - 이 행을 Claim한 Batch의 Fencing Token. status='PUBLISHING'일 때만 의미가 있다 -
    // markPublished/markRetry/markFailedTerminal은 이 값이 정확히 일치할 때만 적용된다(OutboxPublishWriter 참고).
    @Column(name = "claim_token")
    private UUID claimToken;

    protected OutboxEventEntity() {
        // JPA
    }

    public OutboxEventEntity(UUID eventId, String eventType, Map<String, String> payload, String partitionKey,
            Instant createdAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.partitionKey = partitionKey;
        this.status = STATUS_PENDING;
        this.createdAt = createdAt;
        this.attempts = 0;
        this.nextAttemptAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Map<String, String> getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public UUID getClaimToken() {
        return claimToken;
    }
}
