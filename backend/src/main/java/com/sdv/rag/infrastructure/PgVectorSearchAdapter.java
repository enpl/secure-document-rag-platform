package com.sdv.rag.infrastructure;

import com.sdv.rag.application.port.VectorSearchPort;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.VectorCandidate;
import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F-BE-105(M12 신규). {@link VectorSearchPort}를 이미 존재하는 {@link
 * DocumentEmbeddingJpaRepository#searchAllowed}(M07A)로 구현한다 - 새 Native
 * Query를 만들지 않는다. 이 Adapter가 추가하는 것은 오직 경계 검증뿐이다: 빈
 * {@code allowedDocumentIds}는 제약 없는 검색으로 이어지지 않고, {@code
 * queryEmbedding}은 차원/유한성을 반드시 통과해야 하며, {@code topK}는 항상
 * 안전한 상한 안으로 Bound된다.
 */
@Component
public class PgVectorSearchAdapter implements VectorSearchPort {

    /** 과도하게 큰 topK 요청으로부터 DB/응답 크기를 보호하는 절대 상한. */
    static final int MAX_TOP_K = 50;

    private final DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository;

    public PgVectorSearchAdapter(DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository) {
        this.documentEmbeddingJpaRepository = documentEmbeddingJpaRepository;
    }

    @Override
    public List<VectorCandidate> searchAllowed(Collection<Long> allowedDocumentIds, float[] queryEmbedding,
            int topK) {
        if (allowedDocumentIds == null || allowedDocumentIds.isEmpty()) {
            // INV-RAG-002 - 빈 허용 집합은 "제한 없음"이 아니라 "허용된 문서가 없음"이다.
            return List.of();
        }
        validateEmbedding(queryEmbedding);
        int boundedTopK = Math.max(1, Math.min(topK, MAX_TOP_K));
        List<DocumentEmbeddingEntity> rows = documentEmbeddingJpaRepository.searchAllowed(allowedDocumentIds,
                toPgVectorText(queryEmbedding), boundedTopK);
        return rows.stream().map(PgVectorSearchAdapter::toCandidate).toList();
    }

    private static void validateEmbedding(float[] queryEmbedding) {
        if (queryEmbedding == null || queryEmbedding.length != DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException(
                    "queryEmbedding must have exactly " + DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS
                            + " dimensions");
        }
        for (float value : queryEmbedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("queryEmbedding must contain only finite values");
            }
        }
    }

    /** pgvector 표준 텍스트 입력 형식(예: {@code "[0.1,0.2,...]"}) - Repository Javadoc 참고. */
    private static String toPgVectorText(float[] embedding) {
        StringBuilder builder = new StringBuilder(embedding.length * 8 + 2);
        builder.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private static VectorCandidate toCandidate(DocumentEmbeddingEntity row) {
        return new VectorCandidate(row.getDocumentId(), row.getChunkIndex(), LocatorType.valueOf(row.getLocatorType()),
                row.getLocatorValue(), row.getSourceVersion(), row.getParserVersion(), row.getChunkingVersion(),
                row.getModelVersion());
    }
}
