package com.sdv.source.application;

import com.sdv.source.application.port.SourceTokenStore;
import com.sdv.source.application.port.TokenEnvelope;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.google.GoogleOAuthClient;
import com.sdv.source.infrastructure.google.GoogleTokenService;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceOAuthTokenJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * M08 3-issue 교정 검증 - "Enforce current ownership/lifecycle/credential generation
 * when publishing tokens"(이 작업 지시사항 1번). 실제 Spring이 관리하는 Proxied Service
 * ({@link GoogleTokenService}/{@link SourceConnectionService}/{@link
 * GoogleDriveOAuthService})와 서로 독립적으로 Commit되는 실제 Transaction을 각기 다른
 * Thread에서 사용한다 - 하나의 Test 전체를 감싸는 {@code @Transactional}을 쓰지 않는다
 * (그러면 두 작업이 같은 Transaction 안에 갇혀 진짜 동시성을 재현하지 못한다).
 *
 * <p>Sleep을 쓰지 않는다 - {@link CountDownLatch}로 "정확히 이 시점"을 강제한다. 각
 * Assertion은 두 작업이 모두 끝난 뒤, 새로 시작하는(둘 중 어느 Thread의 Persistence
 * Context도 재사용하지 않는) 별도 Repository 호출로 DB 상태를 읽는다.</p>
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
class SourceTokenConcurrencyTest {

    private static final String SCOPE = GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY;
    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();
    private static final long BOUND_SECONDS = 10;

