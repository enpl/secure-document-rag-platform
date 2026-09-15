package com.sdv.source.api;

import com.sdv.common.security.CurrentUserProvider;
import com.sdv.source.api.dto.AdminShareResponse;
import com.sdv.source.api.dto.AdminUpdateShareRequest;
import com.sdv.source.application.SourceSharingService;
import com.sdv.source.domain.DocumentShare;
import com.sdv.source.domain.ShareAction;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M10B 신규(SHR-006, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.13) - ADMIN 전용
 * 공유 자료 관리 API: {@code GET/PATCH /api/admin/shares}. {@code /api/admin/**}는
 * 이미 {@link com.sdv.common.config.SecurityConfig}가 ADMIN Role을 요구한다.
 *
 * <p>이 Controller는 게시된 공유의 정책/차단만 관리한다 - 비공개 Drive
 * 탐색이나 Token 접근, 수신자/등급/행위 변경 권한을 ADMIN에게 주지 않는다
 * ("Administrative changes cannot expand publisher consent") - {@link
 * SourceSharingService#adminSetBlocked}는 실제로 {@code adminBlocked}/{@code
 * adminBlockReason} 두 필드만 바꾼다.</p>
 */
@RestController
@RequestMapping("/api/admin/shares")
public class SharedFileAdminController {

    private final SourceSharingService sourceSharingService;
    private final CurrentUserProvider currentUserProvider;

    public SharedFileAdminController(SourceSharingService sourceSharingService,
            CurrentUserProvider currentUserProvider) {
        this.sourceSharingService = sourceSharingService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public List<AdminShareResponse> list() {
        return sourceSharingService.adminList().stream().map(SharedFileAdminController::toResponse).toList();
    }

    @PatchMapping("/{shareId}")
    public AdminShareResponse setBlocked(@PathVariable Long shareId, @Valid @RequestBody AdminUpdateShareRequest request) {
        String adminSubject = currentUserProvider.getCurrentUser().subject();
        DocumentShare share = sourceSharingService.adminSetBlocked(adminSubject, shareId, request.blocked(),
                request.reason());
        return toResponse(share);
    }

    private static AdminShareResponse toResponse(DocumentShare share) {
        Set<String> actionNames = share.getAllowedActions().stream().map(ShareAction::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return new AdminShareResponse(share.getId(), share.getPublisherSubject(), share.getSourceId(),
                share.getDocumentId(), share.getClassification().name(), actionNames, share.getRecipients(),
                share.isAdminBlocked(), share.getAdminBlockReason(), share.getGeneration(), share.getCreatedAt(),
                share.getUpdatedAt());
    }
}
