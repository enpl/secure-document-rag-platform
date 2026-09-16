package com.sdv.source.domain;

import java.util.Objects;

/**
 * M12 신규 - {@code SourceSharingService}가 공유 철회/관리자 차단 직후 발행하는
 * 최소 크기의 In-Process(Spring {@code ApplicationEventPublisher}) 알림. Source
 * Domain은 RAG를 절대 참조하지 않는다(CLAUDE.md 의존 방향 규칙) - 그래서 이 Event
 * 자체는 domain-neutral(문서 ID 하나뿐)이며, 실제로 근거를 제거하는 {@code
 * EphemeralEvidenceStore}는 이 Event를 구독하는 RAG 쪽 Listener가 안다(RAG는
 * Source에 의존할 수 있으므로 그 방향으로만 연결된다).
 */
public record ShareAccessRevokedEvent(Long documentId) {

    public ShareAccessRevokedEvent {
        Objects.requireNonNull(documentId, "documentId must not be null");
    }
}
