package com.sdv.audit.api;

import com.sdv.audit.application.AuditQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.trace.TraceIdFilter;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/admin/audits")
public class AuditAdminController {
    private final AuditQueryService service;
    public AuditAdminController(AuditQueryService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<AuditQueryService.Page> list(@RequestParam(required = false) String action,
            @RequestParam(required = false) String result, @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        try {
            if (page < 0 || page > 500 || size < 1 || size > 100) throw new IllegalArgumentException();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.query(trim(action), trim(result),
                    trim(traceId), instant(from), instant(to), page, size));
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            throw new InvalidAuditQueryException();
        }
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Instant instant(String value) { return trim(value) == null ? null : Instant.parse(value.trim()); }
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    static final class InvalidAuditQueryException extends RuntimeException { }

    @ExceptionHandler(InvalidAuditQueryException.class)
    public ResponseEntity<ApiErrorResponse> invalid(InvalidAuditQueryException ignored) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("VALIDATION_ERROR", "Request validation failed.",
                MDC.get(TraceIdFilter.MDC_KEY)));
    }
}
