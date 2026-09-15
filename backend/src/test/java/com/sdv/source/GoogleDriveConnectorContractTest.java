package com.sdv.source;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.source.application.port.SourceCredentialException;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.source.application.port.SourceTokenStore;
import com.sdv.source.application.port.TokenEnvelope;
import com.sdv.source.domain.SourceChangePage;
import com.sdv.source.domain.SourceChangeType;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourceMetadataPage;
import com.sdv.source.domain.SourceMetadataVerificationOutcome;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.infrastructure.google.GoogleDriveClient;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-TST-003 (M08, Review 교정 반영). {@link GoogleDriveConnector}/{@link GoogleDriveClient}
 * 계약 검증 - 실제 Google 대신 Local Mock HTTP Server({@link ScriptedGoogleServer},
 * JDK 내장 {@link HttpServer} - 새 Dependency 없음)를 씀. 아래 모든 Test는
 * "Mock Server 계약 Test"다 - 실제 Google 동의/공유 드라이브/Restricted
 * Scope 검증/Production Token Store 보안을 증명하지 않는다(각 Test class
 * Javadoc/{@code .claude-handoff/latest.md}에 명시).
 *
 * <p>{@code source_connections} 조회(Owner-Only 결합 확인)를 위해 실제
 * Testcontainers PostgreSQL을 함께 쓴다 - 이미 이 Repository 전역에서 확립된
 * 관례({@code TestcontainersConfiguration})를 그대로 재사용할 뿐, 이 DB가
 * Google을 대신하는 것은 아니다(Google 대신은 오직 Mock HTTP Server).</p>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, GoogleDriveConnectorContractTest.FakeTokenStoreConfig.class})
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class GoogleDriveConnectorContractTest {

    private static final ScriptedGoogleServer SCRIPT = new ScriptedGoogleServer();
    private static final HttpServer MOCK_SERVER = startMockServer();

    @DynamicPropertySource
    static void registerMockServerUrl(DynamicPropertyRegistry registry) {
        registry.add("sdv.google-drive.api-base-url",
                () -> "http://localhost:" + MOCK_SERVER.getAddress().getPort());
    }

    private static HttpServer startMockServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", SCRIPT::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start local mock Google Drive server", e);
        }
    }

    @Autowired
    private GoogleDriveConnector connector;
    @Autowired
    private GoogleDriveClient client;
    @Autowired
    @Qualifier("googleDriveRestClient")
    private RestClient googleDriveRestClient;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private FakeSourceTokenStore fakeSourceTokenStore;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        SCRIPT.reset();
        fakeSourceTokenStore.reset();
        logCapture = new ListAppender<>();
        logCapture.start();
        rootLogger().addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        rootLogger().detachAppender(logCapture);
        logCapture.stop();
    }

    // ------------------------------------------------------------------
    // 1. 최종 사용자 Credential 결합 - Owner가 아닌 요청자는 Owner의 Credential로
    //    권한을 부여받을 수 없다.
    // ------------------------------------------------------------------

    @Test
    void fetchContentDeniesARequesterWhoIsNotTheSourceOwner() {
        long sourceId = createSource("owner-a");
        fakeSourceTokenStore.put(sourceId, "owner-a", "owner-a-secret-access-token");

        SourceContentResult result = connector.fetchContent(userContext("attacker-b"), sourceId, "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.CREDENTIAL_NOT_BOUND_TO_USER);
        assertThat(result.content()).isNull();
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    // ------------------------------------------------------------------
    // 2. 여러 Fail-Closed 상태 - Content Byte가 노출되기 전에 전부 막힌다.
    // ------------------------------------------------------------------

    @Test
    void fetchContentFailsClosedWhenNoTokenIsStored() {
        long sourceId = createSource("owner-missing-token");

        SourceContentResult result = connector.fetchContent(userContext("owner-missing-token"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.MISSING_CREDENTIAL);
        assertThat(result.content()).isNull();
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    @Test
    void fetchContentFailsClosedWhenCredentialResolutionFails() {
        long sourceId = createSource("owner-decrypt-fail");
        fakeSourceTokenStore.failOnLoad(sourceId);

        SourceContentResult result = connector.fetchContent(userContext("owner-decrypt-fail"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.CREDENTIAL_UNREADABLE);
        assertThat(result.content()).isNull();
    }

    @Test
    void fetchContentFailsClosedWhenGoogleReturnsUnauthorized() {
        long sourceId = createSource("owner-revoked");
        fakeSourceTokenStore.put(sourceId, "owner-revoked", "revoked-access-token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(401, "{\"error\":{\"message\":\"invalid credentials\"}}"));

        SourceContentResult result = connector.fetchContent(userContext("owner-revoked"), sourceId, "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.MISSING_CREDENTIAL);
        assertThat(result.content()).isNull();
        assertThat(SCRIPT.callCount("GET_FILE")).as("401 must not be retried").isEqualTo(1);
    }

    @Test
    void fetchContentFailsClosedOnPermissionDenied() {
        long sourceId = createSource("owner-denied");
        fakeSourceTokenStore.put(sourceId, "owner-denied", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(403,
                "{\"error\":{\"errors\":[{\"reason\":\"insufficientFilePermissions\"}]}}"));

        SourceContentResult result = connector.fetchContent(userContext("owner-denied"), sourceId, "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.ACCESS_DENIED);
        assertThat(SCRIPT.callCount("GET_FILE")).as("permission 403 must not be retried").isEqualTo(1);
    }

    @Test
    void fetchContentFailsClosedWhenTrashed() {
        long sourceId = createSource("owner-trashed");
        fakeSourceTokenStore.put(sourceId, "owner-trashed", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", true, true,
                "application/pdf")));

        SourceContentResult result = connector.fetchContent(userContext("owner-trashed"), sourceId, "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.TRASHED);
        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA")).isZero();
    }

    @Test
    void fetchContentFailsClosedWhenNotDownloadable() {
        long sourceId = createSource("owner-nodownload");
        fakeSourceTokenStore.put(sourceId, "owner-nodownload", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", false, false,
                "application/pdf")));

        SourceContentResult result = connector.fetchContent(userContext("owner-nodownload"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.NOT_DOWNLOADABLE);
        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA")).isZero();
    }

    @Test
    void fetchContentFailsClosedWhenAccessCannotBeConfirmedAfterRetriesAreExhausted() {
        long sourceId = createSource("owner-quota-exhausted");
        fakeSourceTokenStore.put(sourceId, "owner-quota-exhausted", "token");
        // MAX_ATTEMPTS(3)만큼 계속 재시도 대상만 응답한다 - 결국 신뢰 가능한 답을 얻지 못한다(Fail Closed).
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(503, "{\"error\":{\"message\":\"server error\"}}"));
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(503, "{\"error\":{\"message\":\"server error\"}}"));
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(503, "{\"error\":{\"message\":\"server error\"}}"));

        SourceContentResult result = connector.fetchContent(userContext("owner-quota-exhausted"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome())
                .as("repeated transient failures must never be assumed to mean access is fine")
                .isEqualTo(SourceContentOutcome.ACCESS_UNKNOWN);
        assertThat(SCRIPT.callCount("GET_FILE")).isEqualTo(3);
    }

    @Test
    void getFileRetriesA403WithAQuotaReasonButNotA403WithAPermissionReason() {
        // Quota 계열 403은 재시도 대상이다(권한 계열 403과 다르다) - 공식 문서(2026-09-13 확인) 분류를 검증한다.
        long sourceId = createSource("owner-quota-retry");
        fakeSourceTokenStore.put(sourceId, "owner-quota-retry", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(403,
                "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}"));
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v9", false, true, "application/pdf")));

        SourceDocument document = connector.getMetadata(sourceId, "file-1");

        assertThat(document.getSourceVersion()).isEqualTo("v9");
        assertThat(SCRIPT.callCount("GET_FILE")).as("quota-reason 403 must be retried, unlike a permission-reason 403")
                .isEqualTo(2);
    }

    @Test
    void fetchContentFailsClosedWhenVersionIsUnknown() {
        long sourceId = createSource("owner-unknown-version");
        fakeSourceTokenStore.put(sourceId, "owner-unknown-version", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200,
                "{\"id\":\"file-1\",\"name\":\"Test File file-1\",\"mimeType\":\"application/pdf\",\"trashed\":false,"
                        + "\"capabilities\":{\"canDownload\":true}}"));

        SourceContentResult result = connector.fetchContent(userContext("owner-unknown-version"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.ACCESS_UNKNOWN);
    }

    // ------------------------------------------------------------------
    // 3. Pre-fetch Version 불일치 - Content를 하나도 Fetch하지 않는다.
    // ------------------------------------------------------------------

    @Test
    void fetchContentPreFetchVersionMismatchTransfersNoBytes() {
        long sourceId = createSource("owner-pre-mismatch");
        fakeSourceTokenStore.put(sourceId, "owner-pre-mismatch", "token");
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v2", false, true, "application/pdf")));

        SourceContentResult result = connector.fetchContent(userContext("owner-pre-mismatch"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.VERSION_MISMATCH);
        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA")).isZero();
        assertThat(SCRIPT.callCount("EXPORT")).isZero();
        assertThat(SCRIPT.callCount("GET_FILE")).as("only the pre-fetch check, no post-fetch recheck").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 4/5. Fetch 중 Version 변경(M08 Review 교정 항목 2) - Version은 단조
    //    증가하므로, 한 번 바뀐 뒤 재시도의 Pre-check는 항상 다시 어긋난다
    //    (추가 Download 없이 곧바로 DOCUMENT_CHANGED). Trashed 등 Version과
    //    무관한 접근권한 변화는 그 실제 사유를 그대로 반환한다.
    // ------------------------------------------------------------------

    @Test
    void fetchContentDiscardsBytesAndReturnsDocumentChangedWhenRetryPreCheckStillShowsANewerVersion() {
        long sourceId = createSource("owner-retry-still-stale");
        fakeSourceTokenStore.put(sourceId, "owner-retry-still-stale", "token");
        // Attempt 1: pre(v1)이 기대(v1)와 일치 -> Download -> post(v2) - 단조 증가로 Version이 바뀜을 감지 -> Byte 폐기, 재시도 1회.
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", false, true,
                "application/pdf"))); // pre1
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v2", false, true,
                "application/pdf"))); // post1 - mismatch, retry
        // Attempt 2(재시도): 단조 증가이므로 v1로 "되돌아갈" 수 없다 - Pre-check 자체가 이미 어긋난다(추가 Download 없음).
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v2", false, true,
                "application/pdf"))); // pre2 - 여전히 v2
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.bytes(200, "application/pdf", "attempt-1-bytes".getBytes(UTF8())));

        SourceContentResult result = connector.fetchContent(userContext("owner-retry-still-stale"), sourceId,
                "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.DOCUMENT_CHANGED);
        assertThat(result.content()).isNull();
        assertThat(SCRIPT.callCount("GET_FILE")).as("pre1 + post1 + pre2, no post2").isEqualTo(3);
        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA"))
                .as("the retry's own pre-check already mismatched - no second download").isEqualTo(1);
    }

    @Test
    void fetchContentReturnsTheRealReasonWhenPostFetchRevealsAccessLossRatherThanVersionInstability() {
        long sourceId = createSource("owner-post-trashed");
        fakeSourceTokenStore.put(sourceId, "owner-post-trashed", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", false, true,
                "application/pdf"))); // pre - OK
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", true, true,
                "application/pdf"))); // post - 이제 Trashed(Version은 그대로 v1)
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.bytes(200, "application/pdf", "bytes".getBytes(UTF8())));

        SourceContentResult result = connector.fetchContent(userContext("owner-post-trashed"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome())
                .as("access loss discovered post-fetch must not be mislabeled as version instability")
                .isEqualTo(SourceContentOutcome.TRASHED);
        assertThat(result.content()).isNull();
        assertThat(SCRIPT.callCount("GET_FILE")).as("no retry for a real access-state change, not a version race")
                .isEqualTo(2);
    }

    @Test
    void fetchContentReturnsAccessDeniedWhenPostFetchReturnsPermissionDeniedWithoutRetrying() {
        long sourceId = createSource("owner-post-403");
        fakeSourceTokenStore.put(sourceId, "owner-post-403", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", false, true,
                "application/pdf"))); // pre - OK
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.bytes(200, "application/pdf", "bytes".getBytes(UTF8())));
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(403,
                "{\"error\":{\"errors\":[{\"reason\":\"insufficientFilePermissions\"}]}}")); // post - 접근권한 회수

        SourceContentResult result = connector.fetchContent(userContext("owner-post-403"), sourceId, "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.ACCESS_DENIED);
        assertThat(result.content()).isNull();
        assertThat(SCRIPT.callCount("GET_FILE"))
                .as("an explicit post-fetch 403 is not a transient failure and is not retried as a version race")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // getMetadata - Catalog Sync 경로(Owner Credential), Credential/실패 상태는
    // Runtime 예외로 Fail Closed 한다.
    // ------------------------------------------------------------------

    @Test
    void getMetadataMapsGoogleFileFieldsIntoASourceDocument() {
        long sourceId = createSource("owner-metadata");
        fakeSourceTokenStore.put(sourceId, "owner-metadata", "token");
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v7", false, true, "application/pdf")));

        SourceDocument document = connector.getMetadata(sourceId, "file-1");

        assertThat(document.getSourceDocumentId()).isEqualTo("file-1");
        assertThat(document.getSourceVersion()).isEqualTo("v7");
        assertThat(document.getMimeType()).isEqualTo("application/pdf");
        assertThat(document.getState().name()).isEqualTo("ACTIVE");
    }

    @Test
    void getMetadataMarksTrashedFilesAsDeletedState() {
        long sourceId = createSource("owner-metadata-trashed");
        fakeSourceTokenStore.put(sourceId, "owner-metadata-trashed", "token");
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v1", true, true, "application/pdf")));

        SourceDocument document = connector.getMetadata(sourceId, "file-1");

        assertThat(document.getState().name()).isEqualTo("DELETED");
    }

    @Test
    void getMetadataFailsClosedWithACredentialExceptionWhenNoTokenIsStored() {
        long sourceId = createSource("owner-metadata-missing-token");

        assertThatThrownBy(() -> connector.getMetadata(sourceId, "file-1"))
                .isInstanceOf(SourceCredentialException.class)
                .satisfies(e -> assertThat(((SourceCredentialException) e).getReason())
                        .isEqualTo(SourceCredentialException.Reason.MISSING_CREDENTIAL));
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    @Test
    void getMetadataFailsClosedWithASyncExceptionWhenGoogleReturnsNotFound() {
        long sourceId = createSource("owner-metadata-not-found");
        fakeSourceTokenStore.put(sourceId, "owner-metadata-not-found", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(404, "{\"error\":{\"message\":\"not found\"}}"));

        assertThatThrownBy(() -> connector.getMetadata(sourceId, "file-1"))
                .isInstanceOf(SourceSyncException.class)
                .satisfies(e -> assertThat(((SourceSyncException) e).getReason())
                        .isEqualTo(SourceSyncException.Reason.NOT_FOUND));
    }

    // ------------------------------------------------------------------
    // 6. Permission Pagination - 모든 페이지를 정확히 한 번씩 처리한다.
    // ------------------------------------------------------------------

    @Test
    void getPermissionsProcessesEveryPageExactlyOnce() {
        long sourceId = createSource("owner-perm-pages");
        fakeSourceTokenStore.put(sourceId, "owner-perm-pages", "token");
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200,
                "{\"nextPageToken\":\"page-2\",\"permissions\":["
                        + "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"alice@example.com\"},"
                        + "{\"type\":\"user\",\"role\":\"writer\",\"emailAddress\":\"bob@example.com\"}]}"));
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200,
                "{\"permissions\":[{\"type\":\"domain\",\"role\":\"reader\",\"domain\":\"example.com\"}]}"));

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.kind()).isEqualTo(SourcePermissionsResult.Kind.OK);
        assertThat(result.permissions()).hasSize(3);
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS")).isEqualTo(2);
    }

    @Test
    void getPermissionsTerminatesSafelyWhenGoogleRepeatsTheSamePageToken() {
        long sourceId = createSource("owner-perm-repeated-token");
        fakeSourceTokenStore.put(sourceId, "owner-perm-repeated-token", "token");
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200,
                "{\"nextPageToken\":\"page-2\",\"permissions\":["
                        + "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"alice@example.com\"}]}"));
        // page 2 illegitimately repeats the same nextPageToken it was called with instead of
        // advancing or terminating - a naive "loop while pageToken != null" would never stop.
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200,
                "{\"nextPageToken\":\"page-2\",\"permissions\":["
                        + "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"bob@example.com\"}]}"));

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.kind())
                .as("a repeated page token must fail closed, not loop forever or claim a complete result")
                .isEqualTo(SourcePermissionsResult.Kind.FAILED);
        assertThat(result.permissions()).isEmpty();
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS"))
                .as("traversal must stop as soon as the repeat is detected, not retry indefinitely")
                .isEqualTo(2);
    }

    @Test
    void getPermissionsTerminatesSafelyWhenPageTokensFormAMultiTokenCycle() {
        long sourceId = createSource("owner-perm-cyclic-tokens");
        fakeSourceTokenStore.put(sourceId, "owner-perm-cyclic-tokens", "token");
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200,
                "{\"nextPageToken\":\"page-2\",\"permissions\":["
                        + "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"alice@example.com\"}]}"));
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200,
                "{\"nextPageToken\":\"page-3\",\"permissions\":["
                        + "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"bob@example.com\"}]}"));
        // page 3 points back to page-2, which was already seen - a two-token cycle (page-2 -> page-3 -> page-2).
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200,
                "{\"nextPageToken\":\"page-2\",\"permissions\":["
                        + "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"carol@example.com\"}]}"));

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.kind())
                .as("a multi-token page cycle must fail closed rather than loop forever")
                .isEqualTo(SourcePermissionsResult.Kind.FAILED);
        assertThat(result.permissions())
                .as("partially collected permissions must never be returned as an authoritative result")
                .isEmpty();
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS")).isEqualTo(3);
    }

    @Test
    void getPermissionsExcludesAmbiguousPrincipalsRatherThanFabricatingThem() {
        long sourceId = createSource("owner-perm-ambiguous");
        fakeSourceTokenStore.put(sourceId, "owner-perm-ambiguous", "token");
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200,
                "{\"permissions\":["
                        + "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"alice@example.com\"},"
                        + "{\"type\":\"user\",\"role\":\"reader\",\"deleted\":true,\"emailAddress\":\"gone@example.com\"},"
                        + "{\"type\":\"user\",\"role\":\"reader\"},"
                        + "{\"type\":\"unknownType\",\"role\":\"reader\"}]}"));

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.permissions()).hasSize(1);
        assertThat(result.permissions().get(0).principal().value()).isEqualTo("alice@example.com");
    }

    @Test
    void getPermissionsExcludesAnAlreadyExpiredPermissionGrant() {
        long sourceId = createSource("owner-perm-expired");
        fakeSourceTokenStore.put(sourceId, "owner-perm-expired", "token");
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200,
                "{\"permissions\":["
                        + "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"alice@example.com\","
                        + "\"expirationTime\":\"2020-01-01T00:00:00.000Z\"},"
                        + "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"bob@example.com\"}]}"));

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.permissions()).hasSize(1);
        assertThat(result.permissions().get(0).principal().value()).isEqualTo("bob@example.com");
    }

    @Test
    void getPermissionsExcludesAPermissionWithAMalformedExpirationTime() {
        long sourceId = createSource("owner-perm-malformed-expiry");
        fakeSourceTokenStore.put(sourceId, "owner-perm-malformed-expiry", "token");
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200,
                "{\"permissions\":[{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"alice@example.com\","
                        + "\"expirationTime\":\"not-a-timestamp\"}]}"));

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.permissions())
                .as("an unparsable expiration time must not be assumed to mean the grant is still active")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // 6B. getPermissions Source/Credential 결합 확인(M08 후속 교정) - 다른
    //    Catalog-Sync 연산과 동일한 Source 존재/종류/ACTIVE/Owner 결합 검사를
    //    거치지 않으면 통과해서는 안 된다. 모든 경우 Google 호출 0회.
    // ------------------------------------------------------------------

    @Test
    void getPermissionsFailsClosedWhenTheSourceConnectionDoesNotExist() {
        long nonExistentSourceId = 987_654_321L;

        SourcePermissionsResult result = connector.getPermissions(nonExistentSourceId, "file-1");

        assertThat(result.kind()).isEqualTo(SourcePermissionsResult.Kind.UNKNOWN);
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS")).isZero();
    }

    @Test
    void getPermissionsFailsClosedWhenTheSourceConnectionIsNotAGoogleDriveSource() {
        SourceConnectionEntity nonGoogleSource = new SourceConnectionEntity("SHAREPOINT", "Test Source", "ACTIVE",
                "FULL", "owner-perm-wrong-type");
        sourceConnectionJpaRepository.saveAndFlush(nonGoogleSource);
        fakeSourceTokenStore.put(nonGoogleSource.getId(), "owner-perm-wrong-type", "token");

        SourcePermissionsResult result = connector.getPermissions(nonGoogleSource.getId(), "file-1");

        assertThat(result.kind()).isEqualTo(SourcePermissionsResult.Kind.UNKNOWN);
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS")).isZero();
    }

    @Test
    void getPermissionsFailsClosedWhenTheSourceConnectionIsNotActive() {
        long sourceId = createSource("owner-perm-inactive", "DISABLED");
        fakeSourceTokenStore.put(sourceId, "owner-perm-inactive", "token");

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.kind()).isEqualTo(SourcePermissionsResult.Kind.UNKNOWN);
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS")).isZero();
    }

    @Test
    void getPermissionsFailsClosedWhenNoCredentialIsStored() {
        long sourceId = createSource("owner-perm-missing-token");

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.kind()).isEqualTo(SourcePermissionsResult.Kind.UNKNOWN);
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS")).isZero();
    }

    @Test
    void getPermissionsFailsClosedWhenCredentialResolutionFails() {
        long sourceId = createSource("owner-perm-unreadable");
        fakeSourceTokenStore.put(sourceId, "owner-perm-unreadable", "token");
        fakeSourceTokenStore.failOnLoad(sourceId);

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.kind()).isEqualTo(SourcePermissionsResult.Kind.UNKNOWN);
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS")).isZero();
    }

    @Test
    void getPermissionsFailsClosedWhenTheStoredCredentialIsBoundToADifferentSubjectThanTheOwner() {
        long sourceId = createSource("owner-perm-wrong-binding");
        // sourceId의 DB Owner는 owner-perm-wrong-binding인데 결합된 Subject는 다르다.
        fakeSourceTokenStore.put(sourceId, "someone-else", "token");

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.kind())
                .as("a credential bound to a different subject must never be used, even if the DB owner check alone would pass")
                .isEqualTo(SourcePermissionsResult.Kind.UNKNOWN);
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS")).isZero();
    }

    @Test
    void getPermissionsFailsClosedWhenTheStoredCredentialIsExpired() {
        long sourceId = createSource("owner-perm-expired-token");
        fakeSourceTokenStore.putExpiring(sourceId, "owner-perm-expired-token", "token",
                Instant.now().minusSeconds(3600));

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.kind()).isEqualTo(SourcePermissionsResult.Kind.UNKNOWN);
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS")).isZero();
    }

    @Test
    void getPermissionsFailsClosedWhenTheStoredCredentialLacksTheRequiredScope() {
        long sourceId = createSource("owner-perm-insufficient-scope");
        fakeSourceTokenStore.putWithScopes(sourceId, "owner-perm-insufficient-scope", "token",
                List.of("https://www.googleapis.com/auth/drive.file"));

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.kind()).isEqualTo(SourcePermissionsResult.Kind.UNKNOWN);
        assertThat(SCRIPT.callCount("LIST_PERMISSIONS")).isZero();
    }

    // ------------------------------------------------------------------
    // 7. Change Pagination - nextPageToken/newStartPageToken 보존, 삭제-대-접근상실 구분.
    // ------------------------------------------------------------------

    @Test
    void findChangesPreservesPaginationTokensAndDistinguishesRemovedFromChanged() {
        long sourceId = createSource("owner-changes");
        fakeSourceTokenStore.put(sourceId, "owner-changes", "token");
        SCRIPT.enqueue("LIST_CHANGES", CannedResponse.json(200,
                "{\"nextPageToken\":\"c2\",\"changes\":["
                        + "{\"fileId\":\"changed-file\",\"removed\":false,"
                        + "\"file\":" + googleFileJson("changed-file", "v5", false, true, "application/pdf") + "},"
                        + "{\"fileId\":\"gone-file\",\"removed\":true}]}"));

        SourceChangePage page1 = connector.findChanges(sourceId, "c1");

        assertThat(page1.isLastPage()).isFalse();
        assertThat(page1.nextPageToken()).isEqualTo("c2");
        assertThat(page1.newStartPageToken()).isNull();
        assertThat(page1.changes()).hasSize(2);
        assertThat(page1.changes().get(0).type()).isEqualTo(SourceChangeType.CHANGED);
        assertThat(page1.changes().get(0).document()).isNotNull();
        assertThat(page1.changes().get(1).type())
                .as("removed=true must not be silently equated with confirmed global deletion")
                .isEqualTo(SourceChangeType.REMOVED_OR_ACCESS_LOST);
        assertThat(page1.changes().get(1).document()).isNull();

        SCRIPT.enqueue("LIST_CHANGES", CannedResponse.json(200, "{\"newStartPageToken\":\"future-start\",\"changes\":[]}"));

        SourceChangePage page2 = connector.findChanges(sourceId, "c2");

        assertThat(page2.isLastPage()).isTrue();
        assertThat(page2.nextPageToken()).isNull();
        assertThat(page2.newStartPageToken()).isEqualTo("future-start");
    }

    @Test
    void findChangesFailsSafelyOnADriveLevelChangeInsteadOfMisreadingItAsAFileDeletion() {
        // M09A(MVP-08) 교정 검증 - changeType="drive"는 fileId/file이 없는 것이 정상이다(공식
        // 문서). 교정 전에는 이 조합이 requireValidChange에서 changeType을 전혀 보지 않아
        // "removed가 아닌데 file도 없다"는 이유로 malformed 처리되거나(fileId도 비어있으면
        // 그보다 먼저), 혹은 toChangeRecord가 file()==null만 보고 REMOVED_OR_ACCESS_LOST(파일
        // 삭제)로 잘못 승격시킬 수 있었다 - 둘 다 부정확하다. 지금은 명시적으로 안전한
        // SourceSyncException으로 Fail Closed 해야 한다(근거 없는 파일 삭제 판단 금지).
        long sourceId = createSource("owner-drive-level-change");
        fakeSourceTokenStore.put(sourceId, "owner-drive-level-change", "token");
        SCRIPT.enqueue("LIST_CHANGES", CannedResponse.json(200,
                "{\"newStartPageToken\":\"s1\",\"changes\":[{\"changeType\":\"drive\",\"removed\":false}]}"));

        assertThatThrownBy(() -> connector.findChanges(sourceId, "c1"))
                .as("a shared-drive-level change must fail safely and must never be treated as a file deletion")
                .isInstanceOf(SourceSyncException.class);
    }

    @Test
    void findChangesFailsSafelyWhenTheLastPageIsMissingNewStartPageToken() {
        long sourceId = createSource("owner-malformed-changes");
        fakeSourceTokenStore.put(sourceId, "owner-malformed-changes", "token");
        // 마지막 페이지(nextPageToken 없음)인데 newStartPageToken도 없다 - Malformed 응답.
        SCRIPT.enqueue("LIST_CHANGES", CannedResponse.json(200, "{\"changes\":[]}"));

        assertThatThrownBy(() -> connector.findChanges(sourceId, "c1"))
                .as("a malformed last page must fail safely through the source-neutral contract, not as a raw NPE/IAE")
                .isInstanceOf(SourceSyncException.class);
    }

    @Test
    void findChangesFailsSafelyWhenAChangeEntryIsMissingItsFileId() {
        long sourceId = createSource("owner-malformed-change-entry");
        fakeSourceTokenStore.put(sourceId, "owner-malformed-change-entry", "token");
        SCRIPT.enqueue("LIST_CHANGES", CannedResponse.json(200,
                "{\"newStartPageToken\":\"s1\",\"changes\":[{\"removed\":false}]}"));

        assertThatThrownBy(() -> connector.findChanges(sourceId, "c1")).isInstanceOf(SourceSyncException.class);
    }

    @Test
    void findChangesFailsSafelyWhenAChangeEntryHasNoFileAndIsNotMarkedRemoved() {
        long sourceId = createSource("owner-ambiguous-change-entry");
        fakeSourceTokenStore.put(sourceId, "owner-ambiguous-change-entry", "token");
        // removed=false(명시적으로 삭제/접근상실이 아니라고 주장)인데 file도 없다 - 공식
        // changes.list 문서가 의미를 정의하지 않는 조합이다. REMOVED_OR_ACCESS_LOST로
        // 조용히 승격시키지 않고 malformed로 Fail Closed 해야 한다(근거 없는 삭제 판단 금지).
        SCRIPT.enqueue("LIST_CHANGES", CannedResponse.json(200,
                "{\"newStartPageToken\":\"s1\",\"changes\":[{\"fileId\":\"ambiguous-file\",\"removed\":false}]}"));

        assertThatThrownBy(() -> connector.findChanges(sourceId, "c1"))
                .as("removed=false with no file must fail closed as malformed, not be silently promoted to a deletion signal")
                .isInstanceOf(SourceSyncException.class);
    }

    @Test
    void getStartPageTokenFailsSafelyWhenGoogleReturnsABlankToken() {
        long sourceId = createSource("owner-malformed-start-token");
        fakeSourceTokenStore.put(sourceId, "owner-malformed-start-token", "token");
        SCRIPT.enqueue("START_PAGE_TOKEN", CannedResponse.json(200, "{\"startPageToken\":\"\"}"));

        assertThatThrownBy(() -> connector.getStartPageToken(sourceId)).isInstanceOf(SourceSyncException.class);
    }

    @Test
    void getPermissionsFailsSafelyWhenGoogleReturnsAnEmptyBody() {
        long sourceId = createSource("owner-malformed-permissions");
        fakeSourceTokenStore.put(sourceId, "owner-malformed-permissions", "token");
        SCRIPT.enqueue("LIST_PERMISSIONS", CannedResponse.json(200, "null"));

        SourcePermissionsResult result = connector.getPermissions(sourceId, "file-1");

        assertThat(result.kind()).isEqualTo(SourcePermissionsResult.Kind.FAILED);
    }

    // ------------------------------------------------------------------
    // 8. Shared Drive Parameter 존재 확인.
    // ------------------------------------------------------------------

    @Test
    void metadataAndChangeRequestsIncludeSharedDriveParameters() {
        long sourceId = createSource("owner-shared-drive");
        fakeSourceTokenStore.put(sourceId, "owner-shared-drive", "token");
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v1", false, true, "application/pdf")));
        connector.getMetadata(sourceId, "file-1");
        assertThat(SCRIPT.lastQuery("GET_FILE")).contains("supportsAllDrives=true");

        SCRIPT.enqueue("LIST_CHANGES", CannedResponse.json(200, "{\"newStartPageToken\":\"s1\",\"changes\":[]}"));
        connector.findChanges(sourceId, "c1");
        assertThat(SCRIPT.lastQuery("LIST_CHANGES")).contains("supportsAllDrives=true")
                .contains("includeItemsFromAllDrives=true");
    }

    // ------------------------------------------------------------------
    // 8B. Whole-Drive Metadata Discovery(M08 Review 교정 항목 1) - files.list.
    // ------------------------------------------------------------------

    @Test
    void listMetadataProcessesEveryPageExactlyOnceAndReportsCompleteness() {
        long sourceId = createSource("owner-list-metadata");
        fakeSourceTokenStore.put(sourceId, "owner-list-metadata", "token");
        SCRIPT.enqueue("LIST_FILES", CannedResponse.json(200,
                "{\"nextPageToken\":\"p2\",\"incompleteSearch\":false,\"files\":["
                        + googleFileJson("file-1", "v1", false, true, "application/pdf") + ","
                        + googleFileJson("file-2", "v1", false, true, "application/pdf") + "]}"));
        SCRIPT.enqueue("LIST_FILES", CannedResponse.json(200,
                "{\"incompleteSearch\":false,\"files\":["
                        + googleFileJson("file-3", "v1", false, true, "application/pdf") + "]}"));

        SourceMetadataPage page1 = connector.listMetadata(sourceId, null);
        assertThat(page1.isLastPage()).isFalse();
        assertThat(page1.nextPageToken()).isEqualTo("p2");
        assertThat(page1.isComplete()).isTrue();
        assertThat(page1.documents()).hasSize(2);

        SourceMetadataPage page2 = connector.listMetadata(sourceId, "p2");
        assertThat(page2.isLastPage()).isTrue();
        assertThat(page2.nextPageToken()).isNull();
        assertThat(page2.documents()).hasSize(1);

        assertThat(SCRIPT.callCount("LIST_FILES")).isEqualTo(2);
    }

    @Test
    void listMetadataReportsIncompleteSearchRatherThanClaimingACompleteResult() {
        long sourceId = createSource("owner-list-metadata-incomplete");
        fakeSourceTokenStore.put(sourceId, "owner-list-metadata-incomplete", "token");
        SCRIPT.enqueue("LIST_FILES", CannedResponse.json(200,
                "{\"incompleteSearch\":true,\"files\":["
                        + googleFileJson("file-1", "v1", false, true, "application/pdf") + "]}"));

        SourceMetadataPage page = connector.listMetadata(sourceId, null);

        assertThat(page.isLastPage()).isTrue();
        assertThat(page.isComplete())
                .as("incompleteSearch=true must never be silently promoted to a complete/trustworthy result")
                .isFalse();
    }

    @Test
    void listFilesRequestIncludesTheDocumentedWholeDriveDiscoveryParameters() {
        long sourceId = createSource("owner-list-metadata-params");
        fakeSourceTokenStore.put(sourceId, "owner-list-metadata-params", "token");
        SCRIPT.enqueue("LIST_FILES", CannedResponse.json(200, "{\"incompleteSearch\":false,\"files\":[]}"));

        connector.listMetadata(sourceId, null);

        assertThat(SCRIPT.lastQuery("LIST_FILES")).contains("corpora=allDrives").contains("supportsAllDrives=true")
                .contains("includeItemsFromAllDrives=true").contains("spaces=drive");
    }

    // ------------------------------------------------------------------
    // 9. 미지원/기본 비활성화 포맷 - Media/Export 요청 자체가 없다.
    // ------------------------------------------------------------------

    @Test
    void fetchContentForXlsxNeverCallsMediaOrExport() {
        long sourceId = createSource("owner-xlsx");
        fakeSourceTokenStore.put(sourceId, "owner-xlsx", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", false, true,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));

        SourceContentResult result = connector.fetchContent(userContext("owner-xlsx"), sourceId, "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.UNSUPPORTED_FORMAT);
        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA")).isZero();
        assertThat(SCRIPT.callCount("EXPORT")).isZero();
    }

    @Test
    void fetchContentForGoogleSheetsNeverCallsMediaOrExport() {
        long sourceId = createSource("owner-sheets");
        fakeSourceTokenStore.put(sourceId, "owner-sheets", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", false, true,
                "application/vnd.google-apps.spreadsheet")));

        SourceContentResult result = connector.fetchContent(userContext("owner-sheets"), sourceId, "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.UNSUPPORTED_FORMAT);
        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA")).isZero();
        assertThat(SCRIPT.callCount("EXPORT")).isZero();
    }

    // ------------------------------------------------------------------
    // 10. Binary Download Byte 상한 - "받는 도중" 중단(다 받은 뒤가 아니라).
    // ------------------------------------------------------------------

    @Test
    void downloadMediaAbortsOnceTheByteLimitIsExceededWhileStreaming() {
        byte[] oversized = new byte[1000];
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.bytes(200, "application/octet-stream", oversized));

        assertThatThrownBy(() -> client.downloadMedia("token", "file-1", 100))
                .hasMessageContaining("byte limit");
    }

    @Test
    void downloadMediaSucceedsExactlyAtTheByteLimit() {
        byte[] exact = new byte[100];
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.bytes(200, "application/octet-stream", exact));

        byte[] result = client.downloadMedia("token", "file-1", 100);

        assertThat(result).hasSize(100);
    }

    // ------------------------------------------------------------------
    // 11. 동기 Export 상한(M08 Review 교정 항목 6 - 보수적으로 10진 10,000,000
    //    Byte를 쓴다, 10 MiB로 넉넉하게 잡지 않는다) - 정확한 경계 및 실제 초과.
    // ------------------------------------------------------------------

    @Test
    void exportFileSucceedsAtExactlyTheConfiguredLimit() {
        byte[] exact = new byte[100];
        SCRIPT.enqueue("EXPORT", CannedResponse.bytes(200, "application/pdf", exact));

        byte[] result = client.exportFile("token", "file-1", "application/pdf", 100);

        assertThat(result).hasSize(100);
    }

    @Test
    void exportFileRejectsOneByteOverTheConfiguredLimit() {
        byte[] overLimit = new byte[101];
        SCRIPT.enqueue("EXPORT", CannedResponse.bytes(200, "application/pdf", overLimit));

        assertThatThrownBy(() -> client.exportFile("token", "file-1", "application/pdf", 100))
                .hasMessageContaining("byte limit");
    }

    /** 실제 상수(10,000,000 Byte 정확히)를 통해 Adapter/Connector 수준에서 정확한 경계 성공을 확인한다. */
    @Test
    void fetchContentSucceedsWhenGoogleDocExportIsExactlyAtTheConfiguredLimit() {
        long sourceId = createSource("owner-export-exact");
        fakeSourceTokenStore.put(sourceId, "owner-export-exact", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", false, true,
                "application/vnd.google-apps.document")));
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", false, true,
                "application/vnd.google-apps.document")));
        byte[] exact = new byte[10_000_000]; // GoogleDriveContentAdapter.EXPORT_SYNC_LIMIT_BYTES 정확히.
        SCRIPT.enqueue("EXPORT", CannedResponse.bytes(200, "application/pdf", exact));

        SourceContentResult result = connector.fetchContent(userContext("owner-export-exact"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.VERIFIED);
        assertThat(result.content()).hasSize(10_000_000);
    }

    /** 실제 상수를 1 Byte 초과하는 경계를 통해 Adapter 수준에서 EXPORT_LIMIT_EXCEEDED를 확인한다. */
    @Test
    void fetchContentReturnsExportLimitExceededWhenGoogleDocExportExceedsTheConfiguredLimit() {
        long sourceId = createSource("owner-export-overflow");
        fakeSourceTokenStore.put(sourceId, "owner-export-overflow", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", false, true,
                "application/vnd.google-apps.document")));
        byte[] overflow = new byte[10_000_001]; // EXPORT_SYNC_LIMIT_BYTES(10,000,000) + 1.
        SCRIPT.enqueue("EXPORT", CannedResponse.bytes(200, "application/pdf", overflow));

        SourceContentResult result = connector.fetchContent(userContext("owner-export-overflow"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.EXPORT_LIMIT_EXCEEDED);
        assertThat(result.content()).isNull();
    }

    // ------------------------------------------------------------------
    // 12. 재시도/취소/절대 Deadline 계약.
    // ------------------------------------------------------------------

    @Test
    void getFileRetriesRetryableServerErrorsUpToTheBoundThenSucceeds() {
        // GoogleDriveClient.GoogleFile은 com.sdv.source.infrastructure.google 밖으로 노출되지
        // 않는다(의도적) - connector.getMetadata(Source-neutral)를 통해 같은 재시도 경로를 검증한다.
        long sourceId = createSource("owner-retry-metadata");
        fakeSourceTokenStore.put(sourceId, "owner-retry-metadata", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(503, "{\"error\":{\"message\":\"server error\"}}"));
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(503, "{\"error\":{\"message\":\"server error\"}}"));
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v1", false, true, "application/pdf")));

        SourceDocument document = connector.getMetadata(sourceId, "file-1");

        assertThat(document.getSourceVersion()).isEqualTo("v1");
        assertThat(SCRIPT.callCount("GET_FILE")).isEqualTo(3);
    }

    @Test
    void getFileDoesNotRetryOnPermissionDenied() {
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(403,
                "{\"error\":{\"errors\":[{\"reason\":\"insufficientFilePermissions\"}]}}"));

        assertThatThrownBy(() -> client.getFile("token", "file-1"));

        assertThat(SCRIPT.callCount("GET_FILE")).isEqualTo(1);
    }

    @Test
    void getFileDoesNotRetryOnNotFound() {
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(404, "{\"error\":{\"message\":\"not found\"}}"));

        assertThatThrownBy(() -> client.getFile("token", "file-1"));

        assertThat(SCRIPT.callCount("GET_FILE")).isEqualTo(1);
    }

    @Test
    void getFileDoesNotRetryOnUnauthorized() {
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(401, "{\"error\":{\"message\":\"invalid credentials\"}}"));

        assertThatThrownBy(() -> client.getFile("token", "file-1"));

        assertThat(SCRIPT.callCount("GET_FILE")).as("no refresh/retry attempt at this layer").isEqualTo(1);
    }

    @Test
    void getFileFailsSafelyInsteadOfARawExceptionWhenGoogleReturnsAnEmptyBody() {
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, "null"));

        assertThatThrownBy(() -> client.getFile("token", "file-1"))
                .as("an empty/malformed google response must fail safely, not escape as a raw NPE")
                .isNotInstanceOf(NullPointerException.class);
    }

    @Test
    void getFileFailsSafelyWhenTheRequiredNameFieldIsMissing() {
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, "{\"id\":\"file-1\",\"mimeType\":\"application/pdf\"}"));

        assertThatThrownBy(() -> client.getFile("token", "file-1"))
                .as("a file missing its required name must fail safely, not escape as a raw IllegalArgumentException")
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void downloadMediaRetriesA403WithAQuotaReasonButNotA403WithAPermissionReason() {
        // M08 Review 교정 항목 4 - boundedGet(Media/Export)도 GET_FILE과 동일하게 reason 기반으로 구분해야 한다.
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.json(403,
                "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}"));
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.bytes(200, "application/octet-stream", "ok".getBytes(UTF8())));

        byte[] result = client.downloadMedia("token", "file-1", 100);

        assertThat(new String(result, UTF8())).isEqualTo("ok");
        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA")).as("a quota-reason 403 on a content download must be retried too")
                .isEqualTo(2);
    }

    @Test
    void downloadMediaDoesNotRetryA403WithAPermissionReason() {
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.json(403,
                "{\"error\":{\"errors\":[{\"reason\":\"insufficientFilePermissions\"}]}}"));

        assertThatThrownBy(() -> client.downloadMedia("token", "file-1", 100));

        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA")).isEqualTo(1);
    }

    @Test
    void downloadMediaTreatsAnUnparsableForbiddenBodyAsPermissionDeniedRatherThanCrashing() {
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.bytes(403, "text/html", "<html>not json</html>".getBytes(UTF8())));

        assertThatThrownBy(() -> client.downloadMedia("token", "file-1", 100));

        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA"))
                .as("an unparsable 403 body must fail closed as permission-denied (no retry), not crash")
                .isEqualTo(1);
    }

    @Test
    void retryBackoffStopsPromptlyWhenTheCallingThreadIsCancelled() throws Exception {
        // 계속 재시도 대상(503)만 큐에 넣는다 - 정상적으로는 MAX_ATTEMPTS까지 Backoff를 거치며 시간이 걸린다.
        for (int i = 0; i < 10; i++) {
            SCRIPT.enqueue("GET_FILE", CannedResponse.json(503, "{\"error\":{\"message\":\"server error\"}}"));
        }
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                client.getFile("token", "file-1");
            } catch (RuntimeException ignored) {
                // 취소/예외 어느 쪽이든 여기서는 "빨리 끝났는가"만 확인한다.
            }
        });
        worker.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50); // 첫 시도가 이미 실패해 Backoff 대기에 들어갈 시간을 준다.
        long cancelledAt = System.nanoTime();
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).as("cancelled call must stop promptly, not run all bounded retries").isFalse();
        long elapsedMs = (System.nanoTime() - cancelledAt) / 1_000_000;
        assertThat(elapsedMs).as("must stop well before the full retry budget would have elapsed").isLessThan(2_000);
    }

    @Test
    void downloadMediaStopsPromptlyWhenCancelledDuringAnActiveSlowTransfer() throws Exception {
        // M08 Review 교정 항목 5 - 이전 취소 Test는 Backoff 대기 중 취소만 증명했다. 이번에는 실제로
        // Byte가 조금씩 도착하는 도중(Active Transfer)에 취소되는 경로를 증명한다.
        byte[] body = new byte[40];
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.slowBytes(200, "application/octet-stream", body, 10, 300));
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                client.downloadMedia("token", "file-1", 1000);
            } catch (RuntimeException ignored) {
                // 취소/예외 어느 쪽이든 여기서는 "빨리 끝났는가"만 확인한다.
            }
        });
        worker.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150); // 첫 Chunk는 이미 도착했고, 두 번째 Chunk를 기다리는 도중이어야 한다.
        long cancelledAt = System.nanoTime();
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).as("an active, in-progress transfer must stop promptly when cancelled").isFalse();
        long elapsedMs = (System.nanoTime() - cancelledAt) / 1_000_000;
        assertThat(elapsedMs).as("must not wait for all remaining slow chunks to finish").isLessThan(2_000);
    }

    @Test
    void operationDeadlineStopsRetryingBeforeTheBoundedRetryBudgetIsExhausted() {
        // M08 Review 교정 항목 5 - 재시도 예산(MAX_ATTEMPTS)이 남아있어도 전체 작업 Deadline이 지나면 멈춘다.
        // 이 Test 전용의 매우 짧은 Deadline을 가진 별도 Client를 직접 구성한다(같은 Mock Server RestClient를 공유).
        GoogleDriveClient shortDeadlineClient = new GoogleDriveClient(googleDriveRestClient, objectMapper, 50);
        for (int i = 0; i < 5; i++) {
            SCRIPT.enqueue("GET_FILE", CannedResponse.json(503, "{\"error\":{\"message\":\"server error\"}}"));
        }

        long start = System.nanoTime();
        assertThatThrownBy(() -> shortDeadlineClient.getFile("token", "file-1"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("must stop at the operation deadline rather than exhausting all bounded retries")
                .isLessThan(2_000);
        assertThat(SCRIPT.callCount("GET_FILE"))
                .as("the 50ms deadline must cut off before all 3 bounded attempts run").isLessThan(3);
    }

    // ------------------------------------------------------------------
    // 13. Credential/Content Canary가 Log/예외/Metadata 어디에도 남지 않는다.
    // ------------------------------------------------------------------

    @Test
    void noSyntheticCredentialOrContentCanaryLeaksIntoLogsOrExceptions() {
        long sourceId = createSource("owner-canary");
        String tokenCanary = "TOKEN-CANARY-" + UUID.randomUUID();
        String contentCanary = "CONTENT-CANARY-" + UUID.randomUUID();
        fakeSourceTokenStore.put(sourceId, "owner-canary", tokenCanary);
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v1", false, true, "application/pdf")));
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v1", false, true, "application/pdf")));
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.bytes(200, "application/pdf", contentCanary.getBytes(UTF8())));

        SourceContentResult result = connector.fetchContent(userContext("owner-canary"), sourceId, "file-1", "v1");
        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.VERIFIED);

        boolean tokenLeaked = logCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message != null && message.contains(tokenCanary));
        boolean contentLeaked = logCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message != null && message.contains(contentCanary));
        assertThat(tokenLeaked).as("access token must never appear in logs").isFalse();
        assertThat(contentLeaked).as("document content must never appear in logs").isFalse();
    }

    // ------------------------------------------------------------------
    // 14. Source Connection 상태 확인(M08 Review 교정 항목 7) - 없거나/비활성인
    //    Connection에 대해서는 Google 호출 자체가 없어야 한다.
    // ------------------------------------------------------------------

    @Test
    void fetchContentFailsClosedWhenTheSourceConnectionIsNotActive() {
        long sourceId = createSource("owner-inactive", "DISABLED");
        fakeSourceTokenStore.put(sourceId, "owner-inactive", "token");

        SourceContentResult result = connector.fetchContent(userContext("owner-inactive"), sourceId, "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.ACCESS_DENIED);
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    @Test
    void getMetadataFailsClosedWhenTheSourceConnectionIsNotActive() {
        long sourceId = createSource("owner-inactive-metadata", "DISABLED");
        fakeSourceTokenStore.put(sourceId, "owner-inactive-metadata", "token");

        assertThatThrownBy(() -> connector.getMetadata(sourceId, "file-1")).isInstanceOf(SourceSyncException.class);
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    // ------------------------------------------------------------------
    // 15. Credential 결합/만료/Scope 사전 검사(M08 Review 교정 항목 7) - 하나라도
    //    실패하면 Google 호출 0회로 Fail Closed 한다.
    // ------------------------------------------------------------------

    @Test
    void fetchContentFailsClosedWhenTheStoredCredentialIsBoundToADifferentSubjectThanTheOwner() {
        long sourceId = createSource("owner-wrong-binding");
        fakeSourceTokenStore.put(sourceId, "someone-else", "token"); // sourceId의 DB Owner는 owner-wrong-binding인데 결합된 Subject는 다르다.

        SourceContentResult result = connector.fetchContent(userContext("owner-wrong-binding"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome())
                .as("a credential bound to a different subject must never be used, even if the DB owner check alone would pass")
                .isEqualTo(SourceContentOutcome.CREDENTIAL_NOT_BOUND_TO_USER);
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    @Test
    void fetchContentFailsClosedWhenTheStoredCredentialIsExpired() {
        long sourceId = createSource("owner-expired");
        fakeSourceTokenStore.putExpiring(sourceId, "owner-expired", "token", Instant.now().minusSeconds(3600));

        SourceContentResult result = connector.fetchContent(userContext("owner-expired"), sourceId, "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.MISSING_CREDENTIAL);
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    @Test
    void fetchContentSucceedsWhenTheStoredCredentialHasAFutureExpiry() {
        long sourceId = createSource("owner-not-yet-expired");
        fakeSourceTokenStore.putExpiring(sourceId, "owner-not-yet-expired", "token", Instant.now().plusSeconds(3600));
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v1", false, true, "application/pdf")));
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v1", false, true, "application/pdf")));
        SCRIPT.enqueue("DOWNLOAD_MEDIA", CannedResponse.bytes(200, "application/pdf", "bytes".getBytes(UTF8())));

        SourceContentResult result = connector.fetchContent(userContext("owner-not-yet-expired"), sourceId, "file-1",
                "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.VERIFIED);
    }

    @Test
    void fetchContentFailsClosedWhenTheStoredCredentialLacksTheRequiredScope() {
        long sourceId = createSource("owner-insufficient-scope");
        fakeSourceTokenStore.putWithScopes(sourceId, "owner-insufficient-scope", "token",
                List.of("https://www.googleapis.com/auth/drive.file"));

        SourceContentResult result = connector.fetchContent(userContext("owner-insufficient-scope"), sourceId,
                "file-1", "v1");

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.INSUFFICIENT_SCOPE);
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    @Test
    void getMetadataFailsClosedWithAReauthorizationReasonWhenTheOwnerCredentialIsExpired() {
        long sourceId = createSource("owner-metadata-expired");
        fakeSourceTokenStore.putExpiring(sourceId, "owner-metadata-expired", "token",
                Instant.now().minusSeconds(3600));

        assertThatThrownBy(() -> connector.getMetadata(sourceId, "file-1"))
                .isInstanceOf(SourceCredentialException.class)
                .satisfies(e -> assertThat(((SourceCredentialException) e).getReason())
                        .isEqualTo(SourceCredentialException.Reason.REAUTHORIZATION_REQUIRED));
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    @Test
    void getMetadataFailsClosedWithAnInsufficientScopeReasonWhenTheOwnerCredentialLacksScope() {
        long sourceId = createSource("owner-metadata-scope");
        fakeSourceTokenStore.putWithScopes(sourceId, "owner-metadata-scope", "token", List.of());

        assertThatThrownBy(() -> connector.getMetadata(sourceId, "file-1"))
                .isInstanceOf(SourceCredentialException.class)
                .satisfies(e -> assertThat(((SourceCredentialException) e).getReason())
                        .isEqualTo(SourceCredentialException.Reason.INSUFFICIENT_SCOPE));
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    // ------------------------------------------------------------------
    // 16. verifyCurrentMetadata(M10 신규, RAG-011 File Metadata Discovery) -
    //    Content Byte를 절대 요청하지 않고, Format 화이트리스트와 무관하게
    //    지금 이 순간의 Metadata만 재확인한다.
    // ------------------------------------------------------------------

    @Test
    void verifyCurrentMetadataDeniesARequesterWhoIsNotTheSourceOwner() {
        long sourceId = createSource("owner-md-a");
        fakeSourceTokenStore.put(sourceId, "owner-md-a", "owner-md-a-token");

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(userContext("attacker-md-b"),
                sourceId, "file-1");

        assertThat(result.outcome()).isEqualTo(SourceMetadataVerificationOutcome.CREDENTIAL_NOT_BOUND_TO_USER);
        assertThat(result.name()).isNull();
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    @Test
    void verifyCurrentMetadataFailsClosedWhenNoTokenIsStored() {
        long sourceId = createSource("owner-md-missing-token");

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(
                userContext("owner-md-missing-token"), sourceId, "file-1");

        assertThat(result.outcome()).isEqualTo(SourceMetadataVerificationOutcome.MISSING_CREDENTIAL);
        assertThat(SCRIPT.callCount("GET_FILE")).isZero();
    }

    @Test
    void verifyCurrentMetadataReturnsLiveMetadataWithoutEverDownloadingContent() {
        long sourceId = createSource("owner-md-verified");
        fakeSourceTokenStore.put(sourceId, "owner-md-verified", "token");
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v7", false, true, "application/pdf")));

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(userContext("owner-md-verified"),
                sourceId, "file-1");

        assertThat(result.outcome()).isEqualTo(SourceMetadataVerificationOutcome.VERIFIED);
        assertThat(result.name()).isEqualTo("Test File file-1");
        assertThat(result.mimeType()).isEqualTo("application/pdf");
        assertThat(result.sourceVersion()).isEqualTo("v7");
        assertThat(result.downloadable()).isTrue();
        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA"))
                .as("metadata verification must never fetch content, even for a Core-supported format").isZero();
        assertThat(SCRIPT.callCount("EXPORT")).isZero();
    }

    @Test
    void verifyCurrentMetadataSucceedsForANonCoreFormatBecauseVisibilityIsFormatAgnostic() {
        // PNG/ZIP 등은 Content 검색 대상이 아니지만(§2A.7), Metadata 가시성은 형식과 무관하다(§2A.4).
        long sourceId = createSource("owner-md-png");
        fakeSourceTokenStore.put(sourceId, "owner-md-png", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200, googleFileJson("file-1", "v1", false, true, "image/png")));

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(userContext("owner-md-png"),
                sourceId, "file-1");

        assertThat(result.outcome()).isEqualTo(SourceMetadataVerificationOutcome.VERIFIED);
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(SCRIPT.callCount("DOWNLOAD_MEDIA")).isZero();
        assertThat(SCRIPT.callCount("EXPORT")).isZero();
    }

    @Test
    void verifyCurrentMetadataReportsNotDownloadableWithoutTreatingItAsInvisible() {
        // capabilities.canDownload=false는 Content 접근 불가일 뿐 - Metadata 자체는 여전히 노출 대상이다.
        long sourceId = createSource("owner-md-nodownload");
        fakeSourceTokenStore.put(sourceId, "owner-md-nodownload", "token");
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v1", false, false, "application/pdf")));

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(userContext("owner-md-nodownload"),
                sourceId, "file-1");

        assertThat(result.outcome()).isEqualTo(SourceMetadataVerificationOutcome.VERIFIED);
        assertThat(result.downloadable()).isFalse();
    }

    @Test
    void verifyCurrentMetadataFailsClosedWhenTrashed() {
        long sourceId = createSource("owner-md-trashed");
        fakeSourceTokenStore.put(sourceId, "owner-md-trashed", "token");
        SCRIPT.enqueue("GET_FILE",
                CannedResponse.json(200, googleFileJson("file-1", "v1", true, true, "application/pdf")));

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(userContext("owner-md-trashed"),
                sourceId, "file-1");

        assertThat(result.outcome()).isEqualTo(SourceMetadataVerificationOutcome.TRASHED);
    }

    @Test
    void verifyCurrentMetadataFailsClosedWhenGoogleReturnsNotFound() {
        long sourceId = createSource("owner-md-not-found");
        fakeSourceTokenStore.put(sourceId, "owner-md-not-found", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(404, "{\"error\":{\"message\":\"not found\"}}"));

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(userContext("owner-md-not-found"),
                sourceId, "file-1");

        assertThat(result.outcome()).isEqualTo(SourceMetadataVerificationOutcome.NOT_FOUND);
    }

    @Test
    void verifyCurrentMetadataFailsClosedWhenGoogleReturnsUnauthorized() {
        long sourceId = createSource("owner-md-revoked");
        fakeSourceTokenStore.put(sourceId, "owner-md-revoked", "revoked-access-token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(401, "{\"error\":{\"message\":\"invalid credentials\"}}"));

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(userContext("owner-md-revoked"),
                sourceId, "file-1");

        assertThat(result.outcome()).isEqualTo(SourceMetadataVerificationOutcome.MISSING_CREDENTIAL);
        assertThat(SCRIPT.callCount("GET_FILE")).as("401 must not be retried").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 16B. verifyCurrentMetadata 응답 결합/검증(M10 후속 교정) - Google 응답을 요청한
    //    파일에 결합하고, 필수 필드(Identity/mimeType/version/trashed/modifiedTime)를
    //    노출 전에 검증한다. 원본 응답 본문은 절대 옮기지 않는다.
    // ------------------------------------------------------------------

    @Test
    void verifyCurrentMetadataFailsClosedWhenTheReturnedFileIdDoesNotMatchTheRequestedFile() {
        long sourceId = createSource("owner-md-wrong-id");
        fakeSourceTokenStore.put(sourceId, "owner-md-wrong-id", "token");
        // "file-1"을 요청했지만 Google이 다른 File("file-999")의 Metadata를 반환한다(Malformed/오배선).
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200,
                "{\"id\":\"file-999\",\"name\":\"Test File file-999\",\"mimeType\":\"application/pdf\","
                        + "\"version\":\"v1\",\"modifiedTime\":\"2026-09-13T00:00:00.000Z\",\"trashed\":false,"
                        + "\"capabilities\":{\"canDownload\":true}}"));

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(userContext("owner-md-wrong-id"),
                sourceId, "file-1");

        assertThat(result.outcome())
                .as("a response identifying a different file must never be bound to the requested document")
                .isEqualTo(SourceMetadataVerificationOutcome.FAILED);
        assertThat(result.name()).isNull();
    }

    @Test
    void verifyCurrentMetadataReturnsAccessUnknownWhenTrashedStateIsMissing() {
        long sourceId = createSource("owner-md-missing-trashed");
        fakeSourceTokenStore.put(sourceId, "owner-md-missing-trashed", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200,
                "{\"id\":\"file-1\",\"name\":\"Test File file-1\",\"mimeType\":\"application/pdf\","
                        + "\"version\":\"v1\",\"modifiedTime\":\"2026-09-13T00:00:00.000Z\","
                        + "\"capabilities\":{\"canDownload\":true}}"));

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(
                userContext("owner-md-missing-trashed"), sourceId, "file-1");

        assertThat(result.outcome())
                .as("an unknown trash state must not be assumed to mean the document is not trashed")
                .isEqualTo(SourceMetadataVerificationOutcome.ACCESS_UNKNOWN);
    }

    @Test
    void verifyCurrentMetadataFailsClosedWhenModifiedTimeIsMissingOrMalformed() {
        long sourceId = createSource("owner-md-bad-modified-time");
        fakeSourceTokenStore.put(sourceId, "owner-md-bad-modified-time", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200,
                "{\"id\":\"file-1\",\"name\":\"Test File file-1\",\"mimeType\":\"application/pdf\","
                        + "\"version\":\"v1\",\"trashed\":false,\"capabilities\":{\"canDownload\":true}}"));

        SourceMetadataVerificationResult missing = connector.verifyCurrentMetadata(
                userContext("owner-md-bad-modified-time"), sourceId, "file-1");

        assertThat(missing.outcome()).as("required modification metadata must be present before VERIFIED")
                .isEqualTo(SourceMetadataVerificationOutcome.FAILED);

        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200,
                "{\"id\":\"file-1\",\"name\":\"Test File file-1\",\"mimeType\":\"application/pdf\","
                        + "\"version\":\"v1\",\"modifiedTime\":\"not-a-timestamp\",\"trashed\":false,"
                        + "\"capabilities\":{\"canDownload\":true}}"));

        SourceMetadataVerificationResult malformed = connector.verifyCurrentMetadata(
                userContext("owner-md-bad-modified-time"), sourceId, "file-1");

        assertThat(malformed.outcome()).as("an unparsable modification time must not be silently accepted")
                .isEqualTo(SourceMetadataVerificationOutcome.FAILED);
    }

    @Test
    void verifyCurrentMetadataFailsClosedWhenMimeTypeIsMissing() {
        long sourceId = createSource("owner-md-missing-mime");
        fakeSourceTokenStore.put(sourceId, "owner-md-missing-mime", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200,
                "{\"id\":\"file-1\",\"name\":\"Test File file-1\",\"version\":\"v1\","
                        + "\"modifiedTime\":\"2026-09-13T00:00:00.000Z\",\"trashed\":false,"
                        + "\"capabilities\":{\"canDownload\":true}}"));

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(
                userContext("owner-md-missing-mime"), sourceId, "file-1");

        assertThat(result.outcome()).isEqualTo(SourceMetadataVerificationOutcome.FAILED);
    }

    @Test
    void verifyCurrentMetadataReturnsAccessUnknownWhenVersionIsMissing() {
        long sourceId = createSource("owner-md-missing-version");
        fakeSourceTokenStore.put(sourceId, "owner-md-missing-version", "token");
        SCRIPT.enqueue("GET_FILE", CannedResponse.json(200,
                "{\"id\":\"file-1\",\"name\":\"Test File file-1\",\"mimeType\":\"application/pdf\","
                        + "\"modifiedTime\":\"2026-09-13T00:00:00.000Z\",\"trashed\":false,"
                        + "\"capabilities\":{\"canDownload\":true}}"));

        SourceMetadataVerificationResult result = connector.verifyCurrentMetadata(
                userContext("owner-md-missing-version"), sourceId, "file-1");

        assertThat(result.outcome())
                .as("an unknown version must not be assumed to be the expected/current one")
                .isEqualTo(SourceMetadataVerificationOutcome.ACCESS_UNKNOWN);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long createSource(String ownerSubject) {
        return createSource(ownerSubject, "ACTIVE");
    }

    private long createSource(String ownerSubject, String status) {
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", status,
                "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        return connection.getId();
    }

    private static UserContext userContext(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of());
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static java.nio.charset.Charset UTF8() {
        return StandardCharsets.UTF_8;
    }

    private static String googleFileJson(String id, String version, boolean trashed, boolean canDownload,
            String mimeType) {
        return "{\"id\":\"" + id + "\",\"name\":\"Test File " + id + "\",\"mimeType\":\"" + mimeType
                + "\",\"version\":\"" + version + "\","
                + "\"modifiedTime\":\"2026-09-13T00:00:00.000Z\",\"trashed\":" + trashed
                + ",\"capabilities\":{\"canDownload\":" + canDownload + "}}";
    }

    // ------------------------------------------------------------------
    // Test Double - Production Stub이 아니다(SourceTokenStore Class Javadoc
    // 참고 - M08은 Production 구현체를 만들지 않는다).
    // ------------------------------------------------------------------

    static class FakeSourceTokenStore implements SourceTokenStore {
        private final Map<Long, TokenEnvelope> tokens = new ConcurrentHashMap<>();
        private final Set<Long> failOnLoad = ConcurrentHashMap.newKeySet();

        /** 정상 경로 - 실제로 그 sourceId를 소유한 Subject에게 결합된, 필요한 Scope를 가진, 만료되지 않은 Token. */
        void put(long sourceId, String boundSubject, String accessToken) {
            tokens.put(sourceId, new TokenEnvelope(boundSubject, accessToken, null, null,
                    List.of(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY)));
        }

        void putExpiring(long sourceId, String boundSubject, String accessToken, Instant expiresAt) {
            tokens.put(sourceId, new TokenEnvelope(boundSubject, accessToken, null, expiresAt,
                    List.of(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY)));
        }

        void putWithScopes(long sourceId, String boundSubject, String accessToken, List<String> scopes) {
            tokens.put(sourceId, new TokenEnvelope(boundSubject, accessToken, null, null, scopes));
        }

        void failOnLoad(long sourceId) {
            failOnLoad.add(sourceId);
        }

        @Override
        public void save(Long sourceId, TokenEnvelope token) {
            tokens.put(sourceId, token);
        }

        @Override
        public Optional<TokenEnvelope> load(Long sourceId) {
            if (failOnLoad.contains(sourceId)) {
                throw new IllegalStateException("simulated credential decryption failure");
            }
            return Optional.ofNullable(tokens.get(sourceId));
        }

        @Override
        public void delete(Long sourceId) {
            tokens.remove(sourceId);
        }

        void reset() {
            tokens.clear();
            failOnLoad.clear();
        }
    }

    @TestConfiguration
    static class FakeTokenStoreConfig {
        // M08 MVP OAuth 후속 교정: GoogleTokenStoreAdapter가 이제 실제 SourceTokenStore
        // Bean으로 항상 등록된다 - @Primary로 이 Fake를 명시적으로 우선시켜, 이 Contract
        // Test가 계속 결정론적으로 이 FakeSourceTokenStore만 받게 한다(Class Javadoc의
        // "Production Stub이 아니다"라는 서술은 그대로 유효하다 - 실제 Production Bean과의
        // 충돌 방지를 위한 순수 배선 문제일 뿐이다).
        @Bean
        @Primary
        FakeSourceTokenStore fakeSourceTokenStore() {
            return new FakeSourceTokenStore();
        }
    }

    // ------------------------------------------------------------------
    // Local Mock Google Drive HTTP Server - JDK 내장 HttpServer만 쓴다(새
    // Dependency 없음). 각 Test가 호출 종류별로 응답을 미리 큐에 넣는다.
    // ------------------------------------------------------------------

    record CannedResponse(int status, String contentType, byte[] body, int chunkSize, long chunkDelayMillis) {
        static CannedResponse json(int status, String json) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return new CannedResponse(status, "application/json", bytes, bytes.length, 0L);
        }

        static CannedResponse bytes(int status, String contentType, byte[] body) {
            return new CannedResponse(status, contentType, body, body.length, 0L);
        }

        /** M08 Review 교정 항목 5 전용 - Chunk 사이에 지연을 둬 "느리게 조금씩 오는" 응답을 재현한다. */
        static CannedResponse slowBytes(int status, String contentType, byte[] body, int chunkSize,
                long chunkDelayMillis) {
            return new CannedResponse(status, contentType, body, chunkSize, chunkDelayMillis);
        }
    }

    static final class ScriptedGoogleServer {
        private final Map<String, Deque<CannedResponse>> queues = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
        private final Map<String, String> lastQueries = new ConcurrentHashMap<>();

        void enqueue(String kind, CannedResponse response) {
            queues.computeIfAbsent(kind, k -> new ArrayDeque<>()).add(response);
        }

        long callCount(String kind) {
            return counts.getOrDefault(kind, new AtomicLong()).get();
        }

        String lastQuery(String kind) {
            return lastQueries.get(kind);
        }

        void reset() {
            queues.clear();
            counts.clear();
            lastQueries.clear();
        }

        void handle(HttpExchange exchange) throws IOException {
            try {
                URI uri = exchange.getRequestURI();
                String kind = classify(uri);
                counts.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
                lastQueries.put(kind, uri.getQuery() == null ? "" : uri.getQuery());
                Deque<CannedResponse> queue = queues.get(kind);
                CannedResponse response = (queue == null || queue.isEmpty())
                        ? CannedResponse.json(500, "{\"error\":{\"message\":\"unscripted call: " + kind + "\"}}")
                        : queue.poll();
                exchange.getResponseHeaders().add("Content-Type", response.contentType());
                exchange.sendResponseHeaders(response.status(), response.body().length);
                writeBody(exchange, response);
            } finally {
                exchange.close();
            }
        }

        private static void writeBody(HttpExchange exchange, CannedResponse response) throws IOException {
            try (OutputStream os = exchange.getResponseBody()) {
                byte[] body = response.body();
                if (response.chunkDelayMillis() <= 0 || body.length == 0) {
                    os.write(body);
                    return;
                }
                int offset = 0;
                int chunkSize = Math.max(1, response.chunkSize());
                while (offset < body.length) {
                    int len = Math.min(chunkSize, body.length - offset);
                    os.write(body, offset, len);
                    os.flush();
                    offset += len;
                    if (offset < body.length) {
                        sleepQuietly(response.chunkDelayMillis());
                    }
                }
            }
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static String classify(URI uri) {
            String path = uri.getPath();
            String query = uri.getQuery() == null ? "" : uri.getQuery();
            if (path.equals("/drive/v3/changes/startPageToken")) {
                return "START_PAGE_TOKEN";
            }
            if (path.equals("/drive/v3/changes")) {
                return "LIST_CHANGES";
            }
            if (path.equals("/drive/v3/files")) {
                return "LIST_FILES";
            }
            if (path.matches("/drive/v3/files/[^/]+/permissions")) {
                return "LIST_PERMISSIONS";
            }
            if (path.matches("/drive/v3/files/[^/]+/export")) {
                return "EXPORT";
            }
            if (path.matches("/drive/v3/files/[^/]+") && query.contains("alt=media")) {
                return "DOWNLOAD_MEDIA";
            }
            if (path.matches("/drive/v3/files/[^/]+")) {
                return "GET_FILE";
            }
            return "UNKNOWN:" + path;
        }
    }
}
