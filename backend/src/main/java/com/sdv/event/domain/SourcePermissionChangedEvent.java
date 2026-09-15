package com.sdv.event.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * F-BE-070 (M09A 신규). 한 Source 문서의 ACL이 다시 동기화됐음을 알리는
 * 이벤트다 - "무엇이 바뀌었는지"가 아니라 "다시 평가가 필요하다"는 신호일
 * 뿐이다. 실제 Principal 목록은 {@code source_permissions}(V001, DB)에만
 * 존재한다 - 절대 이 이벤트 Payload에 담지 않는다(CLAUDE.md Kafka Event
 * 규칙, "ACL Principal 목록을 이벤트/재시도/로그에 절대 담지 않는다").
 */
public record SourcePermissionChangedEvent(UUID eventId, Long sourceId, String sourceAccountSubject,
        Long internalDocumentId, String externalDocumentId, Instant occurredAt, String traceId)
        implements DomainEvent {

    public static final String EVENT_TYPE = "SOURCE_PERMISSION_CHANGED";

    public SourcePermissionChangedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(internalDocumentId, "internalDocumentId must not be null");
        Objects.requireNonNull(externalDocumentId, "externalDocumentId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public Map<String, String> toPayload() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("eventType", EVENT_TYPE);
        payload.put("schemaVersion", String.valueOf(schemaVersion()));
        payload.put("sourceId", sourceId.toString());
        payload.put("sourceAccountSubject", sourceAccountSubject);
        payload.put("internalDocumentId", internalDocumentId.toString());
        payload.put("externalDocumentId", externalDocumentId);
        payload.put("occurredAt", occurredAt.toString());
        payload.put("traceId", traceId);
        return payload;
    }
}
