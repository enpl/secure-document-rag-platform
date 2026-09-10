package com.sdv.audit.infrastructure.persistence;

import com.sdv.audit.application.port.AuditEventPort;
import com.sdv.audit.domain.AuditEvent;
import com.sdv.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.sdv.audit.infrastructure.persistence.repository.AuditLogJpaRepository;
import org.springframework.stereotype.Component;

/**
 * {@link AuditEventPort}의 최소 Persistence 구현 - {@code audit_logs}(V001)에
 * 그대로 저장한다.
 *
 * <p><b>비-canonical 설계 결정:</b> 현재 v3.2 Repository Markdown Manifest는 이
 * Adapter 파일 경로 자체에 File ID를 부여하지 않는다(Entity는 F-BE-120, Repository는
 * F-BE-121로 이미 canonical). 이 경로는 M03에서 새로 결정한 것이며, 새 File ID를
 * 임의로 만들어 붙이지 않는다 - handoff에 그대로 기록한다.</p>
 *
 * <p>스키마/Migration은 전혀 건드리지 않는다 - 기존 V001 {@code audit_logs} 컬럼에만
 * 매핑한다. Query API, 검색, RAG Recorder, Aspect, 보존정책, 해시 등은 구현하지
 * 않는다(M03 범위 밖).</p>
 */
@Component
public class AuditLogPersistenceAdapter implements AuditEventPort {

    /** M03이 만드는 모든 Audit Event는 HTTP 요청(인증/인가 거부)에서 비롯된다. */
    private static final String HTTP_REQUEST_TARGET_TYPE = "HTTP_REQUEST";

    private final AuditLogJpaRepository auditLogJpaRepository;

    public AuditLogPersistenceAdapter(AuditLogJpaRepository auditLogJpaRepository) {
        this.auditLogJpaRepository = auditLogJpaRepository;
    }

    @Override
    public void save(AuditEvent event) {
        AuditLogEntity entity = new AuditLogEntity(
                event.actor(),
                event.action(),
                HTTP_REQUEST_TARGET_TYPE,
                event.target(),
                event.result(),
                event.reasonCode(),
                event.traceId(),
                event.metadata());
        auditLogJpaRepository.save(entity);
    }
}
