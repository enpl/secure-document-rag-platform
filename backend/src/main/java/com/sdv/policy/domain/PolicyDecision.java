package com.sdv.policy.domain;

import java.util.Objects;

/**
 * F-BE-079. 최종 접근 판단 결과(POL-004, POL-008): {@code ALLOW}/{@code DENY} +
 * 기계 판독 가능한 사유. JPA/HTTP 기술에 의존하지 않는 순수 Domain 모델이다.
 *
 * <p>{@link #allow()}/{@link #deny(PolicyReasonCode)}로만 생성한다 - 허용 결과에
 * DENY 계열 사유를, 거부 결과에 {@link PolicyReasonCode#ALLOWED}를 붙이는 모순된
 * 조합을 생성자 자체가 막는다.</p>
 */
public record PolicyDecision(boolean allowed, PolicyReasonCode reasonCode) {

    public PolicyDecision {
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        if (allowed && reasonCode != PolicyReasonCode.ALLOWED) {
            throw new IllegalArgumentException("An allowed decision must carry reasonCode ALLOWED");
        }
        if (!allowed && reasonCode == PolicyReasonCode.ALLOWED) {
            throw new IllegalArgumentException("A denied decision must not carry reasonCode ALLOWED");
        }
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(true, PolicyReasonCode.ALLOWED);
    }

    public static PolicyDecision deny(PolicyReasonCode reasonCode) {
        return new PolicyDecision(false, reasonCode);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public boolean isDenied() {
        return !allowed;
    }
}
