package com.sdv.common.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sdv.common.trace.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-BE-010/011/012 통합 검증. {@link GlobalExceptionHandlerProbeController}는
 * 이 테스트 전용 Test Double이며 운영 코드에는 존재하지 않는다.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerProbeController.class)
class GlobalExceptionHandlerWebTest {

    private static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    private static final Pattern UUID_PATTERN = Pattern.compile(UUID_REGEX);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successfulRequestReturnsEffectiveTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/test-probe/ok"))
                .andExpect(status().isOk())
                .andReturn();

        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER_NAME);
        assertThat(traceId).matches(UUID_PATTERN);
    }

    @Test
    void handledFailureReturnsSameTraceIdInHeaderAndBody() throws Exception {
        MvcResult result = mockMvc.perform(get("/test-probe/boom").header(TraceIdFilter.HEADER_NAME, "case-trace-0001"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.traceId").value("case-trace-0001"))
                .andReturn();

        assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER_NAME)).isEqualTo("case-trace-0001");
    }

    @Test
    void safeIncomingTraceIdIsPropagated() throws Exception {
        MvcResult result = mockMvc.perform(get("/test-probe/ok").header(TraceIdFilter.HEADER_NAME, "safe-trace-abc-123"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER_NAME)).isEqualTo("safe-trace-abc-123");
    }

    @Test
    void missingOrUnsafeIncomingTraceIdIsReplacedWithGeneratedUuid() throws Exception {
        MvcResult missing = mockMvc.perform(get("/test-probe/ok")).andReturn();
        assertThat(missing.getResponse().getHeader(TraceIdFilter.HEADER_NAME)).matches(UUID_PATTERN);

        MvcResult crlf = mockMvc.perform(get("/test-probe/ok")
                        .header(TraceIdFilter.HEADER_NAME, "bad" + "\r\n" + "Injected: 1"))
                .andReturn();
        assertThat(crlf.getResponse().getHeader(TraceIdFilter.HEADER_NAME)).matches(UUID_PATTERN);

        String tooLong = "a".repeat(200);
        MvcResult long1 = mockMvc.perform(get("/test-probe/ok").header(TraceIdFilter.HEADER_NAME, tooLong))
                .andReturn();
        assertThat(long1.getResponse().getHeader(TraceIdFilter.HEADER_NAME)).matches(UUID_PATTERN);
    }

    @Test
    void traceStateIsClearedAfterRequestCompletion() throws Exception {
        mockMvc.perform(get("/test-probe/ok").header(TraceIdFilter.HEADER_NAME, "clear-me-trace"));

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void validationFailureUsesSafeStandardErrorContract() throws Exception {
        mockMvc.perform(post("/test-probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unexpectedFailureUsesSafeStandardErrorContractAndHidesInternalDetails() throws Exception {
        MvcResult result = mockMvc.perform(get("/test-probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(GlobalExceptionHandlerProbeController.INTERNAL_DETAIL_MARKER);
        assertThat(body).doesNotContain("RuntimeException");
        assertThat(body).doesNotContainIgnoringCase("stacktrace");
    }

    /**
     * 실제로 방출된 Logging Event를 직접 캡처해 검증한다: 예외(및 그 Cause)에 담긴
     * 합성 마커가 로그 메시지에도, 첨부된 Throwable 데이터에도 전혀 남지 않아야
     * 하며, 동시에 안전한 code/traceId는 응답 Header/Body 모두에 그대로 남아야 한다.
     */
    @Test
    void unexpectedFailureLoggingNeverEmitsMarkersOrThrowableWhileResponseKeepsSafeContract() throws Exception {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            MvcResult result = mockMvc.perform(get("/test-probe/boom-with-cause")
                            .header(TraceIdFilter.HEADER_NAME, "log-capture-trace-001"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.traceId").value("log-capture-trace-001"))
                    .andReturn();

            assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER_NAME))
                    .as("response header must carry the same trace ID as the response body")
                    .isEqualTo("log-capture-trace-001");

            assertThat(appender.list).as("expected at least one captured log event").isNotEmpty();
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage())
                        .as("log message must not leak the top-level exception marker")
                        .doesNotContain(GlobalExceptionHandlerProbeController.INTERNAL_DETAIL_MARKER);
                assertThat(event.getFormattedMessage())
                        .as("log message must not leak the nested-cause marker")
                        .doesNotContain(GlobalExceptionHandlerProbeController.NESTED_CAUSE_MARKER);
                assertThat(event.getThrowableProxy())
                        .as("no Throwable object (message/cause/suppressed) may be attached to the log event")
                        .isNull();
                assertThat(event.getFormattedMessage())
                        .as("a useful safe error code must remain in the log")
                        .contains("INTERNAL_ERROR");
                assertThat(event.getFormattedMessage())
                        .as("the validated trace ID must remain in the log")
                        .contains("log-capture-trace-001");
            }
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }
}
