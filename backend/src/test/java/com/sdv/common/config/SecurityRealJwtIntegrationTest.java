package com.sdv.common.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.sdv.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.sdv.audit.infrastructure.persistence.repository.AuditLogJpaRepository;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Finding 1 회귀(subject 검증): {@code SecurityConfig#defaultValidator}에 추가된
 * subject Validator가 Production {@code JwtDecoder} Bean에 실제로 연결되어
 * 있음을, {@code spring-security-test}의 {@code jwt()} Mock이 아니라 실제로
 * 서명한 JWT를 {@code Authorization} Header에 실어 종단으로 증명한다.
 *
 * <p>이 프로세스 안에서만 살아있는 로컬 JWKS Endpoint(JDK 내장 HttpServer +
 * Ephemeral RSA Key)를 {@link DynamicPropertySource}로 실제
 * {@code sdv.keycloak.issuer-uri}에 연결한다 - 개발용 Keycloak이나 실제
 * 네트워크에 의존하지 않는다({@link JwtDecoderValidationTest}와 동일한 방식이며,
 * 여기서는 전체 HTTP 경로까지 태운다).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityRealJwtIntegrationTest {

    private static final String AUDIENCE = "sdv-backend";
    private static final String REALM_PATH = "/realms/sdv-real-jwt-test";
    private static final String CERTS_PATH = REALM_PATH + "/protocol/openid-connect/certs";

    private static HttpServer jwksServer;
    private static String issuerUri;
    private static RSAKey signingKey;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditLogJpaRepository auditLogJpaRepository;

    @BeforeAll
    static void startJwksServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();

        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String jwksJson = new JWKSet(signingKey.toPublicJWK()).toString();
        jwksServer.createContext(CERTS_PATH, exchange -> {
            byte[] responseBytes = jwksJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(responseBytes);
            }
        });
        jwksServer.start();

        issuerUri = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + REALM_PATH;
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("sdv.keycloak.issuer-uri", () -> issuerUri);
        registry.add("sdv.keycloak.audience", () -> AUDIENCE);
    }

    /** 대조군: 이 설정으로 실제 유효한 서명된 ADMIN Token이 /api/admin/health에 도달할 수 있음을 먼저 증명한다. */
    @Test
    void validSignedAdminTokenControlCanAccessAdminHealth() throws Exception {
        String token = signedToken("admin-real-1", List.of("ADMIN"));

        mockMvc.perform(get("/api/admin/health").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void tokenWithMissingSubjectReceives401OnAdminHealthAndMe() throws Exception {
        String token = signedTokenWithoutSubject(List.of("ADMIN"));
        assertInvalidSubjectTokenReceives401(token, "trace-subj-missing");
    }

    @Test
    void tokenWithEmptySubjectReceives401OnAdminHealthAndMe() throws Exception {
        String token = signedToken("", List.of("ADMIN"));
        assertInvalidSubjectTokenReceives401(token, "trace-subj-empty");
    }

    @Test
    void tokenWithWhitespaceOnlySubjectReceives401OnAdminHealthAndMe() throws Exception {
        String token = signedToken("   ", List.of("ADMIN"));
        assertInvalidSubjectTokenReceives401(token, "trace-subj-blank");
    }

    private void assertInvalidSubjectTokenReceives401(String token, String traceIdPrefix) throws Exception {
        for (String path : List.of("/api/admin/health", "/api/me")) {
            // TraceIdFilter의 안전 문자 집합은 [A-Za-z0-9-]뿐이다(밑줄/슬래시 불가) -
            // 여기서 재사용될 traceId도 그 규칙을 지켜야 실제로 그대로 재사용된다.
            String pathSuffix = path.equals("/api/admin/health") ? "admin-health" : "me";
            String traceId = traceIdPrefix + "-" + pathSuffix;

            MvcResult result = mockMvc.perform(get(path)
                            .header("Authorization", "Bearer " + token)
                            .header(TraceIdFilter.HEADER_NAME, traceId))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                    .andExpect(jsonPath("$.traceId").value(traceId))
                    .andReturn();

            assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER_NAME))
                    .as("response header trace ID must match response body")
                    .isEqualTo(traceId);

            String body = result.getResponse().getContentAsString();
            assertThat(body).as("response must not contain the raw JWT").doesNotContain(token);
            assertThat(body).as("response must not contain the decoder exception type")
                    .doesNotContainIgnoringCase("JwtException")
                    .doesNotContainIgnoringCase("exception");
            assertThat(body).as("response must not echo the rejected subject claim name")
                    .doesNotContainIgnoringCase("subject");

            AuditLogEntity saved = auditLogJpaRepository.findAll().stream()
                    .filter(e -> traceId.equals(e.getTraceId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(saved.getActor()).isEqualTo("anonymous");
            assertThat(saved.getAction()).isEqualTo("AUTHENTICATION_FAILURE");
            assertThat(saved.getResult()).isEqualTo("DENIED");
            assertThat(String.valueOf(saved.getMetadata())).doesNotContain(token);
        }
    }

    private String signedToken(String subject, List<String> realmRoles) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuerUri)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
        addRoles(claimsBuilder, realmRoles);
        return sign(claimsBuilder.build());
    }

    private String signedTokenWithoutSubject(List<String> realmRoles) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .issuer(issuerUri)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
        addRoles(claimsBuilder, realmRoles);
        return sign(claimsBuilder.build());
    }

    private void addRoles(JWTClaimsSet.Builder claimsBuilder, List<String> realmRoles) {
        if (realmRoles != null && !realmRoles.isEmpty()) {
            claimsBuilder.claim("realm_access", Map.of("roles", realmRoles));
        }
    }

    private String sign(JWTClaimsSet claims) throws JOSEException {
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        signedJwt.sign(new RSASSASigner(signingKey));
        return signedJwt.serialize();
    }
}
