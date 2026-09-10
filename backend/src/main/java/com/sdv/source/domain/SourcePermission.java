package com.sdv.source.domain;

/**
 * F-BE-021. 정규화된 Source ACL 모델(POL-001, SRC-005). Source별 권한 객체는
 * Policy Layer에 도달하기 전에 이 형태로 정규화되어야 한다.
 */
public record SourcePermission(SourcePrincipal principal, String permission) {
}
