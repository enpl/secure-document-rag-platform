package com.sdv.ai.application;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class AssistantRouter {
    public record Route(AssistantIntent intent, String reasonCode) { }
    private static final Pattern FILE_DISCOVERY = Pattern.compile("(파일|문서).{0,16}(찾|검색)|찾아줘|어디 있", Pattern.UNICODE_CASE);
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
        if (!hasSelectedDocuments && FILE_DISCOVERY.matcher(question).find())
            return new Route(AssistantIntent.FIND_FILE, null);
        var classified = gateway.classify(question, deadline);
        if (classified.failure() == PolicyEnforcedLlmGateway.Failure.TIMEOUT) return new Route(AssistantIntent.CLARIFICATION_REQUIRED, "REQUEST_TIMEOUT");
        if (classified.failure() != PolicyEnforcedLlmGateway.Failure.NONE || classified.value() == null)
            return new Route(AssistantIntent.CLARIFICATION_REQUIRED, "CLASSIFICATION_UNAVAILABLE");
        return new Route(classified.value(), null);
    }
}
