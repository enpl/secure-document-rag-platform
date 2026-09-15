package com.sdv.source.infrastructure.google;

import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceDownloadResult;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.Arrays;
import java.time.Duration;

/**
 * F-BE-046 (M08 신규, Review 교정 반영). Google Docs/Slides/허용된 파일 형식의
 * Content를 Fetch/Export한다 - 필요한 권한/정책 확인이 끝난 뒤에만 Content를
 * Fetch한다(CORE_SPEC §10). v1.4 Mandatory Live Retrieval(§2A.5)의 핵심 순서를
 * 그대로 구현한다:
 *
 * <ol>
 *   <li>Fetch 전 Metadata 재확인(접근권한/{@code canDownload}/{@code trashed}/
 *       {@code version} 존재/기대 Version 일치) - 하나라도 실패하면 Content를
 *       하나도 Fetch하지 않는다.</li>
 *   <li>포맷 정책 확인 - Core 지원 포맷이 아니면(PPTX/이미지/XLSX 등) Media/
 *       Export 요청 자체를 하지 않는다.</li>
 *   <li>Bounded Fetch(일반 Binary는 {@link GoogleDriveClient#downloadMedia},
 *       Google Docs는 {@link GoogleDriveClient#exportFile} 동기 상한).</li>
 *   <li>Fetch 직후 같은 사용자 기준으로 Metadata를 다시 확인한다.</li>
 *   <li>그 사이에 Version이 바뀌었으면 전부 버리고 전체 순서를 한 번만
 *       재시도한다 - 두 번째도 바뀌면 {@link SourceContentOutcome#DOCUMENT_CHANGED}.</li>
 * </ol>
 *
 * <h2>M08 Review 교정(항목 2) - Version은 단조 증가한다, 재시도는 "다시
 * 확인"이지 "되돌리기"가 아니다</h2>
 * <p>Google의 {@code version}은 단조 증가하는 값이다({@link GoogleDriveClient}
 * Class Javadoc의 공식 문서 인용 참고) - 한 번 더 큰 값이 관측됐다면 그
 * 이후 다시 더 작은/이전 값으로 "되돌아갈" 수 없다. 그래서:</p>
 * <ul>
 *   <li>{@link SourceContentOutcome#DOCUMENT_CHANGED}는 오직 Version 불안정
 *       (기대 Version과의 불일치)에만 쓴다. Fetch 후 재확인에서 Trashed/
 *       Not-downloadable/Access-unknown처럼 Version과 무관한 실제 접근권한
 *       변화가 드러나면, 그 사유를 있는 그대로 반환한다 - "Version이
 *       바뀌었다"는 부정확한 사유로 덮어쓰지 않는다.</li>
 *   <li>재시도(1회)는 여전히 같은 {@code expectedSourceVersion}(호출자가
 *       원래 요청한 Version)을 기준으로 다시 확인한다 - 단조 증가 특성상,
 *       한 번 Version이 실제로 바뀌었다면 재시도의 Pre-check 역시 다시
 *       그 기대 Version과 어긋난 것으로 나타난다(추가 Download를 시도하지
 *       않는다). 이는 결함이 아니라 단조 증가 Model에서 나오는 필연적
 *       결과다 - 재시도가 두 번째 Download 없이 곧바로 {@code
 *       DOCUMENT_CHANGED}로 끝나는 것이 정상 동작이다("Never accept a newer
 *       version under the original expected-version request" - 이 작업
 *       지시사항). 재시도가 실질적인 가치를 갖는 경우는, Pre-check까지는
 *       통과했지만(즉 그 순간까지는 실제로 Version이 바뀌지 않았지만) 그
 *       재시도 자체의 Fetch 도중에 또 한 번(두 번째) 바뀌는, 훨씬 드문
 *       이중 경합(Double-race) 상황이다 - 그 경우에도 세 번째 시도 없이
 *       바로 {@link SourceContentOutcome#DOCUMENT_CHANGED}로 끝난다.</li>
 * </ul>
 */
