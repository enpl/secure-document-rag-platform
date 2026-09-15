package com.sdv.source.api.dto;

import java.time.Instant;
import java.util.Set;

/**
 * M10B 신규 - {@code GET /api/admin/shares} 응답. 게시된 공유의 Metadata만
 * 담는다 - 비공개 Drive 파일 목록이나 Token/Credential은 절대 포함하지 않는다
 * (ADMIN이 이 정보로 콘텐츠/다운로드를 우회할 수 없다).
 */
public record AdminShareResponse(Long id, String publisherSubject, Long sourceId, Long documentId,
        String classification, Set<String> allowedActions, Set<String> recipients, boolean adminBlocked,
        String adminBlockReason, long generation, Instant createdAt, Instant updatedAt) {
}
