package com.sdv.source.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * M10B 신규 - {@code PATCH /api/shares/{shareId}} 요청(소유자 전용 수정).
 * {@code expectedGeneration}은 마지막으로 읽은 공유의 세대다 - 그 사이 다른
 * 변경(본인의 다른 탭, 또는 ADMIN 차단)이 있었으면 409로 거부된다(낙관적
 * 동시성). 관리자 차단 필드는 이 요청에 없다 - 게시자는 그것을 절대 바꿀 수
 * 없다.
 */
public record UpdateShareRequest(
        long expectedGeneration,
        @NotBlank String classification,
        @NotEmpty Set<String> actions,
        @NotEmpty Set<String> recipients) {
}