@Component
public class GoogleDriveContentAdapter {

    /** 일반 Binary Content 상한 - M06 {@code ContentExtractionService}가 이미 쓰던 25MB 관행과 맞춘다. */
    static final long MAX_BINARY_BYTES = 25L * 1024 * 1024;

    /**
     * M08 Review 교정(항목 6) - Google Workspace 동기 Export 상한. 공식 문서는
     * "Exported content is limited to 10 MB."라고만 적혀 있고, 10,485,760(10
     * MiB)인지 10,000,000(10진 10 MB)인지 정의하지 않는다({@link
     * GoogleDriveClient} Class Javadoc의 확인 기록 참고). 근거 없이 더 넉넉한
     * 값(10 MiB)을 허용하지 않고, 보수적으로 10진 값을 쓴다 - 이 모호함
     * 자체는 UNVERIFIED로 남는다(추후 Google이 정확한 값을 명시하거나 실제
     * 한도에 도달하는 실측 증거가 나오면 재조정한다).
     */
    static final long EXPORT_SYNC_LIMIT_BYTES = 10_000_000L;

    private static final String GOOGLE_DOC_MIME = "application/vnd.google-apps.document";
    private static final String GOOGLE_DOC_EXPORT_MIME = "application/pdf";

    /** Core 지원 포맷(PDF/DOCX/TXT/MD) - 그 밖(PPTX/XLSX/이미지/오디오/비디오/Archive/코드/폴더/바로가기 등)은 Metadata-only다. */
    private static final Set<String> CORE_BINARY_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown");

    private final GoogleDriveClient client;

    public GoogleDriveContentAdapter(GoogleDriveClient client) {
        this.client = client;
    }

    public SourceContentResult fetchVerified(String accessToken, String fileId, String expectedSourceVersion) {
        return fetchVerified(accessToken, fileId, expectedSourceVersion, true);
    }

    /**
     * Separate transport for SHR-004.  It deliberately accepts provider-downloadable binary
     * files without treating them as AI/parser-eligible content.
     */
    public SourceDownloadResult fetchVerifiedDownload(String accessToken, String fileId, String expectedSourceVersion,
            long maxBytes, long deadlineMs) {
        return fetchVerifiedDownload(accessToken, fileId, expectedSourceVersion, maxBytes,
                GoogleDriveClient.Deadline.startingNow(Duration.ofMillis(deadlineMs)), true);
    }

