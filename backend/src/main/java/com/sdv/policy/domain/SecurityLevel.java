package com.sdv.policy.domain;

/**
 * F-BE-078. SDV 자체 보안 등급(POL-002). {@code document_security_labels}/
 * {@code ai_usage_policies}(V001)가 이미 정확히 이 네 값만 CHECK 제약으로
 * 허용한다({@code chk_security_level}, {@code chk_ai_security_level}) - 명세가
 * 정의한 값만 갖는다.
 */
public enum SecurityLevel {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    SECRET
}
