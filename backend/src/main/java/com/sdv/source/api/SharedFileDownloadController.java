package com.sdv.source.api;

import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.security.CurrentUserProvider;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.source.application.SharedFileDownloadException;
import com.sdv.source.application.SharedFileDownloadService;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** HTTP-only delivery boundary for the bounded, non-AI shared-download operation. */
@RestController
@RequestMapping("/api/shares")
public class SharedFileDownloadController {
    private final SharedFileDownloadService service;
    private final CurrentUserProvider currentUserProvider;

    public SharedFileDownloadController(SharedFileDownloadService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/{shareId}/download")
    public void download(@PathVariable Long shareId, HttpServletResponse response) throws IOException {
        SharedFileDownloadService.DownloadLease lease = service.download(currentUserProvider.getCurrentUser(), shareId);
        try (lease) {
            // This is deliberately synchronous.  There is no queued StreamingResponseBody task
            // whose execution could begin after authorization has changed.  No 200/header is
            // prepared until this final service check succeeds.
            service.prepareForRelease(currentUserProvider.getCurrentUser(), lease);
            String filename = safeFilename(lease.filename());
            MediaType mimeType = safeMimeType(lease.mimeType());
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(mimeType.toString());
            response.setContentLengthLong(lease.contentLength());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(filename, StandardCharsets.UTF_8).build().toString());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            service.writeReleased(lease, response.getOutputStream());
        } catch (IOException writeFailure) {
            // A client disconnect/write failure may happen after bytes have left the socket.  The
            // lease nevertheless releases its bounded buffer and permit exactly once in finally.
            throw writeFailure;
        };
    }

    @ExceptionHandler(SharedFileDownloadException.class)
    public ResponseEntity<ApiErrorResponse> handleDownloadFailure(SharedFileDownloadException ex) {
        HttpStatus status = switch (ex.reason()) {
            case NOT_AUTHORIZED, NOT_AVAILABLE -> HttpStatus.NOT_FOUND;
            case DOCUMENT_CHANGED -> HttpStatus.CONFLICT;
            case EXPORT_LIMIT_EXCEEDED, FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case REQUEST_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CAPACITY_EXHAUSTED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ApiErrorResponse body = new ApiErrorResponse("SHARED_DOWNLOAD_" + ex.reason().name(),
                "The shared file could not be downloaded.", MDC.get(TraceIdFilter.MDC_KEY));
        return ResponseEntity.status(status).body(body);
    }

    private static MediaType safeMimeType(String candidate) {
        try {
            MediaType parsed = MediaType.parseMediaType(candidate);
            return parsed.isConcrete() ? parsed : MediaType.APPLICATION_OCTET_STREAM;
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String safeFilename(String candidate) {
        if (candidate == null) {
            return "download";
        }
        StringBuilder safe = new StringBuilder();
        candidate.codePoints().filter(codePoint -> codePoint >= 0x20 && codePoint != 0x7f
                && codePoint != '/' && codePoint != '\\' && codePoint != ':' && codePoint != 0)
                .limit(180).forEach(safe::appendCodePoint);
        String value = safe.toString().replace("\r", "").replace("\n", "").trim();
        return value.isEmpty() ? "download" : value;
    }
}
