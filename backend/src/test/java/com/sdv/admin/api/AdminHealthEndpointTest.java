package com.sdv.admin.api;

import com.sdv.common.trace.TraceIdFilter;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-BE-014/015 종단 검증: 실제 Testcontainers PostgreSQL 위에서 전체 Spring
 * Application Context를 부트하고, MockMvc로 {@code GET /api/admin/health}
 * (v3.2 Core Spec §32에 정의된 경로)와 표준 {@code /actuator/health}를 모두
 * 확인한다 - 미구현 의존성을 UP으로 조작하지 않고, 응답 어디에도 민감정보가
 * 없음을 검증한다.
 *
 * <p>실제 HTTP 서버/TestRestTemplate 대신 MockMvc를 사용한다 - 이 Repository의
 * 현재 의존성(spring-boot-restclient가 아직 없음)만으로 전체 Context를 실제
 * 배선 그대로 검증할 수 있는 가장 작은 방법이다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminHealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminHealthReturnsTraceIdAndNeverFabricatesUnimplementedDependencies() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/health"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER_NAME)).isNotBlank();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"database\"");
        assertThat(body).satisfiesAnyOf(
                b -> assertThat(b).contains("\"database\":\"UP\""),
                b -> assertThat(b).contains("\"database\":\"DOWN\""));
        assertThat(body).contains("\"kafka\":\"NOT_IMPLEMENTED\"");
        assertThat(body).contains("\"keycloak\":\"NOT_IMPLEMENTED\"");
        assertThat(body).contains("\"aiService\":\"NOT_IMPLEMENTED\"");
        assertThat(body).contains("\"source\":\"NOT_IMPLEMENTED\"");
    }

    @Test
    void actuatorHealthIsReachableAndExposesNoSecrets() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("password");
        assertThat(body).doesNotContainIgnoringCase("credential");
    }
}
