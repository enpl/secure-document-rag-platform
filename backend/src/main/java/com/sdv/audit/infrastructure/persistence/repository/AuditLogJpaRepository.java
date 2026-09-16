package com.sdv.audit.infrastructure.persistence.repository;

import com.sdv.audit.infrastructure.persistence.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * F-BE-121. {@code audit_logs} Persistence. 순수 저장/조회만 담당한다 - Admin
 * Audit 검색 API는 M03 범위 밖이다.
 */
public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, Long>, JpaSpecificationExecutor<AuditLogEntity> {
}
