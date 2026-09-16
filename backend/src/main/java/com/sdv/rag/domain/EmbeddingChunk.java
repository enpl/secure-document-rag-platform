package com.sdv.rag.domain;

import java.util.List;
import java.util.Objects;

/**
 * M11 신규 - Python AI Service {@code /index} 호출이 청크(Chunk) 하나에 대해
 * 돌려주는 결과. 절대 평문 Chunk Text를 담지 않는다 - Embedding Vector와
 * 일반화된 Locator, 원문을 복원할 수 없는 Content HMAC만 담는다(v1.4 §2A.2/
 * §2A.3, "return vectors/locators/version metadata rather than plaintext
 * chunks").
 */
public record EmbeddingChunk(int chunkIndex, LocatorType locatorType, String locatorValue, List<Float> embedding,
        String contentHmac) {

    public EmbeddingChunk {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        Objects.requireNonNull(locatorType, "locatorType must not be null");
        Objects.requireNonNull(locatorValue, "locatorValue must not be null");
        if (locatorValue.isBlank()) {
            throw new IllegalArgumentException("locatorValue must not be blank");
        }
        Objects.requireNonNull(embedding, "embedding must not be null");
        embedding = List.copyOf(embedding);
        Objects.requireNonNull(contentHmac, "contentHmac must not be null");
        if (contentHmac.isBlank()) {
            throw new IllegalArgumentException("contentHmac must not be blank");
        }
    }
}
