package com.sdv.rag.infrastructure;

import com.sdv.rag.domain.VectorCandidate;
import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M12 Focused Acceptance Test - {@link PgVectorSearchAdapter}의 경계 검증(INV-RAG-002,
 * 차원/유한성, topK 상한). 실제 pgvector Query 자체는 이미 {@code
 * DocumentEmbeddingJpaRepositoryTest}(Testcontainers)가 검증한다 - 이 Test는
 * Repository를 Mock해 Adapter 계층의 방어 로직만 좁게 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PgVectorSearchAdapterTest {

    @Mock
    private DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository;

    private PgVectorSearchAdapter adapter;

    private static float[] validEmbedding() {
        float[] embedding = new float[DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS];
        embedding[0] = 0.42f;
        return embedding;
    }

    @Test
    void anEmptyAllowedDocumentSetNeverCallsTheRepositoryAndReturnsNoResults() {
        adapter = new PgVectorSearchAdapter(documentEmbeddingJpaRepository);

        List<VectorCandidate> result = adapter.searchAllowed(Set.of(), validEmbedding(), 5);

        assertThat(result).isEmpty();
        verifyNoInteractions(documentEmbeddingJpaRepository);
    }

    @Test
    void aNullAllowedDocumentSetIsTreatedTheSameAsEmpty() {
        adapter = new PgVectorSearchAdapter(documentEmbeddingJpaRepository);

        List<VectorCandidate> result = adapter.searchAllowed(null, validEmbedding(), 5);

        assertThat(result).isEmpty();
        verifyNoInteractions(documentEmbeddingJpaRepository);
    }

    @Test
    void aQueryEmbeddingWithTheWrongDimensionCountIsRejected() {
        adapter = new PgVectorSearchAdapter(documentEmbeddingJpaRepository);

        assertThatThrownBy(() -> adapter.searchAllowed(Set.of(1L), new float[512], 5))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(documentEmbeddingJpaRepository);
    }

    @Test
    void aQueryEmbeddingContainingANonFiniteValueIsRejected() {
        adapter = new PgVectorSearchAdapter(documentEmbeddingJpaRepository);
        float[] embedding = validEmbedding();
        embedding[10] = Float.NaN;

        assertThatThrownBy(() -> adapter.searchAllowed(Set.of(1L), embedding, 5))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(documentEmbeddingJpaRepository);
    }

    @Test
    void anExcessivelyLargeTopKIsBoundedBeforeReachingTheRepository() {
        adapter = new PgVectorSearchAdapter(documentEmbeddingJpaRepository);
        when(documentEmbeddingJpaRepository.searchAllowed(any(), any(), anyInt())).thenReturn(List.of());

        adapter.searchAllowed(Set.of(1L), validEmbedding(), 10_000);

        verify(documentEmbeddingJpaRepository).searchAllowed(any(), any(), org.mockito.ArgumentMatchers.eq(
                PgVectorSearchAdapter.MAX_TOP_K));
    }

    @Test
    void resultsAreMappedInTheOrderReturnedByTheRepositoryWithoutExposingScores() {
        adapter = new PgVectorSearchAdapter(documentEmbeddingJpaRepository);
        DocumentEmbeddingEntity row = new DocumentEmbeddingEntity(7L, 2, "PAGE", "3", "[0.1]", "v1", "a".repeat(64),
                "parser-1", "1", "bge-m3:567m", Instant.now());
        when(documentEmbeddingJpaRepository.searchAllowed(any(), any(), anyInt())).thenReturn(List.of(row));

        List<VectorCandidate> result = adapter.searchAllowed(Set.of(7L), validEmbedding(), 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).documentId()).isEqualTo(7L);
        assertThat(result.get(0).chunkIndex()).isEqualTo(2);
        assertThat(result.get(0).locatorType().name()).isEqualTo("PAGE");
        assertThat(result.get(0).locatorValue()).isEqualTo("3");
    }
}
