package com.sdv.audit.application;

import com.sdv.audit.application.port.AuditEventPort;
import com.sdv.audit.domain.AuditEvent;
import com.sdv.common.logging.SensitiveLogFilter;
import com.sdv.common.trace.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-BE-116 검증: metadata sanitization(INV-AUD-001)과 Port 실패 전파(swallow 금지).
 *
 * <p>key-aware sanitization 회귀 검증: metadata의 key 자체가 민감 카테고리로
 * 인식되면(token/password/secret/content/document/question/prompt 및 표기
 * 변형), 값의 내용과 무관하게 값 전체가 {@link SensitiveLogFilter#MASK}로
 * 교체되어야 한다 - 단순 {@code key=value} 패턴 매칭만으로는 순수 값(예:
 * {@code Map.of("token", "그냥 비밀값")})을 놓치기 때문이다.</p>
 */
class AuditServiceTest {

    private static final String MARKER = "SDV_TEST_MARKER_9d21ab";

    @AfterEach
    void clearMdc() {
        MDC.remove(TraceIdFilter.MDC_KEY);
    }

    @Test
    void recordsEffectiveTraceIdAndSanitizesMetadataBeforeSaving() {
        RecordingPort port = new RecordingPort();
        AuditService service = new AuditService(port);

        MDC.put(TraceIdFilter.MDC_KEY, "trace-abc-123");
        service.record("user-1", "LOGIN", "session-1", "SUCCESS", "OK",
                Map.of("note", "token=" + MARKER));

        assertThat(port.saved).isNotNull();
        assertThat(port.saved.traceId()).isEqualTo("trace-abc-123");
        assertThat(port.saved.actor()).isEqualTo("user-1");
        assertThat(port.saved.action()).isEqualTo("LOGIN");
        assertThat(port.saved.metadata().get("note")).doesNotContain(MARKER);
    }

    @Test
    void propagatesPortFailureInsteadOfSwallowingIt() {
        AuditEventPort failingPort = event -> {
            throw new IllegalStateException("simulated persistence failure");
        };
        AuditService service = new AuditService(failingPort);

        assertThatThrownBy(() -> service.record("user-1", "LOGIN", "session-1", "FAILURE", "ERR", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tokenKeyedPlainSyntheticValueIsReplacedEntirely() {
        RecordingPort port = record(Map.of("token", MARKER));

        assertThat(port.saved.metadata().get("token")).isEqualTo(SensitiveLogFilter.MASK);
    }

    @Test
    void passwordKeyedMultiwordSyntheticValueIsReplacedEntirely() {
        String multiword = "correct horse battery " + MARKER;

        RecordingPort port = record(Map.of("password", multiword));

        assertThat(port.saved.metadata().get("password")).isEqualTo(SensitiveLogFilter.MASK);
    }

    @Test
    void promptQuestionContentKeyedMultilineValuesAreReplacedEntirely() {
        for (String key : List.of("prompt", "question", "content")) {
            String multiline = "line one" + System.lineSeparator()
                    + "line two " + MARKER + System.lineSeparator() + "line three";

            RecordingPort port = record(Map.of(key, multiline));

            assertThat(port.saved.metadata().get(key)).as(key).isEqualTo(SensitiveLogFilter.MASK);
        }
    }

    @Test
    void accessTokenAndApiKeyNamingVariantsAreRecognizedAsSensitive() {
        for (String key : List.of("accessToken", "access_token", "apiKey", "api_key", "API-Key")) {
            RecordingPort port = record(Map.of(key, MARKER));

            assertThat(port.saved.metadata().get(key)).as(key).isEqualTo(SensitiveLogFilter.MASK);
        }
    }

    @Test
    void safeMetadataIsPreservedUnchanged() {
        RecordingPort port = record(Map.of("actor", "user-1", "action", "LOGIN"));

        assertThat(port.saved.metadata()).containsEntry("actor", "user-1");
        assertThat(port.saved.metadata()).containsEntry("action", "LOGIN");
    }

    private static RecordingPort record(Map<String, String> metadata) {
        RecordingPort port = new RecordingPort();
        AuditService service = new AuditService(port);
        service.record("user-1", "LOGIN", "session-1", "SUCCESS", "OK", metadata);
        return port;
    }

    private static final class RecordingPort implements AuditEventPort {
        private AuditEvent saved;

        @Override
        public void save(AuditEvent event) {
            this.saved = event;
        }
    }
}
