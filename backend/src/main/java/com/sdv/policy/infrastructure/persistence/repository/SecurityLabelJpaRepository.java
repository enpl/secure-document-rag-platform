package com.sdv.policy.infrastructure.persistence.repository;

import com.sdv.policy.infrastructure.persistence.entity.DocumentSecurityLabelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F-BE-134. {@code document_security_labels} 조회(POL-002, POL-006). 기본
 * 키가 {@code document_id}이므로 상속받은 {@code findById(Long)}만으로
 * {@link com.sdv.policy.application.SecurityLabelService}가 필요로 하는
 * get/set이 충분하다 - 추가 메서드를 만들지 않는다.
 */
public interface SecurityLabelJpaRepository extends JpaRepository<DocumentSecurityLabelEntity, Long> {
}
