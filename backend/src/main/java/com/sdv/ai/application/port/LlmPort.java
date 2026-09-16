package com.sdv.ai.application.port;

import com.sdv.ai.application.AssistantIntent;

import java.util.List;

/** Local model boundary. Document content is accepted only by generate(), never classify(). */
public interface LlmPort {
    ClassificationResult classify(String question, String model, long deadlineMillis);
    GenerationResult generate(GenerationRequest request, String model, long deadlineMillis);

    record ClassificationResult(Kind kind, AssistantIntent intent) {
        public enum Kind { SUCCESS, TIMEOUT, FAILED }
    }

    record EvidenceInput(String label, String text) { }
    record GenerationRequest(String systemInstruction, String prompt, List<EvidenceInput> evidence) {
        public GenerationRequest(String prompt, List<EvidenceInput> evidence) {
            this(null, prompt, evidence);
        }

        public GenerationRequest {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }
    record Claim(String text, List<String> evidenceLabels, List<String> supportingQuotes) { }
    record GenerationResult(Kind kind, List<Claim> claims, String generatedAnalysis) {
        public enum Kind { SUCCESS, TIMEOUT, FAILED }
        public GenerationResult {
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }
}
