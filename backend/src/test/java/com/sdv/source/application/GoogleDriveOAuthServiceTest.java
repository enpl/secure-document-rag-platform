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
    // M10B - about.get이 반환할 안정적 Google 계정 식별자. 기본값은 "account-a"라는
    // 하나의 계정을 흉내낸다 - 다른 계정을 흉내내려는 Test는 "account-b" 등으로 바꾼다.
    private static volatile String scriptedPermissionId = "account-a";
    private static volatile boolean scriptedAboutFails = false;

    @DynamicPropertySource
    static void registerMockServerUris(DynamicPropertyRegistry registry) {
        registry.add("sdv.google-oauth.token-uri",
                () -> "http://localhost:" + MOCK_TOKEN_SERVER.getAddress().getPort() + "/token");
        // GoogleDriveClient.getAbout()이 호출하는 Base URL - 같은 Local Mock Server의
        // 다른 Context("/drive/v3/about")를 가리키게 한다.
        registry.add("sdv.google-drive.api-base-url",
                () -> "http://localhost:" + MOCK_TOKEN_SERVER.getAddress().getPort());
        // M10B 후속 교정 - 이 Test 파일이 이제 실제 disconnect()(GoogleTokenService.revoke
        // -> GoogleOAuthClient.revokeToken)를 호출하는 Test를 포함한다. 이 property가
        // 없으면 revokeToken()이 실제 Google Endpoint(기본값
        // https://oauth2.googleapis.com/revoke)로 나가려 하므로, 같은 Local Mock
        // Server의 "/revoke" Context를 가리키게 한다("No real Google/Keycloak calls").
        registry.add("sdv.google-oauth.revoke-uri",
                () -> "http://localhost:" + MOCK_TOKEN_SERVER.getAddress().getPort() + "/revoke");
    }

    private static HttpServer startMockServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/token", GoogleDriveOAuthServiceTest::handleTokenRequest);
            server.createContext("/drive/v3/about", GoogleDriveOAuthServiceTest::handleAboutRequest);
            server.createContext("/revoke", GoogleDriveOAuthServiceTest::handleRevokeRequest);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start local mock Google token server", e);
        }
    }

    /**
     * M10B 후속 교정 - {@code GoogleOAuthClient.revokeToken}이 호출하는 Mock
     * Revoke Endpoint. 실제 Google처럼 빈 본문 200을 돌려주기만 하면 된다({@code
     * toBodilessEntity()}만 확인하므로) - Content-Free 성공 응답이며 Token 값
     * 자체는 이 Mock이 검사하지 않는다(실제 Google 계약을 재현하는 것이 목적이
     * 아니라, "이 호출이 실패하지 않고 로컬 Loopback으로만 나간다"만 보장한다).
     */
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

    /** M10B - {@code GoogleDriveClient.getAbout}이 호출하는 {@code about.get} Mock 응답. */
    private static void handleAboutRequest(HttpExchange exchange) throws IOException {
        try {
            if (scriptedAboutFails) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            String json = "{\"user\":{\"permissionId\":\"" + scriptedPermissionId
                    + "\",\"emailAddress\":\"mock@example.com\"}}";
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
    @Autowired
    private SourceConnectionService sourceConnectionService;

    @BeforeEach
    void resetScript() {
        scriptedScope = "https://www.googleapis.com/auth/drive.readonly";
        scriptedOmitRefreshToken = false;
        scriptedPermissionId = "account-a";
        scriptedAboutFails = false;
        LAST_REQUEST_BODY.clear();
    }

    @AfterEach
    void resetAfter() {
        scriptedScope = "https://www.googleapis.com/auth/drive.readonly";
        scriptedOmitRefreshToken = false;
        scriptedPermissionId = "account-a";
        scriptedAboutFails = false;
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
        assertThat(connection.getStatus()).isEqualTo("ACTIVE");
        // M10B - 최초 연결이므로 지금 확인된 계정을 그대로 채택한다.
        assertThat(connection.getProviderAccountId()).isEqualTo("account-a");

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

    /**
     * M10B 교정 - 이 Test의 이름/의도가 바뀌었다: 이전에는 "Disconnect된 Source는
     * 절대 재연결할 수 없다"는 것이 옳은 동작이었지만, CORE_SPEC §2A.14가 정확히
     * 이 시나리오(같은 인증된 소유자가 검증된 동일 Provider 계정으로 재연결)를
     * 지원하도록 요구한다. 신원(permissionId)이 그대로 일치하면 이 Callback은
     * 이제 성공하고 Source를 ACTIVE로 되돌린다 - 안전성은 더 이상 "Disabled면
     * 무조건 거부"가 아니라 "다른 계정이면 거부"에서 나온다(아래 identity mismatch
     * Test 참고).
     */
    @Test
    void handleCallbackReconnectsADisabledSourceWhenTheVerifiedProviderIdentityStillMatches() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        // 최초 연결로 identity(account-a)를 먼저 채택시킨다.
        GoogleDriveOAuthService.AuthorizeResult firstAuthorize = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        googleDriveOAuthService.handleCallback(extractQueryParam(firstAuthorize.authorizationUrl(), "state"),
                "first-code", firstAuthorize.browserBinding());

        // 사용자가 연결을 끊는다(Disconnect) - 이 Test는 SourceConnectionService를
        // 거치지 않고 직접 상태만 바꿔 이 Service 자신의 재연결 논리만 검증한다.
        SourceConnectionEntity disabled = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        disabled.changeStatus("DISABLED");
        sourceConnectionJpaRepository.saveAndFlush(disabled);

        GoogleDriveOAuthService.AuthorizeResult reconnectAuthorize = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(reconnectAuthorize.authorizationUrl(), "state");

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state,
                "reconnect-code", reconnectAuthorize.browserBinding());

        assertThat(outcome.success()).as("a same-identity reconnect of a disabled source must succeed").isTrue();
        SourceConnectionEntity reconnected = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(reconnected.getStatus()).isEqualTo("ACTIVE");
        assertThat(reconnected.getProviderAccountId())
                .as("the originally adopted identity must be unchanged, not silently replaced")
                .isEqualTo("account-a");
        assertThat(sourceTokenStore.load(sourceId)).isPresent();
    }

    /**
     * M10B 신규 - CORE_SPEC §2A.14 "Reject a different provider account without
     * overwriting the original identity/share binding." 다른 Google 계정으로의
     * 재연결 시도는 실패해야 하고, 기존에 채택된 Identity/Credential 무엇도
     * 바뀌지 않아야 한다.
     */
    @Test
    void handleCallbackRejectsAReconnectFromADifferentGoogleAccountWithoutOverwritingTheOriginalIdentity() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        GoogleDriveOAuthService.AuthorizeResult firstAuthorize = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        googleDriveOAuthService.handleCallback(extractQueryParam(firstAuthorize.authorizationUrl(), "state"),
                "first-code", firstAuthorize.browserBinding());
        TokenEnvelope originalCredential = sourceTokenStore.load(sourceId).orElseThrow();

        SourceConnectionEntity disabled = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        disabled.changeStatus("DISABLED");
        sourceConnectionJpaRepository.saveAndFlush(disabled);

        GoogleDriveOAuthService.AuthorizeResult reconnectAuthorize = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(reconnectAuthorize.authorizationUrl(), "state");
        scriptedPermissionId = "account-b"; // 다른 Google 계정으로 동의했다고 가정한다.

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state,
                "wrong-account-code", reconnectAuthorize.browserBinding());

        assertThat(outcome.success()).as("a different Google account must never complete a reconnect").isFalse();
        SourceConnectionEntity unchanged = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(unchanged.getStatus()).as("must stay disabled - not silently reactivated").isEqualTo("DISABLED");
        assertThat(unchanged.getProviderAccountId())
                .as("the original identity must not be overwritten by the rejected attempt")
                .isEqualTo("account-a");
        assertThat(sourceTokenStore.load(sourceId))
                .as("the original credential must be preserved untouched")
                .contains(originalCredential);
    }

    /**
     * M10B 후속 교정 검증 - "A fresh same-identity reconnect after the final
     * disconnect still succeeds." 연결 인가 세대 손실 방지 교정({@code
     * SourceConnectionService.disconnect}가 이제 원자적 DB 증가를 쓴다)이 겹치지
     * 않는 정상적인 두 번 연속 Disconnect 뒤의 평범한 재연결 흐름 자체를 깨뜨리지
     * 않았음을 확인한다 - 경쟁이 전혀 없는 이 경로는 교정 전/후 모두 Epoch가
     * 정확히 +2 전진해야 하고, 그 최종 Epoch를 기준으로 시작한 새 재인증은
     * 정상 성공해야 한다.
     */
    @Test
    void handleCallbackSucceedsForAFreshSameIdentityReconnectAfterTwoSuccessiveDisconnects() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        GoogleDriveOAuthService.AuthorizeResult firstAuthorize = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        googleDriveOAuthService.handleCallback(extractQueryParam(firstAuthorize.authorizationUrl(), "state"),
                "first-code", firstAuthorize.browserBinding());

        sourceConnectionService.disconnect(sourceId, owner);
        sourceConnectionService.disconnect(sourceId, owner); // 겹치지 않는, 곧바로 이어지는 두 번째 Disconnect.

        GoogleDriveOAuthService.AuthorizeResult reconnectAuthorize = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(reconnectAuthorize.authorizationUrl(), "state");

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state,
                "reconnect-code", reconnectAuthorize.browserBinding());

        assertThat(outcome.success())
                .as("a fresh same-identity reconnect started after both disconnects completed must still succeed")
                .isTrue();
        SourceConnectionEntity reconnected = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(reconnected.getStatus()).isEqualTo("ACTIVE");
        assertThat(reconnected.getProviderAccountId())
                .as("the originally adopted identity must be unchanged")
                .isEqualTo("account-a");
        assertThat(sourceTokenStore.load(sourceId)).isPresent();
    }

    /** M10B 신규 - Identity 확인 자체가 실패하면(Network/Malformed 등) 추측하지 않고 재인증 필요로 안전하게 실패한다. */
    @Test
    void handleCallbackFailsClosedWhenTheIdentityCheckItselfFails() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        GoogleDriveOAuthService.AuthorizeResult authorizeResult = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(authorizeResult.authorizationUrl(), "state");
        scriptedAboutFails = true;

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state, "some-code",
                authorizeResult.browserBinding());

        assertThat(outcome.success()).isFalse();
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

    /**
     * M10B 교정 - 이전에는 DISABLED Source가 재인증 자체를 시작할 수 없었다(그래서
     * 이 Test 이름이 "RejectsAnInactiveSource"였다). CORE_SPEC §2A.14의 재연결을
     * 지원하려면 소유자 자신의 DISABLED Source가 이 단계를 통과해야 한다 - 실제
     * 안전장치(다른 계정 거부)는 {@link #handleCallback} 시점으로 옮겨졌다.
     */
    @Test
    void startAuthorizationAllowsReauthorizingTheOwnersOwnDisabledSourceForReconnect() {
        String owner = owner();
        long sourceId = createSource(owner, "DISABLED");

        GoogleDriveOAuthService.AuthorizeResult result = googleDriveOAuthService.startAuthorization(owner, sourceId);

        assertThat(result.authorizationUrl()).isNotBlank();
    }

    @Test
    void startAuthorizationRejectsANonGoogleDriveSourceRegardlessOfStatus() {
        String owner = owner();
        SourceConnectionEntity entity = new SourceConnectionEntity("SOME_OTHER_TYPE", "Test Source", "ACTIVE", "FULL",
                owner);
        sourceConnectionJpaRepository.saveAndFlush(entity);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> googleDriveOAuthService.startAuthorization(owner, entity.getId()))
                .isInstanceOf(com.sdv.common.exception.NotFoundException.class);
    }

    /**
     * M10B 보안 교정(그룹 C) - {@code commitCredential}의 새 {@code connectionEpoch}
     * 확인만 단독으로 검증한다({@code SourceTokenConcurrencyTest}의 두 Latch 기반
     * Test처럼 실제 Thread로 이 자체를 이미 재현하지만, 이 Test는 Status 검사와
     * 완전히 분리해 이 특정 방어선을 결정론적/순차적으로 고정한다). Disconnect를
     * 거치지 않고 Epoch만 직접 올려, "Status는 그대로 ACTIVE인데 Epoch만 바뀐"
     * 경우에도 이 Callback이 거부되는지 확인한다.
     */
    @Test
    void handleCallbackFailsClosedWhenTheConnectionEpochAdvancedAfterAuthorizationStarted() {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        GoogleDriveOAuthService.AuthorizeResult authorizeResult = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractQueryParam(authorizeResult.authorizationUrl(), "state");

        SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        connection.bumpConnectionEpoch();
        sourceConnectionJpaRepository.saveAndFlush(connection);

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state,
                "authorization-code-from-google", authorizeResult.browserBinding());

        assertThat(outcome.success())
                .as("an attempt started before the connection epoch advanced must not be allowed to publish "
                        + "a credential, even though status is still ACTIVE")
                .isFalse();
        assertThat(sourceTokenStore.load(sourceId)).isEmpty();
        SourceConnectionEntity unchanged = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(unchanged.getTokenRef()).isNull();
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
