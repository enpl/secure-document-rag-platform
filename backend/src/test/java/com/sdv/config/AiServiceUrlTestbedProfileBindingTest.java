package com.sdv.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M17 진단 교정 - {@code application-testbed.yml}이 {@code sdv.ai-service.url}을
 * 전혀 바인딩하지 않아, {@code start-testbed.ps1}의 {@code -AiServiceUrl}(운영자가
 * 명시적으로 검증한 정확한 loopback 값)이 실제 애플리케이션 프로퍼티에 조용히
 * 반영되지 않던 배선 공백을 재현/교정한다({@code AI_SERVICE_URL} 환경변수가
 * {@code sdv.ai-service.url}에 바인딩되지 않으면, {@link
 * com.sdv.rag.infrastructure.ai.DocumentParsingClient}/{@link
 * com.sdv.rag.infrastructure.ai.AiServiceRestClientConfig}는 하드코딩된 기본값
 * {@code http://localhost:8000}(명시적 {@code 127.0.0.1}이 아니다)으로 조용히
 * 되돌아간다). {@code application-local.yml}/{@code application-compose.yml}은
 * 이미 이 Placeholder를 갖고 있었다 - 이 Test는 그동안 누락돼 있던 {@code testbed}
 * Profile만 겨냥한다.
 *
 * <p>DB/Kafka/Flyway 등 어떤 AutoConfiguration도 명시적으로 가져오지 않는 최소
 * {@link ConfigurableApplicationContext}만 띄운다 - 이 Test가 검증하려는 것은
 * 순수한 YAML Placeholder 해석 하나뿐이다(실제 서비스 기동/호출 없음).</p>
 */
class AiServiceUrlTestbedProfileBindingTest {

    @Configuration
    static class MarkerConfig {
    }

    private ConfigurableApplicationContext testbedContext(String... args) {
        return new SpringApplicationBuilder(MarkerConfig.class).web(WebApplicationType.NONE)
                .profiles("testbed").run(args);
    }

    @Test
    void explicitAiServiceUrlEnvironmentOverrideIsActuallyBoundUnderTheTestbedProfile() {
        ConfigurableApplicationContext context = testbedContext("--AI_SERVICE_URL=http://127.0.0.1:19999");
        try {
            assertThat(context.getEnvironment().getProperty("sdv.ai-service.url"))
                    .as("the operator-supplied loopback address must actually reach the application - "
                            + "this was silently ignored before this correction (missing YAML placeholder)")
                    .isEqualTo("http://127.0.0.1:19999");
        } finally {
            context.close();
        }
    }

    @Test
    void theExistingHardcodedDefaultIsUnchangedWhenNoOverrideIsSupplied() {
        ConfigurableApplicationContext context = testbedContext();
        try {
            assertThat(context.getEnvironment().getProperty("sdv.ai-service.url"))
                    .as("baseline behavior (no explicit override) must not change")
                    .isEqualTo("http://localhost:8000");
        } finally {
            context.close();
        }
    }
}
