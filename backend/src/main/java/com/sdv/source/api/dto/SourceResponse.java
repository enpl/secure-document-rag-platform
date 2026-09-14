package com.sdv.source.api.dto;

import com.sdv.source.domain.SourceType;

import java.time.Instant;

/**
 * F-BE-029. Source 응답 DTO. {@code owner}/Token 참조 같은 민감/내부 정보는
 * 절대 포함하지 않는다.
 *
 * <p>MVP-17({@code docs/plan/SDV_MVP_DEFERRED.md}) - {@code name}(기존
 * {@code SourceConnection.getDisplayName()})과 {@code credentialPresent}(로컬에
 * 암호화 저장된 Google Credential 존재 여부, {@code source_oauth_tokens} 행
 * 존재만 의미 - 실제 Google 자격 유효성의 증거가 아니다)를 추가했다. Google
 * Token/Token 참조/암호화 Key ID는 여전히 어디에도 노출하지 않는다.</p>
 */
public record SourceResponse(Long id, SourceType type, String name, String status, Instant lastSyncAt,
        boolean credentialPresent) {
}