    private SourceDownloadResult fetchVerifiedDownload(String accessToken, String fileId, String expectedSourceVersion,
            long maxBytes, GoogleDriveClient.Deadline deadline, boolean allowRetryOnChange) {
        GoogleDriveClient.GoogleFile preFetch;
        try {
            preFetch = client.getFile(accessToken, fileId, deadline);
        } catch (GoogleApiException e) {
            return SourceDownloadResult.failed(outcomeForMetadataFailure(e), safeReason(e));
        }
        if (!fileId.equals(preFetch.id())) {
            return SourceDownloadResult.failed(SourceContentOutcome.FAILED, "provider file identity mismatch");
        }
        Optional<SourceContentOutcome> preCheck = verify(preFetch, expectedSourceVersion);
        if (preCheck.isPresent()) {
            return SourceDownloadResult.failed(preCheck.get(), "pre-fetch verification failed");
        }

        DownloadFormatDecision format = decideDownloadFormat(preFetch.mimeType());
        if (format.unsupported()) {
            return SourceDownloadResult.failed(SourceContentOutcome.UNSUPPORTED_FORMAT,
                    "workspace export is not supported for this file type");
        }

        byte[] content;
        try {
            content = format.isWorkspaceExport()
                    ? client.exportFile(accessToken, fileId, format.outputMimeType(), maxBytes, deadline)
                    : client.downloadMedia(accessToken, fileId, maxBytes, deadline);
        } catch (GoogleContentSizeLimitExceededException e) {
            return SourceDownloadResult.failed(format.isWorkspaceExport()
                    ? SourceContentOutcome.EXPORT_LIMIT_EXCEEDED : SourceContentOutcome.FAILED,
                    "content exceeded the configured size limit");
        } catch (GoogleApiException e) {
            return SourceDownloadResult.failed(outcomeForMetadataFailure(e), safeReason(e));
        }

        GoogleDriveClient.GoogleFile postFetch;
        try {
            postFetch = client.getFile(accessToken, fileId, deadline);
        } catch (GoogleApiException e) {
            discard(content);
            return SourceDownloadResult.failed(outcomeForMetadataFailure(e), safeReason(e));
        }
        if (!fileId.equals(postFetch.id())) {
            discard(content);
            return SourceDownloadResult.failed(SourceContentOutcome.FAILED, "provider file identity mismatch");
        }
        if (postFetch.mimeType() == null || postFetch.mimeType().isBlank() || postFetch.name() == null
                || postFetch.name().isBlank()) {
            discard(content);
            return SourceDownloadResult.failed(SourceContentOutcome.FAILED, "provider metadata response was incomplete");
        }
        Optional<SourceContentOutcome> postCheck = verify(postFetch, expectedSourceVersion);
        if (postCheck.isPresent()) {
            discard(content);
            if (postCheck.get() == SourceContentOutcome.VERSION_MISMATCH && allowRetryOnChange) {
                return fetchVerifiedDownload(accessToken, fileId, expectedSourceVersion, maxBytes, deadline, false);
            }
            return SourceDownloadResult.failed(postCheck.get() == SourceContentOutcome.VERSION_MISMATCH
                    ? SourceContentOutcome.DOCUMENT_CHANGED : postCheck.get(), "post-fetch verification failed");
        }

        String outputName = format.isWorkspaceExport() ? pdfFilename(postFetch.name()) : postFetch.name();
        String outputMime = format.isWorkspaceExport() ? format.outputMimeType() : postFetch.mimeType();
        return SourceDownloadResult.verified(content, outputName, outputMime, postFetch.version(),
                format.isWorkspaceExport());
    }

    private static void discard(byte[] content) {
        if (content != null) {
            Arrays.fill(content, (byte) 0);
        }
    }

    private static String pdfFilename(String original) {
        int dot = original.lastIndexOf('.');
        String stem = dot > 0 ? original.substring(0, dot) : original;
        return stem + ".pdf";
    }

