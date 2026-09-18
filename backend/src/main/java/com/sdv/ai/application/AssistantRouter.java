package com.sdv.ai.application;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class AssistantRouter {
    public record Route(AssistantIntent intent, String reasonCode) { }
    private static final Pattern FILE_DISCOVERY = Pattern.compile("(파일|문서).{0,16}(찾|검색)|찾아줘|어디 있", Pattern.UNICODE_CASE);

    /**
     * M17 라우팅 교정 - "선택한 문서의 핵심 내용을 세 문장으로 요약하고 근거를
     * 제시해줘" 유형의 명백한 요약/비교 요청이 매번 모델 분류를 거치다가
     * {@code classificationDeadlineMs}(기본 3000ms)에 걸려 REQUEST_TIMEOUT을
     * 겪은 실제 실패를 해결한다 - 이 요청들은 애초에 분류가 필요 없을 만큼
     * 명백하다. {@link #clearContentIntent}가 이 패턴들로 판정하며, 문서 선택
     * 여부는 판정에 쓰지 않는다("문서 선택 여부만으로 의도를 단정하지 않는다").
     *
     * <p>인용된 파일명이 우연히 트리거 단어를 담고 있어도(예: 파일명 자체가
     * "요약해줘.txt") 오분류하지 않도록, 판정 전 모든 인용구(따옴표로 감싼
     * 구간)를 제거한다. 부정 표현("요약하지 말고")이나 파일 검색 문구와의
     * 복합/모호한 결합("찾아서 요약해줘")은 명백함의 기준을 만족하지 못하므로
     * {@code null}을 반환해 기존 파일 검색 우선 분기 또는 모델 분류로 넘긴다 -
     * 이 경로 자체가 오분류를 방지하는 Fail Closed 지점이다.</p>
     */
    private static final Pattern QUOTED_SPAN = Pattern.compile(
            "\"[^\"]*\"|'[^']*'|“[^”]*”|‘[^’]*’|「[^」]*」|『[^』]*』");
    private static final Pattern SUMMARIZE_VERB = Pattern.compile("요약|summarize",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COMPARE_VERB = Pattern.compile("비교|compare",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // 부정 표현이 요약/비교 동사 가까이(최대 6자) 있으면 "명백한" 요청으로 보지
    // 않는다 - 애매하면 단정하지 않고 기존 경로로 넘긴다.
    private static final Pattern NEGATED_SUMMARIZE = Pattern.compile(
            "(요약.{0,6}(하지\\s*마|하지\\s*말|말고|필요\\s*없)|(하지\\s*마|하지\\s*말|말고).{0,6}요약|안\\s*요약)",
            Pattern.UNICODE_CASE);
    private static final Pattern NEGATED_COMPARE = Pattern.compile(
            "(비교.{0,6}(하지\\s*마|하지\\s*말|말고|필요\\s*없)|(하지\\s*마|하지\\s*말|말고).{0,6}비교|안\\s*비교)",
            Pattern.UNICODE_CASE);

    private final PromptSecurityService security;
    private final PolicyEnforcedLlmGateway gateway;

    public AssistantRouter(PromptSecurityService security, PolicyEnforcedLlmGateway gateway) {
        this.security = security;
        this.gateway = gateway;
    }

    public Route route(String question, AskDeadline deadline) {
        return route(question, false, deadline);
    }

    public Route route(String question, boolean hasSelectedDocuments, AskDeadline deadline) {
        if (security.isClearBypass(question)) return new Route(AssistantIntent.POLICY_BYPASS, "POLICY_BYPASS");
        Route clear = clearContentIntent(question);
        if (clear != null) return clear;
        if (!hasSelectedDocuments && FILE_DISCOVERY.matcher(question).find())
            return new Route(AssistantIntent.FIND_FILE, null);
        var classified = gateway.classify(question, deadline);
        if (classified.failure() == PolicyEnforcedLlmGateway.Failure.TIMEOUT) return new Route(AssistantIntent.CLARIFICATION_REQUIRED, "REQUEST_TIMEOUT");
        if (classified.failure() != PolicyEnforcedLlmGateway.Failure.NONE || classified.value() == null)
            return new Route(AssistantIntent.CLARIFICATION_REQUIRED, "CLASSIFICATION_UNAVAILABLE");
        return new Route(classified.value(), null);
    }

    private static Route clearContentIntent(String question) {
        String dequoted = QUOTED_SPAN.matcher(question).replaceAll(" ");
        // 파일 검색 문구와 동시에 매칭되는 복합 요청("찾아서 요약해줘")은 단순
        // 키워드로 요약/비교 하나만 단정하지 않는다 - 기존 분기(파일 검색 우선,
        // 또는 모델 분류)로 넘긴다.
        if (FILE_DISCOVERY.matcher(dequoted).find()) return null;
        boolean summarize = SUMMARIZE_VERB.matcher(dequoted).find() && !NEGATED_SUMMARIZE.matcher(dequoted).find();
        boolean compare = COMPARE_VERB.matcher(dequoted).find() && !NEGATED_COMPARE.matcher(dequoted).find();
        // 둘 다 없거나(명백하지 않음) 둘 다 있으면(요약+비교 복합, 모호함) 단정하지 않는다.
        if (summarize == compare) return null;
        return compare ? new Route(AssistantIntent.COMPARE, null) : new Route(AssistantIntent.SUMMARIZE, null);
    }
}
