package com.sdv.rag.domain;

import java.util.Objects;

/**
 * M12 신규(F-AI-embed-query) - Python AI Service {@code /embed-query} 호출 한 번의
 * 결과. 질의(Query) 원문은 이 값 어디에도 담기지 않는다 - {@code embedding}만
 * 존재한다(일시적 값, 어디에도 영속 저장/로그되지 않는다).
 */
public record QueryEmbeddingOutcome(boolean success, float[] embedding, String reason) {

    public QueryEmbeddingOutcome {
        if (success) {
            Objects.requireNonNull(embedding, "embedding must not be null for a successful outcome");
        } else {
            Objects.requireNonNull(reason, "reason must not be null for a failed outcome");
        }
    }

    public static QueryEmbeddingOutcome success(float[] embedding) {
        return new QueryEmbeddingOutcome(true, embedding, null);
    }

    public static QueryEmbeddingOutcome failure(String reason) {
        return new QueryEmbeddingOutcome(false, null, reason);
    }
}
