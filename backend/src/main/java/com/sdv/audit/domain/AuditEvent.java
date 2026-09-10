package com.sdv.audit.domain;

import java.time.Instant;
import java.util.Map;

/**
 * F-BE-115. 감사 이벤트 공통 모델.
 *
 * <p>JPA Entity, HTTP DTO, 로깅 프레임워크 타입에 의존하지 않는 순수 Domain 모델이다.
 * {@code metadata}는 이미 위생 처리(sanitize)된 값만 담아야 한다 - Token/Key/원문
 * Document/전체 질문/전체 Prompt를 포함하지 않는다(INV-AUD-001). 실제 sanitize는
 * 이 Record가 아니라 {@link com.sdv.audit.application.AuditService}가 수행한다 -
 * Domain은 의도적으로 그 정책을 갖지 않는다.</p>
 */
public record AuditEvent(
        String actor,
        String action,
        String target,
        String result,
        String reasonCode,
        String traceId,
        Map<String, String> metadata,
        Instant timestamp
) {
}
