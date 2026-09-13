package com.sdv.source.application;

import com.sdv.source.application.port.SourceTokenStore;
import com.sdv.source.application.port.TokenEnvelope;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M08 MVP OAuth 검증 ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - "Exercise a
 * complete mocked authorize→callback→store→connector-read flow through the actual
 * adapter, not FakeSourceTokenStore alone." 이 Class 하나가 그 요구를 그대로 충족한다:
 * 실제 {@link GoogleDriveOAuthService}가 실제 {@link GoogleOAuthStateStore}로 State를
 * 검증하고, 실제 Google Token Endpoint를 대신하는 Local Mock HTTP Server와 실제
 * Spring Security Token Response Client로 Code를 교환하고, 실제 {@link
 * GoogleTokenStoreAdapter}/{@link com.sdv.source.infrastructure.google.GoogleTokenService}로
 * 암호화해 저장한다 - 마지막에 같은 실제 {@link SourceTokenStore} Port(Connector가 쓰는
 * 것과 동일한 Port)로 다시 읽어(Connector-Read) 왕복을 증명한다.
 *
 * <p>실제 Google에 대한 검증은 아님을 명시한다 - Mock Server 계약 검증이다(Class
 * Javadoc, {@code GoogleDriveConnectorContractTest}와 동일한 성격).</p>
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
class GoogleDriveOAuthServiceTest {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();
    private static final HttpServer MOCK_TOKEN_SERVER = startMockServer();
    private static final BlockingQueue<String> LAST_REQUEST_BODY = new ArrayBlockingQueue<>(8);
    private static volatile String scriptedScope = "https://www.googleapis.com/auth/drive.readonly";
    private static volatile boolean scriptedOmitRefreshToken = false;

    @DynamicPropertySource
    static void registerMockTokenUri(DynamicPropertyRegistry registry) {
        registry.add("sdv.google-oauth.token-uri",
                () -> "http://localhost:" + MOCK_TOKEN_SERVER.getAddress().getPort() + "/token");
    }