    private SourceContentResult fetchVerified(String accessToken, String fileId, String expectedSourceVersion,
            boolean allowRetryOnChange) {
        GoogleDriveClient.GoogleFile preFetch;
        try {
            preFetch = client.getFile(accessToken, fileId);
        } catch (GoogleApiException e) {
            return SourceContentResult.failed(outcomeForMetadataFailure(e), safeReason(e));
        }

        Optional<SourceContentOutcome> preCheck = verify(preFetch, expectedSourceVersion);
        if (preCheck.isPresent()) {
            if (!allowRetryOnChange && preCheck.get() == SourceContentOutcome.VERSION_MISMATCH) {
                // 재시도의 Pre-check조차 여전히 기대 Version과 어긋난다 - 단조 증가 Model에서는 되돌아갈 수 없으므로,
                // 추가 Download를 시도하지 않고 곧바로 Version 불안정으로 끝맺는다(Class Javadoc 참고).
                return SourceContentResult.failed(SourceContentOutcome.DOCUMENT_CHANGED,
                        "version was still different from the expected version on the retry attempt");
            }
            // Fetch 전 실패 - Content를 하나도 Fetch하지 않는다.
            return SourceContentResult.failed(preCheck.get(), "pre-fetch verification failed");
        }

        FormatDecision decision = decideFormat(preFetch.mimeType());
        if (decision.unsupported()) {
            return SourceContentResult.failed(SourceContentOutcome.UNSUPPORTED_FORMAT,
                    "format is metadata-only or disabled by default in Core");
        }

        byte[] content;
        String resultMimeType;
        try {
            if (decision.isWorkspaceExport()) {
                content = client.exportFile(accessToken, fileId, decision.exportMimeType(), EXPORT_SYNC_LIMIT_BYTES);
                resultMimeType = decision.exportMimeType();
            } else {
                content = client.downloadMedia(accessToken, fileId, MAX_BINARY_BYTES);
                resultMimeType = preFetch.mimeType();
            }
        } catch (GoogleContentSizeLimitExceededException e) {
            return SourceContentResult.failed(
                    decision.isWorkspaceExport() ? SourceContentOutcome.EXPORT_LIMIT_EXCEEDED
                            : SourceContentOutcome.FAILED,
                    "content exceeded the configured size limit");
        } catch (GoogleApiException e) {
            return SourceContentResult.failed(outcomeForMetadataFailure(e), safeReason(e));
        }

        GoogleDriveClient.GoogleFile postFetch;
        try {
            postFetch = client.getFile(accessToken, fileId);
        } catch (GoogleApiException e) {
            content = null; // 재확인에 실패했으니 검증되지 않은 Byte를 절대 반환하지 않는다.
            return SourceContentResult.failed(outcomeForMetadataFailure(e), safeReason(e));
        }

        Optional<SourceContentOutcome> postCheck = verify(postFetch, expectedSourceVersion);
        if (postCheck.isPresent()) {
            content = null; // 검증 실패 - Byte 참조를 즉시 버린다(v1.4 §2A.5, 검증 전 노출 금지).
            SourceContentOutcome outcome = postCheck.get();
            if (outcome == SourceContentOutcome.VERSION_MISMATCH) {
                if (allowRetryOnChange) {
                    return fetchVerified(accessToken, fileId, expectedSourceVersion, false);
                }
                return SourceContentResult.failed(SourceContentOutcome.DOCUMENT_CHANGED,
                        "version changed a second time after one retry");
            }
            // Trashed/Not-downloadable/Access-unknown 등은 Version 불안정이 아니다 - 실제 사유를 그대로 반환하고,
            // Version 문제 전용인 재시도를 적용하지 않는다.
            return SourceContentResult.failed(outcome, "post-fetch verification failed");
        }

        return SourceContentResult.verified(content, resultMimeType, postFetch.version(),
                decision.isWorkspaceExport());
    }

    private static Optional<SourceContentOutcome> verify(GoogleDriveClient.GoogleFile file,
            String expectedSourceVersion) {
        if (Boolean.TRUE.equals(file.trashed())) {
            return Optional.of(SourceContentOutcome.TRASHED);
        }
        GoogleDriveClient.GoogleCapabilities capabilities = file.capabilities();
        if (capabilities == null || !Boolean.TRUE.equals(capabilities.canDownload())) {
            return Optional.of(SourceContentOutcome.NOT_DOWNLOADABLE);
        }
        String version = file.version();
        if (version == null || version.isBlank()) {
            // Version을 신뢰 가능하게 알 수 없다 - "바뀌지 않았다"고 가정하지 않는다.
            return Optional.of(SourceContentOutcome.ACCESS_UNKNOWN);
        }
        if (expectedSourceVersion == null || !version.equals(expectedSourceVersion)) {
            return Optional.of(SourceContentOutcome.VERSION_MISMATCH);
        }
        return Optional.empty();
    }

