package com.sdv.policy.infrastructure.persistence.repository;

import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F-BE-093. {@code ai_usage_policies} Persistence(POL-005, AI-003). 기본 키가
 * {@code security_level}이므로 상속받은 {@code findById(String)}만으로
 * {@link com.sdv.policy.application.AiUsagePolicyService}가 필요로 하는 조회가
 * 충분하다 - 추가 메서드를 만들지 않는다.
 */
public interface AiUsagePolicyJpaRepository extends JpaRepository<AiUsagePolicyEntity, String> {
}
