package com.sdv.event.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * F-BE-068 (M09A 신규). Transactional Outbox에 쓰이는 모든 이벤트의 공통 계약
 * (SYN-005). 이 Slice가 실제로 발행하는 세 가지 구체 타입만 {@code permits}에
 * 나열한다 - 추측성으로 더 넓혀두지 않는다({@link
 * com.sdv.sync.application.AbstractSourceSyncJob} 참고, F-BE-072
 * {@code IndexRequestedEvent}는 실제 Consumer/Indexing Orchestration(M11)이
 * "색인이 필요하다"를 어떤 형태로 표현할지 그 작업에서 결정해야 하므로 이
 * Slice에서 지어내지 않는다 - {@code docs/plan/SDV_MVP_DEFERRED.md}에 남은
 * 작업으로 기록한다).
 *
 * <p><b>Payload 안전 규칙(절대 위반 금지)</b>: {@link #toPayload()}가 반환하는
 * 값에는 Token/Secret/서명된 URL/ACL Principal 목록/원본 Byte/추출 Text/
 * Prompt가 절대 담기지 않는다 - Source/계정 식별자, 내부/외부 문서 ID, Source
 * Version, 안전한 사유 문자열, Trace 메타데이터만 담는다(CLAUDE.md "Kafka
 * Event"/"원본 비보관" 규칙).</p>
 */
public sealed interface DomainEvent
        permits SourceDocumentChangedEvent, SourceDocumentDeletedEvent, SourcePermissionChangedEvent {

    UUID eventId();

    String eventType();

    Long sourceId();

    Instant occurredAt();

    /** 현재 요청의 Trace ID(있다면) - Audit과 동일하게 상관관계 추적용일 뿐이다. */
    String traceId();

    /** 이 이벤트의 Payload 스키마 버전 - 이 Slice의 세 이벤트 모두 1이다. */
    default int schemaVersion() {
        return 1;
    }

    /**
     * {@code outbox_events.payload}(JSONB)에 그대로 저장될 평면(Flat)
     * Key-Value 표현. 구현체는 위 Class Javadoc의 안전 규칙을 반드시 지킨다.
     */
    Map<String, String> toPayload();
}
