package com.sdv.source.api.dto;

import com.sdv.source.domain.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * F-BE-028. Source 생성 요청. {@code owner}/{@code accountId} 등 소유자 관련
 * 필드는 의도적으로 존재하지 않는다 - 소유자는 항상 인증된 Subject에서만
 * 결정된다({@code SourceAdminController}).
 */
public record CreateSourceRequest(
        @NotNull SourceType type,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 30) String syncMode) {
}
