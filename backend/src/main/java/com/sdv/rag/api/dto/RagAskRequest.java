package com.sdv.rag.api.dto;

import java.util.List;

public record RagAskRequest(String question, List<Long> selectedDocumentIds) {
    public RagAskRequest { selectedDocumentIds = selectedDocumentIds == null ? List.of() : List.copyOf(selectedDocumentIds); }
}
