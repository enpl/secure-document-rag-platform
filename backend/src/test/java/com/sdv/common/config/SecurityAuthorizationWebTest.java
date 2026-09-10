package com.sdv.common.config;

import com.sdv.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.sdv.audit.infrastructure.persistence.repository.AuditLogJpaRepository;
import com.sdv.common.security.JwtAuthenticationConverter;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-BE-002/006/007/016 종단 검증: 실제 Testcontainers PostgreSQL 위에서 전체
 * Application Context를 부트하고, 401/403/200 경로와 인가 규칙, 신원 위조 방지,
 * 그리고 인증/인가 거부에 대한 감사(Audit) 기록까지 실제 HTTP 요청으로 검증한다.
 *
 * <p>{@code spring-security-test}의 {@code jwt()} Mock 인증은 Role 매핑/인가
 * 자체를 검증하는 데만 쓰고({@code forgedUnsupportedRoleClaimDoesNotGrantAdminEndToEnd}는
 * 실제 {@link JwtAuthenticationConverter}로 산출한 권한을 그대로 사용한다), 서명/
 * 만료/발급자/Audience 검증의 유일한 증거로 삼지 않는다 - 그 부분은
 * {@link JwtDecoderValidationTest}가 실제 암호화로 증명한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class SecurityAuthorizationWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditLogJpaRepository auditLogJpaRepository;

    @Test
    void missingTokenReturns401WithStandardContractAndMatchingTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/me").header(TraceIdFilter.HEADER_NAME, "trace-401-me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.traceId").value("trace-401-me"))
                .andReturn();

        assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER_NAME)).isEqualTo("trace-401-me");
    }

    @Test
    void validUserCanAccessMe() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt()
                        .jwt(builder -> builder.subject("user-1").claim("email", "user1@example.com"))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("user-1"))
                .andExpect(jsonPath("$.email").value("user1@example.com"));
    }

    @Test
    void userReceives403FromAdminHealthWithStandardContractAndMatchingTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/health")
                        .header(TraceIdFilter.HEADER_NAME, "trace-403-admin")
                        .with(jwt().jwt(builder -> builder.subject("user-2"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.traceId").value("trace-403-admin"))
                .andReturn();

        assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER_NAME)).isEqualTo("trace-403-admin");
    }

    @Test
    void adminCanAccessAdminHealth() throws Exception {
        mockMvc.perform(get("/api/admin/health").with(jwt()
                        .jwt(builder -> builder.subject("admin-1"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanAlsoUseNormalAuthenticatedApis() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt()
                        .jwt(builder -> builder.subject("admin-2"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("admin-2"));
    }

    @Test
    void actuatorHealthRemainsPubliclyAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void forgedUnsupportedRoleClaimDoesNotGrantAdminEndToEnd() throws Exception {
        Jwt forgedJwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", "attacker-1", "realm_access", Map.of("roles", List.of("SUPERUSER"))));
        AbstractAuthenticationToken converted = new JwtAuthenticationConverter().convert(forgedJwt);
        List<GrantedAuthority> realAuthorities = List.copyOf(converted.getAuthorities());

        assertThat(realAuthorities).as("forged/unsupported role must not convert to any authority").isEmpty();

        mockMvc.perform(get("/api/admin/health").with(jwt()
                        .jwt(builder -> builder.subject("attacker-1"))
                        .authorities(realAuthorities)))
                .andExpect(status().isForbidden());
    }

    @Test
    void noncanonicalAdminLikeRoleClaimDoesNotGrantAdminEndToEnd() throws Exception {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", "subject-noncanonical-admin", "realm_access", Map.of("roles", List.of("admin"))));
        AbstractAuthenticationToken converted = new JwtAuthenticationConverter().convert(jwt);
        List<GrantedAuthority> realAuthorities = List.copyOf(converted.getAuthorities());

        assertThat(realAuthorities).as("lowercase 'admin' must not convert to any authority").isEmpty();

        mockMvc.perform(get("/api/admin/health").with(jwt()
                        .jwt(builder -> builder.subject("subject-noncanonical-admin"))
                        .authorities(realAuthorities)))
                .andExpect(status().isForbidden());
    }

    @Test
    void queryParametersAndHeadersCannotOverrideIdentityOrAuthorities() throws Exception {
        mockMvc.perform(get("/api/me")
                        .param("subject", "attacker")
                        .param("userId", "attacker")
                        .param("accountId", "attacker")
                        .param("roles", "ADMIN")
                        .header("X-User-Id", "attacker")
                        .header("subject", "attacker")
                        .header("roles", "ADMIN")
                        .with(jwt().jwt(builder -> builder.subject("real-user"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("real-user"))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.roles.length()").value(1));

        // 같은 위조 시도로 ADMIN 전용 API에 접근할 수 없어야 한다(권한이 실제로 바뀌지 않았음을 재확인).
        mockMvc.perform(get("/api/admin/health")
                        .param("roles", "ADMIN")
                        .header("roles", "ADMIN")
                        .with(jwt().jwt(builder -> builder.subject("real-user"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticationFailureProducesSanitizedAuditEventWithoutQueryStringOrToken() throws Exception {
        String marker = "SDV_TEST_MARKER_authn_audit_4e2a";
        mockMvc.perform(get("/api/me")
                        .header(TraceIdFilter.HEADER_NAME, "trace-authn-audit")
                        .param("token", marker)
                        .header("Authorization", "Bearer " + marker))
                .andExpect(status().isUnauthorized());

        AuditLogEntity saved = auditLogJpaRepository.findAll().stream()
                .filter(e -> "trace-authn-audit".equals(e.getTraceId()))
                .findFirst()
                .orElseThrow();

        assertThat(saved.getActor()).isEqualTo("anonymous");
        assertThat(saved.getAction()).isEqualTo("AUTHENTICATION_FAILURE");
        assertThat(saved.getResult()).isEqualTo("DENIED");
        assertThat(saved.getTargetId()).isEqualTo("GET /api/me");
        assertThat(saved.getTargetId()).doesNotContain("?");
        assertThat(saved.getTargetId()).doesNotContain(marker);
        assertThat(String.valueOf(saved.getMetadata())).doesNotContain(marker);
        assertThat(saved.getReasonCode()).isNotBlank();
    }

    @Test
    void authorizationDenialProducesSanitizedAuditEventWithValidatedActorSubject() throws Exception {
        mockMvc.perform(get("/api/admin/health")
                        .header(TraceIdFilter.HEADER_NAME, "trace-authz-audit")
                        .with(jwt().jwt(builder -> builder.subject("subject-authz-audit"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        AuditLogEntity saved = auditLogJpaRepository.findAll().stream()
                .filter(e -> "trace-authz-audit".equals(e.getTraceId()))
                .findFirst()
                .orElseThrow();

        assertThat(saved.getActor()).isEqualTo("subject-authz-audit");
        assertThat(saved.getAction()).isEqualTo("AUTHORIZATION_DENIAL");
        assertThat(saved.getResult()).isEqualTo("DENIED");
        assertThat(saved.getTargetId()).isEqualTo("GET /api/admin/health");
    }
}
