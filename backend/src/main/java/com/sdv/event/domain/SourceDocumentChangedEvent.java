package com.sdv.event.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * F-BE-069 (M09A 신규). Source 문서의 Metadata(이름/MIME/Version 등)가 Catalog
 * Sync로 새로 반영됐음을 알리는 이벤트 - 최초 등록과 변경을 구분하지 않는다
 * (둘 다 "지금 이 Version이 최신"이라는 사실만 전달한다). {@code sourceVersion}
 * 은 재색인 필요 여부를 Consumer(M11)가 스스로 판단하게 하는 최소 근거다.
 */
public record SourceDocumentChangedEvent(UUID eventId, Long sourceId, String sourceAccountSubject,
        Long internalDocumentId, String externalDocumentId, String sourceVersion, Instant occurredAt,
        String traceId) implements DomainEvent {

    public static final String EVENT_TYPE = "SOURCE_DOCUMENT_CHANGED";

    public SourceDocumentChangedEvent {
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
        payload.put("sourceVersion", sourceVersion);
        payload.put("occurredAt", occurredAt.toString());
        payload.put("traceId", traceId);
        return payload;
    }
}
