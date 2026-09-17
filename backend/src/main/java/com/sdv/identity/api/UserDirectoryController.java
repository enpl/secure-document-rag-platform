package com.sdv.identity.api;

import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.model.UserContext;
import com.sdv.common.security.CurrentUserProvider;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.identity.api.dto.DirectoryUserResponse;
import com.sdv.identity.application.DirectorySearchRateLimiter;
import com.sdv.identity.application.IdentityAccessException;
import com.sdv.identity.application.IdentityRegistryService;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/directory/users")
public class UserDirectoryController {
    private final IdentityRegistryService identities;
    private final DirectorySearchRateLimiter rateLimiter;
    private final CurrentUserProvider users;

    public UserDirectoryController(IdentityRegistryService identities, DirectorySearchRateLimiter rateLimiter,
            CurrentUserProvider users) {
        this.identities = identities;
        this.rateLimiter = rateLimiter;
        this.users = users;
    }

    @GetMapping
    public List<DirectoryUserResponse> search(@RequestParam String q) {
        UserContext requester = users.getCurrentUser();
        if (!rateLimiter.allow(requester.subject())) {
            throw new IdentityAccessException(IdentityAccessException.Reason.INVALID_REQUEST, "rate limited");
        }
        return identities.searchDirectory(requester.issuer(), q);
    }

    @ExceptionHandler(IdentityAccessException.class)
    ResponseEntity<ApiErrorResponse> invalid(IdentityAccessException failure) {
        HttpStatus status = failure.reason() == IdentityAccessException.Reason.INVALID_REQUEST
                ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new ApiErrorResponse("DIRECTORY_LOOKUP_FAILED",
                "Directory lookup could not be completed.", MDC.get(TraceIdFilter.MDC_KEY)));
    }
}
