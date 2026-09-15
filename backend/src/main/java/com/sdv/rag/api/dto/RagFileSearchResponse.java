package com.sdv.rag.api.dto;

import java.util.List;

/**
 * M10 신규(RAG-011) - {@code GET /api/rag/files} 응답. 원시 Catalog 총계/거부된
 * 후보 개수·이름/내부 Cursor·원시 DB Offset은 절대 포함하지 않는다 - {@code
 * items.size()}는 "이번 페이지에서 검증되어 반환된 개수"일 뿐 전체 결과 총계가
 * 아니다.
 *
 * <h2>M10 후속 교정 - Page는 원시 DB 행이 아니라 확인된(Verified) 순서 위치를 센다</h2>
 * <p>{@code items}는 정렬 순서 0번 위치부터 정책/Live 검증을 모두 통과해 "확정적으로
 * 보인다"고 판정된 후보에만 매긴 순서 번호가 {@code [page*size, page*size+size)}
 * 구간에 들어오는 것들이다 - 권한 없거나 Live 검증에 실패한 원시 행은 이 번호를
 * 전혀 소모하지 않는다({@code FileMetadataDiscoveryService} 참고). 그래서 앞쪽에
 * 숨은 행이 몇 개 섞여 있든, 실제로 보이는 파일들의 Page 구성 자체는 달라지지
 * 않는다(이전에는 원시 DB 오프셋에 Page를 매핑해, 숨은 행이 섞이면 보이는 파일이
 * 다른 Page로 밀리거나 빈 Page가 나오는 결함이 있었다 - 교정됨).</p>
 *
 * <p>{@code hasMore}는 세 값을 갖는 {@link Boolean}이다(Tri-state):</p>
 * <ul>
 *   <li>{@code true} - 요청한 Page 바로 다음 위치에서 실제로 보이는(정책/Live 검증을
 *       모두 통과한) 후보를 확인했다.</li>
 *   <li>{@code false} - 확인 불가 후보를 한 번도 만나지 않은 채 원시 Catalog 자체가
 *       끝났다 - "더 이상 없다"고 확신할 수 있다.</li>
 *   <li>{@code null}(Unknown) - Bounded Candidate Scan 상한 또는 시간 예산에
 *       도달했거나, 확인 자체에 실패한(Network/자격증명 문제 등) 후보를 만나 그
 *       이후 위치를 더 이상 신뢰할 수 없어 멈췄다. 더 있을 수도, 없을 수도 있다 -
 *       클라이언트는 "없다"로 단정하지 말고 원하면 같은 조건으로 다시 조회해야
 *       한다.</li>
 * </ul>
 *
 * <p>{@code partial} - 이번 응답이 이 Page(및 위 {@code hasMore} 판단)에 필요한
 * 모든 후보를 확정적으로 판정하지 못했는지 - {@code hasMore=null}과 항상 함께
 * {@code true}다(반대로 {@code hasMore}가 {@code true}/{@code false}면 {@code
 * partial=false}). {@code true}면 이번 응답이 완전한 결과가 아닐 수 있다는
 * 뜻이다 - 같은 조건으로 다시 요청하는 것이 안전하다(권한/검증은 매 요청마다
 * 새로 수행되므로). 확인하지 못한 후보 자체는 {@code items}에 담기지 않는다
 * (Fail Closed - 확인 못한 것을 보인다고 주장하지 않는다). 깊은 Page(큰 {@code
 * page*size})는 Bounded Candidate Scan 상한 안에서 도달되지 않을 수 있다는
 * 알려진 한계가 있다 - 그 경우도 이 필드로 정직하게 드러난다.</p>
 */
public record RagFileSearchResponse(List<RagFileItem> items, Boolean hasMore, boolean partial) {
}
