package com.sdv.source.infrastructure.google;

import com.sdv.common.config.SecretProperties;
import com.sdv.source.application.port.TokenEnvelope;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceOAuthTokenEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceOAuthTokenJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F-BE-043 (M08 MVP OAuth, {@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}). Google Token
 * 암호화/조회/폐기(store/load/revoke) - {@link GoogleTokenStoreAdapter}(F-BE-044)가
 * {@link com.sdv.source.application.port.SourceTokenStore} Port의 실제 동작을 이
 * Class에 위임한다.
 *
 * <h2>왜 {@code source_connections.token_ref} 갱신을 이 Class가 직접 하는가</h2>
 * <p>{@link com.sdv.source.application.port.SourceTokenStore#save}는 반환값이
 * {@code void}다 - 호출자는 새로 생성된 {@code tokenRef}(UUID)를 알 방법이 없다.
 * {@link #store}가 그 UUID를 직접 생성하는 유일한 곳이므로, 같은 Transaction 안에서
 * {@code source_oauth_tokens} 행과 {@code source_connections.token_ref}를 함께
 * 갱신하는 책임도 여기서 진다(호출자는 {@code token_ref} 자체를 전혀 몰라도 된다).
 * {@link com.sdv.source.application.SourceConnectionService#disconnect}의 기존
 * {@code delete} 경로는 반대로 호출자가 {@code token_ref}를 지운다(M04부터 이미
 * 그렇게 구현/Test돼 있다) - 두 경로의 책임 소재가 다른 것은 의도적이다(기존 승인된
 * Disconnect 코드를 이 작업에서 다시 열지 않기 위함).</p>
 *
 * <h2>Lock 순서 - 부모 Source 먼저, 자식 Token 나중</h2>
 * <p>{@link #store}/{@link #revoke}는 항상 {@link SourceConnectionJpaRepository#findByIdForUpdate}로
 * 부모 {@code source_connections} 행을 먼저 잠근 뒤에만 {@code source_oauth_tokens}
 * (자식) 행을 건드린다 - {@code SourceConnectionService.disconnect}가 이 Class의
 * {@link #revoke}를 호출할 때도 자연히 같은 순서가 된다({@code docs/plan/SDV_MVP_DEFERRED.md}
 * MVP-06, 옛 M06 Lock Cycle을 이 새 Writer에서 재현하지 않기 위함).</p>
 *
 * <h2>암호화</h2>
 * <p>{@code AES/GCM/NoPadding}, 32-byte Key, 매 암호화마다 새로 만드는 12-byte
 * {@link SecureRandom} Nonce, 128-bit 인증 Tag(JDK {@link Cipher}의 기본 GCM 출력
 * 형식 그대로 Ciphertext 끝에 Tag가 포함된다). AAD는 {@code formatVersion|sourceId|
 * ownerSubject|tokenRef|keyId}를 각 필드 길이-Prefix({@link DataOutputStream#writeUTF})로
 * 묶어 만든다 - Ciphertext가 다른 Source/Token 행으로 옮겨지면(또는 Key가 바뀌면)
 * GCM 인증이 실패하도록 이 Context를 암호문에 결합한다.</p>
 */
@Service
public class GoogleTokenService {

    private static final String GOOGLE_DRIVE_TYPE = "GOOGLE_DRIVE";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int AES_KEY_BYTES = 32;
    private static final int CURRENT_FORMAT_VERSION = 1;
    /** Access Token 만료 60초 이내(또는 이미 만료)면 Refresh를 시도한다 - Clock Skew에 대한 안전 여유. */
    private static final Duration NEAR_EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final SourceOAuthTokenJpaRepository tokenRepository;
    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final SecretProperties secretProperties;
    private final GoogleOAuthClient googleOAuthClient;
    private final Clock clock;

    @Autowired
    public GoogleTokenService(SourceOAuthTokenJpaRepository tokenRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository, SecretProperties secretProperties,
            GoogleOAuthClient googleOAuthClient) {
        this(tokenRepository, sourceConnectionJpaRepository, secretProperties, googleOAuthClient, Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}을 직접 주입하기 위한 패키지 전용 생성자(만료/Refresh 판정 결정론화). */
    GoogleTokenService(SourceOAuthTokenJpaRepository tokenRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository, SecretProperties secretProperties,
            GoogleOAuthClient googleOAuthClient, Clock clock) {
        this.tokenRepository = tokenRepository;
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.secretProperties = secretProperties;
        this.googleOAuthClient = googleOAuthClient;
        this.clock = clock;
    }

    /**
     * 새/갱신된 Credential을 암호화해 저장하고, 같은 Transaction 안에서 {@code
     * source_connections.token_ref}도 함께 갱신한다. 이 메서드는 호출자({@code
     * GoogleDriveOAuthService.commitCredential})가 이미 검증한 소유권/상태를 다시
     * "신뢰"하지 않는다 - Lock을 잡은 뒤 이 메서드 자체가 다시 한번 독립적으로
     * 확인한다("Make all relevant writers coordinate on the parent Source and
     * validate fresh DB state" - 이 작업 지시사항). 이는 "최초 인증(또는 재인증)"
     * 경로 전용이다 - 항상 이 Source의 최신 Credential로 무조건 교체한다(존재하는
     * 이전 행이 있으면 같은 {@code tokenRef}를 재사용해 In-place 교체, 없으면
     * 새로 만든다). Bounded Refresh 발행은 이 메서드를 쓰지 않는다 - {@link
     * #publishRefreshIfStillCurrent}를 참고(최초 인증과 Refresh는 서로 다른 계약을
     * 가진다 - 이 작업 지시사항의 "an initial authorization is distinct from
     * refreshing an existing credential").
     *
     * <p>이 메서드가 {@link SourceConnectionJpaRepository#findByIdForUpdate}(1차
     * 캐시를 우회하지 않는 일반 JPA Lock 조회)만으로 충분한 이유: 이 메서드의 모든
     * 실제 호출 경로({@code GoogleDriveOAuthService.commitCredential})는 Google과의
     * Network 호출이 이미 끝난 뒤, 곧바로(중간에 다른 Blocking 작업 없이) 이 Source
     * 행을 처음 잠근다 - 잠그기 전에 이 Transaction이 이 행을 먼저 Unlocked로 읽어둔
     * 적이 없다("Refresh"({@link #load})와 달리 Lock 이전의 Staleness Gap이 없다).</p>
     */
    @Transactional
    public void store(Long sourceId, TokenEnvelope envelope) {
        SourceConnectionEntity connection = sourceConnectionJpaRepository.findByIdForUpdate(sourceId)
                .orElseThrow(() -> new IllegalStateException(
                        "source connection not found while storing a credential"));
        requireEligibleForPublish(connection.getType(), connection.getStatus(), connection.getOwnerSubject(),
                envelope.boundSubject());

        String keyId = requireActiveKeyId();
        SecretKey key = resolveActiveKey();
        Instant now = clock.instant();

        Optional<SourceOAuthTokenEntity> existing = tokenRepository.findBySourceId(sourceId);
        UUID tokenRef = existing.map(SourceOAuthTokenEntity::getTokenRef).orElseGet(UUID::randomUUID);

        byte[] nonce = randomNonce();
        byte[] aad = buildAad(CURRENT_FORMAT_VERSION, sourceId, envelope.boundSubject(), tokenRef, keyId);
        byte[] ciphertext = encrypt(serialize(envelope), aad, key, nonce);

        if (existing.isPresent()) {
            existing.get().replacePayload(envelope.boundSubject(), CURRENT_FORMAT_VERSION, keyId, nonce, ciphertext,
                    now);
        } else {
            tokenRepository.save(new SourceOAuthTokenEntity(tokenRef, sourceId, envelope.boundSubject(),
                    CURRENT_FORMAT_VERSION, keyId, nonce, ciphertext, now));
        }
        connection.updateTokenRef(tokenRef.toString());
    }

    /**
     * 저장된 Credential을 복호화해 반환한다. {@code source_connections.token_ref}/
     * {@code owner_subject}와 {@code source_oauth_tokens}의 실제 값이 일치하는지
     * 매번 다시 확인한다(Reference 바꿔치기 Fail Closed). 만료/임박 만료면 Refresh를
     * 한 번(이 호출 안에서 최대 한 번) 시도하고, 성공한 새 Credential은 오직 그
     * 세대(Refresh를 시작하기 직전에 실제로 읽었던 {@code tokenRef}+{@code rowVersion})가
     * 발행 시점에도 여전히 그대로일 때만 {@link #publishRefreshIfStillCurrent}로
     * 발행한다 - Refresh 응답을 Google로부터 받는 동안 이 Source가 Disconnect되거나,
     * 다른 재인증이 먼저 새 세대를 발행했다면("A late refresh must not... return the
     * discarded credential" - 이 작업 지시사항) 이 Refresh 결과는 조용히 버려지고
     * {@link Optional#empty()}를 반환한다(Stale한 예전 값을 대신 돌려주지 않는다 -
     * 이미 Disconnect/교체됐다는 구체적 증거가 있기 때문이다). Refresh 호출 자체가
     * 실패(Network/invalid_grant)하면 - Credential 자체는 아직 안 바뀌었을 수 있으므로 -
     * 기존(아직 만료된) 값을 그대로 반환한다({@link GoogleDriveConnector}의 기존 만료
     * 검사가 그 값을 Fail Closed 시킨다 - 여기서 예외를 던지지 않는다, "once per
     * logical call"을 지키기 위함).
     *
     * <p>{@code @Transactional}이 필요한 이유(Self-Invocation): 이 Method는 Refresh
     * 발행 판단을 위해 내부적으로 {@link #publishRefreshIfStillCurrent}를 호출한다 -
     * 같은 Class 안에서의 직접 호출은 Spring AOP Proxy를 거치지 않으므로, 그 Method가
     * 별도 {@code @Transactional}을 가져도 무시된다. 이 Method 자체에
     * {@code @Transactional}을 둬(readOnly로 두지 않는다 - Refresh 발행이 실제 쓰기를
     * 하기 때문이다) 외부 호출(Proxy 경유) 시점에 이미 Thread에 Transaction이
     * 바인딩되게 하면, 내부 Self-Invocation으로 호출되는 JPA/Native 연산도 그 활성
     * Transaction에 자연히 참여한다.</p>
     */
    @Transactional
    public Optional<TokenEnvelope> load(Long sourceId) {
        SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(sourceId).orElse(null);
        if (connection == null) {
            return Optional.empty();
        }
        Optional<SourceOAuthTokenEntity> tokenRow = tokenRepository.findBySourceId(sourceId);
        if (tokenRow.isEmpty()) {
            return Optional.empty();
        }
        SourceOAuthTokenEntity entity = tokenRow.get();
        requireConsistentReference(connection, entity);

        SecretKey key = resolveKeyForRowOrFail(entity.getKeyId());
        byte[] aad = buildAad(entity.getFormatVersion(), sourceId, entity.getOwnerSubject(), entity.getTokenRef(),
                entity.getKeyId());
        TokenEnvelope envelope;
        try {
            envelope = deserialize(decrypt(entity.getCiphertext(), aad, key, entity.getNonce()));
        } catch (GeneralSecurityException tamperOrWrongKey) {
            throw new IllegalStateException("stored credential could not be decrypted", tamperOrWrongKey);
        }

        if (needsRefresh(envelope) && envelope.refreshToken() != null) {
            // "Old Credential"(이 세대의 신원)을 Google을 호출하기 전에 지금 여기서 그대로
            // 캡처한다 - 이 시점 이후로는 이 Source 행에 대해 어떤 Lock도 잡지 않은 채 Network
            // 호출이 진행된다(Bounded 시간이라도 DB Lock을 Network 호출 너머로 들고 가지
            // 않는다).
            UUID capturedTokenRef = entity.getTokenRef();
            long capturedRowVersion = entity.getRowVersion();
            RefreshResult result = tryRefresh(sourceId, envelope, capturedTokenRef, capturedRowVersion);
            return switch (result.kind()) {
                case PUBLISHED -> Optional.of(result.envelope());
                case DISCARDED -> Optional.empty();
                case CALL_FAILED -> Optional.of(envelope);
            };
        }
        return Optional.of(envelope);
    }

    /**
     * Google에 폐기를 요청하고(실패하면 예외를 그대로 전파한다 - 호출자
     * {@code SourceConnectionService.disconnect}의 기존 Transaction Rollback/재시도
     * 계약을 그대로 따른다) 성공한 뒤에만 로컬 행을 지운다. 이미 지워진(또는 애초에
     * 없던) Credential에 대해서는 아무 일도 하지 않는다(멱등적).
     *
     * <h2>M08 후속 교정 - 읽을 수 없는 Credential을 더 이상 조용히 지우지 않는다</h2>
     * <p>이전 구현은 복호화 실패(변조/Key 불일치)를 잡아 로컬 행을 지우고 정상
     * 반환했다 - 호출자({@code SourceConnectionService.disconnect})는 이를 성공으로
     * 착각해 {@code SOURCE_DISCONNECTED} 감사를 SUCCESS로 기록했지만, 실제로는 어떤
     * Google Revoke도 일어나지 않았다("이 작업 지시사항: reject unreadable/wrong-key/
     * tampered/reference-mismatched credentials with a safe error; preserve the
     * local row/reference"). 이제 읽을 수 없거나 Reference가 일치하지 않는 Credential은
     * 예외를 던져 로컬 행을 그대로 보존하고 호출자의 기존 Transaction Rollback/재시도
     * 계약을 그대로 따른다 - 설정을 바로잡은 뒤 재시도할 수 있다. 예외 메시지에
     * Credential 값이나 원본 Provider 오류는 절대 담지 않는다.</p>
     */
    @Transactional
    public void revoke(Long sourceId) {
        // 부모 Source를 먼저 잠그고, 1차 캐시를 우회하는 Native Query로 현재 실제
        // owner_subject/token_ref를 함께 읽는다(disconnect()가 이미 이 Source 행을
        // Unlocked로 한 번 읽어둔 뒤 이 메서드를 호출하므로, 같은 Staleness Gap이
        // 존재한다 - SourceConnectionJpaRepository.lockAndReadCurrentOwnershipState
        // Javadoc 참고).
        Optional<SourceConnectionJpaRepository.OwnershipStateView> currentSource =
                sourceConnectionJpaRepository.lockAndReadCurrentOwnershipState(sourceId);

        Optional<SourceOAuthTokenEntity> tokenRow = tokenRepository.findBySourceId(sourceId);
        if (tokenRow.isEmpty()) {
            return; // 지울 것이 애초에 없다 - 멱등적으로 아무 일도 하지 않는다.
        }
        SourceOAuthTokenEntity entity = tokenRow.get();

        if (currentSource.isEmpty()) {
            // Token 행은 있는데 그 부모 Source 행이 없다 - 정상적으로는 일어날 수 없다
            // (disconnect()가 이미 자신의 조회로 존재를 확인했다). 추측하지 않고 Fail Closed.
            throw new IllegalStateException("source connection not found while revoking a credential");
        }
        SourceConnectionJpaRepository.OwnershipStateView row = currentSource.get();
        String currentOwnerSubject = row.getOwnerSubject();
        String currentTokenRef = row.getTokenRef();
        if (currentTokenRef == null || !currentTokenRef.equals(entity.getTokenRef().toString())
                || !currentOwnerSubject.equals(entity.getOwnerSubject())) {
            throw new IllegalStateException(
                    "stored credential reference does not match its owning source - refusing to revoke it blindly");
        }

        TokenEnvelope envelope;
        try {
            SecretKey key = resolveKeyForRowOrFail(entity.getKeyId());
            byte[] aad = buildAad(entity.getFormatVersion(), sourceId, entity.getOwnerSubject(),
                    entity.getTokenRef(), entity.getKeyId());
            envelope = deserialize(decrypt(entity.getCiphertext(), aad, key, entity.getNonce()));
        } catch (RuntimeException | GeneralSecurityException unreadable) {
            // 무엇을 Google에 Revoke 요청할지조차 알 수 없다(Key 불일치/변조 등) - 이를 성공한
            // Revoke로 조용히 가장하지 않는다. 로컬 행을 보존한 채 예외를 던져 호출자의
            // Transaction을 Rollback시킨다 - 설정을 바로잡은 뒤(또는 Key 복구 후) 재시도할 수
            // 있다("Do not silently convert this into successful external revocation" - 이
            // 작업 지시사항). Credential 값이나 원본 예외 메시지는 담지 않는다.
            throw new IllegalStateException("stored credential could not be read for revocation");
        }

        String revokeTarget = envelope.refreshToken() != null ? envelope.refreshToken() : envelope.accessToken();
        googleOAuthClient.revokeToken(revokeTarget); // 실패하면 예외가 그대로 전파되어 Transaction이 Rollback된다.
        tokenRepository.deleteBySourceId(sourceId);
    }

    // ------------------------------------------------------------------
    // Refresh
    // ------------------------------------------------------------------

    private boolean needsRefresh(TokenEnvelope envelope) {
        Instant expiresAt = envelope.accessTokenExpiresAt();
        return expiresAt != null && !expiresAt.isAfter(clock.instant().plus(NEAR_EXPIRY_MARGIN));
    }

    private RefreshResult tryRefresh(Long sourceId, TokenEnvelope stale, UUID capturedTokenRef,
            long capturedRowVersion) {
        OAuth2AccessTokenResponse response;
        try {
            response = googleOAuthClient.refreshAccessToken(stale.refreshToken());
        } catch (GoogleOAuthException refreshFailed) {
            // 영구적 invalid_grant/취소든 일시적 Network 오류든, 이 호출 안에서는 재시도하지
            // 않는다("once per logical call") - 호출자의 기존 만료 검사가 Fail Closed 한다.
            return RefreshResult.callFailed();
        }
        TokenEnvelope refreshed = toEnvelope(stale.boundSubject(), response, stale.refreshToken());
        boolean published = publishRefreshIfStillCurrent(sourceId, refreshed, capturedTokenRef, capturedRowVersion);
        return published ? RefreshResult.published(refreshed) : RefreshResult.discarded();
    }

    /**
     * Refresh로 받아온 새 Credential을, {@code capturedTokenRef}/{@code
     * capturedRowVersion}(Google을 호출하기 직전에 실제로 읽었던 세대)이 지금도 여전히
     * 그대로일 때만 원자적으로 발행한다. {@link #store}와 달리 이 Method는
     * {@link SourceConnectionJpaRepository#lockAndReadCurrentOwnershipState}(1차 캐시
     * 우회 Native Query)로 Source 상태를 다시 읽는다 - {@link #load}가 Google 호출
     * 이전에 이 Source 행을 이미 Unlocked로 한 번 읽어둔 채로 이 Method에 들어오기
     * 때문이다(Class Javadoc/Repository Javadoc의 Staleness Gap).
     */
    private boolean publishRefreshIfStillCurrent(Long sourceId, TokenEnvelope envelope, UUID capturedTokenRef,
            long capturedRowVersion) {
        Optional<SourceConnectionJpaRepository.OwnershipStateView> current =
                sourceConnectionJpaRepository.lockAndReadCurrentOwnershipState(sourceId);
        if (current.isEmpty()) {
            return false;
        }
        SourceConnectionJpaRepository.OwnershipStateView row = current.get();
        if (!GOOGLE_DRIVE_TYPE.equals(row.getType()) || !SourceConnection.STATUS_ACTIVE.equals(row.getStatus())
                || !row.getOwnerSubject().equals(envelope.boundSubject())) {
            // Source가 그 사이 Disconnect/종류 변경/소유권 변경됐다 - 이 Refresh는 더 이상
            // 유효하지 않다("Refresh publication must match... owner").
            return false;
        }

        String keyId = requireActiveKeyId();
        SecretKey key = resolveActiveKey();
        byte[] nonce = randomNonce();
        byte[] aad = buildAad(CURRENT_FORMAT_VERSION, sourceId, envelope.boundSubject(), capturedTokenRef, keyId);
        byte[] ciphertext = encrypt(serialize(envelope), aad, key, nonce);

        // 캡처된 세대(tokenRef+rowVersion)가 지금도 그대로일 때만 원자적으로 교체한다 -
        // "그 사이 삭제됨"과 "그 사이 다른 쓰기로 이미 교체됨"을 모두 같은 방식(영향받은
        // 행 수 0)으로 잡아낸다. JPA Entity를 거치지 않으므로 1차 캐시 Staleness 위험이
        // 없다(Repository Javadoc 참고).
        int updated = tokenRepository.replaceIfGenerationUnchanged(capturedTokenRef, capturedRowVersion,
                envelope.boundSubject(), CURRENT_FORMAT_VERSION, keyId, nonce, ciphertext, clock.instant());
        return updated == 1;
    }

    private enum RefreshOutcomeKind {
        PUBLISHED,
        DISCARDED,
        CALL_FAILED
    }

    private record RefreshResult(RefreshOutcomeKind kind, TokenEnvelope envelope) {
        static RefreshResult published(TokenEnvelope envelope) {
            return new RefreshResult(RefreshOutcomeKind.PUBLISHED, envelope);
        }

        static RefreshResult discarded() {
            return new RefreshResult(RefreshOutcomeKind.DISCARDED, null);
        }

        static RefreshResult callFailed() {
            return new RefreshResult(RefreshOutcomeKind.CALL_FAILED, null);
        }
    }

    private static TokenEnvelope toEnvelope(String boundSubject, OAuth2AccessTokenResponse response,
            String previousRefreshToken) {
        OAuth2AccessToken accessToken = response.getAccessToken();
        OAuth2RefreshToken newRefreshToken = response.getRefreshToken();
        // Google이 Refresh 응답에 refresh_token을 다시 담지 않는 경우가 흔하다 - 그 경우 기존 값을 보존한다.
        String refreshTokenValue = newRefreshToken != null ? newRefreshToken.getTokenValue() : previousRefreshToken;
        List<String> scopes = new ArrayList<>(accessToken.getScopes());
        return new TokenEnvelope(boundSubject, accessToken.getTokenValue(), refreshTokenValue,
                accessToken.getExpiresAt(), scopes);
    }

    // ------------------------------------------------------------------
    // Reference/Key 검증
    // ------------------------------------------------------------------

    /**
     * {@link #store}(최초 인증/재인증 발행) 전용 - Lock을 잡은 뒤 Source가 여전히
     * Google Drive 종류/ACTIVE 상태이고, 이 Credential의 소유자와 일치하는지 다시
     * 확인한다. 호출자({@code GoogleDriveOAuthService.commitCredential})가 이미
     * 검증했더라도, 이 Method 자체가 독립적으로 재확인한다("Make all relevant
     * writers coordinate on the parent Source and validate fresh DB state").
     */
    private static void requireEligibleForPublish(String type, String status, String ownerSubject,
            String expectedOwnerSubject) {
        if (!GOOGLE_DRIVE_TYPE.equals(type)) {
            throw new IllegalStateException("source connection is not a google drive source");
        }
        if (!SourceConnection.STATUS_ACTIVE.equals(status)) {
            throw new IllegalStateException("source connection is not active");
        }
        if (!ownerSubject.equals(expectedOwnerSubject)) {
            throw new IllegalStateException("credential owner does not match the source owner");
        }
    }

    private static void requireConsistentReference(SourceConnectionEntity connection, SourceOAuthTokenEntity entity) {
        String expectedTokenRef = connection.getTokenRef();
        if (expectedTokenRef == null || !expectedTokenRef.equals(entity.getTokenRef().toString())
                || !connection.getOwnerSubject().equals(entity.getOwnerSubject())) {
            throw new IllegalStateException(
                    "stored credential reference does not match its owning source - refusing to use it");
        }
    }

    private String requireActiveKeyId() {
        String keyId = secretProperties.tokenEncryptionKeyId();
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalStateException("token encryption key id is not configured");
        }
        return keyId;
    }

    /** MVP는 활성 Key 하나만 지원한다(Class Javadoc, 자동 Rotation은 {@code docs/plan/SDV_MVP_DEFERRED.md} MVP-10로 이연) - 저장된 행의 keyId가 다르면 Fail Closed. */
    private SecretKey resolveKeyForRowOrFail(String rowKeyId) {
        String activeKeyId = requireActiveKeyId();
        if (!activeKeyId.equals(rowKeyId)) {
            throw new IllegalStateException(
                    "stored credential was encrypted with a key id that is not the configured active key");
        }
        return resolveActiveKey();
    }

    private SecretKey resolveActiveKey() {
        byte[] keyBytes = resolveActiveKeyMaterial();
        if (keyBytes.length != AES_KEY_BYTES) {
            throw new IllegalStateException("token encryption key must decode to exactly 32 bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** 보호된 Secret 파일(우선) 또는 {@code SOURCE_TOKEN_ENCRYPTION_KEY}(명시적 Local 대안) 순으로 Base64 Key를 읽는다. */
    private byte[] resolveActiveKeyMaterial() {
        String fromFile = readKeyFile(secretProperties.tokenEncryptionKeyFile());
        String base64Key = fromFile != null ? fromFile : secretProperties.tokenEncryptionKey();
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("token encryption key is not configured");
        }
        try {
            return Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("token encryption key is not valid base64", notBase64);
        }
    }

    private static String readKeyFile(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8).strip();
        } catch (IOException unreadable) {
            throw new IllegalStateException("configured token encryption key file could not be read", unreadable);
        }
    }

    // ------------------------------------------------------------------
    // AES/GCM
    // ------------------------------------------------------------------

    private static byte[] randomNonce() {
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    private static byte[] encrypt(byte[] plaintext, byte[] aad, SecretKey key, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            // No custom cryptographic primitives - 이 예외는 JDK Provider가 표준 AES/GCM 자체를
            // 지원하지 않는(사실상 불가능한) 환경에서만 난다 - 구성 오류로 취급한다.
            throw new IllegalStateException("failed to encrypt credential", e);
        }
    }

    private static byte[] decrypt(byte[] ciphertext, byte[] aad, SecretKey key, byte[] nonce)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext); // 인증 실패 시 AEADBadTagException(GeneralSecurityException) - 호출자가 Fail Closed 한다.
    }

    private static byte[] buildAad(int formatVersion, Long sourceId, String ownerSubject, UUID tokenRef,
            String keyId) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(formatVersion);
            out.writeUTF(String.valueOf(sourceId));
            out.writeUTF(ownerSubject);
            out.writeUTF(tokenRef.toString());
            out.writeUTF(keyId);
        } catch (IOException impossible) {
            throw new IllegalStateException("failed to build AAD", impossible);
        }
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------------
    // TokenEnvelope 직렬화(이 Class 밖으로 나가지 않는 내부 전용 형식 - Public Schema가 아니다)
    // ------------------------------------------------------------------

    private static byte[] serialize(TokenEnvelope envelope) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeUTF(envelope.boundSubject());
            out.writeUTF(envelope.accessToken());
            boolean hasRefresh = envelope.refreshToken() != null;
            out.writeBoolean(hasRefresh);
            if (hasRefresh) {
                out.writeUTF(envelope.refreshToken());
            }
            boolean hasExpiry = envelope.accessTokenExpiresAt() != null;
            out.writeBoolean(hasExpiry);
            if (hasExpiry) {
                out.writeLong(envelope.accessTokenExpiresAt().toEpochMilli());
            }
            List<String> scopes = envelope.scopes();
            out.writeInt(scopes.size());
            for (String scope : scopes) {
                out.writeUTF(scope);
            }
        } catch (IOException impossible) {
            throw new IllegalStateException("failed to serialize credential", impossible);
        }
        return buffer.toByteArray();
    }

    private static TokenEnvelope deserialize(byte[] plaintext) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            String boundSubject = in.readUTF();
            String accessToken = in.readUTF();
            String refreshToken = in.readBoolean() ? in.readUTF() : null;
            Instant expiresAt = in.readBoolean() ? Instant.ofEpochMilli(in.readLong()) : null;
            int scopeCount = in.readInt();
            List<String> scopes = new ArrayList<>(scopeCount);
            for (int i = 0; i < scopeCount; i++) {
                scopes.add(in.readUTF());
            }
            return new TokenEnvelope(boundSubject, accessToken, refreshToken, expiresAt, scopes);
        } catch (IOException malformed) {
            throw new IllegalStateException("stored credential payload is malformed", malformed);
        }
    }
}
