package com.sdv.identity.api;

import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.security.CurrentUserProvider;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.identity.api.dto.AdminUserPageResponse;
import com.sdv.identity.api.dto.AdminUserResponse;
import com.sdv.identity.api.dto.UpdateUserAccessRequest;
import com.sdv.identity.application.IdentityAccessException;
import com.sdv.identity.application.IdentityRegistryService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserAccessController {
    private final IdentityRegistryService identities;
    private final CurrentUserProvider users;

    public AdminUserAccessController(IdentityRegistryService identities, CurrentUserProvider users) {
        this.identities = identities;
        this.users = users;
    }

    @GetMapping
    public AdminUserPageResponse list(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        users.getCurrentUser();
        return identities.adminSearch(q, page, size);
    }

    @GetMapping("/{id}")
    public AdminUserResponse get(@PathVariable Long id) {
        users.getCurrentUser();
        return identities.adminGet(id);
    }

    @PatchMapping("/{id}/access")
    public AdminUserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserAccessRequest request) {
        String actor = users.getCurrentUser().subject();
        return identities.updateAccess(actor, id, request.expectedVersion(), request.maximumClassification(),
                request.active());
    }

    @ExceptionHandler(IdentityAccessException.class)
    ResponseEntity<ApiErrorResponse> failure(IdentityAccessException failure) {
        HttpStatus status = switch (failure.reason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        String code = failure.reason() == IdentityAccessException.Reason.CONFLICT
                ? "USER_ACCESS_CONFLICT" : "USER_ACCESS_REQUEST_FAILED";
        return ResponseEntity.status(status).body(new ApiErrorResponse(code,
                status == HttpStatus.CONFLICT ? "User access changed; reload and retry." : "User access request failed.",
                MDC.get(TraceIdFilter.MDC_KEY)));
    }
}
