package com.sdv.source.api.dto;

import java.time.Instant;

/**
 * M10B 신규(SHR-001) - {@code GET /api/sources/{id}/files}(소유자 전용 비공개
 * 선택기) 응답 한 건. {@link com.sdv.rag.api.dto.RagFileItem}과 달리 Live
 * 재확인 값이 아니다(순수 Metadata Catalog 조회) - 이 화면은 게시자 본인이
 * "무엇을 공유할지" 고르는 용도이지, 다른 사용자에게 노출하기 위한 것이
 * 아니다.
 */
public record SourceFileResponse(Long documentId, String name, String mimeType, Instant modifiedAt,
        String indexStatus) {
}
