package com.sdv.ai.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sdv.rag.assistant")
public record AssistantProperties(boolean enabled, String model, int contextTokens, int reservedOutputTokens,
        int maxQuestionChars, int maxSelectedDocuments, int maxEvidenceSpans, int maxPromptBytes,
        int maxConcurrentModelCalls, long totalDeadlineMs, long classificationDeadlineMs,
        long generationDeadlineMs) {

    public AssistantProperties {
        model = model == null ? "disabled" : model;
        contextTokens = positive(contextTokens, 8192);
        reservedOutputTokens = positive(reservedOutputTokens, 1024);
        maxQuestionChars = positive(maxQuestionChars, 2000);
        maxSelectedDocuments = positive(maxSelectedDocuments, 5);
        maxEvidenceSpans = positive(maxEvidenceSpans, 10);
        maxPromptBytes = positive(maxPromptBytes, 24_000);
        maxConcurrentModelCalls = positive(maxConcurrentModelCalls, 2);
        totalDeadlineMs = positive(totalDeadlineMs, 60_000);
        classificationDeadlineMs = positive(classificationDeadlineMs, 3_000);
        generationDeadlineMs = positive(generationDeadlineMs, 30_000);
        if (reservedOutputTokens >= contextTokens) {
            throw new IllegalArgumentException("reservedOutputTokens must be smaller than contextTokens");
        }
    }

    private static int positive(int value, int fallback) { return value > 0 ? value : fallback; }
    private static long positive(long value, long fallback) { return value > 0 ? value : fallback; }
}
