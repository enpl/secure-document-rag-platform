package com.sdv.source.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * M10B 신규(SHR-001, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.13) - {@code POST
 * /api/shares} 요청. {@code publisherSubject}는 존재하지 않는다 - 항상 인증된
 * Subject에서만 결정된다({@code SourceShareController}). {@code classification}은
 * {@link com.sdv.policy.domain.SecurityLevel} 이름 문자열이어야 한다(그 밖의 값은
 * {@code InvalidShareRequestException} → 400) - 누락되면 Fail Closed로 거부한다.
 */
public record CreateShareRequest(
        @NotNull Long sourceId,
        @NotNull Long documentId,
        @NotBlank String classification,
        @NotEmpty Set<String> actions,
        @NotEmpty Set<String> recipients) {
}
