package com.sdv.rag.application;

import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.source.application.GoogleDriveOAuthService;
import com.sdv.source.application.SourceConnectionService;
import com.sdv.source.application.SourceSharingService;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * M11 후속 교정 검증(이 작업 지시사항 2번, "disconnect then same-account reconnect while
 * old embedding work waits") - {@link IndexOrchestratorDisconnectConcurrencyTest}(MVP-18)와
 * 같은 기법(실제 Proxied Service + 실제 Testcontainers PostgreSQL + {@link CountDownLatch},
 * Sleep 없음)을 재사용하되, Disconnect에서 멈추지 않고 실제로 같은 계정으로 재연결까지
 * 완료한다 - "ACTIVE -> Disconnect -> 재연결로 다시 ACTIVE"가 옛 연산을 되살리지 못하는지,
 * 그리고 그 재연결 자체가 새 색인 작업을 Bounded 예약하는지(1번 "connect a successful
 * verified same-account reconnect to bounded scheduling of fresh eligible work")를 함께
 * 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.secrets.google-client-id=test-client-id",
        "sdv.secrets.google-client-secret=test-client-secret",
        "sdv.secrets.google-redirect-uri=http://localhost:8080/api/admin/sources/google/callback",
        // 합성(Synthetic) Test 전용 32-byte AES Key - 실제 Secret이 아니다.
        "sdv.secrets.token-encryption-key=354C9a3yRzwUV/1rt1s8AX4yflicnzQj0Z8aItHYw3w=",
        "sdv.secrets.token-encryption-key-id=test-key-v1"
})
class IndexOrchestratorReconnectRaceConcurrencyTest {

    private static final long BOUND_SECONDS = 10;
    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();
    private static final HttpServer MOCK_OAUTH_SERVER = startMockServer();

    @DynamicPropertySource
    static void registerMockServerUris(DynamicPropertyRegistry registry) {
        registry.add("sdv.google-oauth.token-uri",
                () -> "http://localhost:" + MOCK_OAUTH_SERVER.getAddress().getPort() + "/token");
        registry.add("sdv.google-drive.api-base-url",
                () -> "http://localhost:" + MOCK_OAUTH_SERVER.getAddress().getPort());
        registry.add("sdv.google-oauth.revoke-uri",
                () -> "http://localhost:" + MOCK_OAUTH_SERVER.getAddress().getPort() + "/revoke");
    }

