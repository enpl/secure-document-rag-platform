package com.sdv.ai.application;

import com.sdv.ai.application.port.LlmPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a bounded prompt. UTF-8 byte count is deliberately conservative for non-ASCII input. */
@Service
public class PromptComposer {
    public static final String SYSTEM_POLICY = StructuredOutputContract.GENERATION_SYSTEM;
    public record Composition(LlmPort.GenerationRequest request, boolean partial, String failureReason) { }
    private final AssistantProperties properties;
    private final ObjectMapper mapper;

    public PromptComposer(AssistantProperties properties) { this(properties, new ObjectMapper()); }

    @Autowired
    public PromptComposer(AssistantProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public Composition compose(String question, List<LlmPort.EvidenceInput> inputs) {
        // Byte-fallback tokenizers cannot use more tokens than UTF-8 bytes. Treating one byte
        // as one token is conservative for Korean and other non-ASCII text (unlike chars/4).
        int tokenBudgetBytes = properties.contextTokens() - properties.reservedOutputTokens();
        int maxBytes = Math.min(properties.maxPromptBytes(), tokenBudgetBytes);
        List<LlmPort.EvidenceInput> accepted = new ArrayList<>();
        boolean partial = false;
        for (LlmPort.EvidenceInput input : inputs) {
            if (accepted.size() >= properties.maxEvidenceSpans()) { partial = true; break; }
            List<LlmPort.EvidenceInput> proposed = new ArrayList<>(accepted);
            proposed.add(input);
            if (serializedWorkBytes(question, proposed) > maxBytes) { partial = true; break; }
            accepted = proposed;
        }
        if (accepted.isEmpty()) return new Composition(null, partial, "CONTEXT_LIMIT_EXCEEDED");
        try {
            return new Composition(new LlmPort.GenerationRequest(SYSTEM_POLICY,
                    mapper.writeValueAsString(userPayload(question, accepted)), accepted), partial, null);
        } catch (RuntimeException failure) {
            return new Composition(null, partial, "CONTEXT_LIMIT_EXCEEDED");
        }
    }

    private int serializedWorkBytes(String question, List<LlmPort.EvidenceInput> evidence) {
        try {
            int system = mapper.writeValueAsBytes(SYSTEM_POLICY).length;
            int schema = mapper.writeValueAsBytes(
                    StructuredOutputContract.generationSchema(properties.maxEvidenceSpans())).length;
            int payload = mapper.writeValueAsBytes(userPayload(question, evidence)).length;
            return Math.addExact(Math.addExact(system, schema), Math.addExact(payload, 256));
        } catch (RuntimeException failure) {
            return Integer.MAX_VALUE;
        }
    }

    private static Map<String, Object> userPayload(String question, List<LlmPort.EvidenceInput> evidence) {
        List<Map<String, String>> spans = evidence.stream().map(input -> {
            Map<String, String> span = new LinkedHashMap<>();
            span.put("label", input.label());
            span.put("text", input.text());
            return span;
        }).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("evidence", spans);
        return payload;
    }
}