    private static HttpServer startMockServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/token", GoogleDriveOAuthServiceTest::handleTokenRequest);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start local mock Google token server", e);
        }
    }

    private static void handleTokenRequest(HttpExchange exchange) throws IOException {
        try {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            LAST_REQUEST_BODY.offer(new String(requestBody, StandardCharsets.UTF_8));
            String refreshTokenField = scriptedOmitRefreshToken ? "" : "\"refresh_token\":\"mock-refresh-token\",";
            String json = "{\"access_token\":\"mock-access-token\"," + refreshTokenField
                    + "\"expires_in\":3600,\"token_type\":\"Bearer\",\"scope\":\"" + scriptedScope + "\"}";
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
    private GoogleDriveOAuthService googleDriveOAuthService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceTokenStore sourceTokenStore; // 실제 GoogleTokenStoreAdapter Bean - Fake가 아니다.
    @Autowired
    private com.sdv.source.infrastructure.google.GoogleTokenService googleTokenService;

    @BeforeEach
    void resetScript() {
        scriptedScope = "https://www.googleapis.com/auth/drive.readonly";
        scriptedOmitRefreshToken = false;
        LAST_REQUEST_BODY.clear();
    }

    @AfterEach
    void resetAfter() {
        scriptedScope = "https://www.googleapis.com/auth/drive.readonly";
        scriptedOmitRefreshToken = false;
    }

    @Test
    void fullAuthorizeCallbackStoreAndConnectorReadFlowSucceedsThroughTheRealAdapter() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");

        GoogleDriveOAuthService.AuthorizeResult authorizeResult = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(authorizeResult.authorizationUrl(), "state");
        assertThat(state).isNotBlank();

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state,
                "authorization-code-from-google", authorizeResult.browserBinding());

        assertThat(outcome.success()).isTrue();

        // Connector-Read: GoogleDriveConnector가 실제로 호출하는 것과 같은 SourceTokenStore.load().
        Optional<TokenEnvelope> stored = sourceTokenStore.load(sourceId);
        assertThat(stored).isPresent();
        assertThat(stored.get().boundSubject()).isEqualTo(owner);
        assertThat(stored.get().accessToken()).isEqualTo("mock-access-token");
        assertThat(stored.get().refreshToken()).isEqualTo("mock-refresh-token");
        assertThat(stored.get().scopes()).contains("https://www.googleapis.com/auth/drive.readonly");

        SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(connection.getTokenRef()).isNotBlank();

        // PKCE code_verifier가 실제로 Token 요청 본문에 실렸는지 확인한다(Spring Security 라이브러리가
        // AbstractRestClientOAuth2AccessTokenResponseClient를 통해 자동으로 붙인다).
        String requestBody = LAST_REQUEST_BODY.poll();
        assertThat(requestBody).contains("code_verifier=");
        assertThat(requestBody).contains("code=authorization-code-from-google");
    }

    /**
     * {@link com.sdv.source.infrastructure.google.GoogleTokenService#load}의 Bounded Refresh도
     * (Mockito가 아니라) 같은 Local Mock Token Server를 통해 실제 {@code
     * RestClientRefreshTokenTokenResponseClient} 왕복으로 한 번 더 검증한다 - Authorization-Code
     * 교환 경로만 실제 RestClient/Converter로 검증하고 Refresh 경로는 Mock으로만 검증하면,
     * 이 둘이 공유하는 RestClient 설정(Class Javadoc의 {@code GoogleOAuthRestClientConfig}
     * Converter 배선)의 결함을 놓칠 수 있다.
     */
    @Test
    void loadRefreshesThroughTheRealTokenResponseClientAgainstTheMockServer() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        googleTokenService.store(sourceId, new TokenEnvelope(owner, "stale-access-token", "stale-refresh-token",
                java.time.Instant.now().minusSeconds(10), java.util.List.of(scriptedScope)));

        Optional<TokenEnvelope> refreshed = googleTokenService.load(sourceId);

        assertThat(refreshed).isPresent();
        assertThat(refreshed.get().accessToken()).isEqualTo("mock-access-token");
        assertThat(refreshed.get().refreshToken()).isEqualTo("mock-refresh-token");
    }

    @Test
    void handleCallbackFailsClosedAndCallsGoogleZeroTimesWhenTheBrowserBindingDoesNotMatch() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        GoogleDriveOAuthService.AuthorizeResult authorizeResult = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(authorizeResult.authorizationUrl(), "state");

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state, "some-code",
                "wrong-browser-binding");

        assertThat(outcome.success()).isFalse();
        assertThat(sourceTokenStore.load(sourceId)).isEmpty();
        assertThat(LAST_REQUEST_BODY.poll()).as("a mismatched browser binding must never reach the token endpoint")
                .isNull();
    }

    @Test
    void handleCallbackFailsClosedWhenTheGrantedScopeIsMissing() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        GoogleDriveOAuthService.AuthorizeResult authorizeResult = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(authorizeResult.authorizationUrl(), "state");
        scriptedScope = "https://www.googleapis.com/auth/drive.file"; // Required Scope가 아니다.

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state, "some-code",
                authorizeResult.browserBinding());

        assertThat(outcome.success())
                .as("missing the required scope must never create a usable connection")
                .isFalse();
        assertThat(sourceTokenStore.load(sourceId)).isEmpty();
    }

    /**
     * M08 3-issue 교정(이 작업 지시사항 3번) - "For authorization-code responses without a
     * refresh token... do not label the durable offline connection successful." 이 Source는
     * 이번이 최초 연결이라 대체할 기존 Credential조차 없다 - Refresh Token 없이는 Callback을
     * 성공으로 표시하지 않는다.
     */
    @Test
    void handleCallbackFailsClosedWhenTheCodeExchangeResponseOmitsARefreshTokenAndNoCredentialExistedBefore() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        GoogleDriveOAuthService.AuthorizeResult authorizeResult = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(authorizeResult.authorizationUrl(), "state");
        scriptedOmitRefreshToken = true;

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state, "some-code",
                authorizeResult.browserBinding());

        assertThat(outcome.success())
                .as("a code exchange response without a refresh token must never be reported as a successful "
                        + "durable offline connection")
                .isFalse();
        assertThat(sourceTokenStore.load(sourceId)).isEmpty();
    }

    /**
     * 같은 요구사항, 두 번째 절반: "leaving the prior credential unchanged." 이미 연결돼 있던
     * Source를 재인증(Reconnect)하는데 이번 응답에 Refresh Token이 없다 - 같은 SDV Owner라는
     * 사실만으로 같은 Google 계정이라고 가정해 기존 Refresh Token을 재사용하지 않는다("Matching
     * an SDV owner alone must not be treated as proof that two Google accounts are identical").
     * 대신 재인증 필요로 실패 처리하고, 기존에 저장돼 있던 Credential은 그대로 둔다.
     */
    @Test
    void handleCallbackFailsClosedAndPreservesTheExistingCredentialWhenAReconnectResponseOmitsARefreshToken() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        googleTokenService.store(sourceId, new TokenEnvelope(owner, "previously-stored-access-token",
                "previously-stored-refresh-token", java.time.Instant.now().plusSeconds(3600),
                java.util.List.of("https://www.googleapis.com/auth/drive.readonly")));

        GoogleDriveOAuthService.AuthorizeResult authorizeResult = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(authorizeResult.authorizationUrl(), "state");
        scriptedOmitRefreshToken = true;

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state, "some-code",
                authorizeResult.browserBinding());

        assertThat(outcome.success())
                .as("must not silently reuse a previously stored refresh token just because the SDV owner matches")
                .isFalse();
        Optional<TokenEnvelope> stillStored = sourceTokenStore.load(sourceId);
        assertThat(stillStored)
                .as("the prior credential must be left completely unchanged, not cleared or overwritten")
                .isPresent();
        assertThat(stillStored.get().accessToken()).isEqualTo("previously-stored-access-token");
        assertThat(stillStored.get().refreshToken()).isEqualTo("previously-stored-refresh-token");
    }

    @Test
    void handleCallbackDoesNotResurrectATokenForASourceThatWasDisconnectedAfterAuthorizeStarted() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        GoogleDriveOAuthService.AuthorizeResult authorizeResult = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(authorizeResult.authorizationUrl(), "state");

        // 사용자가 동의 화면에 머무는 동안 Source가 Disconnect(DISABLED)됐다고 가정한다.
        SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        connection.changeStatus("DISABLED");
        sourceConnectionJpaRepository.saveAndFlush(connection);

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state, "some-code",
                authorizeResult.browserBinding());

        assertThat(outcome.success())
                .as("a late callback must not resurrect a token for a source that is no longer active")
                .isFalse();
        assertThat(sourceTokenStore.load(sourceId)).isEmpty();
    }

    @Test
    void startAuthorizationRejectsASourceOwnedByAnotherSubject() {
        String realOwner = owner();
        long sourceId = createSource(realOwner, "ACTIVE");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> googleDriveOAuthService.startAuthorization("someone-else", sourceId))
                .isInstanceOf(com.sdv.common.exception.NotFoundException.class);
    }

    @Test
    void startAuthorizationRejectsAnInactiveSource() {
        String owner = owner();
        long sourceId = createSource(owner, "DISABLED");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> googleDriveOAuthService.startAuthorization(owner, sourceId))
                .isInstanceOf(com.sdv.common.exception.NotFoundException.class);
    }

    private long createSource(String ownerSubject, String status) {
        SourceConnectionEntity entity = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", status, "FULL",
                ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(entity);
        return entity.getId();
    }

    private static String owner() {
        return "owner-oauth-flow-" + OWNER_SEQUENCE.incrementAndGet();
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
}
