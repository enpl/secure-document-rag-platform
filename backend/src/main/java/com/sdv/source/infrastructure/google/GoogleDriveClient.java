package com.sdv.source.infrastructure.google;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * F-BE-041 (M08 신규, Review 교정 반영). Google Drive API v3 저수준 HTTP
 * Wrapper - {@code files}, {@code permissions}, {@code changes}, {@code export}
 * 기술 호출만 담당한다. Google 응답 DTO(이 파일의 {@code record}들)는 이
 * Package 밖으로 나가지 않는다.
 *
 * <p>Google 공식 SDK({@code google-api-services-drive})를 추가하지 않는다 -
 * 이미 있는 Spring {@link RestClient}로 충분히 Bounded/Testable하게 구현할
 * 수 있어서, 새 Dependency를 정당화할 이유가 없다.</p>
 *
 * <h2>공식 문서 확인(2026-09-13)</h2>
 * <ul>
 *   <li>{@code https://developers.google.com/workspace/drive/api/reference/rest/v3/files} -
 *       {@code version}은 "A monotonically increasing version number for
 *       the file... reflects every change made to the file on the server"
 *       (int64를 문자열로 담음) - Source Version 비교의 1차 근거로 쓰고,
 *       {@code modifiedTime}은 보조 진단값으로만 유지한다.</li>
 *   <li>{@code https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list} -
 *       {@code corpora=allDrives}로 My Drive+공유 드라이브 전체를 한 Query로
 *       훑는다({@code driveId}는 {@code corpora=drive}일 때만 필요, {@code
 *       allDrives}에는 불필요). {@code incompleteSearch=true}는 "일부 결과가
 *       누락됐을 수 있다"는 뜻이다 - 이 Client는 이를 그대로 {@link
 *       GoogleFilesListPage#incompleteSearch()}로 전달하고, 절대 "완전한
 *       결과"로 자체 승격하지 않는다. {@code pageToken}이 거부되면 처음부터
 *       다시 시작해야 한다는 것이 공식 안내이나, 그 재시작 결정 자체는 M09
 *       (Cursor Orchestration) 책임이다.</li>
 *   <li>{@code https://developers.google.com/workspace/drive/api/guides/manage-downloads} -
 *       "Exported content is limited to 10 MB." 이 문서는 정확한 Byte 수를
 *       정의하지 않는다(10,485,760 vs 10,000,000 중 어느 쪽인지 명시 없음) -
 *       그래서 이 구현은 보수적으로 10진 10,000,000 Byte를 상한으로 쓴다
 *       ({@link GoogleDriveContentAdapter#EXPORT_SYNC_LIMIT_BYTES} 참고,
 *       근거 없이 10 MiB(10,485,760)로 넉넉하게 잡지 않는다). 더 큰 문서는
 *       별도의 Long-running {@code files.download} Operation Polling을 쓰라고
 *       안내한다 - 이 작업은 그 Polling 흐름을 구현하지 않는다(범위 밖 -
 *       Polling/취소/정리를 절반만 구현하는 것보다, 이 작업 지시사항이
 *       명시적으로 허용하는 대안인 {@code EXPORT_LIMIT_EXCEEDED}를
 *       반환하는 편이 더 안전하다).</li>
 *   <li>{@code https://developers.google.com/workspace/drive/api/reference/rest/v3/changes} -
 *       {@code removed}는 "deletion or loss of access"를 구분하지 않는다
 *       ({@link com.sdv.source.domain.SourceChangeType} Javadoc 참고).</li>
 *   <li>{@code https://developers.google.com/workspace/drive/api/guides/handle-errors} -
 *       429/5xx와 403의 Quota 계열({@code rateLimitExceeded} 등)만 재시도
 *       대상, 403 권한 계열({@code insufficientFilePermissions} 등)과
 *       401/404/400은 재시도하지 않는다 - {@link #withRetry}가 그대로
 *       반영한다. 이 문서는 "OAuth Scope 부족"을 위한 별도 {@code reason}
 *       값을 정의하지 않는다 - "This error can also be caused by missing
 *       authorization for the requested scopes"라고만 언급하고, 실제로는
 *       평범한 401 {@code authError}("Invalid Credentials")와 구분되지
 *       않는다. 그래서 Scope 부족은 Google 응답을 사후에 분류해서 알아낼 수
 *       없다 - {@link GoogleDriveConnector}가 저장된 {@link
 *       com.sdv.source.application.port.TokenEnvelope#scopes()}를 Google을
 *       호출하기 전에 직접 확인하는 사전 검사만이 신뢰할 수 있는 유일한
 *       방법이다(이 Client가 아니라 Connector의 책임).</li>
 * </ul>
 */
@Component
public class GoogleDriveClient {

    static final String FILE_FIELDS = "id,name,mimeType,version,modifiedTime,trashed,size,driveId,"
            + "shortcutDetails/targetId,exportLinks,capabilities/canDownload";

    private static final String FILE_LIST_FIELDS = "nextPageToken,incompleteSearch,files(" + FILE_FIELDS + ")";
    private static final String PERMISSION_FIELDS =
            "nextPageToken,permissions(id,type,role,emailAddress,domain,deleted,expirationTime)";
    private static final String CHANGE_FIELDS = "nextPageToken,newStartPageToken,changes(fileId,removed,changeType,"
            + "file(" + FILE_FIELDS + "))";

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(150);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);
    /** 하나의 files.list 호출당 요청 Page Size - 공식 상한(1000) 안에서 보수적인 값을 쓴다. */
    private static final int FILES_LIST_PAGE_SIZE = 200;
    /** 403 오류 본문에서 {@code reason}만 뽑아내기 위해 읽는 최대 Byte 수 - 이보다 큰 본문이 와도 이 이상 할당하지 않는다. */
    private static final int ERROR_BODY_MAX_BYTES = 8192;

    private static final List<String> QUOTA_REASONS = List.of("rateLimitExceeded", "userRateLimitExceeded",
            "dailyLimitExceeded", "storageQuotaExceeded");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Duration operationDeadline;
    private final URI apiBaseUri;

    @Autowired
    public GoogleDriveClient(@Qualifier("googleDriveRestClient") RestClient restClient, ObjectMapper objectMapper,
            @Value("${sdv.google-drive.operation-deadline-ms:30000}") long operationDeadlineMs,
            @Value("${sdv.google-drive.api-base-url:https://www.googleapis.com}") String apiBaseUrl) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.operationDeadline = Duration.ofMillis(operationDeadlineMs);
        this.apiBaseUri = URI.create(apiBaseUrl);
    }

    /** Retained for existing isolated RestClient contract tests. */
    public GoogleDriveClient(RestClient restClient, ObjectMapper objectMapper, long operationDeadlineMs) {
        this(restClient, objectMapper, operationDeadlineMs, "http://localhost");
    }

    /** {@code files.get} - {@code supportsAllDrives=true}로 공유 드라이브 문서도 조회한다. */
    public GoogleFile getFile(String accessToken, String fileId) {
        return getFile(accessToken, fileId, Deadline.startingNow(operationDeadline));
    }

    GoogleFile getFile(String accessToken, String fileId, Deadline deadline) {
        if (deadline.transportBound()) {
            return withRetry(deadline, () -> boundedFileGet(accessToken, fileId, deadline));
        }
        return withRetry(deadline, () -> {
            try {
                GoogleFile file = restClient.get()
                        .uri("/drive/v3/files/{fileId}?fields={fields}&supportsAllDrives=true", fileId, FILE_FIELDS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .retrieve()
                        .body(GoogleFile.class);
                return requireValidFile(file);
            } catch (RestClientResponseException e) {
                throw translate(e);
            } catch (RestClientException e) {
                throw malformedResponse(e);
            }
        });
    }

    /**
     * {@code files.list} - Whole-Drive Metadata Discovery 한 페이지(M08 Review
     * 교정 항목 1). {@code corpora=allDrives}+{@code includeItemsFromAllDrives=true}
     * +{@code supportsAllDrives=true}로 My Drive와 공유 드라이브 전체를 한
     * Query로 훑는다({@code spaces=drive} - App Data Folder 등은 제외).
     */
    public GoogleFilesListPage listFiles(String accessToken, String pageToken) {
        Deadline deadline = Deadline.startingNow(operationDeadline);
        return withRetry(deadline, () -> {
            try {
                String uri = "/drive/v3/files?fields={fields}&spaces=drive&corpora=allDrives"
                        + "&supportsAllDrives=true&includeItemsFromAllDrives=true&pageSize=" + FILES_LIST_PAGE_SIZE
                        + (pageToken == null ? "" : "&pageToken=" + encode(pageToken));
                GoogleFilesListPage page = restClient.get()
                        .uri(uri, FILE_LIST_FIELDS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .retrieve()
                        .body(GoogleFilesListPage.class);
                return requireValidFilesListPage(page);
            } catch (RestClientResponseException e) {
                throw translate(e);
            } catch (RestClientException e) {
                throw malformedResponse(e);
            }
        });
    }

    /** {@code permissions.list} - 한 페이지. 호출자가 {@code nextPageToken}으로 반복 호출해 전체를 모은다. */
    public GooglePermissionsPage listPermissions(String accessToken, String fileId, String pageToken) {
        Deadline deadline = Deadline.startingNow(operationDeadline);
        return withRetry(deadline, () -> {
            try {
                String uri = "/drive/v3/files/{fileId}/permissions?fields={fields}&supportsAllDrives=true"
                        + "&pageSize=100" + (pageToken == null ? "" : "&pageToken=" + encode(pageToken));
                GooglePermissionsPage page = restClient.get()
                        .uri(uri, fileId, PERMISSION_FIELDS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .retrieve()
                        .body(GooglePermissionsPage.class);
                return requireValidPermissionsPage(page);
            } catch (RestClientResponseException e) {
                throw translate(e);
            } catch (RestClientException e) {
                throw malformedResponse(e);
            }
        });
    }

    /**
     * M10B 신규(`docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.14 same-account reconnect) -
     * {@code about.get}으로 지금 이 Access Token이 속한 Google 계정의 안정적 식별자
     * ({@code user.permissionId})만 반환한다(이메일/표시 이름이 아니다) - 이미 보유한
     * {@code drive.readonly} Scope만으로 호출 가능하다(추가 Scope/동의 불필요). 응답
     * DTO({@link GoogleAbout}/{@link GoogleAboutUser})는 이 Package 밖으로 노출하지
     * 않는다(Class Javadoc 원칙) - 재연결 판단에 실제로 필요한 값 하나만 반환한다.
     */
    public String getAccountIdentity(String accessToken) {
        Deadline deadline = Deadline.startingNow(operationDeadline);
        return withRetry(deadline, () -> {
            try {
                GoogleAbout about = restClient.get()
                        .uri("/drive/v3/about?fields=user(permissionId,emailAddress)")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .retrieve()
                        .body(GoogleAbout.class);
                return requireValidAbout(about).user().permissionId();
            } catch (RestClientResponseException e) {
                throw translate(e);
            } catch (RestClientException e) {
                throw malformedResponse(e);
            }
        });
    }

    /** {@code changes.getStartPageToken} - 새 변경 추적을 시작할 때의 최초 Token. */
    public String getStartPageToken(String accessToken) {
        Deadline deadline = Deadline.startingNow(operationDeadline);
        return withRetry(deadline, () -> {
            try {
                GoogleStartPageTokenResponse response = restClient.get()
                        .uri("/drive/v3/changes/startPageToken?supportsAllDrives=true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .retrieve()
                        .body(GoogleStartPageTokenResponse.class);
                if (response == null || isBlank(response.startPageToken())) {
                    throw malformedResponse(null);
                }
                return response.startPageToken();
            } catch (RestClientResponseException e) {
                throw translate(e);
            } catch (RestClientException e) {
                throw malformedResponse(e);
            }
        });
    }

    /** {@code changes.list} - 한 페이지. */
    public GoogleChangesPage listChanges(String accessToken, String pageToken) {
        Deadline deadline = Deadline.startingNow(operationDeadline);
        return withRetry(deadline, () -> {
            try {
                String uri = "/drive/v3/changes?fields={fields}&supportsAllDrives=true"
                        + "&includeItemsFromAllDrives=true&pageToken=" + encode(pageToken);
                GoogleChangesPage page = restClient.get()
                        .uri(uri, CHANGE_FIELDS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .retrieve()
                        .body(GoogleChangesPage.class);
                return requireValidChangesPage(page);
            } catch (RestClientResponseException e) {
                throw translate(e);
            } catch (RestClientException e) {
                throw malformedResponse(e);
            }
        });
    }

    /**
     * {@code files.get?alt=media} - 일반 Binary Content를 Bounded Stream으로
     * 받는다. {@code maxBytes}를 넘는 순간(다 받은 뒤가 아니라) 즉시 중단한다.
     */
    public byte[] downloadMedia(String accessToken, String fileId, long maxBytes) {
        return downloadMedia(accessToken, fileId, maxBytes, Deadline.startingNow(operationDeadline));
    }

    byte[] downloadMedia(String accessToken, String fileId, long maxBytes, Deadline deadline) {
        return withRetry(deadline, () -> boundedGet("/drive/v3/files/" + fileId + "?alt=media&supportsAllDrives=true",
                accessToken, maxBytes, deadline));
    }

    /**
     * {@code files.export} - Google Workspace 문서를 지정한 MIME Type으로
     * 변환해 받는다(동기 상한, Class Javadoc 참고).
     */
    public byte[] exportFile(String accessToken, String fileId, String exportMimeType, long maxBytes) {
        return exportFile(accessToken, fileId, exportMimeType, maxBytes, Deadline.startingNow(operationDeadline));
    }

    byte[] exportFile(String accessToken, String fileId, String exportMimeType, long maxBytes, Deadline deadline) {
        return withRetry(deadline, () -> boundedGet("/drive/v3/files/" + fileId + "/export?mimeType=" + encode(exportMimeType),
                accessToken, maxBytes, deadline));
    }

    private byte[] boundedGet(String uri, String accessToken, long maxBytes, Deadline deadline) {
        if (deadline.isExpired()) {
            throw deadline.transportBound() ? timeout() : new GoogleApiException(GoogleApiException.Category.UNKNOWN,
                    "operation deadline exceeded before content transfer");
        }
        if (deadline.transportBound()) {
            return boundedGetTransport(uri, accessToken, maxBytes, deadline);
        }
        return restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .exchange((request, response) -> {
                    // SHR-004 is intentionally a full-file operation.  A 206 can only be a
                    // response to a range request, which this client never issues; accepting it
                    // would make a partial original look complete.
                    if (response.getStatusCode().value() != 200
                            || response.getHeaders().containsHeader(HttpHeaders.CONTENT_RANGE)) {
                        throw translateErrorResponse(response);
                    }
                    long declaredLength = response.getHeaders().getContentLength();
                    if (declaredLength > maxBytes) {
                        throw new GoogleContentSizeLimitExceededException(
                                "content exceeded the configured byte limit before streaming");
                    }
                    return readBounded(response.getBody(), maxBytes, declaredLength, deadline);
                });
    }

    private GoogleFile boundedFileGet(String accessToken, String fileId, Deadline deadline) {
        String uri = "/drive/v3/files/" + encodePathSegment(fileId) + "?fields=" + encode(FILE_FIELDS)
                + "&supportsAllDrives=true";
        byte[] body = boundedHttpGet(uri, accessToken, 1_000_000L, deadline);
        try {
            return requireValidFile(objectMapper.readValue(body, GoogleFile.class));
        } catch (RuntimeException e) {
            throw malformedResponse(e);
        }
    }

    private byte[] boundedGetTransport(String uri, String accessToken, long maxBytes, Deadline deadline) {
        return boundedHttpGet(uri, accessToken, maxBytes, deadline);
    }

    private byte[] boundedHttpGet(String pathAndQuery, String accessToken, long maxBytes, Deadline deadline) {
        HttpURLConnection connection = null;
        try {
            if (deadline.isExpired()) {
                throw timeout();
            }
            connection = (HttpURLConnection) apiBaseUri.resolve(pathAndQuery).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION, bearer(accessToken));
            int timeout = deadline.remainingTimeoutMillis();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            int status = connection.getResponseCode();
            if (status != 200) {
                throw translateStatus(status);
            }
            long declaredLength = connection.getContentLengthLong();
            if (declaredLength > maxBytes) {
                throw new GoogleContentSizeLimitExceededException(
                        "content exceeded the configured byte limit before streaming");
            }
            try (InputStream input = connection.getInputStream()) {
                return readBounded(input, maxBytes, declaredLength, deadline, connection);
            }
        } catch (java.net.SocketTimeoutException e) {
            throw timeout();
        } catch (IOException e) {
            if (deadline.isExpired()) {
                throw timeout();
            }
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN, "google transport failed", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static GoogleApiException timeout() {
        return new GoogleApiException(GoogleApiException.Category.TIMEOUT, "operation deadline exceeded");
    }

    private byte[] readBounded(InputStream in, long maxBytes, long declaredLength, Deadline deadline) {
        return readBounded(in, maxBytes, declaredLength, deadline, null);
    }

    private byte[] readBounded(InputStream in, long maxBytes, long declaredLength, Deadline deadline,
            HttpURLConnection boundedConnection) {
        int initialCapacity = declaredLength >= 0 && declaredLength <= Integer.MAX_VALUE
                ? (int) declaredLength : 8192;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(initialCapacity);
        byte[] chunk = new byte[8192];
        long total = 0;
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new GoogleApiException(GoogleApiException.Category.UNKNOWN,
                            "content transfer was cancelled");
                }
                if (deadline.isExpired()) {
                    throw deadline.transportBound() ? timeout() : new GoogleApiException(
                            GoogleApiException.Category.UNKNOWN, "content transfer exceeded the operation deadline");
                }
                if (boundedConnection != null) {
                    boundedConnection.setReadTimeout(deadline.remainingTimeoutMillis());
                }
                int read = in.read(chunk);
                if (read == -1) {
                    break;
                }
                total += read;
                if (total > maxBytes) {
                    throw new GoogleContentSizeLimitExceededException(
                            "content exceeded the configured byte limit while streaming");
                }
                buffer.write(chunk, 0, read);
            }
        } catch (java.net.SocketTimeoutException e) {
            throw deadline.transportBound() ? timeout()
                    : new GoogleApiException(GoogleApiException.Category.UNKNOWN, "content transfer failed", e);
        } catch (IOException e) {
            if (deadline.transportBound() && deadline.isExpired()) {
                throw timeout();
            }
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN, "content transfer failed", e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Best-effort close after the result has already been determined.
            }
        }
        if (declaredLength >= 0 && total != declaredLength) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN,
                    "content length did not match the response body");
        }
        return buffer.toByteArray();
    }

    /**
     * 재시도 가능한 실패({@link GoogleApiException#isRetryable()})만 Bounded
     * Truncated Exponential Backoff + Jitter로 재시도한다 - 권한 거부/401/
     * 404/400은 첫 실패에서 바로 던진다(재시도하지 않는다). Interrupt(취소)는
     * 즉시 재시도를 멈추고 던진다 - Backoff 대기 자체를 계속하지 않는다.
     * {@code deadline}이 이미 지났으면(재시도 예산이 남아있어도) 다음 시도를
     * 시작하지 않는다 - 재시도 횟수와 별개로 전체 작업에는 절대 상한이 있다.
     */
    private <T> T withRetry(Deadline deadline, Supplier<T> call) {
        int attempt = 0;
        while (true) {
            attempt++;
            if (deadline.isExpired()) {
                throw deadline.transportBound() ? timeout() : new GoogleApiException(
                        GoogleApiException.Category.UNKNOWN, "operation deadline exceeded before attempt " + attempt);
            }
            try {
                return call.get();
            } catch (GoogleApiException e) {
                if (!e.isRetryable() || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                sleepBeforeRetry(attempt, deadline);
            }
        }
    }

    private static void sleepBeforeRetry(int attempt, Deadline deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN, "retry cancelled before backoff");
        }
        long capMs = Math.min(MAX_BACKOFF.toMillis(), BASE_BACKOFF.toMillis() * (1L << (attempt - 1)));
        long jitteredMs = ThreadLocalRandom.current().nextLong(capMs / 2 + 1, capMs + 1);
        long boundedMs = Math.min(jitteredMs, deadline.remaining().toMillis());
        if (boundedMs <= 0) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN,
                    "operation deadline exceeded during backoff");
        }
        try {
            Thread.sleep(boundedMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN, "retry interrupted", e);
        }
    }

    private static GoogleApiException translateStatus(int status) {
        return switch (status) {
            case 401 -> new GoogleApiException(GoogleApiException.Category.UNAUTHORIZED, "unauthorized (401)");
            case 403 -> new GoogleApiException(GoogleApiException.Category.PERMISSION_DENIED, "forbidden (403)");
            case 404 -> new GoogleApiException(GoogleApiException.Category.NOT_FOUND, "not found (404)");
            case 429 -> new GoogleApiException(GoogleApiException.Category.QUOTA_OR_RATE_LIMIT, "rate limited (429)");
            default -> status >= 500
                    ? new GoogleApiException(GoogleApiException.Category.RETRYABLE_SERVER_ERROR,
                            "server error (" + status + ")")
                    : new GoogleApiException(GoogleApiException.Category.BAD_REQUEST,
                            "client error (" + status + ")");
        };
    }

    /** {@code translateStatus}와 다르게, 403 안에서 권한/Quota 계열을 응답 본문의 {@code reason}으로 더 세분화한다. */
    private GoogleApiException translate(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 403) {
            return new GoogleApiException(
                    isQuotaReason(e) ? GoogleApiException.Category.QUOTA_OR_RATE_LIMIT
                            : GoogleApiException.Category.PERMISSION_DENIED,
                    "forbidden (403)", e);
        }
        GoogleApiException byStatus = translateStatus(status);
        return new GoogleApiException(byStatus.getCategory(), byStatus.getMessage(), e);
    }

    private boolean isQuotaReason(RestClientResponseException e) {
        try {
            GoogleErrorResponse body = e.getResponseBodyAs(GoogleErrorResponse.class);
            return hasQuotaReason(body);
        } catch (RuntimeException parseFailure) {
            // 본문을 해석할 수 없으면 권한 거부로 안전하게(재시도하지 않는 쪽으로) 취급한다 - Fail Closed.
            return false;
        }
    }

    /**
     * M08 Review 교정(항목 4) - {@link #boundedGet}(Media Download/Export)의
     * 403도 {@link #translate}와 동일하게 {@code reason} 기반으로 Quota와
     * 권한 거부를 구분한다. 원안은 이 경로만 {@link #translateStatus}(reason
     * 미확인)를 써서, Media/Export의 Quota-계열 403이 재시도되지 않는 결함이
     * 있었다. 오류 본문은 최대 {@link #ERROR_BODY_MAX_BYTES}만 읽는다 - 그
     * 이상 부풀려진(또는 손상된) 본문이 와도 그 이상 메모리를 할당하지
     * 않는다(Fail Closed로 권한 거부 취급).
     */
    private GoogleApiException translateErrorResponse(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        int status;
        try {
            status = response.getStatusCode().value();
        } catch (IOException | RuntimeException e) {
            return new GoogleApiException(GoogleApiException.Category.UNKNOWN, "malformed error response from google");
        }
        if (status != 403) {
            return translateStatus(status);
        }
        boolean quota = hasQuotaReason(readErrorBodyBounded(response));
        return new GoogleApiException(
                quota ? GoogleApiException.Category.QUOTA_OR_RATE_LIMIT : GoogleApiException.Category.PERMISSION_DENIED,
                "forbidden (403)");
    }

    private GoogleErrorResponse readErrorBodyBounded(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            byte[] bounded = readAtMost(body, ERROR_BODY_MAX_BYTES);
            if (bounded.length == 0) {
                return null;
            }
            return objectMapper.readValue(bounded, GoogleErrorResponse.class);
        } catch (IOException | RuntimeException e) {
            // 본문을 읽거나 해석할 수 없으면 권한 거부로 안전하게(재시도하지 않는 쪽으로) 취급한다 - Fail Closed.
            return null;
        }
    }

    private static boolean hasQuotaReason(GoogleErrorResponse body) {
        if (body == null || body.error() == null || body.error().errors() == null) {
            return false;
        }
        return body.error().errors().stream()
                .anyMatch(detail -> detail.reason() != null && QUOTA_REASONS.contains(detail.reason()));
    }

    private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int total = 0;
        int read;
        while (total < limit && (read = in.read(chunk, 0, Math.min(chunk.length, limit - total))) != -1) {
            out.write(chunk, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // M08 Review 교정(항목 3) - Google 응답이 null이거나 필수 필드가
    // 빠져있을 때(malformed/incomplete) NullPointerException/
    // IllegalArgumentException으로 새어나가지 않도록, 이 경계에서 미리
    // 검증해 안전한 GoogleApiException(UNKNOWN)으로 통일한다. version처럼
    // 이미 의도적으로 "없을 수 있음"을 허용한 필드(하위 verify() 로직이
    // ACCESS_UNKNOWN으로 별도 처리한다)는 여기서 요구하지 않는다 - id/name은
    // SourceDocument 생성자가 즉시 IllegalArgumentException을 던지는 진짜
    // 필수값이라 여기서 막는다.
    // ------------------------------------------------------------------

    private static GoogleFile requireValidFile(GoogleFile file) {
        if (file == null || isBlank(file.id()) || isBlank(file.name())) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN, "malformed file response from google");
        }
        return file;
    }

    private static GoogleFilesListPage requireValidFilesListPage(GoogleFilesListPage page) {
        if (page == null) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN,
                    "empty files.list response from google");
        }
        if (page.files() != null) {
            page.files().forEach(GoogleDriveClient::requireValidFile);
        }
        return page;
    }

    private static GooglePermissionsPage requireValidPermissionsPage(GooglePermissionsPage page) {
        if (page == null) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN,
                    "empty permissions response from google");
        }
        return page;
    }

    private static GoogleAbout requireValidAbout(GoogleAbout about) {
        if (about == null || about.user() == null || isBlank(about.user().permissionId())) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN, "malformed about response from google");
        }
        return about;
    }

    private static GoogleChangesPage requireValidChangesPage(GoogleChangesPage page) {
        if (page == null) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN, "empty changes response from google");
        }
        if (page.changes() != null) {
            page.changes().forEach(GoogleDriveClient::requireValidChange);
        }
        boolean isLastPage = page.nextPageToken() == null;
        if (isLastPage && isBlank(page.newStartPageToken())) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN,
                    "malformed changes page from google (missing newStartPageToken on the last page)");
        }
        return page;
    }

    /**
     * M08 후속 교정 - {@code removed != true}인데 {@code file}이 없는 조합을
     * 더 이상 조용히 통과시키지 않는다. 공식 {@code changes.list} 참고
     * 문서(Class Javadoc 참고)는 {@code removed=true}일 때만 {@code file}이
     * 없을 수 있다는 것을 명시한다 - {@code removed}가 참이 아닌데 {@code
     * file}도 없는 조합에 대해서는 공식 문서가 어떤 의미도 정의하지 않는다.
     * 그런 조합을 (호출부 {@code GoogleDriveConnector.toChangeRecord}가 과거에
     * 하던 것처럼) 삭제/접근상실로 자동 승격하면, 근거 없는 삭제 판단을
     * 지어내는 것과 같다 - 대신 여기서 Malformed/검증 불가로 Fail Closed
     * 한다(다른 {@code requireValid*} 메서드와 동일한 경계 방식).
     * {@code removed=true}가 "삭제됐다"인지 "이 Credential이 접근권한을
     * 잃었다"인지의 실제 모호함은 여기서 손대지 않는다 - 그 판단은 여전히
     * M09 몫이다.
     *
     * <p><b>M09A 교정 - {@code changeType="drive"}(공유 드라이브 자체에 대한
     * 변경) 인식.</b> 공식 {@code changes.list} 문서(Class Javadoc 참고,
     * 2026-09-14 재확인)는 {@code changeType}이 {@code file} 또는 {@code drive}
     * 일 수 있고, {@code drive} 일 때는 이 응답이 파일이 아니라 공유 드라이브
     * 자체를 가리키므로 {@code fileId}/{@code file}이 채워지지 않는다고
     * 명시한다. 교정 전에는 이 메서드가 {@code changeType}을 전혀 보지 않고
     * {@code fileId}가 비어있다는 이유만으로 무조건 Malformed로 거부했다 -
     * 정상적인 Drive-Level 이벤트를 실제 검증 실패와 구분하지 못하는 결함
     * 이었다(MVP-08). 이제 {@code changeType="drive"}는 형태만 통과시킨다 -
     * 이를 파일 삭제로 취급할지/명시적으로 미지원 처리할지는 여전히 Connector
     * (M09, {@code GoogleDriveConnector.toChangeRecord})가 결정한다.</p>
     */
    private static GoogleChange requireValidChange(GoogleChange change) {
        if (change == null) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN, "malformed change response from google");
        }
        if ("drive".equals(change.changeType())) {
            return change;
        }
        if (isBlank(change.fileId())) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN, "malformed change response from google");
        }
        if (Boolean.TRUE.equals(change.removed())) {
            return change;
        }
        if (change.file() == null) {
            throw new GoogleApiException(GoogleApiException.Category.UNKNOWN,
                    "malformed change response from google (removed is not true but no file is present)");
        }
        requireValidFile(change.file());
        return change;
    }

    private static GoogleApiException malformedResponse(Throwable cause) {
        return cause == null
                ? new GoogleApiException(GoogleApiException.Category.UNKNOWN, "malformed response from google")
                : new GoogleApiException(GoogleApiException.Category.UNKNOWN, "malformed response from google", cause);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    /** 하나의 논리적 호출(재시도+Backoff+Streaming 전체)에 걸리는 절대 시간 상한(M08 Review 교정 항목 5). */
    static final class Deadline {
        private final long deadlineNanos;
        private final boolean transportBound;

        private Deadline(long deadlineNanos, boolean transportBound) {
            this.deadlineNanos = deadlineNanos;
            this.transportBound = transportBound;
        }

        static Deadline startingNow(Duration timeout) {
            return new Deadline(System.nanoTime() + timeout.toNanos(), false);
        }

        static Deadline liveStartingNow(Duration timeout) {
            return new Deadline(System.nanoTime() + timeout.toNanos(), true);
        }

        boolean isExpired() {
            return System.nanoTime() >= deadlineNanos;
        }

        Duration remaining() {
            long remainingNanos = deadlineNanos - System.nanoTime();
            return remainingNanos <= 0 ? Duration.ZERO : Duration.ofNanos(remainingNanos);
        }

        boolean transportBound() {
            return transportBound;
        }

        int remainingTimeoutMillis() {
            long remainingMs = remaining().toMillis();
            if (remainingMs <= 0) {
                throw timeout();
            }
            return (int) Math.min(Integer.MAX_VALUE, remainingMs);
        }
    }

    // ------------------------------------------------------------------
    // Google JSON DTO - 이 Package 밖으로 절대 노출하지 않는다.
    // ------------------------------------------------------------------

    record GoogleFile(String id, String name, String mimeType, String version, String modifiedTime,
            Boolean trashed, String size, String driveId, GoogleShortcutDetails shortcutDetails,
            Map<String, String> exportLinks, GoogleCapabilities capabilities) {
    }

    record GoogleShortcutDetails(String targetId) {
    }

    record GoogleCapabilities(Boolean canDownload) {
    }

    record GoogleFilesListPage(String nextPageToken, Boolean incompleteSearch, List<GoogleFile> files) {
    }

    record GooglePermissionsPage(String nextPageToken, List<GooglePermission> permissions) {
    }

    record GooglePermission(String id, String type, String role, String emailAddress, String domain,
            Boolean deleted, String expirationTime) {
    }

    record GoogleStartPageTokenResponse(String startPageToken) {
    }

    record GoogleAbout(GoogleAboutUser user) {
    }

    record GoogleAboutUser(String permissionId, String emailAddress) {
    }

    record GoogleChangesPage(String nextPageToken, String newStartPageToken, List<GoogleChange> changes) {
    }

    record GoogleChange(String fileId, Boolean removed, String changeType, GoogleFile file) {
    }

    record GoogleErrorResponse(GoogleErrorBody error) {
    }

    record GoogleErrorBody(Integer code, String message, List<GoogleErrorDetail> errors) {
    }

    record GoogleErrorDetail(String reason) {
    }
}
