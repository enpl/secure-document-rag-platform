package com.sdv.source.api;

import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.security.CurrentUserProvider;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.source.api.dto.CreateShareRequest;
import com.sdv.source.api.dto.ShareResponse;
import com.sdv.source.api.dto.UpdateShareRequest;
import com.sdv.source.application.InvalidShareRequestException;
import com.sdv.source.application.ShareGenerationConflictException;
import com.sdv.source.application.SourceSharingService;
import com.sdv.source.domain.DocumentShare;
import com.sdv.source.domain.ShareAction;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M10B 신규(SHR-001/002/003, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.13) - 게시자
 * 소유 공유 관리 API: {@code GET/POST /api/shares}, {@code PATCH/DELETE
 * /api/shares/{shareId}}. 모든 소유권/현재 Metadata/세대 검증은 {@link
 * SourceSharingService}가 수행한다 - 이 Controller는 HTTP 관심사만 다룬다.
 */
@RestController
@RequestMapping("/api/shares")
public class SourceShareController {

    private static final String VALIDATION_ERROR_CODE = "VALIDATION_ERROR";
    private static final String SHARE_CONFLICT_CODE = "SHARE_GENERATION_CONFLICT";

    private final SourceSharingService sourceSharingService;
    private final CurrentUserProvider currentUserProvider;

    public SourceShareController(SourceSharingService sourceSharingService, CurrentUserProvider currentUserProvider) {
        this.sourceSharingService = sourceSharingService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public List<ShareResponse> list() {
        String publisherSubject = currentUserProvider.getCurrentUser().subject();
        return sourceSharingService.listOwn(publisherSubject).stream().map(SourceShareController::toResponse)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShareResponse create(@Valid @RequestBody CreateShareRequest request) {
        String publisherSubject = currentUserProvider.getCurrentUser().subject();
        DocumentShare share = sourceSharingService.createShare(publisherSubject, request.sourceId(),
                request.documentId(), request.classification(), request.actions(), request.recipients());
        return toResponse(share);
    }

    @PatchMapping("/{shareId}")
    public ShareResponse update(@PathVariable Long shareId, @Valid @RequestBody UpdateShareRequest request) {
        String publisherSubject = currentUserProvider.getCurrentUser().subject();
        DocumentShare share = sourceSharingService.updateShare(publisherSubject, shareId,
                request.expectedGeneration(), request.classification(), request.actions(), request.recipients());
        return toResponse(share);
    }

    @DeleteMapping("/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unshare(@PathVariable Long shareId) {
        String publisherSubject = currentUserProvider.getCurrentUser().subject();
        sourceSharingService.unshare(publisherSubject, shareId);
    }

    @ExceptionHandler(InvalidShareRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidShareRequest(InvalidShareRequestException ex) {
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_ERROR_CODE, "Request validation failed.");
    }

    @ExceptionHandler(ShareGenerationConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleGenerationConflict(ShareGenerationConflictException ex) {
        return respond(HttpStatus.CONFLICT, SHARE_CONFLICT_CODE,
                "The share has changed since it was last read - reload and retry.");
    }

    private static ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String code, String message) {
        ApiErrorResponse body = new ApiErrorResponse(code, message, MDC.get(TraceIdFilter.MDC_KEY));
        return ResponseEntity.status(status).body(body);
    }

    private static ShareResponse toResponse(DocumentShare share) {
        Set<String> actionNames = share.getAllowedActions().stream().map(ShareAction::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return new ShareResponse(share.getId(), share.getSourceId(), share.getDocumentId(),
                share.getClassification().name(), actionNames, share.getRecipients(), share.isAdminBlocked(),
                share.getAdminBlockReason(), share.getGeneration(), share.isActive(), share.getCreatedAt(),
                share.getUpdatedAt(), share.getRevokedAt());
    }
}
