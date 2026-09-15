package com.sdv.event.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * F-BE-071 (M09A 신규). Source 문서가 더 이상 유효한 Catalog 후보가 아니게
 * 됐음을 알리는 이벤트 - 실제 삭제인지 이 Credential의 접근권한 상실인지는
 * {@code reason}이 그대로 드러낸다({@link
 * com.sdv.source.domain.SourceChangeType#REMOVED_OR_ACCESS_LOST} 참고, 물리적
 * 삭제로 단정하지 않는다).
 */
public record SourceDocumentDeletedEvent(UUID eventId, Long sourceId, String sourceAccountSubject,
        Long internalDocumentId, String externalDocumentId, String reason, Instant occurredAt, String traceId)
        implements DomainEvent {

    public static final String EVENT_TYPE = "SOURCE_DOCUMENT_DELETED";

    /** Google Drive {@code changes.list}의 {@code removed=true}(삭제/접근상실 구분 불가). */
    public static final String REASON_REMOVED_OR_ACCESS_LOST = "REMOVED_OR_ACCESS_LOST";
    /** Google Drive {@code trashed=true}. */
    public static final String REASON_TRASHED = "TRASHED";
    /** Source Owner가 직접 연결을 해제했다(Disconnect). */
    public static final String REASON_OWNER_DISCONNECTED = "OWNER_DISCONNECTED";

    public SourceDocumentDeletedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(internalDocumentId, "internalDocumentId must not be null");
        Objects.requireNonNull(externalDocumentId, "externalDocumentId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
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
        payload.put("reason", reason);
        payload.put("occurredAt", occurredAt.toString());
        payload.put("traceId", traceId);
        return payload;
    }
}
