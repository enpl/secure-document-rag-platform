package com.sdv.rag.application.port;

import com.sdv.rag.domain.VectorCandidate;

import java.util.Collection;
import java.util.List;

/**
 * F-BE-104(M12 신규, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.4/§22). 이미 허용된
 * 것으로 계산된 문서 ID 범위 안에서만 동작하는 Embedding Candidate 검색 경계.
 *
 * <p>이 Port는 그 자체로 권한 판단을 하지 않는다 - {@code allowedDocumentIds}는
 * 호출자({@link com.sdv.rag.application.RagRetrievalService})가 {@code
 * EffectivePermissionService.evaluateSharedAccess(...)}(B안 공유 인가) + 현재
 * 색인 Generation 호환성 확인을 이미 마친 뒤에만 넘겨야 하는 값이다(INV-RAG-002:
 * 빈 {@code allowedDocumentIds}는 "제한 없음"이 아니라 "허용된 문서가 하나도
 * 없음"이다 - 구현체는 이 경우 어떤 검색도 수행하지 않고 빈 결과를 반환해야
 * 한다). 반환된 {@link VectorCandidate}는 힌트일 뿐, 권한 증명도 답변 근거도
 * 아니다 - 직접 선택된 파일 ID도 이 Port를 우회하지 않고 동일한 Live Evidence
 * Gate({@link com.sdv.rag.application.LiveEvidenceRetrievalService})를 거쳐야
 * 한다.</p>
 */
public interface VectorSearchPort {

    /**
     * {@code allowedDocumentIds} 범위 안에서만 Cosine Distance 기준 가장 가까운
     * 순으로 정렬된 후보를 반환한다(목록 순서 자체가 순위다 - 원점수(score)는
     * 노출하지 않는다). {@code queryEmbedding}은 정확히 {@code
     * DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS}(1024) 차원의 유한(non-NaN/Inf)
     * 값이어야 한다 - 아니면 {@link IllegalArgumentException}. {@code topK}는
     * 구현체가 안전한 상한으로 Bound한다(호출자가 과도하게 큰 값을 넘겨도 무제한
     * 반환하지 않는다).
     */
    List<VectorCandidate> searchAllowed(Collection<Long> allowedDocumentIds, float[] queryEmbedding, int topK);
}
