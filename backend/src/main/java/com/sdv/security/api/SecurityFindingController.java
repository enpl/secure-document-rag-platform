package com.sdv.security.api;

import com.sdv.common.security.CurrentUserProvider;
import com.sdv.security.application.SecurityFindingService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpStatus;
import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.trace.TraceIdFilter;
import org.slf4j.MDC;

@RestController
@RequestMapping("/api/admin/security/findings")
public class SecurityFindingController {
    private final SecurityFindingService service;
    private final CurrentUserProvider users;
    public SecurityFindingController(SecurityFindingService service, CurrentUserProvider users) {
        this.service = service; this.users = users;
    }
    @GetMapping
    public ResponseEntity<SecurityFindingService.Page> list(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || page > 500 || size < 1 || size > 100) throw new IllegalArgumentException("invalid bounds");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(status == null ? null : status.trim().toUpperCase(java.util.Locale.ROOT), page, size));
    }
    @PatchMapping("/{id}")
    public ResponseEntity<SecurityFindingService.Item> update(@PathVariable Long id, @RequestBody Update request) {
        String status = request == null || request.status() == null ? "" : request.status().trim().toUpperCase(java.util.Locale.ROOT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(users.getCurrentUser(), id, status));
    }
    public record Update(String status) { }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> invalid(IllegalArgumentException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_ERROR", "Request validation failed.",
                        MDC.get(TraceIdFilter.MDC_KEY)));
    }
}
