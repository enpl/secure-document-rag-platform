package com.sdv.source.api.dto;

import com.sdv.source.domain.SourceType;

import java.time.Instant;

/**
 * F-BE-029. Source 응답 DTO. {@code owner}/Token 참조 같은 민감/내부 정보는
 * 절대 포함하지 않는다.
 */
public record SourceResponse(Long id, SourceType type, String status, Instant lastSyncAt) {
}
