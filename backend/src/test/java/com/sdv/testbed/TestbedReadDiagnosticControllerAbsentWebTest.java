package com.sdv.testbed;

import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M16A follow-up local testbed diagnostic - proves the endpoint does not
 * register at all when the {@code testbed} Spring profile is NOT active,
 * even if {@code sdv.testbed.diagnostics.enabled=true} is set by mistake (no
 * {@code @ActiveProfiles} here - this runs under the default profile, same
 * as most other web tests in this repository).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        // 실수로 이 값이 켜져 있어도 - Profile 자체가 없으므로 여전히 등록되지 않아야 한다.
        "sdv.testbed.diagnostics.enabled=true",
        "sdv.testbed.diagnostics.allowed-file-id=allowed-file-1"
})
class TestbedReadDiagnosticControllerAbsentWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theDiagnosticEndpointDoesNotExistOutsideTheTestbedProfileEvenWithTheFlagSet() throws Exception {
        String body = mockMvc.perform(post("/api/admin/testbed/diagnostics/read-check")
                        .with(jwt().jwt(builder -> builder.subject("admin-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\":1,\"fileId\":\"x\"}"))
                // 이 Controller가 없으면 아무 @RequestMapping도 이 경로와 일치하지 않는다 -
                // Spring MVC는 이를 정적 Resource 후보로 넘기고,
                // NoResourceFoundException이 GlobalExceptionHandler의 범용
                // catch-all(Exception.class)로 잡혀 500 INTERNAL_ERROR가 된다(이 Repository의
                // 기존 동작 - 이 Test가 새로 만든 동작이 아니다). 핵심 증거는 상태 코드 자체가
                // 아니라, 실제 진단 응답 형태({@code outcome}/{@code bytesRead} 등)가 전혀
                // 아니라는 사실이다.
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("outcome");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("bytesRead");
    }

    @Test
    void theStatusEndpointAlsoDoesNotExistOutsideTheTestbedProfile() throws Exception {
        // Frontend의 가용성 확인이 의존하는 바로 그 Endpoint - 이것도 Profile 부재
        // 시 등록되지 않아야, Frontend가 "비활성화"를 정확히 판단할 수 있다.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/testbed/diagnostics/status")
                        .with(jwt().jwt(builder -> builder.subject("admin-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isInternalServerError());
    }
}
