package com.sdv.rag.api.dto;

import java.time.Instant;
import java.util.Set;

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
 *   <li>{@code shareId} - M16C 신규. 지금 이 항목을 노출시킨(방금 {@code
 *       EffectivePermissionService.evaluateSharedAccess}를 통과한) 바로 그 공유의
 *       ID다 - {@code documentId}와 절대 혼동하지 않는다. Client가 이 값으로
 *       {@code GET /api/shares/{shareId}/download}를 호출할 수 있다. 위조/추측된
 *       shareId는 그 Endpoint 자신의 서버 측 재검증이 별도로 거부한다 - 이 필드는
 *       그 재검증을 대신하지 않는다.</li>
 *   <li>{@code allowedActions} - 같은 공유가 이 수신자에게 부여한 행위 이름
 *       집합(예: {@code VIEW}/{@code DOWNLOAD}). Client UI는 이 값을 "다운로드
 *       버튼을 보여줄지"에 대한 힌트로만 쓴다 - 실제 인가 결정은 여전히 다운로드
 *       Endpoint 자신이 매 요청마다 다시 내린다(이 값이 낡았거나 틀려도 접근을
 *       넓히지 않는다).</li>
 * </ul>
 */
public record RagFileItem(Long documentId, Long sourceId, String name, String mimeType, String sourceVersion,
        Instant modifiedAt, String indexStatus, boolean sourceVersionCurrent, boolean downloadable, String viewUrl,
        Long shareId, Set<String> allowedActions) {
}
