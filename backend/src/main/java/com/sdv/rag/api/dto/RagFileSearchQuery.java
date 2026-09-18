package com.sdv.rag.api.dto;

import java.time.Instant;

/**
 * M10 신규(RAG-011) - {@code GET /api/rag/files}의 검증된 검색 조건. Controller가
 * 원시 Query Parameter(문자열)를 길이/형식/범위/Allowlist 검사까지 마친 뒤 이
 * Record로 변환한다 - Service/Repository는 이미 검증된 값만 받는다. 인증된
 * 사용자(Subject)는 이 조건에 포함하지 않는다({@code CurrentUserProvider}에서만
 * 가져온다).
 */
public record RagFileSearchQuery(String q, RagFileNameMatch nameMatch, String mimeType, Long sourceId,
        Instant modifiedFrom, Instant modifiedTo, RagFileSortKey sort, int page, int size) {

    public RagFileSearchQuery {
        if (nameMatch == null) {
            nameMatch = RagFileNameMatch.CONTAINS;
        }
    }

    /**
     * M17 자연어 파일 검색 교정 이전의 기존 호출자용 하위 호환 생성자 - 항상
     * {@link RagFileNameMatch#CONTAINS}(기존 의미 그대로 유지). {@code
     * RagQueryController}의 {@code GET /api/rag/files} 직접 호출은 이 생성자를
     * 계속 쓴다 - PREFIX는 {@code NaturalLanguageFileQueryParser}만 명시적으로
     * 선택한다.
     */
    public RagFileSearchQuery(String q, String mimeType, Long sourceId, Instant modifiedFrom, Instant modifiedTo,
            RagFileSortKey sort, int page, int size) {
        this(q, RagFileNameMatch.CONTAINS, mimeType, sourceId, modifiedFrom, modifiedTo, sort, page, size);
    }
}