    @Autowired
    private GoogleTokenService googleTokenService;
    @Autowired
    private SourceConnectionService sourceConnectionService;
    @Autowired
    private GoogleDriveOAuthService googleDriveOAuthService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository;
    // M10B 후속 교정(연결 인가 세대 손실 방지) - 실제 GoogleTokenStoreAdapter Bean
    // 위에 얹는 Spy다(완전한 Mock이 아니다) - 기본적으로는 그대로 실제 delete()/
    // revoke()를 호출해 다른 Test에는 영향이 없고, 아래 두 Overlapping Disconnect
    // Test만 특정 sourceId 인자에 한해 실제 호출 직전 CountDownLatch로 멈춘다.
    @MockitoSpyBean
    private SourceTokenStore sourceTokenStore;
    @MockitoBean
    private GoogleOAuthClient googleOAuthClient;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    /**
     * "pause a refresh after its old credential is captured, complete disconnect, release the
     * response, and inspect DB state from a fresh persistence context." Refresh Network 응답
     * 자체를(Mock 안에서) 붙잡아 둬, 그 사이 실제 {@code SourceConnectionService.disconnect}가
     * 완전히 별도 Transaction으로 끝까지 Commit되게 한다.
     */
    @Test
    void aRefreshPausedMidFlightDoesNotResurrectATokenDisconnectedWhileItWasWaiting() throws Exception {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        googleTokenService.store(sourceId, new TokenEnvelope(owner, "old-access-token", "old-refresh-token",
                Instant.now().minusSeconds(10), List.of(SCOPE))); // 이미 만료 - load()가 즉시 Refresh를 시도한다.

        CountDownLatch oldCredentialCaptured = new CountDownLatch(1);
        CountDownLatch disconnectCommitted = new CountDownLatch(1);
        when(googleOAuthClient.refreshAccessToken("old-refresh-token")).thenAnswer(invocation -> {
            // 이 시점에 GoogleTokenService.load()는 이미 옛 tokenRef/rowVersion(Old
            // Credential의 신원)을 Google 호출 전에 캡처해뒀다 - 어떤 DB Lock도 잡지 않은 채.
            oldCredentialCaptured.countDown();
            boolean disconnectFinishedInTime = disconnectCommitted.await(BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(disconnectFinishedInTime).as("disconnect must complete within the bounded wait").isTrue();
            return OAuth2AccessTokenResponse.withToken("new-access-token-from-late-refresh")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(3600)
                    .scopes(Set.of(SCOPE))
                    .build();
        });

        Future<Optional<TokenEnvelope>> refreshFuture = executor.submit(() -> googleTokenService.load(sourceId));

        assertThat(oldCredentialCaptured.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        // 별도의, 독립적으로 Commit되는 실제 Transaction - Refresh가 멈춰있는 동안 끝까지 진행된다.
        sourceConnectionService.disconnect(sourceId, owner);
        disconnectCommitted.countDown();

        Optional<TokenEnvelope> refreshResult = refreshFuture.get(BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(refreshResult)
                .as("a late refresh must not resurrect a token for a source disconnected while it was in flight")
                .isEmpty();

        // 새 Persistence Context(둘 중 어느 Thread의 것도 아닌, 이 Assertion 자체의 첫 조회)로 확인한다.
        SourceConnectionEntity finalState = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(finalState.getStatus()).as("disconnect's ACTIVE->DISABLED must not be reverted").isEqualTo("DISABLED");
        assertThat(finalState.getTokenRef()).as("token_ref must not be restored by the discarded refresh").isNull();
        assertThat(sourceOAuthTokenJpaRepository.findBySourceId(sourceId))
                .as("the late refresh must not insert a replacement row after disconnect deleted the original")
                .isEmpty();
    }

    /**
     * "Also cover first callback versus a disconnect whose Source initially has no token
     * reference." 최초 인증(Authorization-Code Exchange, Google 응답을 기다리는 동안 Network
     * 호출 자체가 Mock 안에서 붙잡혀 있다)이 진행되는 동안, 아직 Token이 하나도 없는 같은
     * Source에 대해 실제 {@code SourceConnectionService.disconnect}가 완전히 끝까지
     * Commit된다 - 이 Disconnect의 최초 읽기 시점에는 진짜로 {@code token_ref}가 없다.
     */
    @Test
    void firstCallbackDoesNotResurrectAnActiveSourceThatWasDisconnectedWhileItHadNoTokenYet() throws Exception {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE"); // 아직 Token 없음 - token_ref가 실제로 null이다.

        when(googleOAuthClient.buildAuthorizationUrl(any(), any())).thenAnswer(
                invocation -> "https://accounts.google.com/o/oauth2/v2/auth?state="
                        + invocation.getArgument(0, String.class) + "&code_challenge="
                        + invocation.getArgument(1, String.class));

        GoogleDriveOAuthService.AuthorizeResult authorizeResult = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractState(authorizeResult.authorizationUrl());

        CountDownLatch codeExchangeStarted = new CountDownLatch(1);
        CountDownLatch disconnectCommitted = new CountDownLatch(1);
        when(googleOAuthClient.exchangeAuthorizationCode(any(), any())).thenAnswer(invocation -> {
            codeExchangeStarted.countDown();
            boolean disconnectFinishedInTime = disconnectCommitted.await(BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(disconnectFinishedInTime).as("disconnect must complete within the bounded wait").isTrue();
            return OAuth2AccessTokenResponse.withToken("first-access-token")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .refreshToken("first-refresh-token")
                    .expiresIn(3600)
                    .scopes(Set.of(SCOPE))
                    .build();
        });

        Future<GoogleDriveOAuthService.CallbackOutcome> callbackFuture = executor.submit(
                () -> googleDriveOAuthService.handleCallback(state, "first-authorization-code",
                        authorizeResult.browserBinding()));

        assertThat(codeExchangeStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        // disconnect()의 맨 위 조회 시점에 이 Source는 진짜로 Token이 없다(token_ref == null) -
        // 그래도 delete()는 (M08 후속 교정 이후) 무조건 호출된다.
        sourceConnectionService.disconnect(sourceId, owner);
        disconnectCommitted.countDown();

        GoogleDriveOAuthService.CallbackOutcome outcome = callbackFuture.get(BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(outcome.success())
                .as("a first authorization completing after disconnect must not resurrect the source as connected")
                .isFalse();

        SourceConnectionEntity finalState = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo("DISABLED");
        assertThat(finalState.getTokenRef()).isNull();
        assertThat(sourceOAuthTokenJpaRepository.findBySourceId(sourceId))
                .as("the discarded first-authorization credential must never be persisted")
                .isEmpty();
        assertThat(sourceTokenStore.load(sourceId)).isEmpty();
    }

    /**
     * M10B 후속 교정 - "make connection-epoch invalidation safe under concurrent
     * disconnect requests." 실제 확정 결함 재현: {@code disconnect()}는 이 Method
     * 맨 위의(Unlocked) 조회로 {@code entity}를 읽은 뒤, {@code sourceTokenStore.get()
     * .delete(id)}({@code GoogleTokenService.revoke})가 그 행을 {@code SELECT ...
     * FOR UPDATE}로 잠근다 - 그런데 그 Native Query는 Projection만 돌려줄 뿐 이미
     * Managed 상태인 {@code entity}의 Java 필드를 Refresh하지 않는다({@code
     * SourceConnectionJpaRepository.lockAndReadCurrentOwnershipState} Javadoc 참고).
     * 교정 전에는 그 뒤 {@code entity.bumpConnectionEpoch()}가 이 Stale한 Java
     * 값(+1)만 계산했다 - 두 Disconnect가 정확히 이렇게 겹치면(둘 다 잠기기 전에
     * 같은 낡은 값을 읽음), 두 번째 Commit이 첫 번째가 이미 반영한 증가를 같은
     * 값으로 덮어써 한쪽 증가가 통째로 유실됐다(최종 값이 +1만큼만 전진 - +2가
     * 아니다).
     *
     * <p>이 Test는 실제 {@link SourceConnectionService#disconnect} 경로 그 자체를
     * 두 번 동시에 호출한다(Raw SQL 증가나 Entity Helper 직접 호출이 아니다) -
     * {@link #sourceTokenStore}(Spy)의 {@code delete(sourceId)}를 가로채, 두 호출
     * 모두 "자신의 Unlocked 진입 시점 상태 조회는 이미 끝났고, 실제 Row Lock을 얻기
     * 직전" 지점에서 결정론적으로 멈춰 세운다 - Sleep 없이 {@link CountDownLatch}만
     * 사용하며, 한쪽을 완전히 재개해 Commit까지 끝낸 뒤에야 다른 쪽을 재개하므로
     * (동시에 풀지 않는다) Deadlock 가능성이 없다.</p>
     */
    @Test
    void twoOverlappingDisconnectsBothAdvanceTheConnectionEpochWithoutLosingEitherIncrement() throws Exception {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");
        long initialEpoch = sourceConnectionJpaRepository.findById(sourceId).orElseThrow().getConnectionEpoch();

        CountDownLatch firstReachedDelete = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReachedDelete = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger callIndex = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (callIndex.getAndIncrement() == 0) {
                // 첫 번째 disconnect() 호출 - 이미 자신의 Unlocked entity 조회는
                // 끝냈고, 지금부터 실제 delete()/revoke()(Row Lock 획득)를 호출하려는
                // 참이다. 여기서 멈춰, 두 번째 호출도 같은 낡은 값을 읽을 기회를 준다.
                firstReachedDelete.countDown();
                assertThat(releaseFirst.await(BOUND_SECONDS, TimeUnit.SECONDS))
                        .as("test must release the first disconnect within the bounded wait").isTrue();
            } else {
                secondReachedDelete.countDown();
                assertThat(releaseSecond.await(BOUND_SECONDS, TimeUnit.SECONDS))
                        .as("test must release the second disconnect within the bounded wait").isTrue();
            }
            return invocation.callRealMethod();
        }).when(sourceTokenStore).delete(sourceId);

        Future<?> firstDisconnect = executor.submit(() -> sourceConnectionService.disconnect(sourceId, owner));
        assertThat(firstReachedDelete.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        Future<?> secondDisconnect = executor.submit(() -> sourceConnectionService.disconnect(sourceId, owner));
        assertThat(secondReachedDelete.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        // 이 시점에 두 Transaction 모두 자신의 entity를 같은 낡은 epoch 값으로 이미
        // 읽어 뒀다 - Lost Increment가 재현되려면 반드시 필요한 전제 조건이다.

        // 한쪽씩 순서대로 완전히 재개한다(동시에 풀지 않는다 - Deadlock 없이 두 번째가
        // 첫 번째의 실제 Row Lock 해제/Commit을 자연히 기다리게 한다).
        releaseFirst.countDown();
        firstDisconnect.get(BOUND_SECONDS, TimeUnit.SECONDS);
        releaseSecond.countDown();
        secondDisconnect.get(BOUND_SECONDS, TimeUnit.SECONDS);

        long finalEpoch = sourceConnectionJpaRepository.findById(sourceId).orElseThrow().getConnectionEpoch();
        assertThat(finalEpoch)
                .as("two overlapping disconnects must both advance the epoch - neither increment may be lost")
                .isEqualTo(initialEpoch + 2);
    }

    /**
     * M10B 후속 교정 검증 - "An OAuth attempt captured between the first and second
     * completed invalidations cannot publish credentials or reactivate the source
     * after the second invalidation." 이 두 Disconnect는 겹치지 않는(순차) 호출이다 -
     * 위 Overlapping Test가 "동시에 겹치면 증가가 유실되지 않는가"를 증명한다면, 이
     * Test는 "정상적으로 두 번 연속 전진한 Epoch가, 그 사이에 캡처된 낡은 시도를
     * 실제로 거부하는가"라는 그 결과의 End-to-End 효과를 증명한다.
     */
    @Test
    void anOAuthAttemptCapturedBetweenTwoCompletedDisconnectsCannotPublishAfterTheSecond() throws Exception {
        String owner = owner();
        long sourceId = createSource(owner, "ACTIVE");

        sourceConnectionService.disconnect(sourceId, owner); // 첫 번째 무효화 - Epoch가 +1 된다.

        when(googleOAuthClient.buildAuthorizationUrl(any(), any())).thenAnswer(
                invocation -> "https://accounts.google.com/o/oauth2/v2/auth?state="
                        + invocation.getArgument(0, String.class) + "&code_challenge="
                        + invocation.getArgument(1, String.class));
        GoogleDriveOAuthService.AuthorizeResult authorizeResult = googleDriveOAuthService.startAuthorization(owner,
                sourceId);
        String state = extractState(authorizeResult.authorizationUrl()); // 이 시점의 Epoch(+1)를 그대로 캡처.

        sourceConnectionService.disconnect(sourceId, owner); // 두 번째 무효화 - Epoch가 +2로 전진해야 한다.

        when(googleOAuthClient.exchangeAuthorizationCode(any(), any())).thenReturn(
                OAuth2AccessTokenResponse.withToken("late-access-token")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .refreshToken("late-refresh-token")
                        .expiresIn(3600)
                        .scopes(Set.of(SCOPE))
                        .build());

        GoogleDriveOAuthService.CallbackOutcome outcome = googleDriveOAuthService.handleCallback(state,
                "late-authorization-code", authorizeResult.browserBinding());

        assertThat(outcome.success())
                .as("an attempt captured between two completed disconnects must fail after the second invalidation")
                .isFalse();
        SourceConnectionEntity finalState = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo("DISABLED");
        assertThat(finalState.getTokenRef()).isNull();
        assertThat(sourceOAuthTokenJpaRepository.findBySourceId(sourceId))
                .as("the discarded late credential must never be persisted")
                .isEmpty();
    }

    private long createSource(String ownerSubject, String status) {
        SourceConnectionEntity entity = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", status, "FULL",
                ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(entity);
        return entity.getId();
    }

    private static String owner() {
        return "owner-concurrency-" + OWNER_SEQUENCE.incrementAndGet();
    }

    private static String extractState(String authorizationUrl) {
        String query = java.net.URI.create(authorizationUrl).getRawQuery();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if ("state".equals(pair.substring(0, eq))) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("state not found in authorization URL");
    }
}
