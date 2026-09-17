package com.sdv.source.api;

import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.security.CurrentUserProvider;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.source.api.dto.CreateSourceRequest;
import com.sdv.source.api.dto.SourceFileResponse;
import com.sdv.source.api.dto.SourceFilesPageResponse;
import com.sdv.source.api.dto.SourceResponse;
import com.sdv.source.api.mapper.SourceApiMapper;
import com.sdv.source.application.SourceConnectionService;
import com.sdv.source.application.port.SourceCredentialException;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.sync.api.dto.SyncRunResponse;
import com.sdv.sync.application.SourceSyncService;
import com.sdv.sync.application.SyncAlreadyRunningException;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * M10B 신규(`docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.1/2A.13, "v1.5 planned
 * USER/share endpoints") - 일반 OIDC USER 본인 소유 개인 연결 API:
 * {@code GET/POST /api/sources}, {@code DELETE /api/sources/{id}}, {@code POST
 * /api/sources/{id}/sync}, {@code GET /api/sources/{id}/files}. 연결/동기화는
 * 어떤 파일도 게시(공유)하지 않는다 - 그 결정은 {@link
 * com.sdv.source.application.SourceSharingService}만 내린다.
 *
 * <p>{@code /api/admin/sources}(기존 {@link SourceAdminController})와 응용
 * 로직을 그대로 재사용한다 - 소유자 범위(Owner-Scoped)는 이미 {@link
 * SourceConnectionService}/{@link SourceSyncService}가 강제하므로, 이 Controller가
 * ADMIN 전용 경로 밖에서 같은 Use Case를 노출해도 다른 사용자의 Source에
 * 접근할 수 없다({@code SecurityConfig}의 {@code /api/**} 규칙이 이미
 * 인증만 요구한다 - ADMIN Role은 필요 없다).</p>
 */
@RestController
@RequestMapping("/api/sources")
public class SourceUserController {

    private static final String SYNC_CONFLICT_CODE = "SYNC_ALREADY_RUNNING";
    private static final String CREDENTIAL_UNAVAILABLE_CODE = "CREDENTIAL_UNAVAILABLE";
    private static final String SYNC_FAILED_CODE = "SYNC_FAILED";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_FILENAME_QUERY_LENGTH = 200;

    private final SourceConnectionService sourceConnectionService;
    private final SourceSyncService sourceSyncService;
    private final SourceApiMapper sourceApiMapper;
    private final CurrentUserProvider currentUserProvider;

    public SourceUserController(SourceConnectionService sourceConnectionService, SourceSyncService sourceSyncService,
            SourceApiMapper sourceApiMapper, CurrentUserProvider currentUserProvider) {
        this.sourceConnectionService = sourceConnectionService;
        this.sourceSyncService = sourceSyncService;
        this.sourceApiMapper = sourceApiMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public List<SourceResponse> list() {
        String ownerSubject = currentUserProvider.getCurrentUser().subject();
        return sourceConnectionService.list(ownerSubject).stream()
                .map(item -> sourceApiMapper.toResponse(item.connection(), item.credentialPresent()))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceResponse create(@Valid @RequestBody CreateSourceRequest request) {
        String ownerSubject = currentUserProvider.getCurrentUser().subject();
        var created = sourceConnectionService.create(sourceApiMapper.toDomain(request, ownerSubject));
        return sourceApiMapper.toResponse(created);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable Long id) {
        String ownerSubject = currentUserProvider.getCurrentUser().subject();
        sourceConnectionService.disconnect(id, ownerSubject);
    }

    @PostMapping("/{id}/sync")
    public SyncRunResponse sync(@PathVariable Long id) {
        String ownerSubject = currentUserProvider.getCurrentUser().subject();
        return SyncRunResponse.from(sourceSyncService.sync(id, ownerSubject));
    }

    /**
     * 소유자 전용 비공개 Metadata 선택기 - {@link SourceConnectionService#listFiles}가
     * 소유권을 재확인한다. Content Fetch/색인 트리거 없음, 공유(공개) 여부에 아무
     * 영향도 주지 않는다.
     */
    @GetMapping("/{id}/files")
    public SourceFilesPageResponse files(@PathVariable Long id,
            @RequestParam(required = false) String page, @RequestParam(required = false) String size,
            @RequestParam(required = false) String q) {
        String ownerSubject = currentUserProvider.getCurrentUser().subject();
        int parsedPage = parseBoundedInt(page, 0, 0, Integer.MAX_VALUE);
        int parsedSize = parseBoundedInt(size, DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        var result = sourceConnectionService.listFiles(id, ownerSubject, parsedPage, parsedSize,
                normalizeFilenameQuery(q));
        List<SourceFileResponse> items = result.items().stream().map(SourceUserController::toFileResponse).toList();
        return new SourceFilesPageResponse(items, result.hasMore());
    }

    private static String normalizeFilenameQuery(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > MAX_FILENAME_QUERY_LENGTH || value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidPickerQueryException();
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static SourceFileResponse toFileResponse(SourceDocumentEntity entity) {
        return new SourceFileResponse(entity.getId(), entity.getName(), entity.getMimeType(),
                entity.getModifiedAt(), entity.getIndexStatus());
    }

    private static int parseBoundedInt(String value, int defaultValue, int min, int max) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidPickerQueryException();
        }
        if (parsed < min || parsed > max) {
            throw new InvalidPickerQueryException();
        }
        return parsed;
    }

    /** 이 Controller에 국한된 입력 검증 실패 - {@code RagQueryController}와 동일 패턴, 전역 예외 처리 범위를 넓히지 않는다. */
    static final class InvalidPickerQueryException extends RuntimeException {
    }

    @ExceptionHandler(InvalidPickerQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidPickerQuery(InvalidPickerQueryException ex) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.");
    }

    /** 이미 이 Source에 대해 RUNNING인 Sync가 있다. */
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

    private static ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String code, String message) {
        ApiErrorResponse body = new ApiErrorResponse(code, message, MDC.get(TraceIdFilter.MDC_KEY));
        return ResponseEntity.status(status).body(body);
    }
}
