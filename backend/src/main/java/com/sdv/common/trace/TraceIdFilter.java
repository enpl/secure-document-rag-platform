package com.sdv.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * F-BE-012.
 *
 * 매 HTTP 요청마다 하나의 Effective Trace ID를 결정하고, 이를 로깅 컨텍스트(MDC)와
 * 응답 헤더에 일관되게 반영한다. {@code GlobalExceptionHandler}와
 * {@code AuditService}는 이 MDC 값을 그대로 읽어 동일한 Trace ID를 사용한다.
 *
 * Header 이름({@code X-Trace-Id})은 현재 v3.2 Repository Markdown 명세에 명시적으로
 * 정의되어 있지 않다 - 이번 M02 작업에서의 가정이며 handoff에 기록한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    /** CR/LF를 포함한 Log-forging 문자를 차단하고, 길이를 제한하는 안전 문자 집합. */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_NAME);
        String effective = isSafe(incoming) ? incoming : UUID.randomUUID().toString();

        response.setHeader(HEADER_NAME, effective);
        MDC.put(MDC_KEY, effective);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static boolean isSafe(String candidate) {
        return candidate != null && SAFE_TRACE_ID.matcher(candidate).matches();
    }
}
