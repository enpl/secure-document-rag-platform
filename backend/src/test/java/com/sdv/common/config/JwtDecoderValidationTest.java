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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-BE-002 Production {@code JwtDecoder}가 실제로 강제하는 서명/발급자/시간/
 * Audience 검증을 로컬에서 생성한 Ephemeral RSA Key와 합성 JWT로 증명한다.
 *
 * <p>실제 네트워크나 개발용 Keycloak Volume에 의존하지 않는다 - JDK 내장
 * {@link com.sun.net.httpserver.HttpServer}로 이 테스트 프로세스 안에서만 살아있는
 * 로컬 JWKS Endpoint를 띄운다(외부 서비스/새 라이브러리 없음). Validator 구성은
 * {@link SecurityConfig#defaultValidator(String, String)}를 그대로 재사용해
 * Production과 동일한 검증 로직임을 보장한다.</p>
 */
class JwtDecoderValidationTest {

    private static final String AUDIENCE = "sdv-backend";
    private static final String REALM_PATH = "/realms/sdv-test";
    private static final String CERTS_PATH = REALM_PATH + "/protocol/openid-connect/certs";

    private static HttpServer jwksServer;
    private static String issuerUri;
    private static RSAKey signingKey;
    private static RSAKey otherKey;

    @BeforeAll
    static void startJwksServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        otherKey = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();

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

    @Test
    void validSignatureIssuerAudienceAndSubjectAreAccepted() throws Exception {
        JwtDecoder decoder = buildDecoder();
        Instant now = Instant.now();
        String token = signedToken(signingKey, issuerUri, List.of(AUDIENCE), now, now.plusSeconds(300),
                "subject-001");

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("subject-001");
    }

    @Test
    void expiredTokenFails() throws Exception {
        JwtDecoder decoder = buildDecoder();
        Instant now = Instant.now();
        String token = signedToken(signingKey, issuerUri, List.of(AUDIENCE),
                now.minusSeconds(600), now.minusSeconds(300), "subject-001");

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedByAnotherKeyFails() throws Exception {
        JwtDecoder decoder = buildDecoder();
        Instant now = Instant.now();
        String token = signedToken(otherKey, issuerUri, List.of(AUDIENCE), now, now.plusSeconds(300),
                "subject-001");

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void wrongIssuerFails() throws Exception {
        JwtDecoder decoder = buildDecoder();
        Instant now = Instant.now();
        String token = signedToken(signingKey, "http://127.0.0.1:1/realms/wrong-issuer", List.of(AUDIENCE),
                now, now.plusSeconds(300), "subject-001");

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void missingAudienceFails() throws Exception {
        JwtDecoder decoder = buildDecoder();
        Instant now = Instant.now();
        String token = signedToken(signingKey, issuerUri, List.of(), now, now.plusSeconds(300), "subject-001");

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void wrongAudienceFails() throws Exception {
        JwtDecoder decoder = buildDecoder();
        Instant now = Instant.now();
        String token = signedToken(signingKey, issuerUri, List.of("some-other-service"), now, now.plusSeconds(300),
                "subject-001");

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void missingSubjectFails() throws Exception {
        JwtDecoder decoder = buildDecoder();
        Instant now = Instant.now();
        String token = signedTokenWithoutSubject(signingKey, issuerUri, List.of(AUDIENCE), now, now.plusSeconds(300));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void emptySubjectFails() throws Exception {
        JwtDecoder decoder = buildDecoder();
        Instant now = Instant.now();
        String token = signedToken(signingKey, issuerUri, List.of(AUDIENCE), now, now.plusSeconds(300), "");

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void whitespaceOnlySubjectFails() throws Exception {
        JwtDecoder decoder = buildDecoder();
        Instant now = Instant.now();
        String token = signedToken(signingKey, issuerUri, List.of(AUDIENCE), now, now.plusSeconds(300), "   ");

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private static JwtDecoder buildDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(SecurityConfig.jwkSetUri(issuerUri)).build();
        decoder.setJwtValidator(SecurityConfig.defaultValidator(issuerUri, AUDIENCE));
        return decoder;
    }

    private static String signedToken(RSAKey key, String issuer, List<String> audience, Instant issuedAt,
            Instant expiresAt, String subject) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .issueTime(java.util.Date.from(issuedAt))
                .notBeforeTime(java.util.Date.from(issuedAt))
                .expirationTime(java.util.Date.from(expiresAt))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        signedJwt.sign(new RSASSASigner(key));
        return signedJwt.serialize();
    }

    /** {@code sub} Claim을 아예 넣지 않은 Token - "값이 빈 문자열"과는 다른, "Claim 자체가 없음" 시나리오. */
    private static String signedTokenWithoutSubject(RSAKey key, String issuer, List<String> audience,
            Instant issuedAt, Instant expiresAt) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .issueTime(java.util.Date.from(issuedAt))
                .notBeforeTime(java.util.Date.from(issuedAt))
                .expirationTime(java.util.Date.from(expiresAt))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        signedJwt.sign(new RSASSASigner(key));
        return signedJwt.serialize();
    }
}
