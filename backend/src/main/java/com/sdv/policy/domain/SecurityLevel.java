package com.sdv.policy.domain;

/**
 * F-BE-078. SDV 자체 보안 등급(POL-002). {@code document_security_labels}/
 * {@code ai_usage_policies}(V001)가 이미 정확히 이 네 값만 CHECK 제약으로
 * 허용한다({@code chk_security_level}, {@code chk_ai_security_level}) - 명세가
 * 정의한 값만 갖는다.
 */
public enum SecurityLevel {
    PUBLIC(0),
    INTERNAL(1),
    CONFIDENTIAL(2),
    SECRET(3);

    private final int rank;

    SecurityLevel(int rank) {
        this.rank = rank;
    }

    /** Explicit policy rank. Enum declaration order/ordinal is never authorization evidence. */
    public int rank() {
        return rank;
    }

    public boolean covers(SecurityLevel requested) {
        return requested != null && rank >= requested.rank;
    }
}
