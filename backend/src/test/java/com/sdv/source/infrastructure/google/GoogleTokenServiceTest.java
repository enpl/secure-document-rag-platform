package com.sdv.source.infrastructure.google;

import com.sdv.source.application.port.TokenEnvelope;
import com.sdv.common.config.SecretProperties;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceOAuthTokenJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-BE-043 검증 (M08 MVP OAuth, {@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - 실제
 * Testcontainers PostgreSQL 위에서 {@link GoogleTokenService}의 암호화 Round-Trip,
 * 변조/Key 불일치/Source Reference 바꿔치기 거부, 평문 비저장, 잘못된/누락된 설정,
 * Bounded Refresh, Revoke 멱등성/실패 처리를 검증한다. {@link GoogleOAuthClient}는
 * {@link MockitoBean}으로 대체한다 - 이 Test의 초점은 저장/암호화/Refresh 판단
 * 로직이지 실제 Google 프로토콜 왕복이 아니다(그건 이미 검증된 Spring Security
 * 라이브러리의 책임).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        // 합성(Synthetic) Test 전용 32-byte AES Key - 실제 Secret이 아니다.
        "sdv.secrets.token-encryption-key=354C9a3yRzwUV/1rt1s8AX4yflicnzQj0Z8aItHYw3w=",
        "sdv.secrets.token-encryption-key-id=test-key-v1"
})
class GoogleTokenServiceTest {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();

    @Autowired
    private GoogleTokenService googleTokenService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository;
    @Autowired
    private SecretProperties secretProperties;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @MockitoBean
    private GoogleOAuthClient googleOAuthClient;

