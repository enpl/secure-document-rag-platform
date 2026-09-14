package com.sdv.source.api;

import com.sdv.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP-21({@code docs/plan/SDV_MVP_DEFERRED.md}) - {@code GoogleDriveOAuthController}의
 * Callback Endpoint HTTP 경계. Google이 실제로 호출하는 형태(거부/취소, 존재하지 않는
 * State)를 Servlet Container 수준(MockMvc)에서 검증한다 - 두 경우 모두 어떤 Credential도
 * 만들지 않고, Binding Cookie를 매번 즉시 제거(Clear)한다("결과와 무관하게 즉시 Clear" -
 * {@code GoogleDriveOAuthController.callback} Javadoc).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class GoogleDriveOAuthControllerCallbackWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void callbackIsPermittedWithoutABearerTokenButFailsClosedOnAUserDeniedConsent() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/sources/google/callback")
                        .param("error", "access_denied"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertClearsBindingCookie(result);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("access_denied");
    }

    @Test
    void callbackWithACodeButNoRecognizedStateFailsClosedAndClearsTheBindingCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/sources/google/callback")
                        .param("code", "some-code")
                        .param("state", "state-that-was-never-issued")
                        .cookie(new Cookie("sdv_google_oauth_binding", "some-binding")))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertClearsBindingCookie(result);
    }

    private static void assertClearsBindingCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("sdv_google_oauth_binding=");
        assertThat(setCookie).contains("Max-Age=0");
    }
}