    private static SourceContentOutcome outcomeForMetadataFailure(GoogleApiException e) {
        return switch (e.getCategory()) {
            case UNAUTHORIZED -> SourceContentOutcome.MISSING_CREDENTIAL;
            case PERMISSION_DENIED -> SourceContentOutcome.ACCESS_DENIED;
            case NOT_FOUND -> SourceContentOutcome.NOT_FOUND;
            // Quota/5xx는 재시도를 이미 GoogleDriveClient가 소진했다 - 신뢰 가능한 답을 얻지 못했다(Fail Closed).
            case QUOTA_OR_RATE_LIMIT, RETRYABLE_SERVER_ERROR -> SourceContentOutcome.ACCESS_UNKNOWN;
            // UNKNOWN에는 malformed 응답/전체 작업 Deadline 초과/Network 오류가 모두 포함된다 - 전부 안전하게 실패로 처리한다.
            case BAD_REQUEST, UNKNOWN -> SourceContentOutcome.FAILED;
        };
    }

    /** Google이 실제로 반환한 원본 오류 본문을 절대 그대로 옮기지 않는다 - 항상 고정된 안전한 문자열만 쓴다. */
    private static String safeReason(GoogleApiException e) {
        return "google drive api call failed: " + e.getCategory();
    }

    private FormatDecision decideFormat(String mimeType) {
        if (mimeType == null) {
            return FormatDecision.UNSUPPORTED;
        }
        if (CORE_BINARY_MIME_TYPES.contains(mimeType)) {
            return FormatDecision.binary();
        }
        if (GOOGLE_DOC_MIME.equals(mimeType)) {
            return FormatDecision.workspaceExport(GOOGLE_DOC_EXPORT_MIME);
        }
        // XLSX(기본 비활성화), Google Sheets/Slides, 이미지, 오디오, 비디오, Archive, 소스코드, 폴더, 바로가기,
        // 그 밖의 알 수 없는 형식 - Core에서는 전부 Metadata-only다(이 작업 지시사항).
        return FormatDecision.UNSUPPORTED;
    }

    private static DownloadFormatDecision decideDownloadFormat(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return DownloadFormatDecision.UNSUPPORTED;
        }
        if (GOOGLE_DOC_MIME.equals(mimeType)) {
            return DownloadFormatDecision.workspaceExport(GOOGLE_DOC_EXPORT_MIME);
        }
        if (mimeType.startsWith("application/vnd.google-apps.")) {
            return DownloadFormatDecision.UNSUPPORTED;
        }
        return DownloadFormatDecision.binary();
    }

    /** {@code mimeType} 하나를 "일반 Binary/Workspace Export/미지원" 셋 중 하나로 분류한 결과. */
    private record FormatDecision(Kind kind, String exportMimeType) {
        private enum Kind { BINARY, WORKSPACE_EXPORT, UNSUPPORTED }

        static final FormatDecision UNSUPPORTED = new FormatDecision(Kind.UNSUPPORTED, null);

        static FormatDecision binary() {
            return new FormatDecision(Kind.BINARY, null);
        }

        static FormatDecision workspaceExport(String exportMimeType) {
            return new FormatDecision(Kind.WORKSPACE_EXPORT, exportMimeType);
        }

        boolean unsupported() {
            return kind == Kind.UNSUPPORTED;
        }

        boolean isWorkspaceExport() {
            return kind == Kind.WORKSPACE_EXPORT;
        }
    }

    private record DownloadFormatDecision(Kind kind, String outputMimeType) {
        private enum Kind { BINARY, WORKSPACE_EXPORT, UNSUPPORTED }

        private static final DownloadFormatDecision UNSUPPORTED = new DownloadFormatDecision(Kind.UNSUPPORTED, null);

        static DownloadFormatDecision binary() {
            return new DownloadFormatDecision(Kind.BINARY, null);
        }

        static DownloadFormatDecision workspaceExport(String outputMimeType) {
            return new DownloadFormatDecision(Kind.WORKSPACE_EXPORT, outputMimeType);
        }

        boolean unsupported() {
            return kind == Kind.UNSUPPORTED;
        }

        boolean isWorkspaceExport() {
            return kind == Kind.WORKSPACE_EXPORT;
        }
    }
}
