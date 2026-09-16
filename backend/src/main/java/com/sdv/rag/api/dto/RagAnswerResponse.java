package com.sdv.rag.api.dto;

import java.util.List;

public record RagAnswerResponse(String status, String reasonCode, String answer, String generatedAnalysis,
        List<RagCitation> citations, RagFileSearchResponse files, boolean partial) {
    public RagAnswerResponse { citations = citations == null ? List.of() : List.copyOf(citations); }
    public static RagAnswerResponse failed(String status, String reasonCode) {
        return new RagAnswerResponse(status, reasonCode, null, null, List.of(), null, false);
    }

    public static RagAnswerResponse incomplete(String reasonCode) {
        return new RagAnswerResponse("PARTIAL", reasonCode, null, null, List.of(), null, true);
    }
}
