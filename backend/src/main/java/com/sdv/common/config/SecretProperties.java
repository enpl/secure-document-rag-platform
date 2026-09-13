package com.sdv.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * F-BE-128 (M08 MVP OAuth, {@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}). Google OAuth
 * Client 자격/Redirect URI와 Token 암호화 Master Key를 담는 Runtime 설정 - Git/DB/
 * Image 어디에도 실제 값을 담지 않는다({@code .env.example}의 Placeholder만 Repository에
 * 존재한다).
 *
 * <p>의도적으로 여기서는 값을 검증(Validate)하지 않는다 - 값이 비어있어도 Application
 * Context는 정상 기동해야 한다(이 Class를 참조하지 않는 나머지 모든 API/Test는 OAuth
 * 설정과 무관하게 계속 동작해야 한다). 실제 사용 시점({@link com.sdv.source.infrastructure.google.GoogleOAuthClient},
 * {@link com.sdv.source.infrastructure.google.GoogleTokenService})에서 값이 없거나
 * 형식이 잘못됐으면 그 호출만 명시적으로 "OAuth 사용 불가"로 Fail Closed 한다 - 여기서
 * Startup Exception을 던져 무관한 Backend 전체를 막지 않는다.</p>
 *
 * <p>{@link #tokenEncryptionKeyFile}(보호된 Secret 파일 경로)이 우선이고,
 * {@link #tokenEncryptionKey}(환경변수에 담긴 Base64 값, 기존 {@code
 * SOURCE_TOKEN_ENCRYPTION_KEY})는 명시적인 Local 대안이다 - 실제 우선순위/검증 로직은
 * {@code GoogleTokenService}가 담당한다(이 Record는 원시 값을 그대로 옮겨 담을 뿐이다).</p>
 */
@ConfigurationProperties(prefix = "sdv.secrets")
public record SecretProperties(
        String googleClientId,
        String googleClientSecret,
        String googleRedirectUri,
        String tokenEncryptionKeyFile,
        String tokenEncryptionKey,
        String tokenEncryptionKeyId) {
}
