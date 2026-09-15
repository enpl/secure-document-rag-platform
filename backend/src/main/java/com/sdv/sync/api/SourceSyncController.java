package com.sdv.sync.api;

import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.security.CurrentUserProvider;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.source.application.port.SourceCredentialException;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.sync.api.dto.SyncRunResponse;
import com.sdv.sync.application.SourceSyncService;
import com.sdv.sync.application.SyncAlreadyRunningException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F-BE-062 (M09A 신규). Source Sync 트리거 API(SYN-001) - {@code POST
 * /api/admin/sources/{id}/sync}(Manifest 지정 경로). {@code /api/admin/**}는
 * 이미 {@link com.sdv.common.config.SecurityConfig}가 ADMIN Role을 요구한다 -
 * 이 Controller는 그 위에 Source **소유자** 범위(Owner-Scoped)만 추가로 강제한다
 * ({@link SourceSyncService}가 {@code owner_subject}로 조회하므로, 다른 ADMIN이
 * 소유한 Source는 존재 여부까지 동일하게 404로 감춰진다 - {@code
 * SourceAdminController}와 동일한 패턴).
 *
 * <p>자동 실행이 아니다 - 이 HTTP 요청이 있어야만 Sync가 시작된다(Section 5A
 * "자동 Sync는 기본 비활성화"). 진행 중인 동시 Sync는 409로 정직하게 보고한다
 * (숨겨진 Fire-and-Forget이 아니다).</p>
 */
@RestController
@RequestMapping("/api/admin/sources")
public class SourceSyncController {

    private static final String SYNC_CONFLICT_CODE = "SYNC_ALREADY_RUNNING";
    private static final String CREDENTIAL_UNAVAILABLE_CODE = "CREDENTIAL_UNAVAILABLE";
    private static final String SYNC_FAILED_CODE = "SYNC_FAILED";

    private final SourceSyncService sourceSyncService;
    private final CurrentUserProvider currentUserProvider;

    public SourceSyncController(SourceSyncService sourceSyncService, CurrentUserProvider currentUserProvider) {
        this.sourceSyncService = sourceSyncService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/{id}/sync")
    public SyncRunResponse sync(@PathVariable Long id) {
        String ownerSubject = currentUserProvider.getCurrentUser().subject();
        return SyncRunResponse.from(sourceSyncService.sync(id, ownerSubject));
    }

    /** 이미 이 Source에 대해 RUNNING인 Sync가 있다 - V008 DB 제약이 실제로 막았다는 뜻이다. */
    @ExceptionHandler(SyncAlreadyRunningException.class)
    public ResponseEntity<ApiErrorResponse> handleAlreadyRunning(SyncAlreadyRunningException ex) {
        return respond(HttpStatus.CONFLICT, SYNC_CONFLICT_CODE, "A sync is already running for this source.");
    }

    /** Credential이 없거나/만료됐거나/Scope가 부족하다 - 재연결이 필요하다는 뜻이다. */
    @ExceptionHandler(SourceCredentialException.class)
    public ResponseEntity<ApiErrorResponse> handleCredentialUnavailable(SourceCredentialException ex) {
        return respond(HttpStatus.CONFLICT, CREDENTIAL_UNAVAILABLE_CODE,
                "No usable Google Drive credential is available for this source.");
    }

    /** 그 밖의 안전하게 분류된 Sync 실패(Google 호출 실패/Quota/알 수 없음 등). */
    @ExceptionHandler(SourceSyncException.class)
    public ResponseEntity<ApiErrorResponse> handleSyncFailed(SourceSyncException ex) {
        HttpStatus status = ex.getReason() == SourceSyncException.Reason.NOT_FOUND ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_GATEWAY;
        return respond(status, SYNC_FAILED_CODE, "The sync could not be completed.");
    }

    private ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String code, String message) {
        ApiErrorResponse body = new ApiErrorResponse(code, message, MDC.get(TraceIdFilter.MDC_KEY));
        return ResponseEntity.status(status).body(body);
    }
}
