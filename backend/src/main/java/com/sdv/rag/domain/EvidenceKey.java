package com.sdv.rag.domain;

import java.util.Objects;

/**
 * M12 신규(F-BE-206/207/208, §2A.6 Encrypted Ephemeral Evidence) - 근거(Evidence)
 * 한 건이 결합돼야 하는 모든 신원/세대 값. {@link
 * com.sdv.rag.application.port.out.EphemeralEvidenceStore}가 재사용(Reuse) 판단
 * 전에 이 값 전체가 지금 이 순간의 값과 정확히 같은지 비교한다 - 하나라도
 * 달라지면(요청자/대화/공유 또는 연결 세대/Source Version 변경) 같은 근거를
 * 재사용하지 않는다. Client가 준 Conversation ID 자체는 소유 증거가 아니다 -
 * {@code requesterSubject}와 함께 결합될 때만 의미가 있다.
 */
public record EvidenceKey(String requesterSubject, String conversationId, Long sourceId, Long documentId,
        Long shareId, long shareGeneration, long connectionGeneration, long requesterAuthorizationRevision,
        String sourceVersion) {

    public EvidenceKey(String requesterSubject, String conversationId, Long sourceId, Long documentId,
            Long shareId, long shareGeneration, long connectionGeneration, String sourceVersion) {
        this(requesterSubject, conversationId, sourceId, documentId, shareId, shareGeneration,
                connectionGeneration, -1L, sourceVersion);
    }

    public EvidenceKey {
        requireNonBlank(requesterSubject, "requesterSubject");
        requireNonBlank(conversationId, "conversationId");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(shareId, "shareId must not be null");
        requireNonBlank(sourceVersion, "sourceVersion");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
