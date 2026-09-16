package com.sdv.rag.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * M11 신규(V012). {@code processed_events}의 JPA Persistence 매핑 -
 * {@link com.sdv.rag.infrastructure.event.IndexRequestedConsumer}의 멱등성
 * Ledger("A received event is not completed work" - 이 행이 있어야만 그 Consumer가
 * 그 이벤트를 이미 종결 처리했다는 뜻이다). Primary Key는 {@code (eventId,
 * consumerName)} 복합키다 - "keyed by event identity and consumer responsibility"를
 * 그대로 반영한다(지금은 Consumer가 하나뿐이지만, 새 Consumer가 같은 이벤트를 다른
 * 책임으로 소비할 수 있다는 것을 Schema 수준에서 미리 막지 않는다).
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEventEntity.Key.class)
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Id
    @Column(name = "consumer_name", nullable = false, length = 100)
    private String consumerName;

    @Column(name = "outcome", nullable = false, length = 30)
    private String outcome;

    @Column(name = "reason_code", length = 200)
    private String reasonCode;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() {
        // JPA
    }

    public ProcessedEventEntity(UUID eventId, String consumerName, String outcome, String reasonCode,
            Long documentId, Instant processedAt) {
        this.eventId = eventId;
        this.consumerName = consumerName;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.documentId = documentId;
        this.processedAt = processedAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    /** {@code @IdClass} 복합키 - Field 이름/타입이 Entity의 {@code @Id} 필드와 정확히 일치해야 한다. */
    public static final class Key implements Serializable {
        private UUID eventId;
        private String consumerName;

        public Key() {
            // JPA
        }

        public Key(UUID eventId, String consumerName) {
            this.eventId = eventId;
            this.consumerName = consumerName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(eventId, key.eventId) && Objects.equals(consumerName, key.consumerName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumerName);
        }
    }
}
