package com.sdv.source.api.dto;

/**
 * M10B 신규 - {@code PATCH /api/admin/shares/{shareId}} 요청. ADMIN 전용
 * 정책/차단 관리만 가능하다 - 수신자/등급/행위는 이 요청으로 바꿀 수 없다
 * (게시자 동의를 관리자가 넓힐 수 없다). {@code blocked=false}는 차단 해제다.
 */
public record AdminUpdateShareRequest(boolean blocked, String reason) {
}
