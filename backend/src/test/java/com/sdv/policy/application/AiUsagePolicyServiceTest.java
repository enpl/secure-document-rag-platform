package com.sdv.policy.application;

import com.sdv.policy.domain.AiRequestContext;
import com.sdv.policy.domain.PolicyDecision;
import com.sdv.policy.domain.PolicyReasonCode;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 순수 단위 테스트 - {@link AiUsagePolicyJpaRepository}를 Mockito로 대체해 Persistence
 * 없이 {@link AiUsagePolicyService}의 판단 행렬만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AiUsagePolicyServiceTest {

    @Mock
    private AiUsagePolicyJpaRepository aiUsagePolicyJpaRepository;

    @Test
    void missingPolicyIsDenied() {
        when(aiUsagePolicyJpaRepository.findById(SecurityLevel.INTERNAL.name())).thenReturn(Optional.empty());
        AiUsagePolicyService service = new AiUsagePolicyService(aiUsagePolicyJpaRepository);

        PolicyDecision decision = service.evaluate(SecurityLevel.INTERNAL, AiRequestContext.local());

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.AI_USAGE_DENIED);
    }

    @Test
    void aiDeniedModeDeniesEvenLocalProcessing() {
        when(aiUsagePolicyJpaRepository.findById(SecurityLevel.SECRET.name()))
                .thenReturn(Optional.of(new AiUsagePolicyEntity(SecurityLevel.SECRET.name(), "AI_DENIED", false)));
        AiUsagePolicyService service = new AiUsagePolicyService(aiUsagePolicyJpaRepository);

        PolicyDecision decision = service.evaluate(SecurityLevel.SECRET, AiRequestContext.local());

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.AI_USAGE_DENIED);
    }

    @Test
    void localOnlyAllowsLocalProcessing() {
        when(aiUsagePolicyJpaRepository.findById(SecurityLevel.CONFIDENTIAL.name()))
                .thenReturn(Optional.of(
                        new AiUsagePolicyEntity(SecurityLevel.CONFIDENTIAL.name(), "LOCAL_ONLY", false)));
        AiUsagePolicyService service = new AiUsagePolicyService(aiUsagePolicyJpaRepository);

        PolicyDecision decision = service.evaluate(SecurityLevel.CONFIDENTIAL, AiRequestContext.local());

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    void localOnlyDeniesExternalProviderRequest() {
        when(aiUsagePolicyJpaRepository.findById(SecurityLevel.CONFIDENTIAL.name()))
                .thenReturn(Optional.of(
                        new AiUsagePolicyEntity(SecurityLevel.CONFIDENTIAL.name(), "LOCAL_ONLY", false)));
        AiUsagePolicyService service = new AiUsagePolicyService(aiUsagePolicyJpaRepository);

        PolicyDecision decision = service.evaluate(SecurityLevel.CONFIDENTIAL, AiRequestContext.external());

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.AI_EXTERNAL_PROVIDER_DENIED);
    }

    @Test
    void externalAllowedModeRequiresExternalProviderAllowedFlagForExternalRequests() {
        when(aiUsagePolicyJpaRepository.findById(SecurityLevel.PUBLIC.name()))
                .thenReturn(Optional.of(
                        new AiUsagePolicyEntity(SecurityLevel.PUBLIC.name(), "EXTERNAL_ALLOWED", false)));
        AiUsagePolicyService service = new AiUsagePolicyService(aiUsagePolicyJpaRepository);

        PolicyDecision decision = service.evaluate(SecurityLevel.PUBLIC, AiRequestContext.external());

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.AI_EXTERNAL_PROVIDER_DENIED);
    }

    @Test
    void externalAllowedModeWithFlagTruePermitsExternalRequest() {
        when(aiUsagePolicyJpaRepository.findById(SecurityLevel.PUBLIC.name()))
                .thenReturn(Optional.of(
                        new AiUsagePolicyEntity(SecurityLevel.PUBLIC.name(), "EXTERNAL_ALLOWED", true)));
        AiUsagePolicyService service = new AiUsagePolicyService(aiUsagePolicyJpaRepository);

        PolicyDecision decision = service.evaluate(SecurityLevel.PUBLIC, AiRequestContext.external());

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    void externalAllowedModeStillPermitsLocalProcessingRegardlessOfFlag() {
        when(aiUsagePolicyJpaRepository.findById(SecurityLevel.PUBLIC.name()))
                .thenReturn(Optional.of(
                        new AiUsagePolicyEntity(SecurityLevel.PUBLIC.name(), "EXTERNAL_ALLOWED", false)));
        AiUsagePolicyService service = new AiUsagePolicyService(aiUsagePolicyJpaRepository);

        PolicyDecision decision = service.evaluate(SecurityLevel.PUBLIC, AiRequestContext.local());

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    void unrecognizedModeFailsClosed() {
        when(aiUsagePolicyJpaRepository.findById(SecurityLevel.INTERNAL.name()))
                .thenReturn(Optional.of(new AiUsagePolicyEntity(SecurityLevel.INTERNAL.name(), "MYSTERY", true)));
        AiUsagePolicyService service = new AiUsagePolicyService(aiUsagePolicyJpaRepository);

        PolicyDecision decision = service.evaluate(SecurityLevel.INTERNAL, AiRequestContext.local());

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.AI_USAGE_DENIED);
    }
}
