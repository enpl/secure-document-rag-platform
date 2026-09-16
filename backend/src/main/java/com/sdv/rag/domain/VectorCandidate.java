package com.sdv.rag.domain;

import java.util.Objects;

/**
 * M12 신규(F-BE-104/§2A.4) - {@code VectorSearchPort.searchAllowed}가 반환하는 후보
 * 하나. Cosine Distance 자체나 원본 Chunk Text는 담지 않는다 - 목록의 순서 자체가
 * 이미 가장 가까운 순이며(Repository의 {@code ORDER BY embedding <=> ...}), 이
 * 값은 권한 증명도 답변 근거(Evidence)도 아니다(허용된 문서 범위 안에서만 존재할
 * 수 있는 "가능성 있는 위치"에 대한 힌트일 뿐이다). {@link LocatorType}/{@code
 * locatorValue}는 {@link LiveEvidenceRetrievalService}가 새로 Fetch/Parse한 현재
 * 콘텐츠에서 같은 위치를 다시 찾아 근거를 선택하는 데만 쓰인다 - 여기 담긴 값 자체를
 * 근거로 노출하지 않는다.
 */
public record VectorCandidate(Long documentId, int chunkIndex, LocatorType locatorType, String locatorValue,
        String sourceVersion, String parserVersion, String chunkingVersion, String modelVersion) {

    public VectorCandidate {
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        Objects.requireNonNull(locatorType, "locatorType must not be null");
        Objects.requireNonNull(locatorValue, "locatorValue must not be null");
        requireNonBlank(sourceVersion, "sourceVersion");
        requireNonBlank(parserVersion, "parserVersion");
        requireNonBlank(chunkingVersion, "chunkingVersion");
        requireNonBlank(modelVersion, "modelVersion");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
