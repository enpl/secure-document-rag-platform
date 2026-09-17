package com.sdv.source.api;

import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.security.CurrentUserProvider;
import com.sdv.common.model.UserContext;
import com.sdv.identity.application.IdentityAccessException;
import com.sdv.identity.application.IdentityRegistryService;
import com.sdv.identity.api.dto.DirectoryUserResponse;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.source.api.dto.CreateShareRequest;
import com.sdv.source.api.dto.ShareResponse;
import com.sdv.source.api.dto.ShareRecipientResponse;
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
    private final IdentityRegistryService identities;

    public SourceShareController(SourceSharingService sourceSharingService, CurrentUserProvider currentUserProvider,
            IdentityRegistryService identities) {
        this.sourceSharingService = sourceSharingService;
        this.currentUserProvider = currentUserProvider;
        this.identities = identities;
    }

    @GetMapping
    public List<ShareResponse> list() {
        UserContext publisher = currentUserProvider.getCurrentUser();
        return sourceSharingService.listOwn(publisher.subject()).stream().map(share -> toResponse(share, publisher.issuer()))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShareResponse create(@Valid @RequestBody CreateShareRequest request) {
        UserContext publisher = currentUserProvider.getCurrentUser();
        var recipients = resolveRecipients(publisher, request.audience(), request.recipientUserIds());
        DocumentShare share = sourceSharingService.createShare(publisher.subject(), request.sourceId(),
                request.documentId(), request.audience(), request.classification(), request.actions(), recipients);
        return toResponse(share, publisher.issuer());
    }

    @PatchMapping("/{shareId}")
    public ShareResponse update(@PathVariable Long shareId, @Valid @RequestBody UpdateShareRequest request) {
        UserContext publisher = currentUserProvider.getCurrentUser();
        var recipients = resolveRecipients(publisher, request.audience(), request.recipientUserIds());
        DocumentShare share = sourceSharingService.updateShare(publisher.subject(), shareId,
                request.expectedGeneration(), request.audience(), request.classification(), request.actions(), recipients);
        return toResponse(share, publisher.issuer());
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

    @ExceptionHandler(IdentityAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRecipient(IdentityAccessException ex) {
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_ERROR_CODE, "Selected recipient is unavailable.");
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

    private java.util.List<IdentityRegistryService.ResolvedRecipient> resolveRecipients(UserContext publisher,
            String audience, Set<Long> ids) {
        Set<Long> safe = ids == null ? Set.of() : ids;
        if ("NAMED_USERS".equals(audience)) {
            return identities.resolveRecipients(publisher.issuer(), safe);
        }
        if (!safe.isEmpty()) {
            throw new InvalidShareRequestException("recipients are not allowed for this audience");
        }
        return java.util.List.of();
    }

    private ShareResponse toResponse(DocumentShare share, String issuer) {
        Set<String> actionNames = share.getAllowedActions().stream().map(ShareAction::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        var labels = identities.labelsBySubjects(issuer, share.getRecipients());
        java.util.List<ShareRecipientResponse> recipients = share.getRecipients().stream().sorted().map(subject -> {
            DirectoryUserResponse label = labels.get(subject);
            return label == null ? new ShareRecipientResponse(null, "기존 수신자", null)
                    : new ShareRecipientResponse(label.id(), label.loginId(), label.displayName());
        }).toList();
        return new ShareResponse(share.getId(), share.getSourceId(), share.getDocumentId(),
                share.getAudience().name(), share.getClassification().name(), actionNames, recipients, share.isAdminBlocked(),
                share.getAdminBlockReason(), share.getGeneration(), share.isActive(), share.getCreatedAt(),
                share.getUpdatedAt(), share.getRevokedAt());
    }
}
