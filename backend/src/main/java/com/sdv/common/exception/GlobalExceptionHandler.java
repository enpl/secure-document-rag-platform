package com.sdv.common.exception;

import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.trace.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * F-BE-010. 처리되지 않은 예외를 POL-008/INS-003이 요구하는 표준 오류 형태
 * ({@link ApiErrorResponse})로 변환한다.
 *
 * 클라이언트 응답에는 Stack Trace, 예외 타입, SQL, 자격증명, 내부 예외 메시지 원문을
 * 절대 포함하지 않는다 - 소수의 고정된 code만 사용하며, 의도적으로 예외별 세분화된
 * Hierarchy를 만들지 않는다(요구되지 않은 확장).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String NOT_FOUND = "NOT_FOUND";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "Request validation failed.");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "Request validation failed.");
    }

    // M04: Owner-Scoped 조회 실패를 "존재하지 않음"과 "다른 계정 소유"를 구분하지
    // 않는 동일한 404로 변환한다 - Cross-Account 존재 여부를 노출하지 않는다.
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, NOT_FOUND, "The requested resource was not found.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        // 원본 예외 객체(Throwable), 그 메시지, Cause, Suppressed는 절대 로그에 남기지
        // 않는다 - 이미 검증된 traceId와 고정된 code만 남긴다. ex 파라미터는 의도적으로
        // 로깅에 쓰지 않는다(민감정보가 예외 메시지/Cause 체인에 담겨 있을 수 있음).
        log.error("code={} traceId={}", INTERNAL_ERROR, currentTraceId());
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, "An unexpected error occurred.");
    }

    private ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String code, String message) {
        ApiErrorResponse body = new ApiErrorResponse(code, message, currentTraceId());
        return ResponseEntity.status(status).body(body);
    }

    private String currentTraceId() {
        return MDC.get(TraceIdFilter.MDC_KEY);
    }
}
