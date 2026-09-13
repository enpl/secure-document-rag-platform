package com.sdv.source.infrastructure.google;

import com.sdv.common.config.SecretProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * M08 MVP OAuth ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - Google OAuth 2.0
 * 저수준 Wrapper. {@link GoogleDriveClient}가 Drive API 호출의 저수준 Wrapper이듯,
 * 이 Class는 인증/Authorization-Code 교환/Refresh/Revoke API 호출만 담당한다 - Google
 * SDK Type이 이 Package 밖으로 새어나가지 않게 한다.
 *
 * <h2>왜 Authorization Code/Refresh Token 교환을 직접 구현하지 않는가</h2>
 * <p>이 작업 지시사항("Use a maintained OAuth/client library when it materially
 * avoids custom protocol/security code")에 따라, Token Endpoint 요청 인코딩과 응답
 * 해석(Client 인증 방식, {@code expires_in}/{@code scope}(공백 구분) 파싱, PKCE
 * {@code code_verifier} 첨부 등)은 이미 검증된 Spring Security
 * {@code spring-security-oauth2-client}({@link RestClientAuthorizationCodeTokenResponseClient}/
 * {@link RestClientRefreshTokenTokenResponseClient})에 맡긴다. 이 Class는 여전히
 * 다음은 직접 구현한다(둘 다 단순 문자열/URL 조립이라 별도 Library가 필요 없다):</p>
 * <ul>
 *   <li>Authorize URL 조립({@link #buildAuthorizationUrl}) - Browser Redirect
 *       대상이라 Spring Security의 Servlet Filter 기반 Resolver를 재사용할 수 없다
 *       (이 Backend는 {@code oauth2Login()}을 켜지 않는다 - Class Javadoc의 상위
 *       {@code GoogleOAuthRestClientConfig} 참고).</li>
 *   <li>PKCE S256 {@code code_verifier}/{@code code_challenge}({@link #generateCodeVerifier}/
 *       {@link #codeChallengeS256}) - JDK {@link SecureRandom}/{@link MessageDigest}만
 *       쓴다(추가 Dependency 없음, CLAUDE.md "Reuse JDK crypto").</li>
 *   <li>Revoke({@link #revokeToken}) - Spring Security가 Revoke Client를 제공하지
 *       않으므로, {@link GoogleDriveClient}와 같은 방식(직접 {@link RestClient} 호출,
 *       Bounded Timeout)으로 구현한다.</li>
 * </ul>
 *
 * <p>Client 자격/Redirect URI가 비어있으면({@link SecretProperties} 참고) 이 Class의
 * 모든 공개 메서드가 즉시 {@link GoogleOAuthException}({@code Category.UNKNOWN})으로
 * Fail Closed 한다 - Application Context 기동 자체를 막지 않는다.</p>
 */
@Component
public class GoogleOAuthClient {

    static final String REQUIRED_SCOPE = GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY;
    private static final String REGISTRATION_ID = "google";
    private static final int CODE_VERIFIER_RANDOM_BYTES = 64;

    private final SecretProperties secretProperties;
    private final String authorizationUri;
    private final String tokenUri;
    private final String revokeUri;
    private final RestClient restClient;
    private final RestClientAuthorizationCodeTokenResponseClient codeTokenResponseClient =
            new RestClientAuthorizationCodeTokenResponseClient();
    private final RestClientRefreshTokenTokenResponseClient refreshTokenResponseClient =
            new RestClientRefreshTokenTokenResponseClient();

    @Autowired
    public GoogleOAuthClient(SecretProperties secretProperties,
            @Qualifier("googleOAuthRestClient") RestClient restClient,
            @Qualifier("googleOAuthTokenResponseRestClient") RestClient tokenResponseRestClient,
            @Value("${sdv.google-oauth.authorization-uri:https://accounts.google.com/o/oauth2/v2/auth}")
            String authorizationUri,
            @Value("${sdv.google-oauth.token-uri:https://oauth2.googleapis.com/token}") String tokenUri,
            @Value("${sdv.google-oauth.revoke-uri:https://oauth2.googleapis.com/revoke}") String revokeUri) {
        this.secretProperties = secretProperties;
        this.restClient = restClient;
        this.authorizationUri = authorizationUri;
        this.tokenUri = tokenUri;
        this.revokeUri = revokeUri;
        this.codeTokenResponseClient.setRestClient(tokenResponseRestClient);
        this.refreshTokenResponseClient.setRestClient(tokenResponseRestClient);
    }

    /** PKCE {@code code_verifier} - RFC 7636 §4.1(43~128자 unreserved 문자)을 만족하는 64-byte Random 기반 값. */
    public static String generateCodeVerifier() {
        byte[] random = new byte[CODE_VERIFIER_RANDOM_BYTES];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /** PKCE S256 {@code code_challenge} = BASE64URL(SHA256(code_verifier)), RFC 7636 §4.2. */
    public static String codeChallengeS256(String codeVerifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // JDK가 SHA-256을 제공하지 않는 환경은 없다 - 발생한다면 구성 오류다.
            throw new IllegalStateException("SHA-256 is required for PKCE and is not available", e);
        }
    }

    /**
     * Google 동의 화면으로 Browser를 보낼 절대 URL을 조립한다. {@code access_type=offline}+
     * {@code prompt=consent}는 Refresh Token을 신뢰성 있게 받기 위한 Google 공식 권장 조합이다
     * (동의를 매번 다시 요구하지만, 이미 연결된 Source를 재연결하는 드문 Admin 작업이라 허용
     * 가능한 비용이다).
     */
    public String buildAuthorizationUrl(String state, String codeChallenge) {
        requireClientConfigured();
        return authorizationUri
                + "?client_id=" + encode(secretProperties.googleClientId())
                + "&redirect_uri=" + encode(secretProperties.googleRedirectUri())
                + "&response_type=code"
                + "&scope=" + encode(REQUIRED_SCOPE)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(codeChallenge)
                + "&code_challenge_method=S256";
    }

    /**
     * Authorization Code를 Access/Refresh Token으로 교환한다({@code code_verifier}로
     * PKCE를 함께 검증). 호출 전 State/Browser 결합/소유자 확인은 이 Class의 책임이 아니다
     * ({@code GoogleDriveOAuthService}가 담당) - 이 메서드는 이미 검증된 {@code code}만 받는다.
     */
    public OAuth2AccessTokenResponse exchangeAuthorizationCode(String code, String codeVerifier) {
        requireClientConfigured();
        ClientRegistration registration = buildClientRegistration();
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .clientId(registration.getClientId())
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .redirectUri(registration.getRedirectUri())
                .scopes(registration.getScopes())
                .state("n/a") // 실제 State 검증은 GoogleDriveOAuthService가 이미 끝냈다 - 이 필드는 여기서 재사용되지 않는다.
                .attributes(attrs -> attrs.put(PkceParameterNames.CODE_VERIFIER, codeVerifier))
                .build();
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success(code)
                .redirectUri(registration.getRedirectUri())
                .state("n/a")
                .build();
        OAuth2AuthorizationCodeGrantRequest grantRequest = new OAuth2AuthorizationCodeGrantRequest(registration,
                new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
        try {
            return codeTokenResponseClient.getTokenResponse(grantRequest);
        } catch (OAuth2AuthorizationException e) {
            throw new GoogleOAuthException(GoogleOAuthException.Category.INVALID_GRANT,
                    "authorization code exchange was rejected", e);
        } catch (RestClientException e) {
            throw new GoogleOAuthException(GoogleOAuthException.Category.NETWORK_OR_TIMEOUT,
                    "authorization code exchange failed", e);
        } catch (RuntimeException e) {
            throw new GoogleOAuthException(GoogleOAuthException.Category.MALFORMED_RESPONSE,
                    "authorization code exchange returned an unreadable response", e);
        }
    }

    /**
     * 저장된 Refresh Token으로 새 Access Token을 받는다({@code refresh_token} Grant, "once per
     * logical call" - 호출자({@code GoogleTokenService.load})가 이 메서드를 한 Credential
     * Load당 최대 한 번만 호출한다). 영구적인 {@code invalid_grant}(취소/재사용된 Refresh
     * Token)는 {@link GoogleOAuthException.Category#INVALID_GRANT}로 구분된다 - 호출자가
     * 이를 "재인증 필요"로 처리하고, 같은 요청 안에서 다시 시도(Loop)하지 않는다.
     */
    public OAuth2AccessTokenResponse refreshAccessToken(String refreshTokenValue) {
        requireClientConfigured();
        ClientRegistration registration = buildClientRegistration();
        OAuth2AccessToken placeholderAccessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "expired-placeholder", null, null);
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(refreshTokenValue, null);
        OAuth2RefreshTokenGrantRequest grantRequest = new OAuth2RefreshTokenGrantRequest(registration,
                placeholderAccessToken, refreshToken);
        try {
            return refreshTokenResponseClient.getTokenResponse(grantRequest);
        } catch (OAuth2AuthorizationException e) {
            throw new GoogleOAuthException(GoogleOAuthException.Category.INVALID_GRANT,
                    "refresh token was rejected", e);
        } catch (RestClientException e) {
            throw new GoogleOAuthException(GoogleOAuthException.Category.NETWORK_OR_TIMEOUT,
                    "refresh token exchange failed", e);
        } catch (RuntimeException e) {
            throw new GoogleOAuthException(GoogleOAuthException.Category.MALFORMED_RESPONSE,
                    "refresh token exchange returned an unreadable response", e);
        }
    }

    /**
     * Google에 Token 폐기를 명시적으로 요청한다({@code POST /revoke}, RFC 7009 형태로 Google이
     * 문서화한 Endpoint - Access/Refresh Token 어느 쪽을 넘겨도 그 Token이 속한 전체 Grant를
     * 폐기한다). 실패하면(Network/Timeout/비 2xx) {@link GoogleOAuthException}을 던진다 -
     * 호출자({@code GoogleTokenService.revoke})가 "성공하지 않은 폐기를 성공으로 보고하지
     * 않는다"는 계약을 지키기 위해 이 예외를 그대로 전파해야 한다(삼키지 않는다).
     */
    public void revokeToken(String tokenValue) {
        try {
            restClient.post()
                    .uri(revokeUri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("token=" + encode(tokenValue))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new GoogleOAuthException(GoogleOAuthException.Category.NETWORK_OR_TIMEOUT,
                    "token revocation call failed", e);
        }
    }

    private ClientRegistration buildClientRegistration() {
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(secretProperties.googleClientId())
                .clientSecret(secretProperties.googleClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(secretProperties.googleRedirectUri())
                .scope(REQUIRED_SCOPE)
                .authorizationUri(authorizationUri)
                .tokenUri(tokenUri)
                .clientName(REGISTRATION_ID)
                .build();
    }

    private void requireClientConfigured() {
        if (isBlank(secretProperties.googleClientId()) || isBlank(secretProperties.googleClientSecret())
                || isBlank(secretProperties.googleRedirectUri())) {
            throw new GoogleOAuthException(GoogleOAuthException.Category.UNKNOWN,
                    "google oauth client is not configured");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
