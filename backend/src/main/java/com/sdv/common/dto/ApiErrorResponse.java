package com.sdv.common.dto;

/**
 * F-BE-011. 표준 API 오류 응답.
 *
 * {@code code}는 {@link com.sdv.common.exception.GlobalExceptionHandler}가 사용하는
 * 소수의 고정된 값만 가진다. {@code message}는 항상 안전한 고정 문구이며, Stack Trace,
 * 예외 타입, SQL, 자격증명, 내부 예외 메시지 원문을 포함하지 않는다.
 */
public record ApiErrorResponse(String code, String message, String traceId) {
}
