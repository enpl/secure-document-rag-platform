package com.sdv.source.api.dto;

import java.time.Instant;
import java.util.Set;

/**
 * M10B 신규 - 게시자 소유 공유 응답. {@code publisherSubject}/게시자의 Drive
 * Credential은 포함하지 않는다(호출자 본인 것이므로 되돌려줄 필요가 없다).
 * {@code active}는 {@code revokedAt == null}을 그대로 노출한 것 - 철회된 공유도
 * 이력으로 계속 목록에 남는다({@code GET /api/shares}).
 */
public record ShareResponse(Long id, Long sourceId, Long documentId, String audience, String classification,
        Set<String> allowedActions, java.util.List<ShareRecipientResponse> recipients, boolean adminBlocked, String adminBlockReason,
        long generation, boolean active, Instant createdAt, Instant updatedAt, Instant revokedAt) {
}
