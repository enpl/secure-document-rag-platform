package com.sdv.rag.api.dto;

import org.springframework.data.domain.Sort;

/**
 * M10 신규(RAG-011) - {@code GET /api/rag/files}가 허용하는 정렬 키 목록(Allowlist).
 * 클라이언트가 임의 컬럼/식으로 정렬을 요청할 수 없다 - 이 목록에 없는 값은
 * Controller가 400으로 거부한다. {@code id} Tie-breaker는
 * {@code FileMetadataDiscoveryService}가 이 값과 같은 방향으로 항상 덧붙인다
 * (Paging 안정성).
 */
public enum RagFileSortKey {
    NAME_ASC("name", Sort.Direction.ASC),
    NAME_DESC("name", Sort.Direction.DESC),
    MODIFIED_AT_ASC("modifiedAt", Sort.Direction.ASC),
    MODIFIED_AT_DESC("modifiedAt", Sort.Direction.DESC);

    private final String property;
    private final Sort.Direction direction;

    RagFileSortKey(String property, Sort.Direction direction) {
        this.property = property;
        this.direction = direction;
    }

    public String property() {
        return property;
    }

    public Sort.Direction direction() {
        return direction;
    }
}
