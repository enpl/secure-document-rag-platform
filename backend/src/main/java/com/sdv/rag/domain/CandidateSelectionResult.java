package com.sdv.rag.domain;

import java.util.List;

public record CandidateSelectionResult(Status status, List<VectorCandidate> candidates) {
    public enum Status { SUCCESS, NOT_AUTHORIZED, REQUEST_TIMEOUT, NOT_AVAILABLE, NO_EVIDENCE }
    public CandidateSelectionResult { candidates = candidates == null ? List.of() : List.copyOf(candidates); }
    public static CandidateSelectionResult success(List<VectorCandidate> candidates) {
        return new CandidateSelectionResult(candidates.isEmpty() ? Status.NO_EVIDENCE : Status.SUCCESS, candidates);
    }
    public static CandidateSelectionResult failed(Status status) { return new CandidateSelectionResult(status, List.of()); }
}
