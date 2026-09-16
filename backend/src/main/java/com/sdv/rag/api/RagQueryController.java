package com.sdv.rag.api;

import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.model.UserContext;
import com.sdv.common.security.CurrentUserProvider;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.rag.api.dto.RagFileSearchQuery;
import com.sdv.rag.api.dto.RagFileSearchResponse;
import com.sdv.rag.api.dto.RagFileSortKey;
import com.sdv.rag.api.dto.RagAskRequest;
import com.sdv.rag.api.dto.RagAnswerResponse;
import com.sdv.rag.application.FileMetadataDiscoveryService;
import com.sdv.rag.application.RagAnswerService;
import com.sdv.rag.application.RagDiscoveryProperties;
import com.sdv.ai.application.AssistantProperties;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * F-BE-095 (v3.2 Manifest §20 "RAG API" - Manifest는 "search / ask"만 책임으로
 * 적어 두었고, M10이 File Metadata Discovery(RAG-011)를 이 Controller의 첫
 * 실제 Endpoint로 추가한다). Content/Vector 검색("ask")은 이 작업 범위가
 * 아니다 - M12(AI Service/RAG Ingestion) 이후에 별도로 추가한다.
 *
 * <p>{@code GET /api/rag/files} - Metadata/ACL Catalog + Live Drive 재확인
 * 기반 File Discovery(§2A.4). Chunk/Embedding/색인 여부와 무관하게 동작하고,
 * Content Fetch/Parser/LLM을 절대 호출하지 않는다 - Content/RAG 검색과는 다른
 * 기능이다(AGENTS.md "File Search vs Content Search"). 입력 검증(길이/형식/
 * 범위/정렬 Allowlist)은 여기 Controller 책임이다 - Application Service는
 * 이미 검증된 {@link RagFileSearchQuery}만 받는다.</p>
 */
@RestController
@RequestMapping("/api/rag")
public class RagQueryController {

    private static final int MAX_Q_LENGTH = 200;
    private static final int MAX_MIME_TYPE_LENGTH = 150;
    private static final String VALIDATION_ERROR_CODE = "VALIDATION_ERROR";

    private final FileMetadataDiscoveryService fileMetadataDiscoveryService;
    private final CurrentUserProvider currentUserProvider;
    private final RagDiscoveryProperties properties;
    private final RagAnswerService ragAnswerService;
    private final AssistantProperties assistantProperties;

    public RagQueryController(FileMetadataDiscoveryService fileMetadataDiscoveryService,
            CurrentUserProvider currentUserProvider, RagDiscoveryProperties properties,
            RagAnswerService ragAnswerService, AssistantProperties assistantProperties) {
        this.fileMetadataDiscoveryService = fileMetadataDiscoveryService;
        this.currentUserProvider = currentUserProvider;
        this.properties = properties;
        this.ragAnswerService = ragAnswerService;
        this.assistantProperties = assistantProperties;
    }

    @PostMapping("/ask")
    public ResponseEntity<RagAnswerResponse> ask(@RequestBody RagAskRequest request) {
        if (request == null || invalidQuestion(request.question(), assistantProperties.maxQuestionChars())
                || request.selectedDocumentIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new InvalidDiscoveryQueryException();
        }
        RagAnswerResponse response = ragAnswerService.ask(currentUserProvider.getCurrentUser(), request.question(),
                request.selectedDocumentIds());
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(response);
    }

    private static boolean invalidQuestion(String question, int maxQuestionChars) {
        if (question == null || question.isBlank() || question.length() > maxQuestionChars) return true;
        for (int i = 0; i < question.length(); i++) {
            char value = question.charAt(i);
            if (Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t') return true;
        }
        return false;
    }

    @GetMapping("/files")
    public RagFileSearchResponse files(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String mimeType,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String modifiedFrom,
            @RequestParam(required = false) String modifiedTo,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        UserContext user = currentUserProvider.getCurrentUser();
        RagFileSearchQuery query = parseQuery(q, mimeType, sourceId, modifiedFrom, modifiedTo, sort, page, size);
        return fileMetadataDiscoveryService.search(user, query);
    }

    private RagFileSearchQuery parseQuery(String q, String mimeType, String sourceId, String modifiedFrom,
            String modifiedTo, String sort, String page, String size) {
        String parsedQ = blankToNull(q);
        if (parsedQ != null && parsedQ.length() > MAX_Q_LENGTH) {
            throw new InvalidDiscoveryQueryException();
        }
        String parsedMimeType = blankToNull(mimeType);
        if (parsedMimeType != null && parsedMimeType.length() > MAX_MIME_TYPE_LENGTH) {
            throw new InvalidDiscoveryQueryException();
        }
        Long parsedSourceId = parsePositiveLong(sourceId);
        Instant parsedModifiedFrom = parseInstant(modifiedFrom);
        Instant parsedModifiedTo = parseInstant(modifiedTo);
        RagFileSortKey parsedSort = parseSort(sort);
        int parsedPage = parseBoundedInt(page, 0, 0, properties.maxPageIndex());
        int parsedSize = parseBoundedInt(size, properties.defaultPageSize(), 1, properties.maxPageSize());
        return new RagFileSearchQuery(parsedQ, parsedMimeType, parsedSourceId, parsedModifiedFrom, parsedModifiedTo,
                parsedSort, parsedPage, parsedSize);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static Long parsePositiveLong(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(trimmed);
            if (parsed <= 0) {
                throw new InvalidDiscoveryQueryException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new InvalidDiscoveryQueryException();
        }
    }

    private static Instant parseInstant(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new InvalidDiscoveryQueryException();
        }
    }

    private static RagFileSortKey parseSort(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return RagFileSortKey.MODIFIED_AT_DESC;
        }
        try {
            return RagFileSortKey.valueOf(trimmed.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidDiscoveryQueryException();
        }
    }

    private static int parseBoundedInt(String value, int defaultValue, int min, int max) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new InvalidDiscoveryQueryException();
        }
        if (parsed < min || parsed > max) {
            throw new InvalidDiscoveryQueryException();
        }
        return parsed;
    }

    /** 이 Controller에 국한된 입력 검증 실패 - 전역 예외 처리 범위를 넓히지 않는다({@code SourceSyncController}와 동일 패턴). */
    static final class InvalidDiscoveryQueryException extends RuntimeException {
    }

    @ExceptionHandler(InvalidDiscoveryQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidQuery(InvalidDiscoveryQueryException ex) {
        ApiErrorResponse body = new ApiErrorResponse(VALIDATION_ERROR_CODE, "Request validation failed.",
                MDC.get(TraceIdFilter.MDC_KEY));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
