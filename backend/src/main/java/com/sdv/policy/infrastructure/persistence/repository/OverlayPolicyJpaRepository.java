package com.sdv.policy.infrastructure.persistence.repository;

import com.sdv.policy.infrastructure.persistence.entity.OverlayPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F-BE-092. {@code overlay_policies} Persistence(POL-003).
 *
 * <p>{@code findByAction}만 추가한다 - 이 작업(M05)이 실제로 필요로 하는 최소
 * 조회다. 원칙/Group/Role별 세분화된 질의는 만들지 않고,
 * {@link com.sdv.policy.application.OverlayPolicyService}가 Java에서 신뢰
 * 가능한 {@code UserContext} 필드와 대조한다(Core MVP 규모에서
 * Overlay Policy 행 수가 많지 않다고 가정 - 추측성 질의 최적화를 하지
 * 않는다).</p>
 */
public interface OverlayPolicyJpaRepository extends JpaRepository<OverlayPolicyEntity, Long> {

    List<OverlayPolicyEntity> findByAction(String action);
}
