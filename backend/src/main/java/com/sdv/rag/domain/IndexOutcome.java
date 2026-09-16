package com.sdv.rag.domain;

import java.util.List;
import java.util.Objects;

/**
 * M11 신규 - Python AI Service {@code /index} 호출 한 번의 결과(HTTP/JPA에
 * 의존하지 않는 순수 Domain 값). {@link com.sdv.rag.infrastructure.ai.DocumentParsingClient#index}
 * 가 생성하고 {@link com.sdv.rag.application.IndexOrchestrator}가 소비한다.
 *
 * <p>{@link ParseOutcome}과 어휘를 공유한다({@link ParseOutcomeKind}) - Parsing
 * 결과 분류 자체는 하나뿐이어야 하기 때문이다. {@code kind == SUCCESS}일 때만
 * {@code parserVersion}/{@code chunkingVersion}/{@code embeddingModel}/
 * {@code chunks}가 채워진다 - {@code chunks}는 절대 비어있을 수 없다(청크가
 * 0개면 성공이 아니라 {@link ParseOutcomeKind#FAILED}다).</p>
 */
public record IndexOutcome(ParseOutcomeKind kind, String parserVersion, String chunkingVersion, String embeddingModel,
        List<EmbeddingChunk> chunks, String reason) {

    public IndexOutcome {
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind == ParseOutcomeKind.SUCCESS) {
            Objects.requireNonNull(parserVersion, "parserVersion must not be null for SUCCESS");
            Objects.requireNonNull(chunkingVersion, "chunkingVersion must not be null for SUCCESS");
            Objects.requireNonNull(embeddingModel, "embeddingModel must not be null for SUCCESS");
            Objects.requireNonNull(chunks, "chunks must not be null for SUCCESS");
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("chunks must not be empty for SUCCESS");
            }
            chunks = List.copyOf(chunks);
        } else {
            Objects.requireNonNull(reason, "reason must not be null for a non-SUCCESS outcome");
            chunks = List.of();
        }
    }

    public static IndexOutcome success(String parserVersion, String chunkingVersion, String embeddingModel,
            List<EmbeddingChunk> chunks) {
        return new IndexOutcome(ParseOutcomeKind.SUCCESS, parserVersion, chunkingVersion, embeddingModel, chunks,
                null);
    }

    public static IndexOutcome failure(ParseOutcomeKind kind, String reason) {
        if (kind == ParseOutcomeKind.SUCCESS) {
            throw new IllegalArgumentException("Use success(...) for ParseOutcomeKind.SUCCESS");
        }
        return new IndexOutcome(kind, null, null, null, List.of(), reason);
    }
}
