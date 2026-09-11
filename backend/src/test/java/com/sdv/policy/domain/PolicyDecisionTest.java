package com.sdv.policy.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyDecisionTest {

    @Test
    void allowFactoryProducesAllowedTrueWithAllowedReason() {
        PolicyDecision decision = PolicyDecision.allow();

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.isDenied()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.ALLOWED);
    }

    @Test
    void denyFactoryProducesAllowedFalseWithGivenReason() {
        PolicyDecision decision = PolicyDecision.deny(PolicyReasonCode.SOURCE_PERMISSION_DENIED);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.SOURCE_PERMISSION_DENIED);
    }

    @Test
    void anAllowedDecisionCannotCarryANonAllowedReasonCode() {
        assertThatThrownBy(() -> new PolicyDecision(true, PolicyReasonCode.OVERLAY_DENIED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeniedDecisionCannotCarryTheAllowedReasonCode() {
        assertThatThrownBy(() -> new PolicyDecision(false, PolicyReasonCode.ALLOWED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
