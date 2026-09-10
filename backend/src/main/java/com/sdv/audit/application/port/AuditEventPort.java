package com.sdv.audit.application.port;

import com.sdv.audit.domain.AuditEvent;

/**
 * {@code AuditEvent}를 영속화하는 최소 Outbound Port.
 *
 * <p><b>비-canonical 구현 결정:</b> 현재 v3.2 Repository Markdown Manifest는 이
 * 파일/경로를 공식 File ID로 정의하지 않는다. 기존 관례
 * ({@code com.sdv.source.application.port.DocumentSourceConnector},
 * {@code com.sdv.rag.application.port.VectorSearchPort})를 따라
 * {@code <feature>.application.port.<Name>Port} 형태로 가장 작은 규모의 Port만
 * 추가했다 - 새 File ID를 만들어 붙이지 않는다. M02에서는 이 Port를 구현하는
 * Persistence Adapter(F-BE-120/121)를 만들지 않는다 - 이는 이후 작업 범위다.</p>
 *
 * <p>실패는 절대 조용히 삼키지 않는다: 구현체가 예외를 던지면
 * {@link com.sdv.audit.application.AuditService}는 그 예외를 그대로 전파한다.
 * 이는 M02의 최소 fail-visible 동작이며, 최종 Domain 전역 정책으로 확정된 것은
 * 아니다 - 향후 Retry/DLQ 등 더 구체적인 정책이 도입되면 대체될 수 있다.</p>
 */
public interface AuditEventPort {

    void save(AuditEvent event);
}
