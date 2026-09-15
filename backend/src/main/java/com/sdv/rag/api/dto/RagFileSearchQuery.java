package com.sdv.rag.api.dto;

import java.time.Instant;

/**
 * M10 신규(RAG-011) - {@code GET /api/rag/files}의 검증된 검색 조건. Controller가
 * 원시 Query Parameter(문자열)를 길이/형식/범위/Allowlist 검사까지 마친 뒤 이
 * Record로 변환한다 - Service/Repository는 이미 검증된 값만 받는다. 인증된
 * 사용자(Subject)는 이 조건에 포함하지 않는다({@code CurrentUserProvider}에서만
 * 가져온다).
 */
public record RagFileSearchQuery(String q, String mimeType, Long sourceId, Instant modifiedFrom,
        Instant modifiedTo, RagFileSortKey sort, int page, int size) {
}
