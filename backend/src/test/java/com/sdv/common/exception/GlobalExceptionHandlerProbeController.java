package com.sdv.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@link GlobalExceptionHandlerWebTest} 전용 Test Double. 운영 코드에는 존재하지
 * 않으며, {@code GlobalExceptionHandler}/{@code TraceIdFilter}를 실제 HTTP
 * 요청/응답 경로로 태우기 위한 최소한의 성공/검증-실패/예외 Endpoint만 제공한다.
 */
@RestController
@RequestMapping("/test-probe")
class GlobalExceptionHandlerProbeController {

    static final String INTERNAL_DETAIL_MARKER = "SDV_TEST_MARKER_internal_detail_5e01";
    static final String NESTED_CAUSE_MARKER = "SDV_TEST_MARKER_nested_cause_88ab";

    @GetMapping("/ok")
    Map<String, String> ok() {
        return Map.of("status", "ok");
    }

    @PostMapping("/validate")
    Map<String, String> validate(@RequestBody @Valid ProbeRequest request) {
        return Map.of("status", "ok");
    }

    @GetMapping("/boom")
    Map<String, String> boom() {
        throw new RuntimeException("internal detail " + INTERNAL_DETAIL_MARKER + "=hunter2");
    }

    /** {@code boom()}과 달리, 예외 로깅이 Cause 체인까지 절대 남기지 않음을 검증하기 위한 전용 Endpoint. */
    @GetMapping("/boom-with-cause")
    Map<String, String> boomWithCause() {
        RuntimeException cause = new RuntimeException("cause detail " + NESTED_CAUSE_MARKER);
        throw new RuntimeException("top detail " + INTERNAL_DETAIL_MARKER, cause);
    }

    record ProbeRequest(@NotBlank String name) {
    }
}
