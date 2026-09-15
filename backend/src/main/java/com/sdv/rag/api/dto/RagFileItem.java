package com.sdv.rag.api.dto;

import java.time.Instant;

/**
 * M10 신규(RAG-011) - {@code GET /api/rag/files} 응답 항목. JPA Entity를 직접
 * 반환하지 않는다(API DTO/Persistence Entity 분리). 모든 필드는 Live 재확인을
 * 통과한 값만 담는다 - 낡은 Catalog 값을 담지 않는다(v1.4 §2A.4).
 *
 * <ul>
 *   <li>{@code indexStatus} - 저장된(persisted) RAG 색인 상태({@code
 *       DocumentIndexStatus} 이름). Content/RAG 검색 가능 여부의 참고용일 뿐,
 *       이 API 자체가 Content를 검색/노출하지 않는다.</li>
 *   <li>{@code sourceVersionCurrent} - 저장된 {@code sourceVersion}과 지금 이
 *       Live 재확인이 반환한 Version이 같은지. {@code false}면 {@code
 *       indexStatus}가 가리키는 색인이 이 문서의 현재 Content 세대를 반영하지
 *       못할 수 있다는 뜻이다(오래된 INDEXED 세대를 최신인 것처럼 보이지 않게
 *       하기 위함).</li>
 *   <li>{@code downloadable} - Google {@code capabilities.canDownload}. 이
 *       값이 {@code true}라는 것이 이 API가 Content를 읽어도 된다는 뜻은 아니다
 *       - 이 API는 Content Fetch/Parse/LLM 호출을 절대 수행하지 않는다.</li>
 *   <li>{@code viewUrl} - 검증된 File ID로만 만든 고정 스킴/호스트({@code
 *       drive.google.com}) URL. Export Link/Google이 반환한 임의 URL이 아니다.</li>
 * </ul>
 */
public record RagFileItem(Long documentId, Long sourceId, String name, String mimeType, String sourceVersion,
        Instant modifiedAt, String indexStatus, boolean sourceVersionCurrent, boolean downloadable, String viewUrl) {
}