    @Test
    @Transactional
    void storeThenLoadRoundTripsTheEnvelopeExactly() {
        long sourceId = createSource();
        TokenEnvelope original = new TokenEnvelope(owner(sourceId), "access-token-value", "refresh-token-value",
                Instant.now().plusSeconds(3600), List.of(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY));

        googleTokenService.store(sourceId, original);
        Optional<TokenEnvelope> loaded = googleTokenService.load(sourceId);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().boundSubject()).isEqualTo(original.boundSubject());
        assertThat(loaded.get().accessToken()).isEqualTo(original.accessToken());
        assertThat(loaded.get().refreshToken()).isEqualTo(original.refreshToken());
        assertThat(loaded.get().scopes()).isEqualTo(original.scopes());
    }

    @Test
    @Transactional
    void storeUpdatesTheSourceConnectionTokenRefToTheEncryptedRowsReference() {
        long sourceId = createSource();
        googleTokenService.store(sourceId, envelopeFor(sourceId));

        SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        UUID persistedRef = sourceOAuthTokenJpaRepository.findBySourceId(sourceId).orElseThrow().getTokenRef();
        assertThat(connection.getTokenRef()).isEqualTo(persistedRef.toString());
    }

    @Test
    @Transactional
    void noPlaintextTokenValueAppearsInThePersistedCiphertextOrNonce() {
        long sourceId = createSource();
        String accessTokenCanary = "ACCESS-CANARY-" + UUID.randomUUID();
        String refreshTokenCanary = "REFRESH-CANARY-" + UUID.randomUUID();
        googleTokenService.store(sourceId, new TokenEnvelope(owner(sourceId), accessTokenCanary, refreshTokenCanary,
                Instant.now().plusSeconds(3600), List.of(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY)));
        entityManager.flush(); // JPA store()의 Insert를 이 Method의 Raw JDBC 조회가 볼 수 있게 한다.

        byte[] ciphertext = jdbcTemplate.queryForObject(
                "SELECT ciphertext FROM source_oauth_tokens WHERE source_id = ?", byte[].class, sourceId);
        byte[] nonce = jdbcTemplate.queryForObject(
                "SELECT nonce FROM source_oauth_tokens WHERE source_id = ?", byte[].class, sourceId);

        assertThat(containsAscii(ciphertext, accessTokenCanary)).as("access token must not appear in ciphertext").isFalse();
        assertThat(containsAscii(ciphertext, refreshTokenCanary)).as("refresh token must not appear in ciphertext").isFalse();
        assertThat(containsAscii(nonce, accessTokenCanary)).isFalse();
    }

    @Test
    @Transactional
    void tamperedCiphertextFailsClosedOnLoad() {
        long sourceId = createSource();
        googleTokenService.store(sourceId, envelopeFor(sourceId));
        flipLastByte(sourceId, "ciphertext");

        assertThatThrownBy(() -> googleTokenService.load(sourceId))
                .as("a GCM authentication failure on tampered ciphertext must fail closed")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Transactional
    void tokenEncryptedWithAnUnknownKeyIdFailsClosedOnLoad() {
        long sourceId = createSource();
        googleTokenService.store(sourceId, envelopeFor(sourceId));
        entityManager.flush();
        jdbcTemplate.update("UPDATE source_oauth_tokens SET key_id = 'some-other-key-id' WHERE source_id = ?",
                sourceId);
        entityManager.clear();

        assertThatThrownBy(() -> googleTokenService.load(sourceId))
                .as("a key id that does not match the configured active key must fail closed, not fall back to any key")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Transactional
    void swappedTokenReferenceBetweenTwoSourcesFailsClosedOnLoad() {
        long sourceIdA = createSource();
        long sourceIdB = createSource();
        googleTokenService.store(sourceIdA, envelopeFor(sourceIdA));
        googleTokenService.store(sourceIdB, envelopeFor(sourceIdB));
        entityManager.flush();
        String refA = sourceConnectionJpaRepository.findById(sourceIdA).orElseThrow().getTokenRef();
        String refB = sourceConnectionJpaRepository.findById(sourceIdB).orElseThrow().getTokenRef();
        // source_connections.token_ref를 두 Source끼리 맞바꾼다 - 마치 참조가 바꿔치기된 것처럼.
        jdbcTemplate.update("UPDATE source_connections SET token_ref = ? WHERE id = ?", refB, sourceIdA);
        jdbcTemplate.update("UPDATE source_connections SET token_ref = ? WHERE id = ?", refA, sourceIdB);
        entityManager.clear();

        assertThatThrownBy(() -> googleTokenService.load(sourceIdA))
                .as("swapping a reference must fail closed, never silently decrypt the other source's credential")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> googleTokenService.load(sourceIdB)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Transactional
    void missingEncryptionKeyConfigurationFailsClosedWithoutCrashingTheApplication() {
        long sourceId = createSource();
        SecretProperties unconfigured = new SecretProperties(secretProperties.googleClientId(),
                secretProperties.googleClientSecret(), secretProperties.googleRedirectUri(), null, null, null);
        GoogleTokenService serviceWithNoKey = new GoogleTokenService(sourceOAuthTokenJpaRepository,
                sourceConnectionJpaRepository, unconfigured, googleOAuthClient);

        // 이 Test Method 자체가 정상적인 Spring Context 안에서 실행된다는 사실 자체가 이미
        // "무관한 나머지 Backend는 계속 동작한다"는 것을 보여준다 - 아래는 이 Service 인스턴스
        // 하나만 Fail Closed 함을 보인다.
        assertThatThrownBy(() -> serviceWithNoKey.store(sourceId, envelopeFor(sourceId)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Transactional
    void revokeCallsGoogleRevokeAndDeletesTheLocalRow() {
        long sourceId = createSource();
        googleTokenService.store(sourceId, envelopeFor(sourceId));

        googleTokenService.revoke(sourceId);

        verify(googleOAuthClient).revokeToken(any());
        assertThat(sourceOAuthTokenJpaRepository.findBySourceId(sourceId)).isEmpty();
    }

    @Test
    @Transactional
    void revokeIsIdempotentWhenNoCredentialExists() {
        long sourceId = createSource();

        googleTokenService.revoke(sourceId); // no store() beforehand

        verifyNoInteractions(googleOAuthClient);
        assertThat(sourceOAuthTokenJpaRepository.findBySourceId(sourceId)).isEmpty();
    }

    @Test
    @Transactional
    void revocationFailurePreservesTheLocalRowForRetry() {
        long sourceId = createSource();
        googleTokenService.store(sourceId, envelopeFor(sourceId));
        Mockito.doThrow(new GoogleOAuthException(GoogleOAuthException.Category.NETWORK_OR_TIMEOUT, "simulated"))
                .when(googleOAuthClient).revokeToken(any());

        assertThatThrownBy(() -> googleTokenService.revoke(sourceId)).isInstanceOf(GoogleOAuthException.class);

        assertThat(sourceOAuthTokenJpaRepository.findBySourceId(sourceId))
                .as("a failed revocation must not report success or delete the row - the caller must be able to retry")
                .isPresent();
    }

    @Test
    @Transactional
    void loadRefreshesAnExpiredAccessTokenOncePreservingTheRefreshTokenWhenTheResponseOmitsOne() {
        long sourceId = createSource();
        googleTokenService.store(sourceId, new TokenEnvelope(owner(sourceId), "expired-access-token",
                "original-refresh-token", Instant.now().minusSeconds(10),
                List.of(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY)));
        when(googleOAuthClient.refreshAccessToken("original-refresh-token")).thenReturn(
                OAuth2AccessTokenResponse.withToken("refreshed-access-token")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .expiresIn(3600)
                        .scopes(java.util.Set.of(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY))
                        .build());

        Optional<TokenEnvelope> refreshed = googleTokenService.load(sourceId);

        assertThat(refreshed).isPresent();
        assertThat(refreshed.get().accessToken()).isEqualTo("refreshed-access-token");
        assertThat(refreshed.get().refreshToken())
                .as("a refresh response that omits refresh_token must preserve the previously stored one")
                .isEqualTo("original-refresh-token");
        assertThat(refreshed.get().accessTokenExpiresAt()).isAfter(Instant.now().plusSeconds(3000));
        // 갱신된 값이 실제로 재저장됐는지 확인한다.
        Optional<TokenEnvelope> reloaded = googleTokenService.load(sourceId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().accessToken()).isEqualTo("refreshed-access-token");
    }

    @Test
    @Transactional
    void loadReturnsTheStaleEnvelopeWithoutLoopingWhenRefreshFails() {
        long sourceId = createSource();
        googleTokenService.store(sourceId, new TokenEnvelope(owner(sourceId), "expired-access-token",
                "original-refresh-token", Instant.now().minusSeconds(10),
                List.of(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY)));
        when(googleOAuthClient.refreshAccessToken(any()))
                .thenThrow(new GoogleOAuthException(GoogleOAuthException.Category.INVALID_GRANT, "simulated"));

        Optional<TokenEnvelope> result = googleTokenService.load(sourceId);

        assertThat(result).as("a failed refresh must still return the stale envelope, not throw or loop").isPresent();
        assertThat(result.get().accessToken()).isEqualTo("expired-access-token");
        verify(googleOAuthClient, Mockito.times(1)).refreshAccessToken(any());
    }

    @Test
    @Transactional
    void loadDoesNotAttemptRefreshWhenTheAccessTokenIsStillFresh() {
        long sourceId = createSource();
        googleTokenService.store(sourceId, new TokenEnvelope(owner(sourceId), "still-valid-access-token",
                "refresh-token", Instant.now().plusSeconds(3600),
                List.of(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY)));

        Optional<TokenEnvelope> result = googleTokenService.load(sourceId);

        assertThat(result).isPresent();
        assertThat(result.get().accessToken()).isEqualTo("still-valid-access-token");
        verifyNoInteractions(googleOAuthClient);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long createSource() {
        SourceConnectionEntity entity = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL",
                owner(OWNER_SEQUENCE.incrementAndGet()));
        sourceConnectionJpaRepository.saveAndFlush(entity);
        return entity.getId();
    }

    private static String owner(long seed) {
        return "owner-token-service-" + seed;
    }

    private String owner(Long sourceId) {
        return sourceConnectionJpaRepository.findById(sourceId).orElseThrow().getOwnerSubject();
    }

    private TokenEnvelope envelopeFor(long sourceId) {
        return new TokenEnvelope(owner(sourceId), "access-" + sourceId, "refresh-" + sourceId,
                Instant.now().plusSeconds(3600), List.of(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY));
    }

    /**
     * Hibernate가 관리하는 쓰기를 먼저 Flush해(JPA {@code store()}가 아직 DB에 내보내지
     * 않았을 수 있는 변경을 Raw JDBC 조회가 볼 수 있게) Raw JDBC로 직접 변조한 뒤,
     * Persistence Context를 비운다(JPA {@code load()}가 방금 바뀐 실제 행을 다시
     * 읽게 하기 위함 - 그러지 않으면 같은 Transaction 안의 1차 캐시가 변조 전 값을
     * 그대로 돌려준다).
     */
    private void flipLastByte(long sourceId, String column) {
        entityManager.flush();
        byte[] value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM source_oauth_tokens WHERE source_id = ?", byte[].class, sourceId);
        byte[] tampered = value.clone();
        tampered[tampered.length - 1] ^= 0x01;
        jdbcTemplate.update("UPDATE source_oauth_tokens SET " + column + " = ? WHERE source_id = ?", tampered,
                sourceId);
        entityManager.clear();
    }

    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] needleBytes = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - needleBytes.length; i++) {
            for (int j = 0; j < needleBytes.length; j++) {
                if (haystack[i + j] != needleBytes[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
