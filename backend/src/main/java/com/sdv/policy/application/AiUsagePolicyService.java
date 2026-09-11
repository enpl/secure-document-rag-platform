package com.sdv.policy.application;

import com.sdv.policy.domain.AiRequestContext;
import com.sdv.policy.domain.PolicyDecision;
import com.sdv.policy.domain.PolicyReasonCode;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * F-BE-083. 보안 등급별 AI Provider 사용 허용 여부(POL-005, AI-003, INV-AI-002).
 *
 * <p>규칙(v3.2 §20):</p>
 * <ul>
 *   <li>정책이 없으면(등급에 대한 {@code ai_usage_policies} 행이 없음) 거부한다.</li>
 *   <li>{@code AI_DENIED}는 모든 AI 사용을 거부한다.</li>
 *   <li>{@code LOCAL_ONLY}는 Local 처리만 허용하고 External 요청은 거부한다.</li>
 *   <li>{@code EXTERNAL_ALLOWED}는 {@code external_provider_allowed=true}일 때만
 *       External Provider 요청을 허용한다 - External은 기본 OFF다(INV-AI-002).</li>
 *   <li>인식되지 않는 {@code mode} 값은 Fail Closed(거부)한다.</li>
 * </ul>
 *
 * <p>이 Service는 어떤 LLM Provider도 호출하지 않는다 - 순수 정책 판단이다.</p>
 */
@Service
public class AiUsagePolicyService {

    private final AiUsagePolicyJpaRepository aiUsagePolicyJpaRepository;

    public AiUsagePolicyService(AiUsagePolicyJpaRepository aiUsagePolicyJpaRepository) {
        this.aiUsagePolicyJpaRepository = aiUsagePolicyJpaRepository;
    }

    @Transactional(readOnly = true)
    public PolicyDecision evaluate(SecurityLevel securityLevel, AiRequestContext request) {
        Optional<AiUsagePolicyEntity> maybePolicy = aiUsagePolicyJpaRepository.findById(securityLevel.name());
        if (maybePolicy.isEmpty()) {
            return PolicyDecision.deny(PolicyReasonCode.AI_USAGE_DENIED);
        }
        AiUsagePolicyEntity policy = maybePolicy.get();
        String mode = policy.getMode();

        if ("AI_DENIED".equals(mode)) {
            return PolicyDecision.deny(PolicyReasonCode.AI_USAGE_DENIED);
        }
        if ("LOCAL_ONLY".equals(mode)) {
            return request.externalProviderRequested()
                    ? PolicyDecision.deny(PolicyReasonCode.AI_EXTERNAL_PROVIDER_DENIED)
                    : PolicyDecision.allow();
        }
        if ("EXTERNAL_ALLOWED".equals(mode)) {
            if (!request.externalProviderRequested()) {
                return PolicyDecision.allow();
            }
            return policy.isExternalProviderAllowed()
                    ? PolicyDecision.allow()
                    : PolicyDecision.deny(PolicyReasonCode.AI_EXTERNAL_PROVIDER_DENIED);
        }

        // 인식되지 않는 mode 값 - Fail Closed.
        return PolicyDecision.deny(PolicyReasonCode.AI_USAGE_DENIED);
    }
}
