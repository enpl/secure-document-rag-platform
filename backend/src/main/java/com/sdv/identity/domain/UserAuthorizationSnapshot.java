package com.sdv.identity.domain;

import com.sdv.policy.domain.SecurityLevel;

public record UserAuthorizationSnapshot(Long userId, String issuer, String subject, String loginId,
        SecurityLevel maximumClassification, long authorizationRevision) {

    public boolean covers(SecurityLevel classification) {
        return maximumClassification != null && maximumClassification.covers(classification);
    }
}
