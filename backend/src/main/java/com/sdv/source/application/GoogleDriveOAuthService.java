package com.sdv.source.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.exception.NotFoundException;
import com.sdv.source.application.port.SourceTokenStore;
import com.sdv.source.application.port.TokenEnvelope;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.infrastructure.google.GoogleApiException;
import com.sdv.source.infrastructure.google.GoogleDriveClient;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.google.GoogleOAuthClient;
import com.sdv.source.infrastructure.google.GoogleOAuthException;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M08 MVP OAuth ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - {@link
 * com.sdv.source.api.GoogleDriveOAuthController}가 필요로 하는 Use Case/Transaction
 * Boundary(CLAUDE.md "Application Service가 Use Case와 Transaction Boundary를
 * 담당한다"). 공식 Manifest ID는 부여되지 않는다(F-BE-042/043/044/128만 이 작업의
 * 공식 File이다) - 이 Class는 그 Canonical File들을 오케스트레이션하기 위한 필요한
 * 최소 지원 Class다("Small concrete... helpers are authorized implementation
 * support", 이 작업 지시사항).
 *
 * <h2>Network 호출과 DB Transaction을 분리한다</h2>
 * <p>{@link #handleCallback}은 Google Token Endpoint 호출({@link
 * GoogleOAuthClient#exchangeAuthorizationCode})을 먼저 끝낸 뒤에만 짧은 DB
 * Transaction을 연다({@link TransactionTemplate} - Class 전체에 {@code @Transactional}을
 * 붙이면 Spring Proxy가 Method 진입 시점에 곧바로 Transaction/Connection을 열어,
 * Network 호출 내내 DB Connection을 붙들게 된다). Google 호출이 실패하면 DB
 * Transaction 자체가 시작되지 않는다 - 거부/취소된 Callback은 어떤 Credential도
 * 만들지 않는다(이 작업 지시사항의 "A denied/cancelled callback creates no
 * credential").</p>
 */
@Service
public class GoogleDriveOAuthService {

    private static final String GOOGLE_DRIVE_TYPE = "GOOGLE_DRIVE";
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String DISABLED_STATUS = "DISABLED";
    private static final String SUCCESS = "SUCCESS";
    private static final String OK = "OK";

    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final GoogleOAuthStateStore stateStore;
    private final GoogleOAuthClient googleOAuthClient;
    private final GoogleDriveClient googleDriveClient;
    private final Optional<SourceTokenStore> sourceTokenStore;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public GoogleDriveOAuthService(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            GoogleOAuthStateStore stateStore, GoogleOAuthClient googleOAuthClient, GoogleDriveClient googleDriveClient,
            Optional<SourceTokenStore> sourceTokenStore, AuditService auditService,
            PlatformTransactionManager transactionManager) {
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.stateStore = stateStore;
        this.googleOAuthClient = googleOAuthClient;
        this.googleDriveClient = googleDriveClient;
        this.sourceTokenStore = sourceTokenStore;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Authorize 요청을 시작한다 - {@code sourceId}가 요청자 소유의 Google Drive
     * Source여야 한다({@code SourceAdminController}/{@code SourceUserController}의
     * 기존 Create/List API가 이미 제공하는 ID). Cross-Account 존재 노출을 피하기
     * 위해 존재하지 않음/다른 계정 소유/잘못된 종류를 모두 같은 {@link
     * NotFoundException}으로 통일한다(M04 Owner-Scoped 조회의 기존 관례와 동일).
     *
     * <h2>M10B 신규 - DISABLED Source도 재연결(Reconnect) 대상이다</h2>
     * <p>이전에는 {@code status != ACTIVE}이면 이 메서드 자체가 거부했다 - 그래서
     * Disconnect(DISABLED)된 Source는 절대 재연결할 방법이 없었다. CORE_SPEC
     * §2A.14("same authenticated owner + verified stable provider identity"
     * 재연결)를 지원하기 위해 이제 ACTIVE/DISABLED 모두 이 단계는 통과한다 -
     * 실제 Identity 검증(같은 Google 계정인지)은 {@link #commitCredential}이
     * Callback 시점에 수행한다(그 사이 값이 바뀔 수 있으므로 이 시점의 확인만으로
     * 충분하다고 가정하지 않는다).</p>
     */
    public AuthorizeResult startAuthorization(String subject, Long sourceId) {
        SourceConnectionEntity connection = sourceConnectionJpaRepository.findByIdAndOwnerSubject(sourceId, subject)
                .orElseThrow(() -> new NotFoundException("Source connection not found"));
        if (!GOOGLE_DRIVE_TYPE.equals(connection.getType())) {
            throw new NotFoundException("Source connection not found");
        }

        String codeVerifier = GoogleOAuthClient.generateCodeVerifier();
        String codeChallenge = GoogleOAuthClient.codeChallengeS256(codeVerifier);
        String browserBinding = GoogleOAuthStateStore.randomToken();
        // M10B 보안 교정(V011) - 이 시도가 "지금 이 순간의" 연결 인가 세대에 묶인다는 것을
        // 기록해 둔다. 이 값은 Callback이 나중에 도착했을 때 그 사이 Disconnect가 있었는지
        // 판단하는 근거다(commitCredential) - Authorize 응답/URL에는 노출되지 않는다.
        String state = stateStore.create(subject, sourceId, browserBinding, codeVerifier,
                connection.getConnectionEpoch());
        String authorizationUrl = googleOAuthClient.buildAuthorizationUrl(state, codeChallenge);
        return new AuthorizeResult(authorizationUrl, browserBinding);
    }

    /**
     * Callback을 처리한다. 반환값은 항상 안전한 성공/실패 결과일 뿐이다 - 원본
     * Authorization Code/State/Google 오류는 이 메서드를 벗어나지 않는다({@link
     * com.sdv.source.api.GoogleDriveOAuthController}는 이 결과만으로 고정된 Content-Free
     * 응답을 만든다).
     */
    public CallbackOutcome handleCallback(String state, String code, String browserBinding) {
        Optional<GoogleOAuthStateStore.Attempt> attemptOpt = stateStore.consume(state, browserBinding);
        if (attemptOpt.isEmpty()) {
            return CallbackOutcome.FAILED;
        }
        GoogleOAuthStateStore.Attempt attempt = attemptOpt.get();
        if (code == null || code.isBlank()) {
            return CallbackOutcome.FAILED;
        }

        OAuth2AccessTokenResponse tokenResponse;
        try {
            tokenResponse = googleOAuthClient.exchangeAuthorizationCode(code, attempt.codeVerifier());
        } catch (GoogleOAuthException codeExchangeFailed) {
            return CallbackOutcome.FAILED;
        }

        OAuth2AccessToken accessToken = tokenResponse.getAccessToken();
        if (!accessToken.getScopes().contains(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY)) {
            // Missing required scope never creates a usable connection(이 작업 지시사항).
            return CallbackOutcome.FAILED;
        }
        if (tokenResponse.getRefreshToken() == null) {
            // Authorization-Code 교환 응답에 Refresh Token이 없다 - Google이 이미 동의를
            // 받은 계정에 재동의를 요구하지 않았거나 그 밖의 이유로 생략했을 수 있다. 이 새
            // Access Token이 기존에 저장된 Refresh Token과 같은 Google 계정 것인지 증명할
            // 방법이 없다 - 같은 SDV Owner라는 사실만으로 같은 Google 계정이라고 가정하지
            // 않는다("Matching an SDV owner alone must not be treated as proof that two
            // Google accounts are identical" - 이 작업 지시사항). 기존 Refresh Token을
            // 추측으로 재사용하지 않고, 이 시도 전체를 재인증 필요로 실패 처리한다 - 기존에
            // 저장돼 있던 Credential(있다면)은 전혀 건드리지 않는다. Refresh Token 없는
            // Credential은 "지속적인 오프라인 연결"이 아니므로 성공으로 표시하지도 않는다
            // ("does not label the durable offline connection successful").
            return CallbackOutcome.FAILED;
        }

        // M10B 신규(CORE_SPEC §2A.14) - 이 Access Token이 실제로 속한 Google 계정의
        // 안정적 식별자를 확인한다. Network 호출은 여전히 DB Transaction 밖에서
        // 끝낸다(Class Javadoc "Network 호출과 DB Transaction을 분리한다") - 이 확인이
        // 실패하면(Google 호출 실패 등) Identity를 알 수 없으므로 안전하게 재인증
        // 필요로 처리한다(추측하지 않는다).
        String verifiedProviderAccountId;
        try {
            verifiedProviderAccountId = googleDriveClient.getAccountIdentity(accessToken.getTokenValue());
        } catch (GoogleApiException identityCheckFailed) {
            return CallbackOutcome.FAILED;
        }

        TokenEnvelope envelope = toEnvelope(attempt.subject(), tokenResponse);

        Boolean committed = transactionTemplate
                .execute(status -> commitCredential(attempt, envelope, verifiedProviderAccountId));
        return Boolean.TRUE.equals(committed) ? CallbackOutcome.SUCCESS : CallbackOutcome.FAILED;
    }

    /**
     * {@link TransactionTemplate} 안에서만 실행된다 - 부모 Source 행을 먼저 잠근
     * 뒤(Lock 순서, GoogleTokenService Class Javadoc 참고) 소유권/상태를 재확인한다.
     *
     * <h2>M10B 신규 - 재연결은 검증된 동일 Provider 계정일 때만 성공한다</h2>
     * <p>{@code ACTIVE}뿐 아니라 {@code DISABLED}(Disconnect된 Source)도 이제
     * 이 단계까지 도달할 수 있다({@link #startAuthorization} 참고) - 이 메서드가
     * 실제 재연결 여부를 최종 결정한다: 이 Source에 이미 채택된 {@code
     * providerAccountId}가 있는데 방금 확인한 계정과 다르면, 다른 Google 계정으로
     * 기존 Identity/공유 결합을 덮어쓰지 않고 그대로 실패한다(기존 Credential/
     * Identity/document_shares 무엇도 바꾸지 않는다) - "Reject a different provider
     * account without overwriting the original identity/share binding." 처음 채택하는
     * 경우({@code providerAccountId == null}, 이 Migration 이전 기존 연결 포함)는
     * 이번에 확인한 계정을 그대로 채택한다(추측하지 않고 "지금 실제로 확인된 것"만
     * 신뢰한다). 신원이 일치(또는 최초 채택)하면 상태를 ACTIVE로 (재)전이한다 -
     * 이것이 실제 "재연결(Reconnect)" 동작이다.</p>
     *
     * <h2>M10B 보안 교정(V011) - 연결 인가 세대(Connection Epoch) 확인</h2>
     * <p>위 Identity 확인만으로는 부족했다: 이 시도가 시작된 뒤(그리고 이미 State
     * Store에서 consume되어 Token 교환/Identity 확인을 기다리는 동안) Source가
     * Disconnect됐다가 완전히 별개의 새 시도로 다시 성공적으로 재연결됐을 수 있다 -
     * 그 경우 Identity는 여전히 일치하지만(같은 계정), 이 낡은 시도가 그 사이의
     * Disconnect를 전혀 몰랐던 채로 뒤늦게 완료돼 방금 만들어진 최신 Credential/
     * 상태를 덮어쓰면 안 된다. 그래서 잠근 행의 현재 {@code connectionEpoch}가
     * 이 시도가 시작될 때 읽었던 값과 정확히 같을 때만 계속 진행한다.</p>
     */
    private boolean commitCredential(GoogleOAuthStateStore.Attempt attempt, TokenEnvelope envelope,
            String verifiedProviderAccountId) {
        SourceConnectionEntity connection = sourceConnectionJpaRepository.findByIdForUpdate(attempt.sourceId())
                .orElse(null);
        if (connection == null || !GOOGLE_DRIVE_TYPE.equals(connection.getType())
                || !attempt.subject().equals(connection.getOwnerSubject())
                || !(ACTIVE_STATUS.equals(connection.getStatus()) || DISABLED_STATUS.equals(connection.getStatus()))) {
            // Source가 그 사이 삭제/소유권 변경됐거나 이미 인식되지 않는 상태다 - 이미
            // 검증된 State라도 이 시점의 실제 상태를 다시 믿지 않는다("Recheck Source
            // ownership/status before saving").
            return false;
        }
        if (connection.getConnectionEpoch() != attempt.connectionEpoch()) {
            // M10B 보안 교정(V011) - 이 시도가 시작된 뒤 Disconnect가 있었다(연결 인가
            // 세대가 올라갔다). Status가 지금 우연히 ACTIVE/DISABLED 어느 쪽이든(예: 그
            // 사이 완전히 별개의 새 재연결 시도가 이미 성공해 다시 ACTIVE가 됐을 수도
            // 있다) 이 낡은 시도는 그것과 무관하게 실패한다 - "Old callbacks must not
            // overwrite a newer completed authorization or restore deleted credentials."
            // 사용자가 다시 재연결을 시작하면 새 시도가 지금의 세대를 다시 읽어 정상
            // 진행된다.
            return false;
        }
        String existingProviderAccountId = connection.getProviderAccountId();
        if (existingProviderAccountId != null && !existingProviderAccountId.equals(verifiedProviderAccountId)) {
            // 다른 Google 계정 - 기존 Identity/Credential/공유 결합을 그대로 두고 실패한다.
            return false;
        }
        if (sourceTokenStore.isEmpty()) {
            return false;
        }
        // 재연결(DISABLED -> ACTIVE)일 수 있다 - GoogleTokenService.store()가 Token 행을 쓰기
        // 전에 이 Source가 지금 ACTIVE인지 자신의 Native Query로 다시 확인하므로(1차 캐시를
        // 우회한다, SourceConnectionJpaRepository.lockAndReadCurrentOwnershipState Javadoc
        // 참고), 여기서 상태를 먼저 실제 DB 행에 반영(Flush)해 둬야 그 재확인이 통과한다 -
        // Java 필드만 바꾸고 Flush하지 않으면 그 Native Query에는 여전히 옛 DISABLED로 보인다.
        if (existingProviderAccountId == null) {
            connection.adoptProviderAccountId(verifiedProviderAccountId);
        }
        connection.changeStatus(SourceConnection.STATUS_ACTIVE);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        sourceTokenStore.get().save(attempt.sourceId(), envelope);
        auditService.record(attempt.subject(), "SOURCE_GOOGLE_CONNECTED", "source:" + attempt.sourceId(), SUCCESS,
                OK, Map.of());
        return true;
    }

    private static TokenEnvelope toEnvelope(String boundSubject, OAuth2AccessTokenResponse response) {
        OAuth2AccessToken accessToken = response.getAccessToken();
        OAuth2RefreshToken refreshToken = response.getRefreshToken();
        List<String> scopes = new ArrayList<>(accessToken.getScopes());
        return new TokenEnvelope(boundSubject, accessToken.getTokenValue(),
                refreshToken != null ? refreshToken.getTokenValue() : null, accessToken.getExpiresAt(), scopes);
    }

    public record AuthorizeResult(String authorizationUrl, String browserBinding) {
    }

    public enum CallbackOutcome {
        SUCCESS,
        FAILED;

        public boolean success() {
            return this == SUCCESS;
        }
    }
}
