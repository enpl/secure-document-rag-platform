package com.sdv.audit.application;

import com.sdv.audit.application.port.AuditEventPort;
import com.sdv.audit.domain.AuditEvent;
import com.sdv.common.logging.SensitiveLogFilter;
import com.sdv.common.trace.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F-BE-116. 감사 이벤트 저장 Use Case.
 *
 * <p>현재 요청의 Effective Trace ID({@link TraceIdFilter#MDC_KEY})를 그대로
 * 사용하며, metadata 값은 {@link SensitiveLogFilter}로 위생 처리한 뒤에만
 * {@link AuditEvent}에 담는다(INV-AUD-001).</p>
 *
 * <p>M02에서는 {@link AuditEventPort}를 구현하는 Persistence Adapter가 없어
 * Spring Bean으로 등록하지 않았다. M03에서
 * {@code com.sdv.audit.infrastructure.persistence.AuditLogPersistenceAdapter}가
 * 추가되어 {@code @Service}로 등록한다 - 인증/인가 거부 감사를 영속화하기 위함이다.</p>
 */
@Service
public class AuditService {

    private final AuditEventPort auditEventPort;

    public AuditService(AuditEventPort auditEventPort) {
        this.auditEventPort = auditEventPort;
    }

    /**
     * 감사 이벤트를 기록한다. {@code auditEventPort}가 실패하면 그 예외를 그대로
     * 전파한다 - 조용히 삼키지 않는다(M02 최소 fail-visible 동작).
     */
    public void record(String actor, String action, String target, String result, String reasonCode,
            Map<String, String> metadata) {
        AuditEvent event = new AuditEvent(
                actor,
                action,
                target,
                result,
                reasonCode,
                MDC.get(TraceIdFilter.MDC_KEY),
                sanitize(metadata),
                Instant.now());
        auditEventPort.save(event);
    }

    private static Map<String, String> sanitize(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            // key 자체가 민감 카테고리(token/password/secret/content/... 및 표기
            // 변형)로 인식되면, 값의 내용과 무관하게 값 전체를 교체한다 - key가
            // 아니라 값 내부의 key=value 형태만 찾는 SensitiveLogFilter.mask()만으로는
            // "token" -> "그냥 순수 비밀값"처럼 key=value 패턴이 없는 값을 놓친다.
            String safeValue = SensitiveLogFilter.isSensitiveKey(entry.getKey())
                    ? SensitiveLogFilter.MASK
                    : SensitiveLogFilter.mask(entry.getValue());
            sanitized.put(entry.getKey(), safeValue);
        }
        return Map.copyOf(sanitized);
    }
}
