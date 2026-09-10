package com.sdv.common.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sdv.audit.application.port.AuditEventPort;
import com.sdv.common.trace.TraceIdFilter;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M03 요구사항: 인증/인가 거부를 처리하던 중 Audit 영속화가 실패하면, 보호된
 * 요청은 계속 거부 상태로 남고 안전한 503({@code AUDIT_UNAVAILABLE})을 반환해야
 * 한다(조용히 삼키지 않는 Visible Fail-Closed). 또한 그 과정에서 방출되는 로그에
 * 합성 Bearer Token 마커가 전혀 남지 않아야 한다.
 *
 * <p>{@link AuditEventPort}를 항상 실패하는 Bean으로 교체해 이 시나리오를 결정론적으로
 * 재현한다 - 실제 DB(Testcontainers)는 다른 무관한 Bean(DataSource 등) 배선을 위해
 * 여전히 필요하지만, 이 테스트의 감사 경로는 DB에 도달하기 전에 실패한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuditFailureFallbackWebTest.FailingAuditPortConfig.class})
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class AuditFailureFallbackWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void authenticationFailureReturnsAuditUnavailableAndLeaksNoMarkerWhenAuditPersistenceFails() throws Exception {
        String marker = "SDV_TEST_MARKER_bearer_audit_fail_7c19";

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(SecurityConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            MvcResult result = mockMvc.perform(get("/api/me")
                            .header(TraceIdFilter.HEADER_NAME, "trace-audit-fail-001")
                            .header("Authorization", "Bearer " + marker))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                    .andExpect(jsonPath("$.traceId").value("trace-audit-fail-001"))
                    .andReturn();

            assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER_NAME))
                    .as("trace ID must be preserved even on the fail-closed path")
                    .isEqualTo("trace-audit-fail-001");

            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage())
                        .as("captured log must not contain the synthetic bearer token marker")
                        .doesNotContain(marker);
                assertThat(event.getThrowableProxy())
                        .as("the original persistence exception must not be attached to the log event")
                        .isNull();
            }
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }

    @TestConfiguration
    static class FailingAuditPortConfig {

        @Bean
        @Primary
        AuditEventPort failingAuditEventPort() {
            return event -> {
                throw new IllegalStateException("simulated audit persistence outage");
            };
        }
    }
}
