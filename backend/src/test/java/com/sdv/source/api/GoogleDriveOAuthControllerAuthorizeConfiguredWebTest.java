package com.sdv.source.api;

import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP-21({@code docs/plan/SDV_MVP_DEFERRED.md}) - {@code GoogleDriveOAuthController}의
 * HTTP 경계 검증 중, Google OAuth Client가 실제로 설정된 상태(성공 경로)만 다룬다.
 * Google Token Endpoint 자체는 호출하지 않는다({@code authorize}는 Authorization URL만
 * 조립할 뿐이다) - 그래서 {@code GoogleDriveOAuthServiceTest}처럼 Mock HTTP Server가
 * 필요 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.secrets.google-client-id=test-client-id",
        "sdv.secrets.google-client-secret=test-client-secret",
        "sdv.secrets.google-redirect-uri=http://localhost:8080/api/admin/sources/google/callback",
        // 합성(Synthetic) Test 전용 32-byte AES Key - 실제 Secret이 아니다.
        "sdv.secrets.token-encryption-key=354C9a3yRzwUV/1rt1s8AX4yflicnzQj0Z8aItHYw3w=",
        "sdv.secrets.token-encryption-key-id=test-key-v1"
})
class GoogleDriveOAuthControllerAuthorizeConfiguredWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;

    @Test
    void authorizeAsOwningAdminReturnsAuthorizationUrlAndIssuesTheBindingCookie() throws Exception {
        SourceConnectionEntity source = sourceConnectionJpaRepository.saveAndFlush(
                new SourceConnectionEntity("GOOGLE_DRIVE", "Configured Source", "ACTIVE", "FULL",
                        "owner-oauth-configured"));

        MvcResult result = mockMvc.perform(
                        get("/api/admin/sources/google/authorize").param("sourceId", String.valueOf(source.getId()))
                                .with(jwt().jwt(builder -> builder.subject("owner-oauth-configured"))
                                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").isNotEmpty())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("sdv_google_oauth_binding=");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
        assertThat(setCookie).contains("Path=/api/admin/sources/google/callback");
        assertThat(setCookie).contains("Max-Age=600");

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("accounts.google.com");
        // 응답 Body에는 State/Code Verifier 등 Server 내부 값이 절대 노출되지 않는다.
        assertThat(body).doesNotContain("code_verifier");
    }
}
