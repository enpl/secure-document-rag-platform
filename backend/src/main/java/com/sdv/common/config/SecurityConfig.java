package com.sdv.common.config;

import tools.jackson.databind.ObjectMapper;
import com.sdv.audit.application.AuditService;
import com.sdv.common.dto.ApiErrorResponse;
import com.sdv.common.security.JwtAuthenticationConverter;
import com.sdv.common.trace.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * F-BE-002. OIDC/JWT 보호와 ADMIN API 분리(AUT-001, AUT-003).
 *
 * <p>이 파일은 명세(F-BE-002)가 부여한 {@code SecurityFilterChain}/{@code JwtDecoder}
 * Bean뿐 아니라, 401/403 응답을 직접 만들고 감사(Audit)를 남기는 최소한의 Security
 * Handler 로직도 담는다 - 이 Handler는 별도 canonical File이 명세에 없으므로
 * "SecurityConfig 내부의 최소 Handler"로 구현한다(요청사항 그대로).</p>
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    private static final String ACCESS_DENIED = "ACCESS_DENIED";
    private static final String AUDIT_UNAVAILABLE = "AUDIT_UNAVAILABLE";

    private static final String ANONYMOUS_ACTOR = "anonymous";
    private static final String AUTHENTICATION_FAILURE_ACTION = "AUTHENTICATION_FAILURE";
    private static final String AUTHORIZATION_DENIAL_ACTION = "AUTHORIZATION_DENIAL";
    private static final String DENIED_RESULT = "DENIED";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * JWKS는 발급 시점이 아니라 실제 Bearer Token을 Decode할 때 지연 조회된다
     * ({@link NimbusJwtDecoder#withJwkSetUri(String)}는 즉시 네트워크 호출을 하지
     * 않는다) - Application Context는 Keycloak이 떠 있지 않아도 기동해야 한다.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${sdv.keycloak.issuer-uri}") String issuerUri,
            @Value("${sdv.keycloak.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri(issuerUri)).build();
        decoder.setJwtValidator(defaultValidator(issuerUri, audience));
        return decoder;
    }

    /** Keycloak Realm Issuer로부터 인증서(JWKS) Endpoint를 결정론적으로 유도한다. */
    static String jwkSetUri(String issuerUri) {
        return issuerUri + "/protocol/openid-connect/certs";
    }

    /**
     * Production과 Decoder 테스트가 동일하게 사용하는 Validator 구성 - 서명 검증은
     * {@code NimbusJwtDecoder}의 JWKS Source가 담당하고, 여기서는 Issuer 일치,
     * 만료/nbf, 필수 Audience({@code sdv-backend}), 그리고 subject 존재를 검사한다.
     *
     * <p>subject 검사를 여기(인증 단계, Role 기반 인가 이전)에 두는 이유: 이게
     * 없으면 서명/발급자/Audience가 모두 유효하고 ADMIN Role까지 있는 Token이
     * subject 없이도 인가 단계를 통과해버릴 수 있다({@code CurrentUserProvider}는
     * Controller가 실제로 호출할 때만 subject를 검사하므로, 그 이전 단계인 인가
     * 판단 자체는 subject 부재를 막지 못한다). {@code CurrentUserProvider}의
     * 방어적 검사는 유지한다 - 여기는 추가 방어선이다.</p>
     */
    static OAuth2TokenValidator<Jwt> defaultValidator(String issuerUri, String audience) {
        OAuth2TokenValidator<Jwt> withIssuerAndTimestamp = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> withAudience = requiredAudienceValidator(audience);
        OAuth2TokenValidator<Jwt> withSubject = nonBlankSubjectValidator();
        return new DelegatingOAuth2TokenValidator<>(withIssuerAndTimestamp, withAudience, withSubject);
    }

    private static OAuth2TokenValidator<Jwt> requiredAudienceValidator(String requiredAudience) {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            if (audience != null && audience.contains(requiredAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            OAuth2Error error = new OAuth2Error("invalid_token", "Required audience is missing.", null);
            return OAuth2TokenValidatorResult.failure(error);
        };
    }

    private static OAuth2TokenValidator<Jwt> nonBlankSubjectValidator() {
        return jwt -> {
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return OAuth2TokenValidatorResult.success();
            }
            OAuth2Error error = new OAuth2Error("invalid_token", "A non-blank subject is required.", null);
            return OAuth2TokenValidatorResult.failure(error);
        };
    }

    // ------------------------------------------------------------------
    // 401 / 403 응답 + 감사(Audit) - Security Filter 단계의 실패는 Controller
    // Advice(GlobalExceptionHandler) 이전에 발생하므로 여기서 직접 처리한다.
    // ------------------------------------------------------------------

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper, AuditService auditService) {
        return (request, response, authException) -> handleDenied(
                request, response, objectMapper, auditService,
                HttpStatus.UNAUTHORIZED, AUTHENTICATION_REQUIRED, "Authentication is required.",
                "AUTHENTICATION_REQUIRED", ANONYMOUS_ACTOR, AUTHENTICATION_FAILURE_ACTION);
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper, AuditService auditService) {
        return (request, response, accessDeniedException) -> handleDenied(
                request, response, objectMapper, auditService,
                HttpStatus.FORBIDDEN, ACCESS_DENIED, "You do not have permission to perform this action.",
                "AUTHORIZATION_DENIED", currentSubjectOrAnonymous(), AUTHORIZATION_DENIAL_ACTION);
    }

    private static void handleDenied(HttpServletRequest request, HttpServletResponse response,
            ObjectMapper objectMapper, AuditService auditService, HttpStatus status, String code, String message,
            String reasonCode, String actor, String action) throws IOException {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        // target: query string을 포함하지 않는다(HttpServletRequest#getRequestURI는
        // 원래 query string을 포함하지 않는다) - Authorization Header/JWT/Claim/
        // Query String/예외 메시지는 절대 감사에 담지 않는다.
        String target = request.getMethod() + " " + request.getRequestURI();

        try {
            auditService.record(actor, action, target, DENIED_RESULT, reasonCode, Map.of());
        } catch (RuntimeException auditFailure) {
            // 감사 영속화 실패 - 조용히 삼키지 않는다: 보호된 요청은 계속 거부 상태로
            // 남고, 원본 예외는 절대 노출/첨부하지 않은 채 고정 code+traceId만 남긴다
            // (Visible Fail-Closed).
            log.error("code={} traceId={}", AUDIT_UNAVAILABLE, traceId);
            writeError(response, objectMapper, HttpStatus.SERVICE_UNAVAILABLE, AUDIT_UNAVAILABLE,
                    "Audit recording is temporarily unavailable.", traceId);
            return;
        }

        writeError(response, objectMapper, status, code, message, traceId);
    }

    private static String currentSubjectOrAnonymous() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String subject = jwtAuthentication.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        return ANONYMOUS_ACTOR;
    }

    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status,
            String code, String message, String traceId) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(TraceIdFilter.HEADER_NAME, traceId);
        ApiErrorResponse body = new ApiErrorResponse(code, message, traceId);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
