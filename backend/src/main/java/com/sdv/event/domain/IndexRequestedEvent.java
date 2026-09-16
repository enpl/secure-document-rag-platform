package com.sdv.event.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * M11 신규(F-BE-072가 M09A 시절 예고한 바로 그 이벤트, {@link
 * SourceDocumentChangedEvent} Class Javadoc 참고) - "이 문서가 지금 색인
 * 자격이 있을 수 있으니 다시 확인해 달라"는 신호일 뿐이다. 이 이벤트 자체는
 * 어떤 인가도 부여하지 않는다 - {@code IndexRequestedConsumer}/{@code
 * IndexOrchestrator}는 이 Payload의 어떤 값도 신뢰하지 않고, 소비 시점에
 * {@code document_shares}/{@code source_connections}/{@code source_documents}를
 * 서버에서 새로 조회해 자격을 처음부터 다시 판단한다("Never trust
 * event/client identities as authorization").
 *
 * <p>{@link com.sdv.source.application.SourceSharingService}가 공유 생성/수정/
 * 관리자 차단 해제 시 이 이벤트를 발행한다. 명시적 unshare/관리자 차단/
 * disconnect/문서 삭제는 이 이벤트를 발행하지 않는다 - 그 경로들은 대신
 * 기존 {@code SourceDocumentJpaRepository.deleteEmbeddingIndexForDocument}
 * (unshare/adminSetBlocked)/{@code deleteEmbeddingIndexForSource}(disconnect,
 * 기존 M07A 경로)로 즉시 Embedding을 정리한다 - "연결/Catalog Sync 자체는
 * AI 동의가 아니다"를 이 이벤트의 Producer 자체가 강제한다(카탈로그 동기화
 * 이벤트인 {@link SourceDocumentChangedEvent}/{@link SourcePermissionChangedEvent}는
 * 절대 색인을 촉발하지 않는다 - 이 색인 전용 이벤트를 발행하는 유일한 지점은
 * SourceSharingService다).</p>
 */
public record IndexRequestedEvent(UUID eventId, Long sourceId, String sourceAccountSubject, Long internalDocumentId,
        String externalDocumentId, String sourceVersion, Instant occurredAt, String traceId)
        implements DomainEvent {

    public static final String EVENT_TYPE = "INDEX_REQUESTED";

    public IndexRequestedEvent {
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
