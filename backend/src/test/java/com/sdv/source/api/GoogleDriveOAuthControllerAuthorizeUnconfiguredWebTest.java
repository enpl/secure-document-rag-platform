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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP-21({@code docs/plan/SDV_MVP_DEFERRED.md}) - {@code GoogleDriveOAuthController}의
 * HTTP 경계(Cookie/Status/Body)를 실제 Servlet Container(MockMvc)로 검증한다. 이전까지는
 * {@code GoogleDriveOAuthServiceTest}가 Application/Service 계층만 직접 호출했다 - 이
 * Class는 그 위의 HTTP Filter Chain(인증/인가/{@code GoogleOAuthException} Handler)까지
 * 함께 검증한다("A service-only test is not an HTTP/browser test" - 이 작업 지시사항).
 *
 * <p>이 Class는 Google OAuth Client 자격 자체가 설정되지 않은 상태({@code
 * sdv.secrets.google-client-id} 등이 비어있는 기본값)를 다룬다 - "OAuth Unavailable"
 * 경로와, 그 이전에 실행되는 인증/인가(401/403)를 검증한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class GoogleDriveOAuthControllerAuthorizeUnconfiguredWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;

    @Test
    void authorizeWithoutAuthenticationIsRejectedBeforeReachingTheService() throws Exception {
        mockMvc.perform(get("/api/admin/sources/google/authorize").param("sourceId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void authorizeWithOnlyUserRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/sources/google/authorize").param("sourceId", "1")
                        .with(jwt().jwt(builder -> builder.subject("user-not-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void authorizeAsAdminFailsClosedWithOauthUnavailableWhenClientIsNotConfiguredAndSetsNoCookie() throws Exception {
        SourceConnectionEntity source = sourceConnectionJpaRepository.saveAndFlush(
                new SourceConnectionEntity("GOOGLE_DRIVE", "Unconfigured OAuth Source", "ACTIVE", "FULL",
                        "owner-oauth-unconfigured"));

        mockMvc.perform(get("/api/admin/sources/google/authorize").param("sourceId", String.valueOf(source.getId()))
                        .with(jwt().jwt(builder -> builder.subject("owner-oauth-unconfigured"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OAUTH_UNAVAILABLE"))
                // Client 설정이 없어 URL을 조립하기 전에 실패한다 - 이 시점까지는 아직 Binding
                // Cookie를 세팅하지 않았어야 한다(Cookie는 authorize()가 정상 반환한 뒤에만 추가된다).
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
