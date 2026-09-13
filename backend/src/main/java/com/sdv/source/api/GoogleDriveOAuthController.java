package com.sdv.source.api;

import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.security.CurrentUserProvider;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.source.api.dto.GoogleAuthorizeResponse;
import com.sdv.source.application.GoogleDriveOAuthService;
import com.sdv.source.infrastructure.google.GoogleOAuthException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * F-BE-042 (M08 MVP OAuth, {@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - Google OAuth
 * Authorize/Callback API. HTTP 관심사만 다룬다({@link GoogleDriveOAuthService}가 Use
 * Case/Transaction Boundary를 담당) - JpaRepository를 직접 호출하지 않는다.
 *
 * <h2>Callback만 인증 없이 열려있다</h2>
 * <p>{@code GET /api/admin/sources/google/callback}은 Google이 Browser Redirect로
 * 호출한다 - Keycloak Bearer Token을 붙일 수 없다({@link
 * com.sdv.common.config.SecurityConfig}가 이 경로 하나만 명시적으로 {@code permitAll}
 * 한다). 대신 State + HttpOnly Cookie(Browser 결합) + PKCE로 이 Callback을 신뢰한다 -
 * {@code /api/admin/sources/**}의 나머지 모든 경로는 여전히 ADMIN 전용이다.</p>
 */
@RestController
@RequestMapping("/api/admin/sources/google")
public class GoogleDriveOAuthController {

    static final String CALLBACK_PATH = "/api/admin/sources/google/callback";
    private static final String BROWSER_BINDING_COOKIE = "sdv_google_oauth_binding";
    private static final String OAUTH_UNAVAILABLE_CODE = "OAUTH_UNAVAILABLE";

    private static final String SUCCESS_PAGE =
            "<!doctype html><title>Google Drive connected</title><p>Google Drive connected. You can close this window.</p>";
    private static final String FAILURE_PAGE =
            "<!doctype html><title>Google Drive connection failed</title><p>The connection attempt failed or expired. Please try again from SDV.</p>";

    private final GoogleDriveOAuthService googleDriveOAuthService;
    private final CurrentUserProvider currentUserProvider;
    private final String frontendReturnUrl;
    private final boolean cookieSecure;

    public GoogleDriveOAuthController(GoogleDriveOAuthService googleDriveOAuthService,
            CurrentUserProvider currentUserProvider,
            @Value("${sdv.google-oauth.frontend-return-url:}") String frontendReturnUrl,
            @Value("${sdv.google-oauth.cookie-secure:true}") boolean cookieSecure) {
        this.googleDriveOAuthService = googleDriveOAuthService;
        this.currentUserProvider = currentUserProvider;
        this.frontendReturnUrl = frontendReturnUrl;
        this.cookieSecure = cookieSecure;
    }

    @GetMapping("/authorize")
    public GoogleAuthorizeResponse authorize(@RequestParam Long sourceId, HttpServletResponse response) {
        String subject = currentUserProvider.getCurrentUser().subject();
        GoogleDriveOAuthService.AuthorizeResult result = googleDriveOAuthService.startAuthorization(subject,
                sourceId);
        response.addHeader(HttpHeaders.SET_COOKIE, bindingCookie(result.browserBinding(), Duration.ofMinutes(10)));
        return new GoogleAuthorizeResponse(result.authorizationUrl());
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(name = BROWSER_BINDING_COOKIE, required = false) String browserBinding,
            HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, bindingCookie("", Duration.ZERO)); // One-Use - 결과와 무관하게 즉시 Clear.

        boolean success = error == null && code != null && state != null
                && googleDriveOAuthService.handleCallback(state, code, browserBinding).success();

        return contentFreeResponse(success);
    }

    /** Client 설정(Client ID/Secret/Redirect URI) 자체가 없을 때 - "explicit OAuth unavailable behavior", 나머지 API는 계속 정상 동작한다. */
    @ExceptionHandler(GoogleOAuthException.class)
    public ResponseEntity<ApiErrorResponse> handleOAuthUnavailable(GoogleOAuthException ex) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        ApiErrorResponse body = new ApiErrorResponse(OAUTH_UNAVAILABLE_CODE,
                "Google OAuth is not available.", traceId);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private ResponseEntity<String> contentFreeResponse(boolean success) {
        if (!frontendReturnUrl.isBlank()) {
            String separator = frontendReturnUrl.contains("?") ? "&" : "?";
            String location = frontendReturnUrl + separator + "googleConnect=" + (success ? "success" : "failed");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, location)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("Referrer-Policy", "no-referrer")
                    .build();
        }
        return ResponseEntity.status(success ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .body(success ? SUCCESS_PAGE : FAILURE_PAGE);
    }

    private String bindingCookie(String value, Duration maxAge) {
        return ResponseCookie.from(BROWSER_BINDING_COOKIE, value)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(cookieSecure)
                .path(CALLBACK_PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