    private static HttpServer startMockServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/token", IndexOrchestratorReconnectRaceConcurrencyTest::handleTokenRequest);
            server.createContext("/drive/v3/about", IndexOrchestratorReconnectRaceConcurrencyTest::handleAboutRequest);
            server.createContext("/revoke", IndexOrchestratorReconnectRaceConcurrencyTest::handleRevokeRequest);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start local mock Google OAuth server", e);
        }
    }

    private static void handleRevokeRequest(HttpExchange exchange) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
        } finally {
            exchange.close();
        }
    }

    private static void handleTokenRequest(HttpExchange exchange) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            String json = "{\"access_token\":\"mock-access-token\",\"refresh_token\":\"mock-refresh-token\","
                    + "\"expires_in\":3600,\"token_type\":\"Bearer\","
                    + "\"scope\":\"https://www.googleapis.com/auth/drive.readonly\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    private static void handleAboutRequest(HttpExchange exchange) throws IOException {
        try {
            String json = "{\"user\":{\"permissionId\":\"account-a\",\"emailAddress\":\"mock@example.com\"}}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    @Autowired
    private IndexOrchestrator indexOrchestrator;
    @Autowired
    private SourceConnectionService sourceConnectionService;
    @Autowired
    private GoogleDriveOAuthService googleDriveOAuthService;
    @Autowired
    private SourceSharingService sourceSharingService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private DocumentShareJpaRepository documentShareJpaRepository;
    @Autowired
    private DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository;
    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired
    private AiUsagePolicyJpaRepository aiUsagePolicyJpaRepository;

    @MockitoSpyBean
    private GoogleDriveConnector googleDriveConnector;
    @MockitoBean
    private DocumentParsingClient documentParsingClient;

    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @BeforeEach
    void ensureLocalAiUsageIsAllowedForInternalClassification() {
        if (aiUsagePolicyJpaRepository.findById("INTERNAL").isEmpty()) {
            aiUsagePolicyJpaRepository.saveAndFlush(new AiUsagePolicyEntity("INTERNAL", "LOCAL_ONLY", false));
        }
    }

    @Test
    void aReconnectCompletedWhileTheOldFetchIsPausedDoesNotRehabilitateTheStaleOperationAndSchedulesFreshWork()
            throws Exception {
        String owner = "owner-" + OWNER_SEQUENCE.incrementAndGet();
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE",
                "FULL", owner);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        long sourceId = connection.getId();
        SourceDocumentEntity document = new SourceDocumentEntity(sourceId, "ext-doc-1", "Doc.pdf", "application/pdf",
                "v1", null, "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        long documentId = document.getId();
        documentShareJpaRepository.saveAndFlush(
                new DocumentShareEntity(owner, sourceId, documentId, "ALL_AUTHENTICATED", "INTERNAL", "VIEW",
                        Instant.now()));

        // 최초 연결 - 재연결이 비교할 Provider Identity(account-a)를 채택시킨다.
        GoogleDriveOAuthService.AuthorizeResult firstAuthorize = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        googleDriveOAuthService.handleCallback(extractQueryParam(firstAuthorize.authorizationUrl(), "state"),
                "first-code", firstAuthorize.browserBinding());

        // Post-parse Recheck는 이 Test의 관심사가 아니다(Group 2의 다른 Test가 별도로 다룬다) -
        // Google Network 호출 없이 항상 VERIFIED로 고정한다.
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(sourceId), any()))
                .thenReturn(com.sdv.source.domain.SourceMetadataVerificationResult.verified("Doc.pdf",
                        "application/pdf", "v1", Instant.now(), false));

        CountDownLatch contentFetchStarted = new CountDownLatch(1);
        CountDownLatch reconnectCompleted = new CountDownLatch(1);
        doAnswer(invocation -> {
            contentFetchStarted.countDown();
            boolean reconnectFinishedInTime = reconnectCompleted.await(BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(reconnectFinishedInTime).as("reconnect must complete within the bounded wait").isTrue();
            return SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false);
        }).when(googleDriveConnector).fetchContent(any(), eq(sourceId), any(), eq("v1"));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(com.sdv.rag.domain.IndexOutcome.success("pdfminer-1", "1", "bge-m3:567m",
                        List.of(validChunk())));

        Future<IndexProcessingOutcome> indexingFuture = executor.submit(() -> indexOrchestrator.process(documentId));
        assertThat(contentFetchStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 별도의, 독립적으로 Commit되는 실제 Transaction들 - 옛 Fetch가 멈춰있는 동안 Disconnect
        // 후 같은 계정으로 재연결까지 완전히 끝난다.
        sourceConnectionService.disconnect(sourceId, owner);
        GoogleDriveOAuthService.AuthorizeResult reconnectAuthorize = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        GoogleDriveOAuthService.CallbackOutcome reconnectOutcome = googleDriveOAuthService.handleCallback(
                extractQueryParam(reconnectAuthorize.authorizationUrl(), "state"), "reconnect-code",
                reconnectAuthorize.browserBinding());
        assertThat(reconnectOutcome.success()).isTrue();
        reconnectCompleted.countDown();

        IndexProcessingOutcome outcome = indexingFuture.get(BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(outcome)
                .as("a fetch begun before disconnect must not be rehabilitated by a later reconnect, even a "
                        + "same-account one")
                .isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);

        List<com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity> rows =
                documentEmbeddingJpaRepository.findAll().stream()
                        .filter(row -> row.getDocumentId().equals(documentId)).toList();
        assertThat(rows).as("the stale operation must never publish embeddings after an intervening reconnect")
                .isEmpty();

        // 1번 - 재연결이 이 문서의 Bounded 재색인을 예약했다(새 PENDING INDEX_REQUESTED Outbox 행).
        String partitionKey = "source:" + sourceId + ":doc:ext-doc-1";
        boolean scheduled = outboxEventJpaRepository.findAll().stream()
                .anyMatch(e -> partitionKey.equals(e.getPartitionKey())
                        && "INDEX_REQUESTED".equals(e.getEventType())
                        && OutboxEventEntity.STATUS_PENDING.equals(e.getStatus()));
        assertThat(scheduled)
                .as("a successful same-account reconnect must schedule fresh bounded reindexing for the "
                        + "eligible previously-shared document")
                .isTrue();
    }

    private static String extractQueryParam(String url, String name) {
        String query = URI.create(url).getRawQuery();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("query param not found: " + name);
    }

    private static com.sdv.rag.domain.EmbeddingChunk validChunk() {
        return new com.sdv.rag.domain.EmbeddingChunk(0, com.sdv.rag.domain.LocatorType.PAGE, "1",
                java.util.Collections.nCopies(
                        com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS,
                        0.01f),
                "a".repeat(64));
    }
}
